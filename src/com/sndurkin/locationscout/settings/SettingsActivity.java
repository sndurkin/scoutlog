package com.sndurkin.locationscout.settings;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;

import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.Application;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

public class SettingsActivity extends AppCompatActivity {

    public enum LocationsDisplay {
        GRID_VIEW,
        LIST_VIEW,
        MAP_VIEW
    }

    private Tracker tracker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));
        setTitle(R.string.settings_main_title);

        super.onCreate(savedInstanceState);

        tracker = ((Application) getApplication()).getTracker();

        boolean openedScreenType = false;
        Intent intent = getIntent();
        if(intent != null) {
            String settingsScreenType = intent.getStringExtra(Strings.PARAM_SETTINGS_SCREEN_TYPE);
            if(settingsScreenType != null) {
                Fragment fragment;
                if(settingsScreenType.equals("display")) {
                    fragment = new DisplaySettingsFragment();
                }
                else if(settingsScreenType.equals("behavior")) {
                    fragment = new BehaviorSettingsFragment();
                }
                else if(settingsScreenType.equals("sync")) {
                    fragment = new SyncSettingsFragment();
                }
                else if(settingsScreenType.equals("advanced")) {
                    fragment = new AdvancedSettingsFragment();
                }
                else if(settingsScreenType.equals("sync_help")) {
                    fragment = new SyncHelpFragment();
                }
                else {
                    fragment = new MainSettingsFragment();
                }

                getFragmentManager()
                        .beginTransaction()
                        .replace(android.R.id.content, fragment, settingsScreenType)
                        .addToBackStack(settingsScreenType)
                        .commit();
                openedScreenType = true;
            }
        }

        if(!openedScreenType && savedInstanceState == null) {
            getFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new MainSettingsFragment(), "main")
                    .addToBackStack("main")
                    .commit();
        }

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case android.R.id.home:
                navigateUp();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        navigateUp();
    }

    protected void navigateUp() {
        if(getFragmentManager().getBackStackEntryCount() > 1) {
            getFragmentManager().popBackStackImmediate();
        }
        else {
            finish();
        }
    }

}
