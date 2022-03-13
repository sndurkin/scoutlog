package com.sndurkin.locationscout.util;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.Application;
import com.sndurkin.locationscout.R;

public class UnlockAppDialog extends AlertDialog {

    private Tracker tracker;

    public static boolean shouldShowDialog(Context context, int numLocations) {
        if(UIUtils.hasUserUnlockedApp(context)) {
            return false;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean userHasAlreadyExtendedTrial = preferences.getBoolean(Strings.PREF_EXTENDED_TRIAL, false);

        if(!userHasAlreadyExtendedTrial) {
            return numLocations >= 10;
        }
        else {
            return numLocations >= 15;
        }
    }

    public UnlockAppDialog(final Context context) {
        super(context);

        tracker = Application.getInstance().getTracker();
        tracker.send(new HitBuilders.EventBuilder()
                .setCategory(Strings.CAT_UI_EVENT)
                .setLabel(Strings.LBL_ADD_PLACE_LOCKED)
                .build());

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean userHasAlreadyExtendedTrial = preferences.getBoolean(Strings.PREF_EXTENDED_TRIAL, false);

        setTitle(R.string.unlock_app_title);
        if(!userHasAlreadyExtendedTrial) {
            setMessage(context.getString(R.string.unlock_app_places_10_message));
        }
        else {
            setMessage(context.getString(R.string.unlock_app_places_15_message));
        }

        OnClickListener buttonListener = new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch(which) {
                    case BUTTON_NEGATIVE:
                        if(!userHasAlreadyExtendedTrial) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_UNLOCK_APP_NO)
                                    .build());
                        }
                        else {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_UNLOCK_APP_EXTENSION_NO)
                                    .build());
                        }
                        break;
                    case BUTTON_POSITIVE:
                        if(!userHasAlreadyExtendedTrial) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_UNLOCK_APP_YES)
                                    .build());
                        }
                        else {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_UNLOCK_APP_EXTENSION_YES)
                                    .build());
                        }

                        String unlockPkgName = context.getPackageName() + ".unlock";
                        try {
                            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + unlockPkgName)));
                        }
                        catch (android.content.ActivityNotFoundException anfe) {
                            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + unlockPkgName)));
                        }
                        break;
                    case BUTTON_NEUTRAL:
                        tracker.send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_BUTTON)
                                .setLabel(Strings.LBL_UNLOCK_APP_EXTENSION)
                                .build());

                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putBoolean(Strings.PREF_EXTENDED_TRIAL, true);
                        editor.apply();
                        break;
                }
            }
        };

        setButton(AlertDialog.BUTTON_NEGATIVE, context.getString(R.string.unlock_app_no), buttonListener);
        setButton(AlertDialog.BUTTON_POSITIVE, context.getString(R.string.unlock_app_yes), buttonListener);
        if(!userHasAlreadyExtendedTrial) {
            setButton(AlertDialog.BUTTON_NEUTRAL, context.getString(R.string.unlock_app_extension), buttonListener);
        }

        setOnCancelListener(new OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                if(!userHasAlreadyExtendedTrial) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_UNLOCK_APP_NO)
                            .build());
                }
                else {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_UNLOCK_APP_EXTENSION_NO)
                            .build());
                }
            }
        });
    }

}

