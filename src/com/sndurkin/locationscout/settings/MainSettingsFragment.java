package com.sndurkin.locationscout.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import com.google.android.gms.analytics.HitBuilders;
import com.sndurkin.locationscout.AboutActivity;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;
import com.sndurkin.locationscout.util.Versions;

public class MainSettingsFragment extends SettingsFragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_main);

        tracker.setScreenName(Strings.SETTINGS_MAIN_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        initUnlockPref();
        initTranslatePref();
        initAboutPref();

        Versions.checkAndUpdate(preferences, Strings.SETTINGS_SCREEN_VERSION, Versions.Defaults.SETTINGS_SCREEN_VERSION, null);
    }

    @Override
    public void onResume() {
        super.onResume();
        if(isAdded()) {
            getActivity().setTitle(R.string.settings_main_title);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        super.onPreferenceTreeClick(preferenceScreen, preference);

        // If the user has clicked on a preference screen, set up the screen
        if("pref_category_display".equals(preference.getKey())) {
            getFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new DisplaySettingsFragment())
                    .addToBackStack("display")
                    .commit();
            return true;
        }
        else if("pref_category_behavior".equals(preference.getKey())) {
            getFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new BehaviorSettingsFragment())
                    .addToBackStack("behavior")
                    .commit();
            return true;
        }
        else if("pref_category_sync".equals(preference.getKey())) {
            getFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new SyncSettingsFragment())
                    .addToBackStack("sync")
                    .commit();
            return true;
        }
        else if("pref_category_integ".equals(preference.getKey())) {
            getFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new IntegSettingsFragment())
                    .addToBackStack("integ")
                    .commit();
            return true;
        }
        else if("pref_category_advanced".equals(preference.getKey())) {
            getFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new AdvancedSettingsFragment())
                    .addToBackStack("advanced")
                    .commit();
            return true;
        }

        return false;
    }

    protected void initUnlockPref() {
        Preference unlockPref = findPreference(Strings.PREF_UNLOCK);
        if(UIUtils.hasUserUnlockedApp(getActivity())) {
            getPreferenceScreen().removePreference(unlockPref);
            return;
        }

        unlockPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_SETTING)
                        .setLabel(Strings.LBL_UNLOCK_APP)
                        .build());

                final String packageName = getActivity().getPackageName() + ".unlock";
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
                }
                catch (android.content.ActivityNotFoundException anfe) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + packageName)));
                }
                return true;
            }
        });
    }

    protected void initTranslatePref() {
        Preference translatePref = findPreference(Strings.PREF_HELP_TRANSLATE);
        translatePref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("message/rfc822");
                intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"scoutlogapp@gmail.com"});
                intent.putExtra(Intent.EXTRA_SUBJECT, "Translate ScoutLog");
                intent.putExtra(Intent.EXTRA_TEXT, "I would like to help translate ScoutLog. I'm familiar with these languages: ");
                startActivity(intent);
                return true;
            }
        });
    }

    protected void initAboutPref() {
        Preference aboutPref = findPreference(Strings.PREF_ABOUT);
        aboutPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivity(new Intent(getActivity(), AboutActivity.class));
                return true;
            }
        });
    }

}