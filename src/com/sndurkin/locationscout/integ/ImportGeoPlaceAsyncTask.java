package com.sndurkin.locationscout.integ;

import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.widget.Toast;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.Application;
import com.sndurkin.locationscout.MainActivity;
import com.sndurkin.locationscout.util.Strings;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ImportGeoPlaceAsyncTask extends AsyncTask<Object, Void, PlaceResult> {

    public static final Pattern NAME_PATTERN = Pattern.compile("[\\+\\s]*\\((.*)\\)[\\+\\s]*$");
    public static final Pattern COORDS_PATTERN = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?),([+-]?\\d+(?:\\.\\d+)?)");

    private WeakReference<MainActivity> activityWeakReference;

    public ImportGeoPlaceAsyncTask(MainActivity activity) {
        this.activityWeakReference = new WeakReference<>(activity);
    }

    @Override
    protected PlaceResult doInBackground(Object... params) {
        Uri dataUri = (Uri) params[0];
        return importPlace(dataUri);
    }

    @Override
    protected void onPostExecute(PlaceResult placeResult) {
        MainActivity activity = activityWeakReference.get();
        if(activity != null) {
            if(placeResult.errorMsg != null) {
                Toast.makeText(activity, placeResult.errorMsg, Toast.LENGTH_SHORT).show();
                activity.finish();
            }
            else {
                // Launch the list map screen and drop a pin on it.
                activity.dropMapPin(new LatLng(placeResult.latitude, placeResult.longitude), placeResult.title);
                activity.hideImportDialog();
            }
        }
    }

    @NonNull
    public static PlaceResult importPlace(Uri dataUri) {
        try {
            String schemePart = dataUri.getSchemeSpecificPart();
            if(schemePart != null) {
                // Example format:
                //
                //      geo:-33.81,45.05?q=-33.81,45.05 (Example name)
                Matcher nameMatcher = NAME_PATTERN.matcher(schemePart);
                String name = null;
                if(nameMatcher.find()) {
                    name = URLDecoder.decode(nameMatcher.group(1), "UTF-8");
                    if(name != null) {
                        schemePart = schemePart.substring(0, nameMatcher.start());
                    }
                }

                String coordsPart;
                int queryStartIdx = schemePart.indexOf('?');
                if (queryStartIdx == -1) {
                    coordsPart = schemePart;
                }
                else {
                    coordsPart = schemePart.substring(0, queryStartIdx);
                }

                Matcher coordsMatcher = COORDS_PATTERN.matcher(coordsPart);
                if(coordsMatcher.find()) {
                    double latitude = Double.valueOf(coordsMatcher.group(1));
                    double longitude = Double.valueOf(coordsMatcher.group(2));

                    Application.getInstance().getTracker().send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_IMPORT)
                            .setLabel(Strings.LBL_IMPORT_GEO)
                            .build());

                    return new PlaceResult(latitude, longitude, name);
                }
                else {
                    CrashlyticsCore.getInstance().logException(new ParseException("Import data not recognized: " + schemePart));
                }
            }
        }
        catch (UnsupportedEncodingException e) {
            CrashlyticsCore.getInstance().logException(e);
        }

        return new PlaceResult();
    }

}