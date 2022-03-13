package com.sndurkin.locationscout.util;

import android.content.SharedPreferences;

public class Versions {

    public static void checkAndUpdate(SharedPreferences preferences, String preferenceName, int version, Listener listener) {
        boolean shouldUpdate = false;
        if(preferences.contains(preferenceName)) {
            if(preferences.getInt(preferenceName, version) < version) {
                shouldUpdate = true;
                if(listener != null) {
                    listener.onUpdateVersion();
                }
            }
        }
        else {
            shouldUpdate = true;
            if(listener != null) {
                listener.onFirstVersion();
            }
        }

        if(shouldUpdate) {
            update(preferences, preferenceName, version);
        }
    }

    public static void check(SharedPreferences preferences, String preferenceName, int version, Listener listener) {
        if(preferences.contains(preferenceName)) {
            if(preferences.getInt(preferenceName, version) < version) {
                if(listener != null) {
                    listener.onUpdateVersion();
                }
            }
        }
        else {
            if(listener != null) {
                listener.onFirstVersion();
            }
        }
    }

    public static void update(SharedPreferences preferences, String preferenceName, int version) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(preferenceName, version);
        editor.commit();
    }

    public interface Listener {
        void onFirstVersion();
        void onUpdateVersion();
    }

    public class Defaults {

        public static final int SELECT_TAGS_HELP_VERSION = 1;
        public static final int BULK_EDIT_TAGS_HELP_VERSION = 1;
        public static final int STICKY_CURRENT_LOCATION_HELP_VERSION = 1;

        public static final int APP_VERSION = 1;
        public static final int NAV_DRAWER_VERSION = 1;
        public static final int BROWSE_BY_TAGS_SCREEN_VERSION = 1;
        public static final int SETTINGS_SCREEN_VERSION = 1;
        public static final int DETAIL_SCREEN_VERSION = 1;

        // TODO: add defaults for other preferences

    }

}
