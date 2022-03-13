package com.sndurkin.locationscout.util;


import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.MainActivity;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.Snackbar;
import com.sndurkin.locationscout.settings.SettingsActivity;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;

import java.io.File;

public class UIUtils {

    public static void showSyncWarningIfApplicable(final Activity activity, final Tracker tracker) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        if(preferences.getBoolean(Strings.PREF_DISMISSED_SYNC_WARNING, false) ||
           preferences.getString(Strings.PREF_ACCOUNT, null) != null) {
            return;
        }

        DatabaseHelper.getInstance(activity).fetchLocationsCount(new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                long count = result.cursor.getLong(result.cursor.getColumnIndex(DatabaseHelper.TRANSIENT_COUNT_COLUMN));
                if(count > 15) {
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.sync_warning_dialog_title)
                            .setMessage(R.string.sync_warning_dialog_message)
                            .setPositiveButton(R.string.open_sync_settings, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    SharedPreferences.Editor editor = preferences.edit();
                                    editor.putBoolean(Strings.PREF_DISMISSED_SYNC_WARNING, true);
                                    editor.apply();

                                    tracker.send(new HitBuilders.EventBuilder()
                                            .setCategory(Strings.CAT_UI_ACTION)
                                            .setAction(Strings.ACT_CLICK_BUTTON)
                                            .setLabel(Strings.LBL_SYNC_WARNING_OPEN_SETTINGS)
                                            .build());

                                    Intent intent = new Intent(activity, SettingsActivity.class);
                                    intent.putExtra(Strings.PARAM_SETTINGS_SCREEN_TYPE, "sync");
                                    activity.startActivity(intent);
                                }
                            })
                            .setNegativeButton(R.string.dismiss, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    SharedPreferences.Editor editor = preferences.edit();
                                    editor.putBoolean(Strings.PREF_DISMISSED_SYNC_WARNING, true);
                                    editor.apply();

                                    tracker.send(new HitBuilders.EventBuilder()
                                            .setCategory(Strings.CAT_UI_ACTION)
                                            .setAction(Strings.ACT_CLICK_BUTTON)
                                            .setLabel(Strings.LBL_SYNC_WARNING_DISMISS)
                                            .build());
                                }
                            })
                            .show();
                }
            }
        });
    }

    public static boolean showReviewMessageIfApplicable(final MainActivity activity, final Tracker tracker) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        if(preferences.getBoolean(Strings.PREF_DISMISSED_REVIEW_MESSAGE, false) ||
           preferences.getBoolean(Strings.PREF_DISPLAYED_REVIEW_MESSAGE, false)) {
            return false;
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(Strings.PREF_DISPLAYED_REVIEW_MESSAGE, true);
        editor.apply();

        int sessionCount = preferences.getInt(Strings.PREF_SESSION_COUNT, 0);
        if(sessionCount >= 15 && (sessionCount % 5) == 0) {
            Snackbar.ShowConfig config = activity.getSnackbar().new ShowConfig();
            config.text = activity.getString(R.string.review_app_message);
            config.showButton = true;
            config.showCloseIcon = true;
            config.buttonText = R.string.review_app_action;
            config.expireTime = Snackbar.LONG_SNACKBAR_EXPIRE_TIME;
            config.listener = activity.getSnackbar().new Listener() {
                @Override
                public void onExpired() { }

                @Override
                public void onHidden() { }

                @Override
                public void onButtonClicked() {
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putBoolean(Strings.PREF_DISMISSED_REVIEW_MESSAGE, true);
                    editor.apply();

                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_REVIEW_APP_YES)
                            .build());

                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + activity.getPackageName())));
                    }
                    catch (android.content.ActivityNotFoundException anfe) {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + activity.getPackageName())));
                    }
                }

                @Override
                public void onClosed() {
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putBoolean(Strings.PREF_DISMISSED_REVIEW_MESSAGE, true);
                    editor.apply();

                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_REVIEW_APP_NO)
                            .build());
                }
            };
            activity.getSnackbar().show(config);
            return true;
        }

        return false;
    }

    public static void showKeyboard(Activity activity, View view) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }

    public static void hideKeyboard(Activity activity) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        View currentlyFocusedView = activity.getCurrentFocus();
        if(currentlyFocusedView != null) {
            imm.hideSoftInputFromWindow(currentlyFocusedView.getWindowToken(), 0);
        }
    }

    public static int getCurrentTheme(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if(preferences.getString(Strings.PREF_THEME, "0").equals("0")) {
            return R.style.Theme_Light;
        }
        else {
            return R.style.Theme_Dark;
        }
    }

    public static int getCurrentThemeNoActionBar(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if(preferences.getString(Strings.PREF_THEME, "0").equals("0")) {
            return R.style.Theme_Light_NoActionBar;
        }
        else {
            return R.style.Theme_Dark_NoActionBar;
        }
    }

    // Adds a hint (in the form of a Toast) to a clickable view using the view's content description.
    public static void addHintFunctionalityToView(final Context context, final View view) {
        final String hint = view.getContentDescription().toString();
        if(!hint.isEmpty()) {
            view.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    int[] pos = new int[2];
                    view.getLocationInWindow(pos);

                    Toast t = Toast.makeText(context, hint, Toast.LENGTH_SHORT);
                    t.setGravity(Gravity.TOP | Gravity.LEFT, pos[0], pos[1] + view.getHeight());
                    t.show();
                    return true;
                }
            });
        }
    }

    public static void openNavigationApp(Activity activity, LocationInfo locationInfo) {
        if(locationInfo == null) {
            return;
        }

        // See https://developer.android.com/guide/components/intents-common.html#Maps for information
        // on how to properly format this intent. I wasn't able to use the label variant (for derived
        // addresses) because HERE maps did not support it.
        String geoUriStr = "geo:" + locationInfo.location.latitude + "," + locationInfo.location.longitude;
        if (locationInfo.isAddressDerived) {
            geoUriStr = geoUriStr + "?q=" + locationInfo.location.latitude + "," + locationInfo.location.longitude;
        }
        else {
            geoUriStr = geoUriStr + "?q=" + Uri.encode(locationInfo.addressStr);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(geoUriStr));
        activity.startActivity(intent);
    }

    public static BitmapDescriptor getMapMarkerIcon(Context context, int color, String iconPath, LruCache<String, Bitmap> iconCache) {
        if(TextUtils.isEmpty(iconPath)) {
            return getMapMarkerIcon(context, color);
        }

        File iconFile = new File(iconPath);
        if(!iconFile.exists() || !iconFile.canRead()) {
            return getMapMarkerIcon(context, color);
        }

        Bitmap bitmap = null;
        if(iconCache != null) {
            bitmap = iconCache.get(iconPath);
        }
        if(bitmap == null) {
            int smallerSideSize = dpToPx(context, 24);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(iconPath, opts);

            int scale;
            if (opts.outHeight > opts.outWidth) {
                scale = Math.round(opts.outHeight / smallerSideSize);
            }
            else {
                scale = Math.round(opts.outWidth / smallerSideSize);
            }

            // Decode with inSampleSize
            opts = new BitmapFactory.Options();
            opts.inSampleSize = scale;
            bitmap = BitmapFactory.decodeFile(iconPath, opts);

            if(bitmap == null) {
                CrashlyticsCore.getInstance().logException(new RuntimeException("Cannot decode file into bitmap, but it exists and we have permissions to read it: " + iconPath));
                return getMapMarkerIcon(context, color);
            }

            if(iconCache != null) {
                iconCache.put(iconPath, bitmap);
            }
        }

        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    public static BitmapDescriptor getDefaultMapMarkerIcon(Context context) {
        return getMapMarkerIcon(context, 0);
    }

    public static BitmapDescriptor getMapMarkerIcon(Context context, int color) {
        if(color == 0) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            color = preferences.getInt(Strings.PREF_MAP_MARKER_COLOR, Color.RED);
        }

        Bitmap sourceBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.marker);
        Bitmap resultBitmap = Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.getWidth() - 1, sourceBitmap.getHeight() - 1);
        sourceBitmap.recycle();

        Paint p = new Paint();
        ColorFilter filter = new LightingColorFilter(color, 0);
        p.setColorFilter(filter);

        Canvas canvas = new Canvas(resultBitmap);
        canvas.drawBitmap(resultBitmap, 0, 0, p);

        BitmapDescriptor desc = BitmapDescriptorFactory.fromBitmap(resultBitmap);
        resultBitmap.recycle();
        return desc;
    }

    // This method is used to determine whether the user has paid for the app by checking the
    // signatures between this package and the app with package "com.sndurkin.locationscout.unlock"
    // or "com.sndurkin.locationscout.unlock.free".
    public static boolean hasUserUnlockedApp(Context context) {
        int sigMatch = context.getPackageManager().checkSignatures(context.getPackageName(), context.getPackageName() + ".unlock");
        if(sigMatch == PackageManager.SIGNATURE_MATCH) {
            return true;
        }
        else if(sigMatch != PackageManager.SIGNATURE_UNKNOWN_PACKAGE) {
            Crashlytics.getInstance().core.logException(new RuntimeException("Invalid package signature return value: " + sigMatch));
        }

        sigMatch = context.getPackageManager().checkSignatures(context.getPackageName(), context.getPackageName() + ".unlock.free");
        if(sigMatch == PackageManager.SIGNATURE_MATCH) {
            return true;
        }
        else if(sigMatch != PackageManager.SIGNATURE_UNKNOWN_PACKAGE) {
            Crashlytics.getInstance().core.logException(new RuntimeException("Invalid package signature return value: " + sigMatch));
        }

        return false;
    }

    public static int dpToPx(Context context, int dp) {
        return Math.round((float) dp * context.getResources().getDisplayMetrics().density);
    }

    public static int pxToDp(Context context, int px) {
        return Math.round((float) px / context.getResources().getDisplayMetrics().density);
    }

}
