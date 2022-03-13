package com.sndurkin.locationscout.storage;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.os.Bundle;
import android.preference.PreferenceManager;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.sndurkin.locationscout.BuildConfig;
import com.sndurkin.locationscout.GlobalBroadcastManager;
import com.sndurkin.locationscout.util.ErrorUtils;
import com.sndurkin.locationscout.util.PhotoFileCreateException;
import com.sndurkin.locationscout.util.InvalidAccountException;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Serializable;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLException;

public class DriveSyncAdapter extends AbstractThreadedSyncAdapter {

    public static final String DRIVE_SYNC_ADAPTER_BROADCAST = "com.sndurkin.locationscout.DRIVE_SYNC_ADAPTER_BROADCAST";

    private GlobalBroadcastManager broadcastManager;

    public DriveSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);

        broadcastManager = GlobalBroadcastManager.getInstance(context);
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, final SyncResult syncResult) {
        // Check to make sure this account is set in the preferences. If not, the app may have
        // been uninstalled and reinstalled and is now trying to auto-sync. The Android sync
        // system should have removed the account on uninstall but for some reason it hasn't
        // and we will remove the account here now.
        String accountName = PreferenceManager.getDefaultSharedPreferences(getContext()).getString(Strings.PREF_ACCOUNT, null);
        if(!account.name.equals(accountName)) {
            ContentResolver.setIsSyncable(account, Strings.AUTHORITY_SYNC, 0);
            ContentResolver.setSyncAutomatically(account, Strings.AUTHORITY_SYNC, false);

            if(BuildConfig.DEBUG) {
                MiscUtils.logi("DriveSyncAdapter.onPerformSync - Removed connection with " + account.name + " from sync system.");
            }
            return;
        }

        final long startTime = new Date().getTime();

        // Send broadcast that we're starting the sync.
        Intent syncStartedIntent = new Intent(DRIVE_SYNC_ADAPTER_BROADCAST);
        syncStartedIntent.putExtra("requestCode", RequestCodes.SYNC_STARTED);
        broadcastManager.sendBroadcast(syncStartedIntent);

        if(BuildConfig.DEBUG) {
            MiscUtils.logi("DriveSyncAdapter.broadcastSyncMessage - Sync started");
        }

        DatabaseHelper database = DatabaseHelper.getInstance(getContext());
        database.ensureModelsAreDeleted();

        SyncListener syncListener = new SyncListener() {

            @Override
            public void onModelDeleted() {
                syncResult.stats.numDeletes++;
                syncResult.stats.numEntries++;
            }

            @Override
            public void onModelCreated() {
                syncResult.stats.numInserts++;
                syncResult.stats.numEntries++;
            }

            @Override
            public void onModelUpdated() {
                syncResult.stats.numUpdates++;
                syncResult.stats.numEntries++;
            }

            @Override
            public void onModelSkipped() {
                syncResult.stats.numSkippedEntries++;
            }

            @Override
            public void onException(Exception e) {
                // Save the exception to the shared preferences.
                ErrorUtils.saveSyncException(getContext(), e);

                if(BuildConfig.DEBUG) {
                    MiscUtils.logd("Exception encountered during sync", e);
                }

                if(e instanceof UserRecoverableAuthException) {
                    syncResult.stats.numAuthExceptions++;
                    broadcastAuthException(((UserRecoverableAuthException) e).getIntent());
                }
                else if(e instanceof UserRecoverableAuthIOException) {
                    CrashlyticsCore.getInstance().logException(e);
                    syncResult.stats.numAuthExceptions++;
                    broadcastAuthException(((UserRecoverableAuthIOException) e).getIntent());
                }
                else if(e instanceof GoogleAuthException) {
                    CrashlyticsCore.getInstance().logException(e);
                    syncResult.stats.numAuthExceptions++;

                    // Send broadcast that the sync had an auth exception.
                    broadcastSyncMessage(RequestCodes.SYNC_AUTH_EXCEPTION);
                }
                else if(e instanceof GoogleJsonResponseException) {
                    GoogleJsonResponseException gjre = (GoogleJsonResponseException) e;
                    if(gjre.getDetails() != null) {
                        switch(gjre.getDetails().getCode()) {
                            case 500:           // Internal Server Error
                            case 503:           // Backend Error
                                syncResult.stats.numIoExceptions++;
                                broadcastSyncMessage(RequestCodes.SYNC_CANNOT_REACH_SERVER_ERROR);
                                return;
                            case 403:
                                if(!gjre.getDetails().getErrors().isEmpty()) {
                                    GoogleJsonError.ErrorInfo error = gjre.getDetails().getErrors().get(0);
                                    if("quotaExceeded".equals(error.getReason())) {
                                        syncResult.stats.numParseExceptions++;
                                        broadcastSyncMessage(RequestCodes.SYNC_STORAGE_LIMIT_REACHED_ERROR);
                                        return;
                                    }
                                }
                        }
                    }

                    CrashlyticsCore.getInstance().logException(e);
                    syncResult.stats.numIoExceptions++;
                    broadcastSyncMessage(RequestCodes.SYNC_IO_EXCEPTION);
                }
                else if((e instanceof SocketTimeoutException) ||
                        (e instanceof ConnectException) ||
                        (e instanceof SSLException) ||
                        (e instanceof UnknownHostException) ||
                        (e instanceof InterruptedIOException)) {
                    // No need to log these exceptions.
                    syncResult.stats.numIoExceptions++;
                    broadcastSyncMessage(RequestCodes.SYNC_IO_EXCEPTION);
                }
                else if(e instanceof GoogleAuthIOException) {
                    if(e.getCause() != null) {
                        CrashlyticsCore.getInstance().logException(new RuntimeException("GoogleAuthIOException > GoogleAuthException", e.getCause()));
                    }
                    else {
                        CrashlyticsCore.getInstance().logException(new RuntimeException("GoogleAuthIOException > null", e.getCause()));
                    }
                    syncResult.stats.numIoExceptions++;
                    broadcastSyncMessage(RequestCodes.SYNC_IO_EXCEPTION);
                }
                else if(e instanceof InvalidAccountException) {
                    CrashlyticsCore.getInstance().logException(e);
                    syncResult.stats.numAuthExceptions++;

                    // Send broadcast that the user has an invalid account.
                    broadcastSyncMessage(RequestCodes.SYNC_INVALID_ACCOUNT_ERROR);
                }
                else if(e instanceof ProtocolException) {
                    syncResult.stats.numIoExceptions++;
                    if(!"unexpected end of stream".equals(e.getMessage())) {
                        CrashlyticsCore.getInstance().logException(e);
                    }
                    broadcastSyncMessage(RequestCodes.SYNC_IO_EXCEPTION);
                }
                else if(e instanceof IOException) {
                    syncResult.stats.numIoExceptions++;
                    if(!"NetworkError".equals(e.getMessage())) {
                        CrashlyticsCore.getInstance().logException(e);
                    }
                    broadcastSyncMessage(RequestCodes.SYNC_IO_EXCEPTION);
                }
                else if(e instanceof PhotoFileCreateException) {
                    syncResult.stats.numIoExceptions++;

                    Map<String, Serializable> intentValues = new HashMap<>();
                    intentValues.put("folder", ((PhotoFileCreateException) e).getFolder());
                    broadcastSyncMessage(RequestCodes.SYNC_PHOTO_FILE_CREATE_EXCEPTION, intentValues);
                }
                else {
                    CrashlyticsCore.getInstance().logException(new RuntimeException("Unhandled exception", e));
                    syncResult.stats.numIoExceptions++;
                    broadcastSyncMessage(RequestCodes.SYNC_IO_EXCEPTION);
                }
            }

            @Override
            public void onFinished() {
                Map<String, Serializable> intentValues = new HashMap<>();
                intentValues.put(Strings.PARAM_TOTAL_SYNC_TIME, new Date().getTime() - startTime);

                // Send broadcast that we've finished the sync.
                broadcastSyncMessage(RequestCodes.SYNC_FINISHED, intentValues);
            }
        };

        Syncer syncer = new DriveSyncer(getContext(), account, syncListener);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());

        SyncAlgorithm.execute(syncer, database, preferences, syncListener);

        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(Strings.PREF_LAST_SYNC_TIMESTAMP, new Date().getTime());
        editor.commit();
    }

    private void broadcastSyncMessage(int requestCode) {
        broadcastSyncMessage(requestCode, null);
    }

    private void broadcastSyncMessage(int requestCode, Map<String, Serializable> intentValues) {
        Intent intent = new Intent(DRIVE_SYNC_ADAPTER_BROADCAST);
        intent.putExtra("requestCode", requestCode);
        if(intentValues != null) {
            for(Map.Entry<String, Serializable> entry : intentValues.entrySet()) {
                intent.putExtra(entry.getKey(), entry.getValue());
            }
        }
        broadcastManager.sendBroadcast(intent);

        if(BuildConfig.DEBUG) {
            MiscUtils.logi("DriveSyncAdapter.broadcastSyncMessage - Sync finished with requestCode " + requestCode);
        }
    }

    // Send broadcast to SettingsActivity that the user is not authorized.
    private void broadcastAuthException(Intent authExceptionIntent) {
        Intent intent = new Intent(DRIVE_SYNC_ADAPTER_BROADCAST);
        intent.putExtra("requestCode", RequestCodes.SYNC_RECOVERABLE_AUTH_EXCEPTION);
        intent.putExtra("authExceptionIntent", authExceptionIntent);
        broadcastManager.sendBroadcast(intent);
    }

}
