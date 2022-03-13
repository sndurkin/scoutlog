package com.sndurkin.locationscout;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.LruCache;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.LocationSource;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.maps.android.clustering.Cluster;
import com.google.maps.android.clustering.ClusterItem;
import com.google.maps.android.clustering.ClusterManager;
import com.google.maps.android.clustering.view.DefaultClusterRenderer;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.DriveSyncAdapter;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.LocationModelChanges;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.util.LocationServicesDialog;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;
import com.sndurkin.locationscout.util.UnlockAppDialog;
import com.sndurkin.locationscout.util.Versions;

import java.util.ArrayList;
import java.util.List;

public class ListMapFragment extends Fragment implements GoogleApiClient.ConnectionCallbacks,
                                                         GoogleApiClient.OnConnectionFailedListener,
                                                         LocationListener,
                                                         LocationSource,
                                                         GoogleMap.OnMyLocationButtonClickListener,
                                                         OnMapReadyCallback {

    private View view;
    private boolean fragmentJustCreated;

    private Tracker tracker;
    private SharedPreferences preferences;

    private GlobalBroadcastManager broadcastManager;
    private BroadcastReceiver broadcastReceiver;

    private CustomSupportMapFragment mapFragment;
    private GoogleMap map;
    private OnLocationChangedListener onLocationChangedListener;
    private GoogleApiClient apiClient;
    private LocationRequest locationRequest;

    private LruCache<String, Bitmap> iconCache;

    private boolean fetchingLocations = false;
    private int defaultMarkerColor;

    private boolean userRequestedMyLocation = false;
    private boolean constantlyUpdatingLocation = false;

    private boolean showClosestAddress;
    private int clusterThreshold;

    private ImageButton toggleTerrainButton;

    private Marker addLocationMarker;
    private LatLng droppedPinLatLng;
    private String droppedPinTitle;

    // Used for maintaining the map state on orientation change.
    private CameraPosition savedCameraPosition;

    private ClusterManager clusterManager;
    private List<LocationModel> locations = new ArrayList<LocationModel>();

    protected RelativeLayout helpScreenView;
    protected ViewPager helpScreenViewPager;
    protected ViewPagerAdapter helpScreenPagerAdapter;
    protected CircleIndicatorView helpScreenPageIndicator;
    protected Button helpScreenButton;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = setView(inflater, R.layout.list_map_fragment, container);

        ((MainActivity) getActivity()).updateTitle();

        tracker = ((Application) getActivity().getApplication()).getTracker();
        preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        defaultMarkerColor = preferences.getInt(Strings.PREF_MAP_MARKER_COLOR, Color.RED);

        broadcastManager = GlobalBroadcastManager.getInstance(getActivity());
        broadcastReceiver = new BroadcastReceiver();

        setHasOptionsMenu(true);

        // Setup the map.
        mapFragment = (CustomSupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        setupMapButtons(view);

        tracker.setScreenName(Strings.MAP_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        initHelpScreen(view);

        apiClient = new GoogleApiClient.Builder(getActivity())
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        // Get the max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in KB as the LruCache takes an
        // int in its constructor. Use 1/8th of the available memory for
        // this cache.
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        iconCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // The cache size will be measured in KB rather than
                // number of items.
                return bitmap.getByteCount() / 1024;
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                oldValue.recycle();
            }
        };

        Bundle args = getArguments();
        if(args != null && args.containsKey("droppedPinLatLng")) {
            droppedPinLatLng = args.getParcelable("droppedPinLatLng");
            droppedPinTitle = args.getString("droppedPinTitle");
            args.remove("droppedPinLatLng");
            args.remove("droppedPinTitle");
        }
        else if(savedInstanceState != null) {
            savedCameraPosition = savedInstanceState.getParcelable("savedCameraPosition");
            droppedPinLatLng = savedInstanceState.getParcelable("droppedPinLatLng");
            droppedPinTitle = savedInstanceState.getString("droppedPinTitle");
            enableConstantUpdateLocationFeature(savedInstanceState.getBoolean("constantlyUpdatingLocation", false));
        }

        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    RequestCodes.PERMISSION_REQUEST_ALL);
        }

        fragmentJustCreated = true;
        return view;
    }

    protected View setView(LayoutInflater inflater, int layoutId, ViewGroup container) {
        if (view != null) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
        }

        try {
            view = inflater.inflate(layoutId, container, false);
        }
        catch (InflateException e) {
            // Map is already there, so return view as it is.
            CrashlyticsCore.getInstance().logException(e);
        }

        return view;
    }

    @Override
    public void activate(OnLocationChangedListener onLocationChangedListener) {
        this.onLocationChangedListener = onLocationChangedListener;

        // When the user rotates the device, before it calls activate() to set the [onLocationChangedListener]
        // it calls onResume(), which sends the last location broadcast, which is normally what fires the
        // [onLocationChangedListener]. So, this code ensures that [onLocationChangedListener] is still
        // called on device rotate, so that the current location marker is displayed on the map.
        Application.LocationResponse locationResponse = ((Application) getActivity().getApplication()).getCurrentLocation(null);
        if(locationResponse.location != null) {
            onLocationChangedListener.onLocationChanged(locationResponse.location);
        }
    }

    @Override
    public void deactivate() {
        onLocationChangedListener = null;
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;
        mapFragment.setOnMapMovedListener(new CustomSupportMapFragment.OnMapMovedListener() {
            @Override
            public void onMapMoved() {
                enableConstantUpdateLocationFeature(false);
            }
        });

        map.getUiSettings().setTiltGesturesEnabled(false);
        map.getUiSettings().setRotateGesturesEnabled(false);
        map.getUiSettings().setCompassEnabled(false);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setMyLocationButtonEnabled(true);
        map.setOnMyLocationButtonClickListener(this);
        setMyLocationEnabledOnMap();

        // We set our own location source so that we can control when the
        // current location is updated. When the user has the sticky current location
        // feature on, it will constantly update the location. When the user has that
        // feature off, it will only update the location every minute or so.
        map.setLocationSource(this);

        map.setMapType(preferences.getInt(Strings.PREF_MAP_TYPE, GoogleMap.MAP_TYPE_NORMAL));
        map.setOnMapLongClickListener(new GoogleMap.OnMapLongClickListener() {
            @Override
            public void onMapLongClick(LatLng latLng) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_PLACE_MARKER)
                        .build());
                dropAddPlacePin(latLng, null, null);
            }
        });

        setupClusterManager();

        if(savedCameraPosition != null) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(savedCameraPosition));
        }

        fetchLocations();
    }

    @Override
    public boolean onMyLocationButtonClick() {
        if(preferences.getBoolean(Strings.PREF_STICKY_CURRENT_LOCATION, false)) {
            tracker.send(new HitBuilders.EventBuilder()
                    .setCategory(Strings.CAT_UI_ACTION)
                    .setAction(Strings.ACT_CLICK_BUTTON)
                    .setLabel(Strings.LBL_CURRENT_LOCATION_STICKY)
                    .build());

            enableConstantUpdateLocationFeature(!constantlyUpdatingLocation);
            return false;
        }
        else {
            tracker.send(new HitBuilders.EventBuilder()
                    .setCategory(Strings.CAT_UI_ACTION)
                    .setAction(Strings.ACT_CLICK_BUTTON)
                    .setLabel(Strings.LBL_CURRENT_LOCATION)
                    .build());

            goToMyLocation(true);
            return true;
        }
    }

    protected void setupMapButtons(View view) {
        toggleTerrainButton = (ImageButton) view.findViewById(R.id.map_toggle_terrain);
        UIUtils.addHintFunctionalityToView(getActivity(), toggleTerrainButton);
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
    }

    protected void setupClusterManager() {
        clusterManager = new ClusterManager<MyClusterItem>(getActivity(), map) {
            @Override
            public boolean onMarkerClick(Marker marker) {
                // We don't want to display an info window if there's no title or snippet;
                // this is mostly a workaround for marker == currentLocationMarker which
                // doesn't seem to work.
                if(marker.getTitle() == null && marker.getSnippet() == null) {
                    return true;
                }
                return super.onMarkerClick(marker);
            }
        };

        map.setOnCameraChangeListener(clusterManager);
        map.setOnMarkerClickListener(clusterManager);
        map.setOnInfoWindowClickListener(new GoogleMap.OnInfoWindowClickListener() {
            @Override
            public void onInfoWindowClick(Marker marker) {
                if (addLocationMarker != null && addLocationMarker.getId().equals(marker.getId())) {
                    if(isAdded()) {
                        if(UnlockAppDialog.shouldShowDialog(getActivity(), getLocationsCount())) {
                            // If the user has not paid, they can only create a certain number of locations.
                            new UnlockAppDialog(getActivity()).show();
                            return;
                        }

                        tracker.send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_INFO_WINDOW)
                                .setLabel(Strings.LBL_ADD_PLACE_MARKER)
                                .build());

                        ((MainActivity) getActivity()).openLocationDetail(null, marker.getPosition(), droppedPinTitle);
                        droppedPinLatLng = null;
                        droppedPinTitle = null;
                    }
                }
                else {
                    clusterManager.onInfoWindowClick(marker);
                }
            }
        });
        map.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override
            public View getInfoWindow(Marker marker) {
                return null;
            }

            @Override
            public View getInfoContents(Marker marker) {
                View infoWindowView = getActivity().getLayoutInflater().inflate(R.layout.list_map_info_window, null);
                ((TextView) infoWindowView.findViewById(R.id.map_marker_title)).setText(marker.getTitle());
                ((TextView) infoWindowView.findViewById(R.id.map_marker_address)).setText(marker.getSnippet());
                return infoWindowView;
            }
        });

        clusterManager.setRenderer(new MyClusterRenderer());
        clusterManager.getClusterMarkerCollection().setOnMarkerClickListener(new GoogleMap.OnMarkerClickListener() {
            @Override
            public boolean onMarkerClick(Marker marker) {
                return true;
            }
        });
        clusterManager.getMarkerCollection().setOnMarkerClickListener(new GoogleMap.OnMarkerClickListener() {
            @Override
            public boolean onMarkerClick(Marker marker) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_MAP_MARKER)
                        .setLabel(Strings.LBL_PLACE_MARKER)
                        .build());

                return false;
            }
        });
        clusterManager.setOnClusterItemInfoWindowClickListener(new ClusterManager.OnClusterItemInfoWindowClickListener() {
            @Override
            public void onClusterItemInfoWindowClick(ClusterItem item) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_INFO_WINDOW)
                        .setLabel(Strings.LBL_PLACE_MARKER)
                        .build());

                if (isAdded()) {
                    Long id = ((MyClusterItem) item).getLocationModel().getLocalId();
                    ((MainActivity) getActivity()).openLocationDetail(id);
                }
            }
        });
    }

    public void dropAddPlacePin(LatLng latLng, String title, Integer zoom) {
        if(!isAdded()) {
            return;
        }

        if (addLocationMarker != null) {
            addLocationMarker.remove();
        }

        MarkerOptions markerOpts = new MarkerOptions();
        markerOpts.position(latLng);
        markerOpts.title(title != null ? title : getString(R.string.new_place));
        markerOpts.snippet(getString(R.string.new_place_desc, MiscUtils.latLngToString(latLng)));
        markerOpts.icon(UIUtils.getDefaultMapMarkerIcon(getActivity()));
        addLocationMarker = map.addMarker(markerOpts);
        addLocationMarker.showInfoWindow();
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.fromLatLngZoom(latLng, zoom != null ? zoom : map.getCameraPosition().zoom)));
    }

    protected void enableConstantUpdateLocationFeature(boolean constantlyUpdatingLocation) {
        this.constantlyUpdatingLocation = constantlyUpdatingLocation;
        if(constantlyUpdatingLocation) {
            // Turn on the constant location update functionality.
            mapFragment.setMyLocationButtonAlpha(0.4f);
            if(apiClient.isConnected()) {
                startLocationUpdates();
            }
            else {
                apiClient.connect();
            }
        }
        else {
            // Turn off the constant location update functionality.
            mapFragment.setMyLocationButtonAlpha(1.0f);
            if (apiClient.isConnecting() || apiClient.isConnected()) {
                apiClient.disconnect();
            }
        }
    }

    protected void initHelpScreen(View view) {
        helpScreenView = (RelativeLayout) view.findViewById(R.id.help_screen);
        helpScreenViewPager = (ViewPager) view.findViewById(R.id.help_screen_viewpager);
        helpScreenPageIndicator = (CircleIndicatorView) view.findViewById(R.id.help_screen_page_indicator);
        helpScreenPagerAdapter = new ViewPagerAdapter();
        helpScreenButton = (Button) view.findViewById(R.id.help_screen_button);

        helpScreenPagerAdapter.addView((ViewGroup) getActivity().getLayoutInflater().inflate(R.layout.sticky_current_location_help_screen_1, null, false));
        helpScreenPagerAdapter.addView((ViewGroup) getActivity().getLayoutInflater().inflate(R.layout.sticky_current_location_help_screen_2, null, false));
        helpScreenPagerAdapter.addView((ViewGroup) getActivity().getLayoutInflater().inflate(R.layout.sticky_current_location_help_screen_3, null, false));
        helpScreenViewPager.setAdapter(helpScreenPagerAdapter);
        helpScreenPageIndicator.setViewPager(helpScreenViewPager);

        helpScreenButton.setText(R.string.next);
        helpScreenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int currentPage = helpScreenViewPager.getCurrentItem();
                if (currentPage < helpScreenPagerAdapter.getCount() - 1) {
                    helpScreenViewPager.setCurrentItem(helpScreenViewPager.getCurrentItem() + 1, true);
                } else {
                    Versions.update(preferences, Strings.STICKY_CURRENT_LOCATION_HELP_VERSION, Versions.Defaults.STICKY_CURRENT_LOCATION_HELP_VERSION);
                    helpScreenView.setVisibility(View.GONE);
                    ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
                    if (actionBar != null) {
                        actionBar.show();
                    }
                }
            }
        });
        helpScreenPageIndicator.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {
            }

            @Override
            public void onPageSelected(int i) {
                if (i == helpScreenPagerAdapter.getCount() - 1) {
                    helpScreenButton.setText(R.string.got_it);
                } else {
                    helpScreenButton.setText(R.string.next);
                }
            }

            @Override
            public void onPageScrollStateChanged(int i) {
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.map_menu, menu);
        if(isAdded()) {
            ((MainActivity) getActivity()).setupSearchMenu(menu);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_add_location:
                if(UnlockAppDialog.shouldShowDialog(getActivity(), getLocationsCount())) {
                    // If the user has not paid, they can only create a certain number of locations.
                    new UnlockAppDialog(getActivity()).show();
                    return true;
                }

                if(isAdded()) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_MENU_ITEM)
                            .setLabel(Strings.LBL_ADD_PLACE)
                            .build());
                    ((MainActivity) getActivity()).openLocationDetail(null);
                }
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Register broadcasts for this fragment.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST);
        intentFilter.addAction(Application.LOCATION_UPDATE_BROADCAST);
        intentFilter.addAction(MainActivity.LOCATIONS_CHANGED_BROADCAST);
        broadcastManager.registerReceiver(ListMapFragment.class.getCanonicalName(), broadcastReceiver, intentFilter, true);

        showClosestAddress = preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true);
        clusterThreshold = Integer.parseInt(preferences.getString(Strings.PREF_CLUSTER_THRESHOLD, "10"));

        // Check to make sure the user hasn't changed the preference.
        enableConstantUpdateLocationFeature(constantlyUpdatingLocation && preferences.getBoolean(Strings.PREF_STICKY_CURRENT_LOCATION, false));

        if(preferences.getBoolean(Strings.PREF_STICKY_CURRENT_LOCATION, false)) {
            Versions.check(preferences, Strings.STICKY_CURRENT_LOCATION_HELP_VERSION, Versions.Defaults.STICKY_CURRENT_LOCATION_HELP_VERSION, new Versions.Listener() {
                @Override
                public void onFirstVersion() {
                    helpScreenView.setVisibility(View.VISIBLE);
                    ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
                    if(actionBar != null) {
                        actionBar.hide();
                    }
                }

                @Override
                public void onUpdateVersion() {
                    // Nothing here yet.
                }
            });
        }

        if(!fragmentJustCreated) {
            int markerColor = preferences.getInt(Strings.PREF_MAP_MARKER_COLOR, Color.RED);
            if(defaultMarkerColor != markerColor) {
                defaultMarkerColor = markerColor;
                fetchLocations();
            }
            else if(clusterManager != null) {
                // Apparently we need to change the renderer and then call cluster() to properly
                // force a re-cluster.
                clusterManager.setRenderer(new MyClusterRenderer());
                clusterManager.cluster();
            }
        }
        fragmentJustCreated = false;
    }

    @Override
    public void onPause() {
        broadcastManager.unregisterReceiver(broadcastReceiver);

        if(apiClient.isConnected() && constantlyUpdatingLocation) {
            stopLocationUpdates();
        }
        if(addLocationMarker != null) {
            addLocationMarker.remove();
            addLocationMarker = null;
        }
        iconCache.evictAll();
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if(map != null) {
            outState.putParcelable("savedCameraPosition", map.getCameraPosition());
        }
        outState.putParcelable("droppedPinLatLng", droppedPinLatLng);
        outState.putString("droppedPinTitle", droppedPinTitle);
        outState.putBoolean("constantlyUpdatingLocation", constantlyUpdatingLocation);
    }

    protected void startLocationUpdates() {
        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if(locationRequest == null) {
                locationRequest = LocationRequest.create();
                locationRequest.setInterval(10000);
                locationRequest.setFastestInterval(5000);
                locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
                locationRequest.setSmallestDisplacement(30);
            }
            LocationServices.FusedLocationApi.requestLocationUpdates(apiClient, locationRequest, this);
        }
    }

    protected void stopLocationUpdates() {
        LocationServices.FusedLocationApi.removeLocationUpdates(apiClient, this);
    }

    @Override
    public void onLocationChanged(Location location) {
        if(onLocationChangedListener != null) {
            onLocationChangedListener.onLocationChanged(location);
        }
        setMapCamera(new LatLng(location.getLatitude(), location.getLongitude()));
    }

    @Override
    public void onConnected(Bundle bundle) {
        if(constantlyUpdatingLocation) {
            startLocationUpdates();
        }
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        // TODO
    }

    @Override
    public void onConnectionSuspended(int i) {
        // TODO
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if(requestCode == RequestCodes.PERMISSION_REQUEST_STORAGE || requestCode == RequestCodes.PERMISSION_REQUEST_ALL) {
                fetchLocations();
            }
            if(requestCode == RequestCodes.PERMISSION_REQUEST_LOCATION || requestCode == RequestCodes.PERMISSION_REQUEST_ALL) {
                setMyLocationEnabledOnMap();
            }
        }
    }

    protected void fetchLocations() {
        if(!isAdded() || map == null || fetchingLocations) {
            return;
        }

        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            fetchingLocations = true;
            defaultMarkerColor = preferences.getInt(Strings.PREF_MAP_MARKER_COLOR, Color.RED);

            clusterManager.clearItems();
            locations.clear();

            String currentQuery = ((MainActivity) getActivity()).getCurrentQuery();
            String currentQueryType = ((MainActivity) getActivity()).getCurrentQueryType();

            DatabaseHelper.getInstance(getActivity()).fetchLocations(currentQuery, currentQueryType, null, 0, -1, new DatabaseQueryListener() {
                @Override
                public void onQueryExecuted(DatabaseQueryResult result) {
                    if (!isAdded()) return;

                    Cursor cursor = result.cursor;
                    while (!cursor.isAfterLast()) {
                        LocationModel locationModel = ModelUtils.createLocationFromCursorForDisplay(cursor);
                        if (locationModel.hasLocation()) {
                            clusterManager.addItem(new MyClusterItem(locationModel));
                            locations.add(locationModel);
                        }
                        cursor.moveToNext();
                    }
                    cursor.close();

                    if(droppedPinLatLng != null) {
                        dropAddPlacePin(droppedPinLatLng, droppedPinTitle, 10);
                    }
                    else if (savedCameraPosition == null) {
                        zoomToFitAllLocations();
                    }
                    else {
                        clusterManager.cluster();
                    }
                    fetchingLocations = false;
                }
            });
        }
        else {
            requestPermissions(
                    new String[] {
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    RequestCodes.PERMISSION_REQUEST_STORAGE);
        }
    }

    protected void setMyLocationEnabledOnMap() {
        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.setMyLocationEnabled(true);
        }
        else {
            requestPermissions(
                    new String[] {
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    RequestCodes.PERMISSION_REQUEST_LOCATION);
        }
    }

    class MyClusterItem implements ClusterItem {

        private LocationModel locationModel;

        public MyClusterItem(LocationModel locationModel) {
            this.locationModel = locationModel;
        }
        @Override
        public LatLng getPosition() {
            return locationModel.getLocationInfo().location;
        }

        public LocationModel getLocationModel() {
            return locationModel;
        }
    }

    class MyClusterRenderer extends DefaultClusterRenderer<MyClusterItem> {

        public MyClusterRenderer() {
            super(getActivity(), map, clusterManager);
        }

        @Override
        protected void onBeforeClusterItemRendered(MyClusterItem item, MarkerOptions markerOptions) {
            super.onBeforeClusterItemRendered(item, markerOptions);

            if(!isAdded()) {
                return;
            }

            LocationModel locationModel = item.getLocationModel();

            markerOptions.title(locationModel.getTitleForDisplay(showClosestAddress));
            markerOptions.snippet(locationModel.getLocationInfo().getAddressForDisplay(locationModel.getTitleForDisplay(showClosestAddress), showClosestAddress));
            markerOptions.icon(UIUtils.getMapMarkerIcon(getActivity(), locationModel.getColor(), locationModel.getIconPath(), iconCache));
        }

        @Override
        protected boolean shouldRenderAsCluster(Cluster<MyClusterItem> cluster) {
            if(clusterThreshold == 0) {
                return false;
            }
            return cluster.getSize() > clusterThreshold;
        }

    }

    public int getLocationsCount() {
        return locations != null ? locations.size() : 0;
    }

    protected void zoomToFitAllLocations() {
        if(locations.isEmpty()) {
            // We specify that this is a user request here because he doesn't
            // yet have any locations, so we want to force the map to go to the
            // current location.
            goToMyLocation(true);
        }
        else {
            // Set the zoom to encompass all the markers.
            final LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();
            for(LocationModel locationModel : locations) {
                boundsBuilder.include(locationModel.getLocationInfo().location);
            }

            // We check for the view width/height because we were getting an exception here related to the map size being 0.
            if (view.getWidth() > 0 && view.getHeight() > 0) {
                // There's a chance that the bounds are very similar and the call to animateCamera() will not result in
                // onCameraChange being fired, which means the map will not get updated. So, we fix this by temporarily
                // removing the listener and manually calling cluster() ourselves.
                map.setOnCameraChangeListener(null);
                animateMapToLatLngBounds(boundsBuilder.build());
                clusterManager.cluster();
                map.setOnCameraChangeListener(clusterManager);
            }
            else {
                map.setOnMapLoadedCallback(new GoogleMap.OnMapLoadedCallback() {
                    @Override
                    public void onMapLoaded() {
                        animateMapToLatLngBounds(boundsBuilder.build());
                    }
                });
            }
        }
    }

    protected float MIN_LATITTUDE_DEGREES = 0.05f;
    protected void animateMapToLatLngBounds(LatLngBounds bounds) {
        // Restrict the bounds to a certain zoom level.
        LatLng sw = bounds.southwest;
        LatLng ne = bounds.northeast;
        double visibleLatitudeDegrees = Math.abs(sw.latitude - ne.latitude);
        if (visibleLatitudeDegrees < MIN_LATITTUDE_DEGREES) {
            LatLng center = bounds.getCenter();
            sw = new LatLng(center.latitude - (MIN_LATITTUDE_DEGREES / 2), sw.longitude);
            ne = new LatLng(center.latitude + (MIN_LATITTUDE_DEGREES / 2), ne.longitude);
            bounds = new LatLngBounds(sw, ne);
        }

        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, view.getWidth(), view.getHeight(), 100), 300, null);
    }

    // This function has a couple usecases:
    //  1) The function is called once by the app (not a user request).
    //  2) The function is called once, initiated by a user request.
    protected void goToMyLocation(boolean userRequest) {
        if(userRequest && LocationServicesDialog.showIfNecessary(getActivity())) {
            return;
        }

        if(constantlyUpdatingLocation) {
            if(!apiClient.isConnected()) {
                apiClient.connect();
            }
            return;
        }

        if(userRequestedMyLocation) {
            // The location has already been requested, so do nothing at this time.
            return;
        }
        userRequestedMyLocation = userRequest;

        Application.LocationResponse locationResponse = ((Application) getActivity().getApplication()).getCurrentLocation(userRequestedMyLocation ? 0L : null);
        if(locationResponse.location == null) {
            if(userRequestedMyLocation) {
                Toast.makeText(getActivity(), R.string.waiting_for_location, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        setMapCamera(locationResponse.getLatLng());
    }

    public void setMapCamera(LatLng location) {
        CameraPosition.Builder builder = new CameraPosition.Builder();
        float zoom = map.getCameraPosition().zoom;
        if(zoom < 12) {
            zoom = 15;
        }
        builder.zoom(zoom);
        builder.target(location);
        map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), 300, null);
    }

    class BroadcastReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(Application.LOCATION_UPDATE_BROADCAST.equals(action)) {
                if(constantlyUpdatingLocation) {
                    // We're already receiving constant updates.
                    return;
                }

                Location location = intent.getParcelableExtra("location");

                if(userRequestedMyLocation) {
                    setMapCamera(new LatLng(location.getLatitude(), location.getLongitude()));
                    userRequestedMyLocation = false;
                }

                if(onLocationChangedListener != null) {
                    onLocationChangedListener.onLocationChanged(location);
                }
            }
            else if(Application.LOCATION_FAILED_BROADCAST.equals(action)) {
                if(userRequestedMyLocation) {
                    Toast.makeText(getActivity(), R.string.location_request_failed, Toast.LENGTH_SHORT).show();
                    userRequestedMyLocation = false;
                }
            }
            else if(MainActivity.LOCATIONS_CHANGED_BROADCAST.equals(action)) {
                // These variables are used to be smarter about whether to update the map after a location
                // has been modified on the detail screen.
                boolean forceChange = intent.getBooleanExtra(Strings.PARAM_FORCE_CHANGE, true);

                LocationModelChanges locationChanges = intent.getParcelableExtra(Strings.PARAM_LOCATION_CHANGES);
                if(locationChanges == null) {
                    locationChanges = new LocationModelChanges();
                }
                boolean markerColorsChanged = intent.getBooleanExtra(Strings.PARAM_MARKER_COLORS_CHANGED, false);

                if(forceChange || locationChanges.latLng) {
                    savedCameraPosition = null;
                    fetchLocations();
                }
                else if(locationChanges.title || locationChanges.address || markerColorsChanged) {
                    // None of the pins' locations have changed so we don't need to update the view.
                    if(map != null) {
                        savedCameraPosition = map.getCameraPosition();
                    }
                    fetchLocations();
                }

                // We remove the broadcast for ListMapFragment so that on the next resume it doesn't
                // call fetchLocations() again unnecessarily.
                broadcastManager.removeLastBroadcast(ListMapFragment.class.getCanonicalName(), action);
            }
        }
    }

}
