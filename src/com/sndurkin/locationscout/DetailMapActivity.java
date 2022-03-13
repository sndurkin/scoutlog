package com.sndurkin.locationscout;


import android.Manifest;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.LocationSource;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.sndurkin.locationscout.util.LatLngParser;
import com.sndurkin.locationscout.util.LocationServicesDialog;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;
import com.sndurkin.locationscout.util.Versions;

import java.io.IOException;
import java.util.List;

public class DetailMapActivity extends AppCompatActivity implements LocationSource,
                                                                    GoogleMap.OnMyLocationButtonClickListener,
                                                                    OnMapReadyCallback {
    private static final int MAP_OVERLAY_ICON_SIZE_DP = 42;
    private static final int MAP_OVERLAY_ICON_MARGIN_DP = 12;

    private Tracker tracker;
    private SharedPreferences preferences;
    private GlobalBroadcastManager broadcastManager;
    private DetailMapActivityBroadcastReceiver broadcastReceiver;

    private boolean userRequestedMyLocation = false;

    private Geocoder geocoder;
    private GoogleMap map;
    private OnLocationChangedListener onLocationChangedListener;
    private LatLng currentLatLng;

    private Marker currentMarker;
    private LocationInfo locationInfo;

    private ImageButton toggleTerrainButton;
    //private ImageButton myLocationButton;
    //private ImageButton zoomInButton;
    //private ImageButton zoomOutButton;

    private SearchView searchView;
    private String currentQuery;

    private int markerColor;
    private String markerIconPath;
    public boolean locationChanged;
    private CameraPosition savedCameraPosition;

    private View cancelActionView;
    private View saveActionView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentThemeNoActionBar(this));
        super.onCreate(savedInstanceState);

        tracker = ((Application) getApplication()).getTracker();
        tracker.setScreenName(Strings.DETAIL_MAP_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        broadcastManager = GlobalBroadcastManager.getInstance(this);
        broadcastReceiver = new DetailMapActivityBroadcastReceiver();

        setContentView(R.layout.detail_map_activity);
        setupActionBar();

        toggleTerrainButton = (ImageButton) findViewById(R.id.map_toggle_terrain);
        geocoder = new Geocoder(this);

        cancelActionView = findViewById(R.id.map_action_cancel);
        saveActionView = findViewById(R.id.map_action_save);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        onNewIntent(getIntent());

        cancelActionView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_CANCEL_ADDR_EDIT)
                        .build());
                cancelAndFinish();
            }
        });
        saveActionView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_SAVE_ADDR_EDIT)
                        .build());
                if(currentMarker != null) {
                    saveAndFinish();
                }
                else {
                    cancelAndFinish();
                }
            }
        });

        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            markerColor = extras.getInt(Strings.PARAM_COLOR, 0);
            markerIconPath = extras.getString(Strings.PARAM_ICON_PATH);
            if(extras.containsKey("locationInfo")) {
                locationInfo = extras.getParcelable("locationInfo");
            }
        }

        Versions.checkAndUpdate(preferences, Strings.DETAIL_MAP_SCREEN_VERSION, Versions.Defaults.SETTINGS_SCREEN_VERSION, null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        if(Intent.ACTION_SEARCH.equals(intent.getAction())) {
            tracker.send(new HitBuilders.EventBuilder()
                    .setCategory(Strings.CAT_UI_ACTION)
                    .setAction(Strings.ACT_SEARCH)
                    .setLabel(Strings.LBL_RAW_TEXT)
                    .build());

            // This code is executed when the user enters a search query and executes the search without
            // selecting an autocomplete suggestion.
            String query = intent.getStringExtra(SearchManager.QUERY);
            executeAddressSearch(query);
        }
        else if(Intent.ACTION_VIEW.equals(intent.getAction()) && searchView != null) {
            tracker.send(new HitBuilders.EventBuilder()
                    .setCategory(Strings.CAT_UI_ACTION)
                    .setAction(Strings.ACT_SEARCH)
                    .setLabel(Strings.LBL_AUTOCOMPLETE_RESULT)
                    .build());

            // This code is executed when the user enters a search query and selects an autocomplete suggestion.
            String query = intent.getStringExtra(SearchManager.EXTRA_DATA_KEY);
            searchView.setQuery(query, false);
            searchView.clearFocus();

            String reference = intent.getDataString();
            MapAddressAutocompleteTask.loadLocation(this, reference);
        }
    }

    protected void cancelWithWarning() {
        if(locationChanged && preferences.getBoolean(Strings.PREF_NOTIFY_UNSAVED_CHANGES, true)) {
            new AlertDialog.Builder(DetailMapActivity.this)
                    .setTitle(R.string.notify_unsaved_tags_dialog_title)
                    .setMessage(R.string.notify_unsaved_tags_dialog_message)
                    .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_CANCEL_ADDR_EDIT_YES)
                                    .build());
                            cancelAndFinish();
                        }
                    })
                    .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_CANCEL_ADDR_EDIT_NO)
                                    .build());
                            // Do nothing.
                        }
                    })
                    .setNeutralButton(R.string.always, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_CANCEL_ADDR_EDIT_NEVER_AGAIN)
                                    .build());

                            SharedPreferences.Editor editor = preferences.edit();
                            editor.putBoolean(Strings.PREF_NOTIFY_UNSAVED_CHANGES, false);
                            editor.commit();

                            cancelAndFinish();
                        }
                    })
                    .show();
        }
        else {
            cancelAndFinish();
        }
    }

    protected void cancelAndFinish() {
        setResult(RESULT_CANCELED);
        finish();
    }

    protected void saveAndFinish() {
        Intent data = new Intent();
        data.putExtra("locationInfo", locationInfo);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public void onBackPressed() {
        cancelWithWarning();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable("locationInfo", locationInfo);
        outState.putBoolean(Strings.PARAM_DATA_CHANGED, locationChanged);
        outState.putInt(Strings.PARAM_COLOR, markerColor);
        outState.putString(Strings.PARAM_ICON_PATH, markerIconPath);
        if(map != null) {
            outState.putParcelable("cameraPosition", map.getCameraPosition());
        }
        if(searchView != null) {
            outState.putString("currentQuery", searchView.getQuery().toString());
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        locationInfo = savedInstanceState.getParcelable("locationInfo");
        locationChanged = savedInstanceState.getBoolean(Strings.PARAM_DATA_CHANGED);
        markerColor = savedInstanceState.getInt(Strings.PARAM_COLOR, 0);
        markerIconPath = savedInstanceState.getString(Strings.PARAM_ICON_PATH);
        setCurrentAddress(locationInfo, true);
        savedCameraPosition = (CameraPosition) savedInstanceState.get("cameraPosition");

        currentQuery = savedInstanceState.getString("currentQuery");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.detail_map_menu, menu);

        // Get the SearchView and set the searchable configuration
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        MenuItem searchItem = menu.findItem(R.id.map_action_search);
        searchView = (SearchView) MenuItemCompat.getActionView(searchItem);
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        searchView.setIconifiedByDefault(false);
        if(currentQuery != null) {
            searchView.setQuery(currentQuery, false);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case android.R.id.home:
                cancelWithWarning();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    protected void positionMapOverlayButtons() {
        int actionBarHeight = 0;
        TypedValue tv = new TypedValue();
        if(getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        }

        ViewGroup.MarginLayoutParams layoutParams = ((ViewGroup.MarginLayoutParams) toggleTerrainButton.getLayoutParams());
        layoutParams.topMargin = UIUtils.dpToPx(this, UIUtils.pxToDp(this, actionBarHeight) + MAP_OVERLAY_ICON_MARGIN_DP);
        layoutParams.leftMargin = UIUtils.dpToPx(this, MAP_OVERLAY_ICON_MARGIN_DP);
        toggleTerrainButton.setLayoutParams(layoutParams);

        map.setPadding(0, actionBarHeight, 0, actionBarHeight);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;
        map.getUiSettings().setTiltGesturesEnabled(false);
        map.getUiSettings().setRotateGesturesEnabled(false);
        map.getUiSettings().setCompassEnabled(false);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setMyLocationButtonEnabled(true);
        map.setOnMyLocationButtonClickListener(this);
        map.setLocationSource(this);
        map.setMapType(preferences.getInt(Strings.PREF_MAP_TYPE, GoogleMap.MAP_TYPE_NORMAL));
        setMyLocationEnabledOnMap();

        positionMapOverlayButtons();

        map.setOnMapLongClickListener(new GoogleMap.OnMapLongClickListener() {
            @Override
            public void onMapLongClick(LatLng latLng) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_PLACE_MARKER)
                        .build());
                locationChanged = true;
                setCurrentAddress(latLng, false);
            }
        });
        map.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override
            public View getInfoWindow(Marker marker) {
                return null;
            }

            @Override
            public View getInfoContents(Marker marker) {
                View infoWindowView = getLayoutInflater().inflate(R.layout.detail_map_info_window, null);
                ((TextView) infoWindowView.findViewById(R.id.map_marker_title)).setText(marker.getTitle());
                ((TextView) infoWindowView.findViewById(R.id.map_marker_address)).setText(marker.getSnippet());
                return infoWindowView;
            }
        });

        UIUtils.addHintFunctionalityToView(this, toggleTerrainButton);
        toggleTerrainButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences.Editor editor = preferences.edit();
                if (map.getMapType() == GoogleMap.MAP_TYPE_NORMAL) {
                    map.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    toggleTerrainButton.setImageResource(R.drawable.map_terrain_off);
                    editor.putInt(Strings.PREF_MAP_TYPE, GoogleMap.MAP_TYPE_HYBRID);
                } else {
                    map.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                    toggleTerrainButton.setImageResource(R.drawable.map_terrain_on);
                    editor.putInt(Strings.PREF_MAP_TYPE, GoogleMap.MAP_TYPE_NORMAL);
                }
                editor.commit();
            }
        });

        if(locationInfo != null) {
            setCurrentAddress(locationInfo, true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Application.LOCATION_UPDATE_BROADCAST);
        intentFilter.addAction(Application.LOCATION_FAILED_BROADCAST);
        broadcastManager.registerReceiver(DetailMapActivity.class.getCanonicalName(), broadcastReceiver, intentFilter, false);
        broadcastManager.sendLastBroadcast(DetailMapActivity.class.getCanonicalName(), broadcastReceiver, Application.LOCATION_UPDATE_BROADCAST);
    }

    @Override
    public void onPause() {
        broadcastManager.unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    @Override
    public void activate(OnLocationChangedListener onLocationChangedListener) {
        this.onLocationChangedListener = onLocationChangedListener;

        // When the user rotates the device, before it calls activate() to set the [onLocationChangedListener]
        // it calls onResume(), which sends the last location broadcast, which is normally what fires the
        // [onLocationChangedListener]. So, this code ensures that [onLocationChangedListener] is still
        // called on device rotate, so that the current location marker is displayed on the map.
        Application.LocationResponse locationResponse = ((Application) getApplication()).getCurrentLocation(null);
        if(locationResponse.location != null) {
            onLocationChangedListener.onLocationChanged(locationResponse.location);
        }
    }

    @Override
    public void deactivate() {
        onLocationChangedListener = null;
    }

    private void setMyLocationEnabledOnMap() {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.setMyLocationEnabled(true);
        }
        else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[] {
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    RequestCodes.PERMISSION_REQUEST_LOCATION);
        }
    }

    @Override
    public boolean onMyLocationButtonClick() {
        tracker.send(new HitBuilders.EventBuilder()
                .setCategory(Strings.CAT_UI_ACTION)
                .setAction(Strings.ACT_CLICK_BUTTON)
                .setLabel(Strings.LBL_CURRENT_LOCATION)
                .build());
        goToMyLocation(true);
        return true;
    }

    protected void goToMyLocation(boolean userRequest) {
        if(userRequest && LocationServicesDialog.showIfNecessary(this)) {
            return;
        }

        if(userRequestedMyLocation) {
            // The location has already been requested, so do nothing at this time.
            return;
        }
        userRequestedMyLocation = userRequest;

        if(currentLatLng != null && !userRequestedMyLocation) {
            // Use the cached location if it's available and the user isn't requesting
            // the device's location.
            setCurrentAddress(currentLatLng, true);
            return;
        }

        Application.LocationResponse locationResponse = ((Application) getApplication()).getCurrentLocation(userRequestedMyLocation ? 0L : null);
        if(userRequestedMyLocation) {
            Toast.makeText(this, R.string.waiting_for_location, Toast.LENGTH_SHORT).show();
        }
        if(userRequestedMyLocation || locationResponse.location == null) {
            return;
        }
        currentLatLng = locationResponse.getLatLng();
        setCurrentAddress(currentLatLng, true);
    }

    protected void executeAddressSearch(String query) {
        LatLng latLng = LatLngParser.parse(query);
        if(latLng != null) {
            setCurrentAddress(latLng, true);
        }
        else {
            new SearchAddressAsyncTask().execute(query);
        }
    }

    public void setCurrentAddress(final LatLng latLng, boolean moveCamera) {
        if(map == null) {
            return;
        }

        if(moveCamera) {
            CameraPosition.Builder builder = new CameraPosition.Builder();
            builder.zoom(Math.max(map.getCameraPosition().zoom, 10));
            builder.target(latLng);
            map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()));
        }

        // Remove all map markers.
        map.clear();

        // Draw the selected address marker.
        MarkerOptions markerOpts = new MarkerOptions();
        markerOpts.position(latLng);
        markerOpts.draggable(false);
        markerOpts.icon(UIUtils.getMapMarkerIcon(this, markerColor, markerIconPath, null));
        currentMarker = map.addMarker(markerOpts);

        if(moveCamera) {
            map.animateCamera(CameraUpdateFactory.zoomTo(Math.max(map.getCameraPosition().zoom, 16)), 300, null);
        }

        // We set [locationInfo] and then try to lookup the address so that if the user
        // does not have internet access when dropping a pin, he can still save the coordinates.
        locationInfo = new LocationInfo(latLng, null, true);
        locationChanged = true;
        MiscUtils.getClosestAddressFromLocation(this, latLng.latitude, latLng.longitude, new MiscUtils.ClosestAddressListener() {
            @Override
            public void onClosestAddressFound(String addressStr) {
                locationInfo = new LocationInfo(latLng, addressStr, true);
                currentMarker.setTitle(locationInfo.getAddressHeader());
                currentMarker.setSnippet(locationInfo.getAddressForDisplay(null));
                currentMarker.showInfoWindow();
            }
        });
    }

    public void setCurrentAddress(LocationInfo locationInfo, boolean moveCamera) {
        if(map == null || locationInfo == null) {
            return;
        }

        this.locationInfo = locationInfo;

        if(moveCamera) {
            CameraPosition.Builder builder = new CameraPosition.Builder();
            builder.zoom(10);
            builder.target(locationInfo.location);
            map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()));
        }

        // Remove all map markers.
        map.clear();

        // Draw the selected address marker.
        MarkerOptions markerOpts = new MarkerOptions();
        markerOpts.position(locationInfo.location);
        markerOpts.draggable(false);
        markerOpts.title(locationInfo.getAddressHeader());
        markerOpts.snippet(locationInfo.getAddressForDisplay(null));
        markerOpts.icon(UIUtils.getMapMarkerIcon(this, markerColor, markerIconPath, null));
        currentMarker = map.addMarker(markerOpts);
        currentMarker.showInfoWindow();

        if(moveCamera) {
            map.animateCamera(CameraUpdateFactory.zoomTo(16), 300, null);
        }
    }

    protected void restoreMapState() {
        if(savedCameraPosition != null) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(savedCameraPosition));
        }
    }

    protected void setupActionBar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch(requestCode) {
            case RequestCodes.PERMISSION_REQUEST_LOCATION:
                if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    setMyLocationEnabledOnMap();
                }
                break;
        }
    }

    class SearchAddressAsyncTask extends AsyncTask<String, Void, Address> {

        @Override
        protected Address doInBackground(String... params) {
            try {
                List<Address> addresses = geocoder.getFromLocationName(params[0], 1);
                if(addresses.size() > 0) {
                    return addresses.get(0);
                }
            }
            catch(IOException e) {
                // Ignore this exception completely for now.
                // Crashlytics.logException(e);
            }

            return null;
        }

        @Override
        protected void onPostExecute(Address address) {
            if(address != null) {
                locationInfo = new LocationInfo(address);
                locationChanged = true;
                setCurrentAddress(locationInfo, true);
            }
            else {
                Toast.makeText(DetailMapActivity.this, R.string.address_not_found, Toast.LENGTH_SHORT).show();
            }
        }
    }

    class DetailMapActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(Application.LOCATION_UPDATE_BROADCAST.equals(action)) {
                Location location = intent.getParcelableExtra("location");
                currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());

                if(onLocationChangedListener != null) {
                    onLocationChangedListener.onLocationChanged(location);
                }

                if(userRequestedMyLocation) {
                    locationChanged = true;
                    setCurrentAddress(currentLatLng, true);
                    userRequestedMyLocation = false;
                    return;
                }

                // If a location wasn't passed into the activity, then set the current location.
                Bundle extras = getIntent().getExtras();
                if(extras == null || !extras.containsKey("locationInfo") && locationInfo == null) {
                    goToMyLocation(false);
                }
                else if(currentMarker != null) {
                    if(locationInfo != null) {
                        setCurrentAddress(locationInfo, false);
                    }
                    else {
                        restoreMapState();
                    }
                }
            }
            else if(Application.LOCATION_FAILED_BROADCAST.equals(action)) {
                if(userRequestedMyLocation) {
                    Toast.makeText(DetailMapActivity.this, R.string.location_request_failed, Toast.LENGTH_SHORT).show();
                    userRequestedMyLocation = false;
                }
            }
        }
    }

}
