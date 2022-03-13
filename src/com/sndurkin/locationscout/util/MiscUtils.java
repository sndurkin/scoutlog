package com.sndurkin.locationscout.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.BuildConfig;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.*;

public class MiscUtils {

    private static Geocoder geocoder = null;
    private static final DecimalFormat LAT_LNG_FORMAT = new DecimalFormat("##0.000000");

    // Google API key for web; this is being used for the Places API.
    public static final String WEB_API_KEY = "<removed>";

    public static final String APP_FOLDER = "ScoutLog";
    public static final String APP_FOLDER_DEPRECATED = "Location Scout";

    public static String serializeLatLng(LatLng loc) {
        return loc.latitude + "," + loc.longitude;
    }

    public static LatLng deserializeLatLng(String locStr) {
        String[] locArr = locStr.split(",");
        return new LatLng(Double.parseDouble(locArr[0]), Double.parseDouble(locArr[1]));
    }

    public static String latLngToString(LatLng latLng) {
        return LAT_LNG_FORMAT.format(latLng.latitude) + ", " + LAT_LNG_FORMAT.format(latLng.longitude);
    }

    public static String coordToString(double latOrLng) {
        return LAT_LNG_FORMAT.format(latOrLng);
    }

    public static String serializeAddress(Address address) {
        StringBuffer addrStr = new StringBuffer();
        for(int i = 0; i <= address.getMaxAddressLineIndex(); ++i) {
            if(i > 0) {
                addrStr.append("\n");
            }
            addrStr.append(address.getAddressLine(i));
        }
        return addrStr.toString();
    }

    public static Address deserializeAddress(String addrStr) {
        Address address = new Address(Locale.getDefault());
        String[] addrLines = addrStr.split("\n");
        for(int i = 0; i < addrLines.length; ++i) {
            address.setAddressLine(i, addrLines[i]);
        }
        return address;
    }
    public static JSONArray convertListToJSON(List<Long> list) {
        JSONArray arr = new JSONArray();
        for(Long el : list) {
            arr.put(el);
        }
        return arr;
    }

    public static List<Long> convertJSONToList(JSONArray arr) throws JSONException {
        List<Long> list = new ArrayList<Long>();
        for(int i = 0; i < arr.length(); ++i) {
            list.add(arr.getLong(i));
        }
        return list;
    }

    public static String convertListToString(List<? extends Object> list, String delimiter) {
        if(list == null) {
            return "";
        }

        StringBuffer sb = new StringBuffer();
        for(int i = 0; i < list.size(); ++i) {
            if(i > 0) {
                sb.append(delimiter);
            }
            sb.append(list.get(i).toString());
        }
        return sb.toString();
    }

    public static long[] convertLongListToArray(List<Long> list) {
        long[] arr = new long[list.size()];
        for(int i = 0; i < arr.length; ++i) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    public static List<Long> convertLongArrayToList(long[] arr) {
        List<Long> list = new ArrayList<Long>();
        if(arr != null) {
            for(long el : arr) {
                list.add(el);
            }
        }
        return list;
    }

    public static int[] convertIntSetToArray(Set<Integer> list) {
        int[] arr = new int[list.size()];
        int i = 0;
        for(int v : list) {
            arr[i++] = v;
        }
        return arr;
    }

    public static Set<Integer> convertIntArrayToSet(int[] arr) {
        Set<Integer> list = new HashSet<Integer>();
        if(arr != null) {
            for(int el : arr) {
                list.add(el);
            }
        }
        return list;
    }

    @Nullable
    public static String getString(Cursor cursor, String columnName) {
        int colIdx = cursor.getColumnIndex(columnName);
        if(colIdx < 0 || cursor.isNull(colIdx)) {
            return null;
        }
        return cursor.getString(cursor.getColumnIndex(columnName));
    }

    @Nullable
    public static Long getLong(Cursor cursor, String columnName) {
        int colIdx = cursor.getColumnIndex(columnName);
        if(colIdx < 0 || cursor.isNull(colIdx)) {
            return null;
        }
        return cursor.getLong(cursor.getColumnIndex(columnName));
    }

    @Nullable
    public static Integer getInt(Cursor cursor, String columnName) {
        int colIdx = cursor.getColumnIndex(columnName);
        if(colIdx < 0 || cursor.isNull(colIdx)) {
            return null;
        }
        return cursor.getInt(cursor.getColumnIndex(columnName));
    }

    @Nullable
    public static Boolean getBoolean(Cursor cursor, String columnName) {
        int colIdx = cursor.getColumnIndex(columnName);
        if(colIdx < 0 || cursor.isNull(colIdx)) {
            return null;
        }
        return cursor.getInt(cursor.getColumnIndex(columnName)) == 1;
    }

    @NonNull
    public static Boolean getBoolean(Cursor cursor, String columnName, boolean defaultVal) {
        int colIdx = cursor.getColumnIndex(columnName);
        if(colIdx < 0 || cursor.isNull(colIdx)) {
            return defaultVal;
        }
        return cursor.getInt(cursor.getColumnIndex(columnName)) == 1;
    }

    public static boolean isNull(Cursor cursor, String columnName) {
        int colIdx = cursor.getColumnIndex(columnName);
        return (colIdx < 0 || cursor.isNull(colIdx));
    }

    public static boolean checkMD5(String m1, String m2) {
        if(m1 == null) {
            return false;
        }
        return m1.equalsIgnoreCase(m2);
    }

    // This block of code handles fetching the closest address inside an async task, returning the result via callback.
    public interface ClosestAddressListener {
        void onClosestAddressFound(String addressStr);
    }

    public static void getClosestAddressFromLocation(Context context, double latitude, double longitude, ClosestAddressListener listener) {
        new GetClosestAddressTask(context, listener).execute(latitude, longitude);
    }

    public static String getClosestAddressFromLocation(Context context, double latitude, double longitude) {
        if(geocoder == null) {
            geocoder = new Geocoder(context);
        }

        try {
            List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
            if(!addresses.isEmpty()) {
                return serializeAddress(addresses.get(0));
            }
        }
        catch(IOException e) {
            // Ignore, it just means it couldn't reach the server to perform a reverse geocoding lookup.
        }

        return null;
    }

    static class GetClosestAddressTask extends AsyncTask<Double, Void, String> {

        private Context context;
        private ClosestAddressListener listener;

        public GetClosestAddressTask(Context context, ClosestAddressListener listener) {
            this.context = context;
            this.listener = listener;
        }

        @Override
        protected String doInBackground(Double... params) {
            return getClosestAddressFromLocation(context, params[0], params[1]);
        }

        @Override
        protected void onPostExecute(String addressStr) {
            listener.onClosestAddressFound(addressStr);
        }
    }

    public static boolean equals(Object obj1, Object obj2) {
        if(obj1 == obj2) {
            return true;
        }
        if(obj1 == null || obj2 == null) {
            return false;
        }
        return obj1.equals(obj2);
    }

    public static void logi(String msg) { Log.i("ScoutLog", msg); }

    public static void logd(String msg) {
        if(BuildConfig.DEBUG) {
            Log.d("ScoutLog", msg);
        }
    }

    public static void logv(String msg) {
        if(BuildConfig.DEBUG) {
            Log.v("ScoutLog", msg);
        }
    }

    public static void logd(String msg, Exception e) {
        if(BuildConfig.DEBUG) {
            Log.d("ScoutLog", msg, e);
        }
    }

}
