package com.sndurkin.locationscout.util;


import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

public class ErrorUtils {

    // There is a mechanism besides crash reporting that helps debug exceptions in the sync
    // algorithm, for users who seem to have extra trouble. It saves the 5 most recent
    // exceptions thrown by the sync algorithm in the shared preferences, and at any point
    // they can be sent by the user to me via email through the app UI.
    //
    // This function saves a sync exception to the shared preferences. 5 preferences are
    // reserved for the exceptions, and 1 to store the indices of the head and tail of
    // the exception list. It rotates like this:
    //
    //   head                tail
    //   0    1    2    3    4
    //  --------------------------
    //   ex1  ex2  ex3  ex4  ex5
    //
    //  >> another exception is saved
    //
    //   tail head
    //   0    1    2    3    4
    //  --------------------------
    //   ex6  ex2  ex3  ex4  ex5
    //
    private static final int MAX_NUM_EXCEPTIONS_TO_SAVE = 5;
    public static void saveSyncException(Context context, Exception e) {
        // Print the stacktrace to a string.
        Writer stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        e.printStackTrace(printWriter);
        String stacktrace = stringWriter.toString();

        String appVersion;
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
            appVersion = info.versionName + " (" + info.versionCode + ")";
        }
        catch(PackageManager.NameNotFoundException nnfe) {
            appVersion = "Unknown";
        }

        // Gather the other information to save.
        StringBuffer sb = new StringBuffer();
        sb.append("Date: " + getCurrentDateInISOFormat()).append("\n");
        sb.append("Device: " + Build.MANUFACTURER + " " + Build.MODEL + "(\"" + Build.PRODUCT + "\") running SDK " + Build.VERSION.SDK_INT).append("\n");
        sb.append("App Version: " + appVersion).append("\n\n");
        sb.append(stacktrace);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String[] indices = preferences.getString(Strings.PREF_SYNC_EXCEPTION_INDICES, "0,0").split(",");
        int head = Integer.parseInt(indices[0]);
        int tail = Integer.parseInt(indices[1]);

        // There are 3 usecases:
        //  1) Exception list is empty, so add at the tail, then increment the tail.
        //  2) Exception list is full, so increment both the head and the tail, then add at the tail.
        //  3) Exception list is not full, so increment the tail, then add at the tail.
        SharedPreferences.Editor editor = preferences.edit();
        if(head == tail) {
            editor.putString(Strings.PREF_SYNC_EXCEPTION_PREFIX + tail, sb.toString());

            tail = (tail + 1) % MAX_NUM_EXCEPTIONS_TO_SAVE;
        }
        else if(((tail + 1) % MAX_NUM_EXCEPTIONS_TO_SAVE) == head) {
            head = (head + 1) % MAX_NUM_EXCEPTIONS_TO_SAVE;
            tail = (tail + 1) % MAX_NUM_EXCEPTIONS_TO_SAVE;

            editor.putString(Strings.PREF_SYNC_EXCEPTION_PREFIX + tail, sb.toString());
        }
        else {
            tail = (tail + 1) % MAX_NUM_EXCEPTIONS_TO_SAVE;

            editor.putString(Strings.PREF_SYNC_EXCEPTION_PREFIX + tail, sb.toString());
        }

        editor.putString(Strings.PREF_SYNC_EXCEPTION_INDICES, head + "," + tail);
        editor.apply();
    }

    public static String getSavedExceptions(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String[] indices = preferences.getString(Strings.PREF_SYNC_EXCEPTION_INDICES, "0,0").split(",");
        int head = Integer.parseInt(indices[0]);
        int tail = Integer.parseInt(indices[1]);

        // Iterate the list from head to tail (inclusive) and add the exceptions to a list, reverse
        // that list so that the most recent is at the front, join them together, and return the result.
        List<String> exceptionsList = new ArrayList<>(MAX_NUM_EXCEPTIONS_TO_SAVE);
        int i = head;
        boolean reachedTail = false;
        while(true) {
            exceptionsList.add(preferences.getString(Strings.PREF_SYNC_EXCEPTION_PREFIX + i, null));

            if(reachedTail) {
                break;
            }
            else if(i == tail) {
                reachedTail = true;
            }

            i = (i + 1) % MAX_NUM_EXCEPTIONS_TO_SAVE;
        }
        Collections.reverse(exceptionsList);
        return TextUtils.join("\n\n-----\n\n", exceptionsList);
    }

    private static final String ISO_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS zzz";
    private static String getCurrentDateInISOFormat() {
        SimpleDateFormat sdf = new SimpleDateFormat(ISO_FORMAT);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

}
