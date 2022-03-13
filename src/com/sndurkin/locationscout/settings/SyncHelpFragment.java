package com.sndurkin.locationscout.settings;


import android.accounts.Account;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.api.client.googleapis.extensions.android.accounts.GoogleAccountManager;
import com.sndurkin.locationscout.Application;
import com.sndurkin.locationscout.GlobalBroadcastManager;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.storage.DriveSyncAdapter;
import com.sndurkin.locationscout.util.ErrorUtils;
import com.sndurkin.locationscout.util.FileProvider;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class SyncHelpFragment extends Fragment {

    protected View view;

    protected GoogleAccountManager accountManager;
    protected Account account;

    protected ProgressBar fullSyncProgressBar;
    protected ProgressBar syncEmailProgressBar;

    protected SharedPreferences preferences;
    protected Tracker tracker;

    private SettingsBroadcastReceiver broadcastReceiver;
    private GlobalBroadcastManager broadcastManager;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setView(inflater, R.layout.sync_help_fragment, container);

        preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        tracker = Application.getInstance().getTracker();

        tracker.setScreenName(Strings.SYNC_HELP_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        broadcastReceiver = new SettingsBroadcastReceiver();
        broadcastManager = GlobalBroadcastManager.getInstance(getActivity());

        String accountName = preferences.getString(Strings.PREF_ACCOUNT, null);
        if(accountName != null) {
            accountManager = new GoogleAccountManager(getActivity());
            account = accountManager.getAccountByName(accountName);
        }

        fullSyncProgressBar = (ProgressBar) view.findViewById(R.id.full_sync_progress_bar);
        syncEmailProgressBar = (ProgressBar) view.findViewById(R.id.sync_email_progress_bar);

        Button fullSyncButton = (Button) view.findViewById(R.id.full_sync_button);
        fullSyncButton.setEnabled(account != null);
        fullSyncButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_SETTING)
                        .setLabel(Strings.LBL_SYNC_NOW_FULL)
                        .build());

                // Clear the sync metadata, and then initiate a new sync.
                SharedPreferences.Editor editor = preferences.edit();
                editor.remove(Strings.PREF_LAST_DRIVE_CHANGE_ID_PREFIX + account.name);
                editor.remove(Strings.PREF_LAST_LOCAL_MODIFIED_TIME);
                editor.remove(Strings.PREF_LAST_SYNC_TIMESTAMP);
                editor.commit();

                SyncSettingsFragment.forceSync(account);
            }
        });

        final Button syncEmailButton = (Button) view.findViewById(R.id.sync_email_button);
        syncEmailButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("message/rfc822");
                intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"scoutlogapp@gmail.com"});
                intent.putExtra(Intent.EXTRA_SUBJECT, "Help with syncing in ScoutLog");

                String savedExceptionsStr = ErrorUtils.getSavedExceptions(getActivity());

                try {
                    File syncErrorsFile = new File(getActivity().getFilesDir(), "sync_errors.txt");
                    if(!syncErrorsFile.exists()) {
                        syncErrorsFile.createNewFile();
                    }

                    PrintWriter out = new PrintWriter(new FileWriter(syncErrorsFile, false));
                    out.print(savedExceptionsStr);
                    out.flush(); out.close();

                    intent.putExtra(Intent.EXTRA_TEXT, "I think ScoutLog is having trouble syncing, my error log is attached.");
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://" + FileProvider.AUTHORITY + "/" + syncErrorsFile.getName()));
                }
                catch(IOException e) {
                    intent.putExtra(Intent.EXTRA_TEXT, "I think ScoutLog is having trouble syncing. Here is my error log:\n\n" + savedExceptionsStr);
                }

                try {
                    startActivity(Intent.createChooser(intent, getString(R.string.send_email)));
                }
                catch (android.content.ActivityNotFoundException ex) {
                    Toast.makeText(getActivity(), R.string.no_email_apps, Toast.LENGTH_SHORT).show();
                }
            }
        });

        return view;
    }

    protected void setView(LayoutInflater inflater, int layoutId, ViewGroup container) {
        if (view != null) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
        }

        try {
            view = inflater.inflate(layoutId, container, false);
        }
        catch (InflateException e) {
            // Return view as it is.
            CrashlyticsCore.getInstance().logException(e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if(isAdded()) {
            getActivity().setTitle(R.string.sync_help_title);
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST);
        broadcastManager.registerReceiver(SyncHelpFragment.class.getCanonicalName(), broadcastReceiver, intentFilter, true);
    }

    @Override
    public void onPause() {
        broadcastManager.unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    private class SettingsBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST.equals(action)) {
                int requestCode = intent.getIntExtra("requestCode", -1);
                if(requestCode == RequestCodes.SYNC_STARTED) {
                    fullSyncProgressBar.setVisibility(View.VISIBLE);
                }
                else {
                    fullSyncProgressBar.setVisibility(View.GONE);
                    broadcastManager.removeLastBroadcast(SyncHelpFragment.class.getCanonicalName(), action);
                }
            }
        }
    }

}
