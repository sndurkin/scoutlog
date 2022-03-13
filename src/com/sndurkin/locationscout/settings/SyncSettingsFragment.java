package com.sndurkin.locationscout.settings;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.support.v13.app.FragmentCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.text.format.DateFormat;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.BaseAdapter;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.common.AccountPicker;
import com.google.api.client.googleapis.extensions.android.accounts.GoogleAccountManager;
import com.sndurkin.locationscout.Application;
import com.sndurkin.locationscout.GlobalBroadcastManager;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DriveSyncAdapter;

import java.util.Date;


public class SyncSettingsFragment extends SettingsFragment {

    public static final String ACCOUNT_SELECTED_BROADCAST = "com.sndurkin.locationscout.ACCOUNT_SELECTED_BROADCAST";
    public static final long REFRESH_ICON_DISMISS_DELAY = 5000L;

    private Preference accountPref;
    private CheckBoxPreference autoSyncPref;
    private Preference syncNowPref;

    private AccountPrefChangeListener accountPrefChangeListener;
    private GoogleAccountManager accountManager;
    private Account account;

    private Handler refreshDismissHandler;

    private SettingsBroadcastReceiver broadcastReceiver;
    private GlobalBroadcastManager broadcastManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_sync);
        setHasOptionsMenu(true);

        tracker.setScreenName(Strings.SETTINGS_SYNC_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        broadcastReceiver = new SettingsBroadcastReceiver();
        broadcastManager = GlobalBroadcastManager.getInstance(getActivity());

        accountManager = new GoogleAccountManager(getActivity());

        refreshDismissHandler = new Handler();

        initAccountPref();
        initPhotoStoragePref();
    }

    @Override
    public void onResume() {
        super.onResume();

        if(isAdded()) {
            getActivity().setTitle(R.string.settings_sync_title);
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST);
        intentFilter.addAction(ACCOUNT_SELECTED_BROADCAST);
        broadcastManager.registerReceiver(MainSettingsFragment.class.getCanonicalName(), broadcastReceiver, intentFilter, true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.clear();
        inflater.inflate(R.menu.settings_help_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_help:
                getFragmentManager()
                        .beginTransaction()
                        .replace(android.R.id.content, new SyncHelpFragment())
                        .addToBackStack("sync_help")
                        .commit();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPause() {
        broadcastManager.unregisterReceiver(broadcastReceiver);
        refreshDismissHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    protected void initPhotoStoragePref() {
        ListPreference photoStoragePref = (ListPreference) findPreference(Strings.PREF_PHOTO_STORAGE);
        bindPreferenceSummaryToValue(photoStoragePref);
    }

    protected void initAccountPref() {
        accountPref = findPreference(Strings.PREF_ACCOUNT);
        autoSyncPref = (CheckBoxPreference) findPreference(Strings.PREF_AUTO_SYNC);
        autoSyncPref.setChecked(ContentResolver.getMasterSyncAutomatically() && preferences.getBoolean(Strings.PREF_AUTO_SYNC, true));
        autoSyncPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if((Boolean) newValue) {
                    if(!ContentResolver.getMasterSyncAutomatically()) {
                        new AlertDialog.Builder(getActivity())
                                .setTitle(R.string.auto_sync_dialog_title)
                                .setMessage(R.string.auto_sync_dialog_message)
                                .setPositiveButton(R.string.turn_on_auto_sync, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        ContentResolver.setMasterSyncAutomatically(true);
                                        ContentResolver.setSyncAutomatically(account, Strings.AUTHORITY_SYNC, true);

                                        autoSyncPref.setChecked(true);
                                        SharedPreferences.Editor editor = preferences.edit();
                                        editor.putBoolean(Strings.PREF_AUTO_SYNC, true);
                                        editor.apply();
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .create().show();
                        return false;
                    }
                }

                ContentResolver.setSyncAutomatically(account, Strings.AUTHORITY_SYNC, (Boolean) newValue);
                return true;
            }
        });

        syncNowPref = findPreference(Strings.PREF_SYNC_NOW);
        syncNowPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_SETTING)
                        .setLabel(Strings.LBL_SYNC_NOW)
                        .build());

                if(accountManager.getAccountByName(account.name) == null) {
                    promptAccountPicker(SyncSettingsFragment.this);
                    return true;
                }

                // By calling forceSync(), we're assuming the sync will begin and SYNC_STARTED will get broadcast.
                // If the device doesn't have a good internet connection, this won't happen, so we set a timer to
                // hide the refresh icon and show an error.
                forceSync(account);
                showSyncIcon();
                refreshDismissHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isAdded()) {
                            hideSyncIcon();
                            showSyncError(R.string.pref_sync_now_summary_error);
                        }
                    }
                }, REFRESH_ICON_DISMISS_DELAY);

                return true;
            }
        });
        updateLastSyncSummary();

        accountPrefChangeListener = new AccountPrefChangeListener();
        initPreferenceSummaryValue(accountPref, accountPrefChangeListener);

        accountPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {
                    promptAccountPicker(SyncSettingsFragment.this);
                }
                else {
                    FragmentCompat.requestPermissions(
                            SyncSettingsFragment.this,
                            new String[] { Manifest.permission.GET_ACCOUNTS },
                            RequestCodes.PERMISSION_REQUEST_CONTACTS
                    );
                }
                return true;
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch(requestCode) {
            case RequestCodes.PERMISSION_REQUEST_CONTACTS:
                if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    promptAccountPicker(this);
                }
                break;
        }
    }

    private class AccountPrefChangeListener implements Preference.OnPreferenceChangeListener {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            account = accountManager.getAccountByName(newValue.toString());
            if(account != null) {
                preference.setSummary(newValue.toString());
                autoSyncPref.setEnabled(true);
                syncNowPref.setEnabled(true);
            }
            else {
                CrashlyticsCore.getInstance().logException(new RuntimeException("getAccountByName() returned null for: " + newValue.toString()));

                preference.setSummary(R.string.pref_account_summary);
                syncNowPref.setSummary("");
                autoSyncPref.setEnabled(false);
                syncNowPref.setEnabled(false);

                // Ensure the preferences are cleared, because the account may have been deleted.
                removeGoogleAccount(preferences, false);
            }

            return true;
        }
    }

    public static void removeGoogleAccount(SharedPreferences preferences, boolean commit) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(Strings.PREF_LAST_DRIVE_CHANGE_ID_PREFIX + preferences.getString(Strings.PREF_ACCOUNT, ""));
        editor.remove(Strings.PREF_LAST_LOCAL_MODIFIED_TIME);
        editor.remove(Strings.PREF_ACCOUNT);
        editor.remove(Strings.PREF_LAST_SYNC_TIMESTAMP);
        if(commit) {
            editor.commit();
        }
        else {
            editor.apply();
        }
    }

    public static void forceSync(Account account) {
        Bundle options = new Bundle();
        options.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        options.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        ContentResolver.requestSync(account, Strings.AUTHORITY_SYNC, options);
    }

    private class SettingsBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST.equals(action)) {
                int requestCode = intent.getIntExtra("requestCode", -1);

                switch (requestCode) {
                    case RequestCodes.SYNC_STARTED:
                        showSyncIcon();
                        refreshDismissHandler.removeCallbacksAndMessages(null);
                        break;
                    case RequestCodes.SYNC_CANNOT_REACH_SERVER_ERROR:
                        hideSyncIcon();
                        showSyncError(R.string.pref_sync_now_summary_google_error);
                        break;
                    case RequestCodes.SYNC_AUTH_EXCEPTION:
                    case RequestCodes.SYNC_IO_EXCEPTION:
                        hideSyncIcon();
                        showSyncError(R.string.pref_sync_now_summary_error);
                        break;
                    case RequestCodes.SYNC_STORAGE_LIMIT_REACHED_ERROR:
                        hideSyncIcon();
                        showStorageFullDialog(getActivity(), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                tracker.send(new HitBuilders.EventBuilder()
                                        .setCategory(Strings.CAT_UI_ACTION)
                                        .setAction(Strings.ACT_CLICK_BUTTON)
                                        .setLabel(Strings.LBL_GDRIVE_STORAGE_FULL_OK)
                                        .build());

                                broadcastManager.removeLastBroadcast(action);
                            }
                        });
                        break;
                    case RequestCodes.SYNC_PHOTO_FILE_CREATE_EXCEPTION:
                        hideSyncIcon();
                        String folder = intent.getStringExtra("folder");
                        SyncSettingsFragment.showPhotoFileCreateErrorDialog(getActivity(), folder, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                broadcastManager.removeLastBroadcast(action);
                            }
                        });
                        break;
                    case RequestCodes.SYNC_INVALID_ACCOUNT_ERROR:
                        hideSyncIcon();
                        promptAccountPicker(SyncSettingsFragment.this);

                        // Remove broadcast from all receivers.
                        broadcastManager.removeLastBroadcast(action);
                        break;
                    case RequestCodes.SYNC_RECOVERABLE_AUTH_EXCEPTION:
                        // This is necessary for SYNC_RECOVERABLE_AUTH_EXCEPTION because once the Request Authorization
                        // activity returns, the settings page will be resumed and the GlobalBroadcastManager will
                        // fire the last broadcast, which is this same auth exception.
                        broadcastManager.removeLastBroadcast(MainSettingsFragment.class.getCanonicalName(), action);

                        // Fall through
                    case RequestCodes.SYNC_FINISHED:
                        hideSyncIcon();
                        updateLastSyncSummary();

                        if(requestCode == RequestCodes.SYNC_RECOVERABLE_AUTH_EXCEPTION) {
                            Intent authExceptionIntent = intent.getParcelableExtra("authExceptionIntent");
                            startActivityForResult(authExceptionIntent, RequestCodes.REQUEST_AUTHORIZATION);
                        }
                        break;
                }
            }
            else if(ACCOUNT_SELECTED_BROADCAST.equals(action)) {
                String newAccountName = intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                String oldAccountName = preferences.getString(Strings.PREF_ACCOUNT, "");

                if(oldAccountName.equals(newAccountName)) {
                    //CrashlyticsCore.getInstance().logException(new RuntimeException("User selected account name that was the same as old account"));
                    return;
                }

                // Ensure that we can no longer sync to the old account.
                if(account != null) {
                    ContentResolver.setIsSyncable(account, Strings.AUTHORITY_SYNC, 0);
                    ContentResolver.setSyncAutomatically(account, Strings.AUTHORITY_SYNC, false);
                }

                // Nullify the DRIVE_FILE_ID column in all tables so we can sync with a new account.
                DatabaseHelper.getInstance(getActivity()).nullifyAllDriveIds();

                // Remove the old account and timestamp, and store the new account name.
                removeGoogleAccount(preferences, true);

                SharedPreferences.Editor editor = preferences.edit();
                editor.putString(Strings.PREF_ACCOUNT, newAccountName);
                editor.commit();

                updateLastSyncSummary();

                // Switch to the new account and enable the related settings.
                accountPrefChangeListener.onPreferenceChange(accountPref, newAccountName);

                // Sync with the new account, if there is one. There is a very rare case where the user can delete
                // the account and go back to this app, and it will be null.
                if(account != null && preferences.getBoolean(Strings.PREF_AUTO_SYNC, true)) {
                    ContentResolver.setIsSyncable(account, Strings.AUTHORITY_SYNC, 1);
                    ContentResolver.setSyncAutomatically(account, Strings.AUTHORITY_SYNC, true);
                }
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch(requestCode) {
            case RequestCodes.SELECT_GOOGLE_ACCOUNT:
            case RequestCodes.REQUEST_AUTHORIZATION:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);

                    Intent intent = new Intent(ACCOUNT_SELECTED_BROADCAST);
                    intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, accountName);
                    broadcastManager.sendBroadcastSync(intent);
                }
                else if(requestCode == RequestCodes.REQUEST_AUTHORIZATION) {
                    promptAccountPicker(this);
                }
                break;
        }
    }

    // Shows a dialog to the user informing him that he has no space left in Google Drive.
    public static void showStorageFullDialog(Context context, DialogInterface.OnClickListener listener) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.gdrive_storage_full_title)
                .setMessage(R.string.gdrive_storage_full_message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, listener)
                .show();
    }

    // Shows a dialog to the user informing him that there was a problem create a photo file.
    public static void showPhotoFileCreateErrorDialog(final Context context, String folder, final DialogInterface.OnClickListener listener) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.photo_file_create_error_title)
                .setMessage(context.getString(R.string.photo_file_create_error_message, folder))
                .setCancelable(false)
                .setNegativeButton(R.string.dismiss, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if(listener != null) {
                            listener.onClick(dialog, which);
                        }

                        Application.getInstance().getTracker().send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_BUTTON)
                                .setLabel(Strings.LBL_PHOTO_CREATE_ERROR_DISMISS)
                                .build());
                    }
                })
                .setPositiveButton(R.string.open_settings, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if(listener != null) {
                            listener.onClick(dialog, which);
                        }

                        Application.getInstance().getTracker().send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_BUTTON)
                                .setLabel(Strings.LBL_PHOTO_CREATE_ERROR_OPEN_SETTINGS)
                                .build());

                        Intent intent = new Intent(context, SettingsActivity.class);
                        intent.putExtra(Strings.PARAM_SETTINGS_SCREEN_TYPE, "behavior");
                        context.startActivity(intent);
                    }
                })
                .show();
    }

    public static void promptAccountPicker(Activity activity) {
        Intent intent = AccountPicker.newChooseAccountIntent(null, null, new String[]{"com.google"}, true, null, null, null, null);
        activity.startActivityForResult(intent, RequestCodes.SELECT_GOOGLE_ACCOUNT);
    }
    public static void promptAccountPicker(Fragment fragment) {
        Intent intent = AccountPicker.newChooseAccountIntent(null, null, new String[]{"com.google"}, true, null, null, null, null);
        fragment.startActivityForResult(intent, RequestCodes.SELECT_GOOGLE_ACCOUNT);
    }
    public static void promptAccountPicker(android.support.v4.app.Fragment fragment) {
        Intent intent = AccountPicker.newChooseAccountIntent(null, null, new String[]{"com.google"}, true, null, null, null, null);
        fragment.startActivityForResult(intent, RequestCodes.SELECT_GOOGLE_ACCOUNT);
    }

    private void showSyncIcon() {
        if(syncNowPref != null) {
            syncNowPref.setWidgetLayoutResource(R.layout.pref_sync_now_widget);
            ((BaseAdapter) getPreferenceScreen().getRootAdapter()).notifyDataSetChanged();
        }
    }

    private void hideSyncIcon() {
        if(syncNowPref != null) {
            syncNowPref.setWidgetLayoutResource(0);
            ((BaseAdapter) getPreferenceScreen().getRootAdapter()).notifyDataSetChanged();
        }
    }

    private void updateLastSyncSummary() {
        long timestamp = preferences.getLong(Strings.PREF_LAST_SYNC_TIMESTAMP, 0L);
        if(timestamp > 0L) {
            Date datetime = new Date(timestamp);
            String datetimeStr = DateFormat.getDateFormat(getActivity()).format(datetime) + " " + DateFormat.getTimeFormat(getActivity()).format(datetime);
            syncNowPref.setSummary(getString(R.string.pref_sync_now_summary, datetimeStr));
        }
        else {
            syncNowPref.setSummary("");
        }
    }

    private void showSyncError(int syncErrorMessageId) {
        if(syncNowPref != null) {
            syncNowPref.setSummary(syncErrorMessageId);

            // Show sync error icon.
            syncNowPref.setWidgetLayoutResource(R.layout.pref_sync_now_error_widget);
            ((BaseAdapter) getPreferenceScreen().getRootAdapter()).notifyDataSetChanged();
        }
    }

}
