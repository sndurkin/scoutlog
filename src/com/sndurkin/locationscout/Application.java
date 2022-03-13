package com.sndurkin.locationscout;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.multidex.MultiDex;
import android.support.v4.content.ContextCompat;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.util.Strings;

import java.util.TimerTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.fabric.sdk.android.Fabric;

public class Application extends android.app.Application implements GoogleApiClient.ConnectionCallbacks,
                                                                    GoogleApiClient.OnConnectionFailedListener,
                                                                    LocationListener {

    public static final String LOCATION_UPDATE_BROADCAST = "com.sndurkin.locationscout.LOCATION_UPDATE_BROADCAST";
    public static final String LOCATION_FAILED_BROADCAST = "com.sndurkin.locationscout.LOCATION_FAILED_BROADCAST";

    private static Application instance;

    private Tracker tracker;
    private Tracker timingTracker;

    private GlobalBroadcastManager broadcastManager;
    private GoogleApiClient apiClient;
    private Location lastLocation;
    private Long lastLocationFetchTime;

    private ScheduledThreadPoolExecutor executor;
    private TimerTask task;

    private boolean googlePlayServicesAvailable;

    public Application() {
        instance = this;
    }

    public static synchronized Application getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        CrashlyticsCore crashlyticsCore = new CrashlyticsCore.Builder().disabled(BuildConfig.DEBUG).build();
        Fabric.with(this, new Crashlytics.Builder().core(crashlyticsCore).build());

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(Strings.PREF_SESSION_COUNT, preferences.getInt(Strings.PREF_SESSION_COUNT, 0) + 1);
        editor.remove(Strings.PREF_DISPLAYED_REVIEW_MESSAGE);
        editor.apply();

        broadcastManager = GlobalBroadcastManager.getInstance(this);
        apiClient = new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        task = new TimerTask() {
            @Override
            public void run() {
                // Cancel the location request and just use the last location (if available).
                if(apiClient.isConnected()) {
                    LocationServices.FusedLocationApi.removeLocationUpdates(apiClient, Application.this);
                    if(ContextCompat.checkSelfPermission(Application.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        onLocationChanged(LocationServices.FusedLocationApi.getLastLocation(apiClient));
                    }
                }
                else {
                    onLocationChanged(null);
                }
            }
        };
        /*
        if(googlePlayServicesAvailable) {
            apiClient.connect();
        }
        */
        DatabaseHelper.getInstance(getApplicationContext()).ensureModelsAreDeleted(null);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        if(BuildConfig.DEBUG) {
            MultiDex.install(this);
        }
    }

    public void setGooglePlayServicesAvailable(boolean available) {
        googlePlayServicesAvailable = available;
    }

    public synchronized Tracker getTracker() {
        if(tracker == null) {
            GoogleAnalytics analytics = GoogleAnalytics.getInstance(this);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            analytics.setAppOptOut(!preferences.getBoolean(Strings.PREF_REPORTING, true));
            analytics.enableAutoActivityReports(this);

            tracker = analytics.newTracker(BuildConfig.DEBUG ? R.xml.analytics_tracker_dev
                                                             : R.xml.analytics_tracker_prod);
        }

        return tracker;
    }

    public synchronized Tracker getTimingTracker() {
        if(timingTracker == null) {
            GoogleAnalytics analytics = GoogleAnalytics.getInstance(this);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            analytics.setAppOptOut(!preferences.getBoolean(Strings.PREF_REPORTING, true));

            timingTracker = analytics.newTracker(BuildConfig.DEBUG ? R.xml.analytics_timing_tracker_dev
                                                                   : R.xml.analytics_timing_tracker_prod);
        }

        return timingTracker;
    }

    @Override
    public void onConnected(Bundle bundle) {
        if(ContextCompat.checkSelfPermission(Application.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationRequest locationRequest = LocationRequest.create()
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                    .setNumUpdates(1);
            LocationServices.FusedLocationApi.requestLocationUpdates(apiClient, locationRequest, this);

            executor = new ScheduledThreadPoolExecutor(1);
            executor.schedule(task, 3, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        executor.shutdownNow();
        lastLocation = location;
        apiClient.disconnect();

        if(lastLocation == null) {
            broadcastManager.sendBroadcast(new Intent(LOCATION_FAILED_BROADCAST));
            return;
        }

        lastLocationFetchTime = System.currentTimeMillis();

        Intent locationUpdateIntent = new Intent(LOCATION_UPDATE_BROADCAST);
        locationUpdateIntent.putExtra("location", lastLocation);
        broadcastManager.sendBroadcast(locationUpdateIntent);
    }

    @Override
    public void onConnectionSuspended(int i) {
        executor = null;
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        executor = null;
        broadcastManager.sendBroadcast(new Intent(LOCATION_FAILED_BROADCAST));
    }

    // This function will return the last known location and will update the current location if it was
    // fetched less than [bufferTime] ms ago. If [bufferTime] is null, it will default to 5 minutes.
    public LocationResponse getCurrentLocation(Long bufferTime) {
        if(bufferTime == null) {
            bufferTime = 300000L;
        }

        boolean fetchingNewLocation = false;
        boolean permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if(permissionGranted) {
            // If the last location was fetched more than [bufferTime] ms ago, update it.
            if(lastLocationFetchTime == null || System.currentTimeMillis() - lastLocationFetchTime > bufferTime) {
                if(googlePlayServicesAvailable && !apiClient.isConnecting() && !apiClient.isConnected()) {
                    fetchingNewLocation = true;
                    apiClient.connect();
                }
            }
        }

        return new LocationResponse(lastLocation, fetchingNewLocation, permissionGranted);
    }

    class LocationResponse {
        public Location location;
        public boolean fetchingNewLocation;
        public boolean permissionGranted;

        public LocationResponse(Location location, boolean fetchingNewLocation, boolean permissionGranted) {
            this.location = location;
            this.fetchingNewLocation = fetchingNewLocation;
            this.permissionGranted = permissionGranted;
        }

        public LatLng getLatLng() {
            return new LatLng(location.getLatitude(), location.getLongitude());
        }
    }

}
