package com.sndurkin.locationscout.settings;


import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;

import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.Application;

public class SettingsFragment extends PreferenceFragment {

    protected Tracker tracker;
    protected SharedPreferences preferences;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tracker = ((Application) getActivity().getApplication()).getTracker();
        preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
    }

    // A preference value change listener that updates the preference's summary to reflect its new value.
    protected static Preference.OnPreferenceChangeListener bindPreferenceSummaryListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object value) {
            String stringValue = value.toString();

            if(preference instanceof ListPreference) {
                // For list preferences, look up the correct display value in
                // the preference's 'entries' list.
                ListPreference listPreference = (ListPreference) preference;
                int index = listPreference.findIndexOfValue(stringValue);

                // Set the summary to reflect the new value.
                preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);
            }
            else {
                // For all other preferences, set the summary to the value's
                // simple string representation.
                preference.setSummary(stringValue);
            }
            return true;
        }
    };

    // Binds a preference's summary to its value. More specifically, when the
    // preference's value is changed, its summary (line of text below the
    // preference title) is updated to reflect the value. The summary is also
    // immediately updated upon calling this method. The exact display format is
    // dependent on the type of preference.
    protected static void bindPreferenceSummaryToValue(Preference preference) {
        bindPreferenceSummaryToValue(preference, bindPreferenceSummaryListener);
    }

    protected static void bindPreferenceSummaryToValue(Preference preference, Preference.OnPreferenceChangeListener listener) {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(listener);
        initPreferenceSummaryValue(preference, listener);
    }

    protected static void initPreferenceSummaryValue(Preference preference, Preference.OnPreferenceChangeListener listener) {
        String val = PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getString(preference.getKey(), "");
        listener.onPreferenceChange(preference, val);
    }

}
