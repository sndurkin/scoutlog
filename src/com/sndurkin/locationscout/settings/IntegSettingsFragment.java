package com.sndurkin.locationscout.settings;


import android.os.Bundle;
import android.preference.ListPreference;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.google.android.gms.analytics.HitBuilders;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.util.Strings;

public class IntegSettingsFragment extends SettingsFragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_integ);
        setHasOptionsMenu(true);

        tracker.setScreenName(Strings.SETTINGS_INTEG_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        initImportMultiplePhotosPref();
    }

    @Override
    public void onResume() {
        super.onResume();

        if(isAdded()) {
            getActivity().setTitle(R.string.settings_integ_title);
        }
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
                        .replace(android.R.id.content, new IntegHelpFragment())
                        .addToBackStack("integ_help")
                        .commit();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    protected void initImportMultiplePhotosPref() {
        ListPreference importMultiplePhotosPref = (ListPreference) findPreference(Strings.PREF_IMPORT_MULTIPLE_PHOTOS);
        bindPreferenceSummaryToValue(importMultiplePhotosPref);
    }

}
