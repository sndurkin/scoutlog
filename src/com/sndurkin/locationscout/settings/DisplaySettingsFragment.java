package com.sndurkin.locationscout.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;

import com.google.android.gms.analytics.HitBuilders;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.util.PhotoLoader;
import com.sndurkin.locationscout.util.Strings;


public class DisplaySettingsFragment extends SettingsFragment {

    private ColorPickerPreference mapMarkerColorPref;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_display);
        getActivity().setTitle(R.string.settings_display_title);

        tracker.setScreenName(Strings.SETTINGS_DISPLAY_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        initThemePref();
        initMeasurementPref();
        initMapMarkerColorPref();
    }

    @Override
    public void onStop() {
        if(mapMarkerColorPref != null && mapMarkerColorPref.getColorPickerDialog() != null) {
            mapMarkerColorPref.getColorPickerDialog().dismiss();
        }
        super.onStop();
    }

    protected void initThemePref() {
        final ListPreference themePref = (ListPreference) findPreference(Strings.PREF_THEME);
        bindPreferenceSummaryToValue(themePref, new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(final Preference preference, final Object newValue) {
                final String currentValue = preferences.getString(Strings.PREF_THEME, "0");
                if (!currentValue.equals(newValue) && isAdded()) {
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putString(Strings.PREF_THEME, (String) newValue);
                    editor.commit();

                    getActivity().recreate();
                    return false;
                }

                return bindPreferenceSummaryListener.onPreferenceChange(preference, newValue);
            }
        });
    }

    protected void initMeasurementPref() {
        ListPreference measurementPref = (ListPreference) findPreference(Strings.PREF_MEASUREMENT);
        bindPreferenceSummaryToValue(measurementPref);
    }

    protected void initMapMarkerColorPref() {
        mapMarkerColorPref = (ColorPickerPreference) findPreference(Strings.PREF_MAP_MARKER_COLOR);
    }
}
