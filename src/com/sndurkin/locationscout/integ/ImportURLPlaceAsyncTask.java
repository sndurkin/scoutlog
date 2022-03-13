package com.sndurkin.locationscout.integ;

import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.widget.Toast;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.Application;
import com.sndurkin.locationscout.MainActivity;
import com.sndurkin.locationscout.util.Strings;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URLDecoder;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ImportURLPlaceAsyncTask extends AsyncTask<Void, Void, PlaceResult> {

    public static final Pattern GOOGLE_URL_PATTERN = Pattern.compile("https?://goo\\.gl.*$");
    public static final Pattern GOOGLE_COORDS_PATTERN = Pattern.compile("\\[[+-]?\\d+(?:\\.\\d+)?,[+-]?\\d+(?:\\.\\d+)?\\]");

    public static final Pattern HERE_URL_PATTERN = Pattern.compile("https?://her\\.is.*$");
    public static final Pattern HERE_COORDS_PATTERN = Pattern.compile("map=[+-]?\\d+(?:\\.\\d+)?,[+-]?\\d+(?:\\.\\d+)?");

    private WeakReference<MainActivity> activityWeakReference;
    private String url;
    private String title;

    public ImportURLPlaceAsyncTask(MainActivity activity, String url, String title) {
        this.activityWeakReference = new WeakReference<>(activity);
        this.url = url;
        this.title = title;
    }

    @Override
    protected PlaceResult doInBackground(Void... params) {
        try {
            return importPlace(url);
        }
        catch(Exception e) {
            CrashlyticsCore.getInstance().logException(e);
        }

        return new PlaceResult();
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
                Tracker tracker = Application.getInstance().getTracker();
                if(GOOGLE_URL_PATTERN.matcher(url).matches()) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_EVENT)
                            .setLabel(Strings.LBL_IMPORT_GOOGLE_MAPS)
                            .build());
                }
                else if(HERE_URL_PATTERN.matcher(url).matches()) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_EVENT)
                            .setLabel(Strings.LBL_IMPORT_HERE_MAPS)
                            .build());
                }

                // Launch the list map screen and drop a pin on it.
                activity.dropMapPin(new LatLng(placeResult.latitude, placeResult.longitude), placeResult.title);
                activity.hideImportDialog();
            }
        }
    }

    @NonNull
    public static PlaceResult importPlace(String url) throws IOException, ParseException {
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);
        clientBuilder.interceptors().add(new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                Request request = chain.request();
                Response response = null;
                for (int tryCount = 0; tryCount < 3; ++tryCount) {
                    try {
                        response = chain.proceed(request);
                        if (response.isSuccessful()) {
                            break;
                        }
                    } catch (Exception e) {
                        CrashlyticsCore.getInstance().logException(e);
                    }
                }

                return response;
            }
        });
        OkHttpClient client = clientBuilder.build();

        Request request = new Request.Builder().url(url).build();
        Response response = client.newCall(request).execute();
        String resolvedUrl = URLDecoder.decode(response.request().url().toString(), "UTF-8");
        Response networkResponse = response.networkResponse();
        if(networkResponse.code() == 200) {
            String html = response.body().string();
            if(GOOGLE_URL_PATTERN.matcher(url).matches()) {
                Matcher matcher = GOOGLE_COORDS_PATTERN.matcher(html);
                while(matcher.find()) {
                    try {
                        JSONArray arr = new JSONArray(html.substring(matcher.start(), matcher.end()));
                        Double latitude = arr.getDouble(0);
                        Double longitude = arr.getDouble(1);
                        if(latitude < 90.0 && latitude > -90 && longitude < 180.0 && longitude > -180.0) {
                            Application.getInstance().getTracker().send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_IMPORT)
                                    .setLabel(Strings.LBL_IMPORT_GOOGLE_MAPS)
                                    .build());

                            return new PlaceResult(latitude, longitude);
                        }
                    }
                    catch(JSONException e) {
                        throw new ParseException("Failed to parse the response body for coordinates: " + url, e);
                    }
                }
            }
            else if(HERE_URL_PATTERN.matcher(url).matches()) {
                Matcher matcher = HERE_COORDS_PATTERN.matcher(resolvedUrl);
                if(matcher.find()) {
                    try {
                        String coordsStr = matcher.group();
                        coordsStr = coordsStr.substring(coordsStr.indexOf("=") + 1);
                        String[] coordsArr = coordsStr.split(",");
                        Double latitude = Double.parseDouble(coordsArr[0]);
                        Double longitude = Double.parseDouble(coordsArr[1]);

                        Application.getInstance().getTracker().send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_IMPORT)
                                .setLabel(Strings.LBL_IMPORT_HERE_MAPS)
                                .build());

                        return new PlaceResult(latitude, longitude);
                    }
                    catch(NumberFormatException e) {
                        throw new ParseException("Failed to parse the request url for coordinates: " + resolvedUrl, e);
                    }
                }
                else {
                    throw new ParseException("Failed to parse the request url for coordinates: " + resolvedUrl);
                }
            }
        }
        else {
            throw new IOException("Failed to import place, network code = " + networkResponse.code());
        }

        return new PlaceResult();
    }

}