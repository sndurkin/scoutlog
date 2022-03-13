package com.sndurkin.locationscout.settings;

import android.os.Bundle;

import com.google.android.gms.analytics.HitBuilders;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.util.Strings;


public class AdvancedSettingsFragment extends SettingsFragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_advanced);
        getActivity().setTitle(R.string.settings_advanced_title);

        tracker.setScreenName(Strings.SETTINGS_ADVANCED_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());
    }

}
