package com.sndurkin.locationscout.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceManager;

import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.HitBuilders;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.SelectFolderActivity;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;

import java.io.File;


public class BehaviorSettingsFragment extends SettingsFragment {

    protected Preference photosLocationPref;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_behavior);
        getActivity().setTitle(R.string.settings_behavior_title);

        tracker.setScreenName(Strings.SETTINGS_BEHAVIOR_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        initClusterThresholdPref();
        initPhotosLocationPref();
        initReportingPref();
    }

    protected void initClusterThresholdPref() {
        ListPreference measurementPref = (ListPreference) findPreference(Strings.PREF_CLUSTER_THRESHOLD);
        bindPreferenceSummaryToValue(measurementPref, new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                ListPreference listPreference = (ListPreference) preference;
                int index = listPreference.findIndexOfValue(value.toString());
                if(index >= 0) {
                    preference.setSummary(getString(R.string.pref_cluster_threshold_summary, listPreference.getEntries()[index]));
                }
                else {
                    preference.setSummary(null);
                }
                return true;
            }
        });
    }

    protected void initPhotosLocationPref() {
        photosLocationPref = findPreference(Strings.PREF_PHOTOS_LOCATION);
        photosLocationPref.setOnPreferenceChangeListener(bindPreferenceSummaryListener);
        photosLocationPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivityForResult(new Intent(getActivity(), SelectFolderActivity.class), RequestCodes.SELECT_FOLDER);
                return true;
            }
        });

        File photoDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), MiscUtils.APP_FOLDER);
        String defaultPhotosLocation = photoDir.getAbsolutePath();

        String val = PreferenceManager.getDefaultSharedPreferences(photosLocationPref.getContext()).getString(photosLocationPref.getKey(), defaultPhotosLocation);
        bindPreferenceSummaryListener.onPreferenceChange(photosLocationPref, val);
    }

    protected void initReportingPref() {
        CheckBoxPreference reportingPref = (CheckBoxPreference) findPreference(Strings.PREF_REPORTING);
        reportingPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                GoogleAnalytics.getInstance(getActivity()).setAppOptOut(!(Boolean) newValue);
                return true;
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch(requestCode) {
            case RequestCodes.SELECT_FOLDER:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    String newPhotosLocation = data.getStringExtra(Strings.PREF_PHOTOS_LOCATION);
                    bindPreferenceSummaryListener.onPreferenceChange(photosLocationPref, newPhotosLocation);
                }
                break;
        }
    }
}
