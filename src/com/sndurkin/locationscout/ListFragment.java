package com.sndurkin.locationscout;

import android.Manifest;
import android.accounts.Account;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.CardView;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.api.client.googleapis.extensions.android.accounts.GoogleAccountManager;
import com.sndurkin.locationscout.integ.ExportService;
import com.sndurkin.locationscout.settings.SettingsActivity;
import com.sndurkin.locationscout.settings.SyncSettingsFragment;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.DriveSyncAdapter;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.PhotoLoader;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;
import com.sndurkin.locationscout.util.UnlockAppDialog;
import com.sndurkin.locationscout.util.SwipeActionAdapter;
import com.sndurkin.locationscout.util.SwipeDirection;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ListFragment extends Fragment implements AbsListView.OnScrollListener,
                                                      SwipeRefreshLayout.OnRefreshListener,
                                                      SwipeRefreshLayout.OnChildScrollUpListener {

    private View view;
    private boolean fragmentJustCreated;

    private Tracker tracker;
    private Tracker timingTracker;

    private GlobalBroadcastManager broadcastManager;
    private BroadcastReceiver broadcastReceiver;
    private AmnesticBroadcastReceiver amnesticBroadcastReceiver;

    private List<LocationModel> locations;
    private boolean fetchingLocationForPlacesSort = false;

    private boolean usingGridView;
    private boolean usingImagesInListView;
    private boolean showMapMarkers;
    private boolean showClosestAddress;
    private boolean enableDates;

    private SwipeRefreshLayout swipeRefreshLayout;
    private Account account;
    private boolean isSyncing = false;

    private Handler refreshDismissHandler;

    private ProgressDialog exportProgressDialog;

    private ListView locationsList;
    private LocationListAdapter listAdapter;
    private SwipeActionAdapter swipeAdapter;

    private GridView locationsGrid;
    private LocationGridAdapter gridAdapter;

    private CardView switchToListSuggestionCard;

    private Parcelable locationsState;

    private DatabaseHelper.LocationsQuerySortType sortType;

    private TypedValue listTagImage;

    private LocationPermissionRequestType locationPermissionRequestType;

    private static final int LOAD_TOLERANCE_AMOUNT = 20;
    private static final int LOAD_CHUNK_AMOUNT = 100;
    private int currentNumLoaded;
    private int numLoadedBeforeRotate;
    private boolean loadedTotal;
    private boolean fetchingLocations = false;

    private LocationsMultiChoiceModeListener multiChoiceModeListener;
    private Set<Integer> selectedIndices;

    private SharedPreferences preferences;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setView(inflater, R.layout.list_fragment, container);

        ((MainActivity) getActivity()).updateTitle();

        // Preferences need to be set before setHasOptionsMenu() is called because it results in
        // onCreateOptionsMenu() being called, which uses the SharedPreferences object.
        initPrefsAndTrackers();

        setHasOptionsMenu(true);

        broadcastManager = GlobalBroadcastManager.getInstance(getActivity().getApplicationContext());
        broadcastReceiver = new BroadcastReceiver();
        amnesticBroadcastReceiver = new AmnesticBroadcastReceiver();

        swipeRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.swipe_refresh_layout);
        locationsList = (ListView) view.findViewById(R.id.list_locations);
        locationsGrid = (GridView) view.findViewById(R.id.grid_locations);

        switchToListSuggestionCard = (CardView) view.findViewById(R.id.switch_to_list_suggestion_card);

        multiChoiceModeListener = new LocationsMultiChoiceModeListener();
        selectedIndices = new HashSet<>();

        LocationsItemClickListener itemClickListener = new LocationsItemClickListener();
        locationsList.setOnItemClickListener(itemClickListener);
        locationsList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        locationsList.setMultiChoiceModeListener(multiChoiceModeListener);
        locationsList.setOnScrollListener(this);
        locationsList.setEmptyView(view.findViewById(R.id.empty_locations_view));
        swipeAdapter = new SwipeActionAdapter(listAdapter = new LocationListAdapter());
        swipeAdapter.setListView(locationsList);
        locationsList.setAdapter(swipeAdapter);
        swipeAdapter.addBackground(SwipeDirection.DIRECTION_NORMAL_LEFT, R.layout.list_item_navigate_button);
        swipeAdapter.setSwipeActionListener(new SwipeActionAdapter.SwipeActionListener() {
            @Override
            public boolean hasActions(int position, SwipeDirection direction) {
                return direction.isLeft();
            }

            @Override
            public void onActionClicked(int position, int actionResId) {
                if(!isAdded() || locations == null) {
                    return;
                }
                if(position >= locations.size()) {
                    CrashlyticsCore.getInstance().logException(new RuntimeException("How is the idx (" + position + ") greater than the size (" + locations.size() + ")"));
                    return;
                }

                switch(actionResId) {
                    case R.id.navigate_button:
                        LocationModel locationModel = locations.get(position);
                        LocationInfo locationInfo = locationModel.getLocationInfo();
                        if (locationInfo == null) {
                            return;
                        }

                        tracker.send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_BUTTON)
                                .setLabel(Strings.LBL_NAVIGATE_SWIPE)
                                .build());

                        UIUtils.openNavigationApp(getActivity(), locationInfo);
                        break;
                }
            }

        });

        locationsGrid.setOnItemClickListener(itemClickListener);
        locationsGrid.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        locationsGrid.setMultiChoiceModeListener(multiChoiceModeListener);
        locationsGrid.setOnScrollListener(this);
        locationsGrid.setEmptyView(view.findViewById(R.id.empty_locations_view));
        locationsGrid.setAdapter(gridAdapter = new LocationGridAdapter());

        listTagImage = new TypedValue();
        getActivity().getTheme().resolveAttribute(R.attr.iconTags, listTagImage, true);

        int sortTypeIdx = preferences.getInt(Strings.PREF_LOCATIONS_SORT, DatabaseHelper.LocationsQuerySortType.SORT_NEWEST.ordinal());
        sortType = DatabaseHelper.LocationsQuerySortType.values()[sortTypeIdx];

        Button addLocationButton = (Button) view.findViewById(R.id.add_location_button);
        addLocationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_ADD_PLACE)
                        .build());
                if (isAdded()) {
                    ((MainActivity) getActivity()).openLocationDetail(null);
                }
            }
        });

        if(savedInstanceState != null) {
            locationsState = savedInstanceState.getParcelable(Strings.PARAM_LOCATIONS_STATE);
            usingImagesInListView = savedInstanceState.getBoolean(Strings.PREF_LIST_VIEW_PHOTOS, true);
            showMapMarkers = savedInstanceState.getBoolean(Strings.PREF_SHOW_MAP_MARKERS, true);
            showClosestAddress = savedInstanceState.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true);
            enableDates = savedInstanceState.getBoolean(Strings.PREF_ENABLE_DATES, true);
            selectedIndices = MiscUtils.convertIntArrayToSet(savedInstanceState.getIntArray("selectedIndices"));
            numLoadedBeforeRotate = savedInstanceState.getInt("numLoadedBeforeRotate", 0);
            fetchingLocationForPlacesSort = savedInstanceState.getBoolean("fetchingLocationForPlacesSort", false);
        }

        swipeRefreshLayout.setOnRefreshListener(this);
        swipeRefreshLayout.setOnChildScrollUpListener(this);
        swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_green_dark,
                android.R.color.holo_red_dark,
                android.R.color.holo_blue_dark,
                android.R.color.holo_orange_dark
        );

        refreshDismissHandler = new Handler();

        usingGridView = Integer.valueOf(preferences.getString(Strings.PREF_LOCATIONS_VIEW, "0")) == SettingsActivity.LocationsDisplay.GRID_VIEW.ordinal();
        if(usingGridView) {
            tracker.setScreenName(Strings.GRID_VIEW);
        }
        else {
            tracker.setScreenName(Strings.LIST_VIEW);
        }
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
           ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    RequestCodes.PERMISSION_REQUEST_STORAGE);
        }

        fragmentJustCreated = true;
        return view;
    }

    protected void setView(LayoutInflater inflater, int layoutId, ViewGroup container) {
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
            // Return view as it is.
            CrashlyticsCore.getInstance().logException(e);
        }
    }

    protected void initPrefsAndTrackers() {
        if(preferences == null) {
            preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
            tracker = Application.getInstance().getTracker();
            timingTracker = Application.getInstance().getTimingTracker();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Register broadcasts for this fragment.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST);
        intentFilter.addAction(Application.LOCATION_UPDATE_BROADCAST);
        intentFilter.addAction(MainActivity.LOCATIONS_CHANGED_BROADCAST);
        intentFilter.addAction(ExportService.EXPORT_BROADCAST);
        broadcastManager.registerReceiver(ListFragment.class.getCanonicalName(), broadcastReceiver, intentFilter, true);

        // The location failed broadcast should not remember the last broadcast.
        intentFilter = new IntentFilter();
        intentFilter.addAction(Application.LOCATION_FAILED_BROADCAST);
        broadcastManager.registerReceiver(ListFragment.class.getCanonicalName(), amnesticBroadcastReceiver, intentFilter, false);

        boolean shouldEnableSwipeRefresh = false;
        String accountName = preferences.getString(Strings.PREF_ACCOUNT, null);
        if(accountName != null) {
            account = new GoogleAccountManager(getActivity()).getAccountByName(accountName);
            if(account != null) {
                shouldEnableSwipeRefresh = true;
            }
        }
        swipeRefreshLayout.setEnabled(shouldEnableSwipeRefresh);

        usingGridView = Integer.valueOf(preferences.getString(Strings.PREF_LOCATIONS_VIEW, "0")) == SettingsActivity.LocationsDisplay.GRID_VIEW.ordinal();
        boolean locationsDisplayChanged = usingGridView != (locationsGrid.getVisibility() == View.VISIBLE);

        boolean showMapMarkersUpdated = preferences.getBoolean(Strings.PREF_SHOW_MAP_MARKERS, true);
        if(showMapMarkers != showMapMarkersUpdated) {
            showMapMarkers = showMapMarkersUpdated;
            locationsDisplayChanged = true;
        }

        boolean usingImagesInListViewUpdated = preferences.getBoolean(Strings.PREF_LIST_VIEW_PHOTOS, true);
        if(usingImagesInListView != usingImagesInListViewUpdated) {
            usingImagesInListView = usingImagesInListViewUpdated;
            locationsDisplayChanged = true;
        }

        boolean showClosestAddressUpdated = preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true);
        if(showClosestAddress != showClosestAddressUpdated) {
            showClosestAddress = showClosestAddressUpdated;
            locationsDisplayChanged = true;
        }

        boolean enableDatesUpdated = preferences.getBoolean(Strings.PREF_ENABLE_DATES, true);
        if(enableDates != enableDatesUpdated) {
            enableDates = enableDatesUpdated;
            locationsDisplayChanged = true;
        }

        if(usingGridView) {
            if(!preferences.getBoolean(Strings.PREF_DISMISSED_SWITCH_TO_LIST_SUGGESTION, false)) {
                DatabaseHelper.getInstance(getActivity()).hasPhotosForPlaces(new DatabaseQueryListener() {
                    @Override
                    public void onQueryExecuted(DatabaseQueryResult result) {
                        if (result.result) {
                            Button noButton = (Button) switchToListSuggestionCard.findViewById(R.id.switch_button_no);
                            noButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    tracker.send(new HitBuilders.EventBuilder()
                                            .setCategory(Strings.CAT_UI_ACTION)
                                            .setAction(Strings.ACT_CLICK_BUTTON)
                                            .setLabel(Strings.LBL_SWITCH_DISPLAY_MODE_SUGGESTION_NO)
                                            .build());

                                    switchToListSuggestionCard.setVisibility(View.GONE);

                                    SharedPreferences.Editor editor = preferences.edit();
                                    editor.putBoolean(Strings.PREF_DISMISSED_SWITCH_TO_LIST_SUGGESTION, true);
                                    editor.apply();
                                }
                            });
                            ImageView switchToListIcon = (ImageView) switchToListSuggestionCard.findViewById(R.id.switch_to_list_icon);
                            switchToListIcon.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    tracker.send(new HitBuilders.EventBuilder()
                                            .setCategory(Strings.CAT_UI_ACTION)
                                            .setAction(Strings.ACT_CLICK_BUTTON)
                                            .setLabel(Strings.LBL_SWITCH_DISPLAY_MODE_SUGGESTION_LIST)
                                            .build());

                                    switchToListSuggestionCard.setVisibility(View.GONE);

                                    SharedPreferences.Editor editor = preferences.edit();
                                    editor.putBoolean(Strings.PREF_DISMISSED_SWITCH_TO_LIST_SUGGESTION, true);
                                    editor.apply();

                                    if(isAdded()) {
                                        ((MainActivity) getActivity()).getNavDrawer().switchDisplay(SettingsActivity.LocationsDisplay.LIST_VIEW.ordinal());
                                    }
                                }
                            });
                            ImageView switchToMapIcon = (ImageView) switchToListSuggestionCard.findViewById(R.id.switch_to_map_icon);
                            switchToMapIcon.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    tracker.send(new HitBuilders.EventBuilder()
                                            .setCategory(Strings.CAT_UI_ACTION)
                                            .setAction(Strings.ACT_CLICK_BUTTON)
                                            .setLabel(Strings.LBL_SWITCH_DISPLAY_MODE_SUGGESTION_MAP)
                                            .build());

                                    switchToListSuggestionCard.setVisibility(View.GONE);

                                    SharedPreferences.Editor editor = preferences.edit();
                                    editor.putBoolean(Strings.PREF_DISMISSED_SWITCH_TO_LIST_SUGGESTION, true);
                                    editor.apply();

                                    if(isAdded()) {
                                        ((MainActivity) getActivity()).getNavDrawer().switchDisplay(SettingsActivity.LocationsDisplay.MAP_VIEW.ordinal());
                                    }
                                }
                            });

                            switchToListSuggestionCard.setVisibility(View.VISIBLE);
                        }
                        else {
                            switchToListSuggestionCard.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }

        if(locationsState != null) {
            locationsList.onRestoreInstanceState(locationsState);
        }

        if(fragmentJustCreated || locationsDisplayChanged) {
            // There are 2 cases where we want to fetch the locations on fragment resume:
            //  1) the fragment was just created
            //  2) a preference was changed in the settings while this fragment was paused
            fetchLocations();
            fragmentJustCreated = false;
        }
    }

    public void refreshDisplay() {
        // I'm not sure why preferences can be null here, but a crash showed that it can be.
        initPrefsAndTrackers();
        usingGridView = Integer.valueOf(preferences.getString(Strings.PREF_LOCATIONS_VIEW, "0")) == SettingsActivity.LocationsDisplay.GRID_VIEW.ordinal();
        if(usingGridView) {
            tracker.setScreenName(Strings.GRID_VIEW);
        }
        else {
            tracker.setScreenName(Strings.LIST_VIEW);
        }
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        fetchLocations();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.list_menu, menu);

        if(!enableDates) {
            menu.findItem(R.id.menu_sort_newest).setVisible(false);
            menu.findItem(R.id.menu_sort_oldest).setVisible(false);
        }

        ((MainActivity) getActivity()).setupSearchMenu(menu);

        int sortTypeIdx = preferences.getInt(Strings.PREF_LOCATIONS_SORT, DatabaseHelper.LocationsQuerySortType.SORT_NEWEST.ordinal());
        sortType = DatabaseHelper.LocationsQuerySortType.values()[sortTypeIdx];
        MenuItem sortMenuItem = null;
        switch(sortType) {
            case SORT_ALPHABETICAL:
                sortMenuItem = menu.findItem(R.id.menu_sort_alpha);
                break;
            case SORT_REVERSE_ALPHABETICAL:
                sortMenuItem = menu.findItem(R.id.menu_sort_reverse_alpha);
                break;
            case SORT_NEWEST:
                sortMenuItem = menu.findItem(R.id.menu_sort_newest);
                break;
            case SORT_OLDEST:
                sortMenuItem = menu.findItem(R.id.menu_sort_oldest);
                break;
            case SORT_CLOSEST:
                sortMenuItem = menu.findItem(R.id.menu_sort_closest);
                break;
        }

        sortMenuItem.setChecked(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        String sortTypeStr;
        switch(item.getItemId()) {
            case R.id.menu_add_location:
                if(isAdded()) {
                    if(UnlockAppDialog.shouldShowDialog(getActivity(), getLocationsAdapter().getCount())) {
                        // If the user has not paid, they can only create a certain number of locations.
                        new UnlockAppDialog(getActivity()).show();
                        return true;
                    }

                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_MENU_ITEM)
                            .setLabel(Strings.LBL_ADD_PLACE)
                            .build());

                    ((MainActivity) getActivity()).openLocationDetail(null);
                }

                return true;
            case R.id.menu_sort_alpha:
                sortType = DatabaseHelper.LocationsQuerySortType.SORT_ALPHABETICAL;
                sortTypeStr = Strings.LBL_SORT_ALPHABETICAL;
                break;
            case R.id.menu_sort_reverse_alpha:
                sortType = DatabaseHelper.LocationsQuerySortType.SORT_REVERSE_ALPHABETICAL;
                sortTypeStr = Strings.LBL_SORT_REVERSE_ALPHABETICAL;
                break;
            case R.id.menu_sort_newest:
                sortType = DatabaseHelper.LocationsQuerySortType.SORT_NEWEST;
                sortTypeStr = Strings.LBL_SORT_NEWEST;
                break;
            case R.id.menu_sort_oldest:
                sortType = DatabaseHelper.LocationsQuerySortType.SORT_OLDEST;
                sortTypeStr = Strings.LBL_SORT_OLDEST;
                break;
            case R.id.menu_sort_closest:
                sortType = DatabaseHelper.LocationsQuerySortType.SORT_CLOSEST;
                sortTypeStr = Strings.LBL_SORT_CLOSEST;
                break;
            default:
                return super.onOptionsItemSelected(item);
        }

        tracker.send(new HitBuilders.EventBuilder()
                .setCategory(Strings.CAT_UI_ACTION)
                .setAction(Strings.ACT_CHANGE_LOCATION_SORT)
                .setLabel(sortTypeStr)
                .build());

        item.setChecked(true);

        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(Strings.PREF_LOCATIONS_SORT, sortType.ordinal());
        editor.commit();

        fetchLocations();
        return true;
    }

    @Override
    public void onRefresh() {
        if(swipeRefreshLayout.isRefreshing()) {
            tracker.send(new HitBuilders.EventBuilder()
                    .setCategory(Strings.CAT_UI_ACTION)
                    .setAction(Strings.ACT_CLICK_SETTING)
                    .setLabel(Strings.LBL_SYNC_NOW_SWIPE)
                    .build());

            // By calling forceSync(), we're assuming the sync will begin and SYNC_STARTED will get broadcast.
            // If the device doesn't have a good internet connection, this won't happen, so we set a timer to
            // hide the refresh icon and show an error.
            SyncSettingsFragment.forceSync(account);
            refreshDismissHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isAdded()) {
                        swipeRefreshLayout.setRefreshing(false);

                        // Show a snackbar with an error.
                        Snackbar snackbar = ((MainActivity) getActivity()).getSnackbar();
                        snackbar.expire();

                        Snackbar.ShowConfig config = snackbar.new ShowConfig();
                        config.text = getString(R.string.pref_sync_now_summary_error);
                        config.showButton = false;
                        snackbar.show(config);
                    }
                }
            }, SyncSettingsFragment.REFRESH_ICON_DISMISS_DELAY);
        }
    }

    @Override
    public boolean canChildScrollUp() {
        AbsListView absListView = getLocationsView();
        return absListView.getFirstVisiblePosition() > 0 ||
               absListView.getChildAt(0) == null ||
               absListView.getChildAt(0).getTop() < 0;
    }

    @Override
    public void onPause() {
        super.onPause();

        broadcastManager.unregisterReceiver(broadcastReceiver);
        broadcastManager.unregisterReceiver(amnesticBroadcastReceiver);

        if(swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
            swipeRefreshLayout.destroyDrawingCache();
            swipeRefreshLayout.clearAnimation();

            refreshDismissHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if(locationsList.getVisibility() == View.VISIBLE) {
            outState.putParcelable(Strings.PARAM_LOCATIONS_STATE, locationsList.onSaveInstanceState());
        }
        else {
            outState.putParcelable(Strings.PARAM_LOCATIONS_STATE, locationsGrid.onSaveInstanceState());
        }
        outState.putBoolean(Strings.PREF_LIST_VIEW_PHOTOS, usingImagesInListView);
        outState.putBoolean(Strings.PREF_SHOW_MAP_MARKERS, showMapMarkers);
        outState.putBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, showClosestAddress);
        outState.putBoolean(Strings.PREF_ENABLE_DATES, enableDates);
        outState.putIntArray("selectedIndices", MiscUtils.convertIntSetToArray(selectedIndices));
        outState.putInt("numLoadedBeforeRotate", currentNumLoaded);
        outState.putBoolean("fetchingLocationForPlacesSort", fetchingLocationForPlacesSort);
    }

    protected void fetchLocations() {
        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            locations = new ArrayList<>();
            currentNumLoaded = numLoadedBeforeRotate = 0;
            loadedTotal = false;

            if(sortType == DatabaseHelper.LocationsQuerySortType.SORT_CLOSEST) {
                sortPlacesByClosest();
            }
            else {
                fetchMoreLocations();
            }
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

    protected void fetchMoreLocations() {
        if(fetchingLocations) {
            return;
        }
        fetchingLocations = true;

        if(!isAdded()) {
            return;
        }

        String currentQuery = ((MainActivity) getActivity()).getCurrentQuery();
        String currentQueryType = ((MainActivity) getActivity()).getCurrentQueryType();
        int numToLoad = (currentNumLoaded == 0 && numLoadedBeforeRotate > 0) ? numLoadedBeforeRotate
                                                                             : LOAD_CHUNK_AMOUNT;

        DatabaseHelper.getInstance(getActivity()).fetchLocations(currentQuery, currentQueryType, sortType, currentNumLoaded, numToLoad, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                if (!isAdded()) return;

                Cursor cursor = result.cursor;
                if (cursor.getCount() == 0) {
                    loadedTotal = true;
                }
                else {
                    currentNumLoaded += cursor.getCount();
                    if (cursor.getCount() < LOAD_CHUNK_AMOUNT) {
                        loadedTotal = true;
                    }

                    while (!cursor.isAfterLast()) {
                        locations.add(ModelUtils.createLocationFromCursorForDisplay(cursor));
                        cursor.moveToNext();
                    }
                    cursor.close();
                }

                fetchingLocations = false;
                refreshLocations();

                // Hide the refresh icon after we're done loading the locations from the DB.
                swipeRefreshLayout.setRefreshing(isSyncing);
            }
        });
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if(loadedTotal || fetchingLocations) {
            return;
        }
        if(totalItemCount > 0 && totalItemCount - firstVisibleItem < LOAD_TOLERANCE_AMOUNT) {
            fetchMoreLocations();
        }
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
        boolean alreadyEnabled = swipeAdapter.getTouchListener().isEnabled();
        swipeAdapter.getTouchListener().setEnabled(alreadyEnabled && scrollState != AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            switch(requestCode) {
                case RequestCodes.PERMISSION_REQUEST_STORAGE:
                    fetchLocations();
                    break;
                case RequestCodes.PERMISSION_REQUEST_LOCATION:
                    if(locationPermissionRequestType == LocationPermissionRequestType.SORT_CLOSEST) {
                        sortPlacesByClosest();
                    }
                    else if(locationPermissionRequestType == LocationPermissionRequestType.RENDER_DISTANCES) {
                        renderDistances();
                    }
                    break;
            }
        }
    }

    enum LocationPermissionRequestType {
        SORT_CLOSEST,
        RENDER_DISTANCES
    }
    protected void sortPlacesByClosest() {
        locationPermissionRequestType = null;
        swipeRefreshLayout.setRefreshing(true);

        Application.LocationResponse locationResponse = Application.getInstance().getCurrentLocation(null);
        if(locationResponse.location != null) {
            DatabaseHelper.getInstance(getActivity()).setDistancesForLocations(locationResponse.getLatLng(), new DatabaseQueryListener() {
                @Override
                public void onQueryExecuted(DatabaseQueryResult result) {
                    fetchMoreLocations();
                }
            });
        }
        else if(!locationResponse.permissionGranted) {
            locationPermissionRequestType = LocationPermissionRequestType.SORT_CLOSEST;
            swipeRefreshLayout.setRefreshing(false);

            requestPermissions(
                    new String[] {
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    RequestCodes.PERMISSION_REQUEST_LOCATION);
        }
        else {
            fetchingLocationForPlacesSort = true;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch(requestCode) {
            case RequestCodes.BULK_EDIT_TAGS_ACTIVITY:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    ArrayList<Long> selectedLocationIds = (ArrayList<Long>) data.getSerializableExtra(Strings.PARAM_SELECTED_LOCATION_IDS);
                    if((Boolean) data.getSerializableExtra(Strings.PARAM_ADDING_TAGS)) {
                        ArrayList<Long> tagIdsToAddBefore = (ArrayList<Long>) data.getSerializableExtra(Strings.PARAM_SELECTED_TAG_IDS);
                        ArrayList<Long> tagIdsToAddAfter = (ArrayList<Long>) data.getSerializableExtra(Strings.PARAM_SELECTED_AFTER_TAG_IDS);
                        if(tagIdsToAddBefore.isEmpty() && tagIdsToAddAfter.isEmpty()) {
                            return;
                        }

                        DatabaseHelper.getInstance(getActivity()).addTagsToLocations(selectedLocationIds, tagIdsToAddBefore, tagIdsToAddAfter, new DatabaseQueryListener() {
                            @Override
                            public void onQueryExecuted(DatabaseQueryResult result) {
                                onFinishEditingTags();
                            }
                        });
                    }
                    else {
                        ArrayList<Long> tagIdsToRemove = (ArrayList<Long>) data.getSerializableExtra(Strings.PARAM_SELECTED_TAG_IDS);
                        if(tagIdsToRemove.isEmpty()) {
                            return;
                        }

                        DatabaseHelper.getInstance(getActivity()).removeTagsFromLocations(selectedLocationIds, tagIdsToRemove, new DatabaseQueryListener() {
                            @Override
                            public void onQueryExecuted(DatabaseQueryResult result) {
                                onFinishEditingTags();
                            }
                        });
                    }
                }
                break;
        }
    }

    protected void onFinishEditingTags() {
        if(isAdded()) {
            broadcastManager.sendBroadcastSync(new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST));
        }
    }

    public void refreshLocations() {
        if(!isAdded()) {
            return;
        }

        if(locations.isEmpty()) {
            // Set the empty list view text depending on whether a query is set or not.
            TextView emptyListText = (TextView) view.findViewById(R.id.empty_list_text);
            if(isAdded() && ((MainActivity) getActivity()).getCurrentQuery() != null) {
                emptyListText.setText(R.string.empty_places_search_text);
            }
            else {
                emptyListText.setText(R.string.empty_places_text);
            }
        }

        // This code is used to prevent out-of-bounds exceptions; if the user has loaded more than
        // [LOAD_CHUNK_AMOUNT] and then rotates the device, [locations] will be reset and
        // so should the [selectedIndices] if they're now out-of-bounds.
        //
        // TODO: make sure new ListView.onSaveInstanceState/onRestoreInstanceState method works.
        for(Integer idx : selectedIndices) {
            if(idx >= locations.size()) {
                ((MainActivity) getActivity()).finishActionMode();
                break;
            }
        }

        // Display the locations in the grid or list, depending on which is set in the preferences.
        if(usingGridView) {
            locationsGrid.setVisibility(View.VISIBLE);
            locationsList.setVisibility(View.GONE);

            gridAdapter.notifyDataSetChanged();
            if(locationsState != null) {
                locationsGrid.onRestoreInstanceState(locationsState);
                locationsState = null;
            }
            locationsGrid.invalidateViews();
        }
        else {
            locationsGrid.setVisibility(View.GONE);
            locationsList.setVisibility(View.VISIBLE);

            listAdapter.notifyDataSetChanged();
            if(locationsState != null) {
                locationsList.onRestoreInstanceState(locationsState);
                locationsState = null;
            }
            locationsList.invalidateViews();

            renderDistances();
        }
    }

    protected void renderDistances() {
        locationPermissionRequestType = null;

        Application.LocationResponse locationResponse = ((Application) getActivity().getApplication()).getCurrentLocation(null);
        if(locationResponse.permissionGranted) {
            renderDistances(locationResponse.location);
        }
        else {
            locationPermissionRequestType = LocationPermissionRequestType.RENDER_DISTANCES;
            requestPermissions(
                    new String[] {
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    RequestCodes.PERMISSION_REQUEST_LOCATION);
        }
    }
    protected void renderDistances(Location location) {
        if(usingGridView || locations == null || location == null) {
            return;
        }
        for(LocationModel locationModel : locations) {
            setDistance(locationModel, location);
        }
        locationsList.invalidateViews();
    }

    protected void setDistance(LocationModel locationModel, Location location) {
        if(!locationModel.hasLocation()) {
            return;
        }

        float[] results = new float[1];
        Location.distanceBetween(
                location.getLatitude(),
                location.getLongitude(),
                locationModel.getLocationInfo().location.latitude,
                locationModel.getLocationInfo().location.longitude,
                results);
        float distance = results[0];
        locationModel.setDistance(distance);
    }

    // distance is input as meters and should be converted depending on the user's preferences.
    protected String getDistanceStr(float distance) {
        boolean useMetricSystem = preferences.getString(Strings.PREF_MEASUREMENT, "0").equals("0");
        String measurement;
        if(useMetricSystem) {
            measurement = "m";
            if(distance >= 1000.0) {
                measurement = "km";
                distance /= 1000;
            }
        }
        else {
            // Convert meters to miles or feet.
            distance *= 0.00062137;
            measurement = "mi";
            if(distance < 1.0) {
                distance *= 5280;
                measurement = "ft";
            }
        }

        return new DecimalFormat("###,###.#").format(distance) + " " + measurement;
    }

    protected ArrayAdapter<LocationModel> getLocationsAdapter() {
        if(usingGridView) {
            return gridAdapter;
        }
        return listAdapter;
    }

    protected AbsListView getLocationsView() {
        if(usingGridView) {
            return locationsGrid;
        }
        return locationsList;
    }

    protected void setSelected(int position, boolean isSelected) {
        if(isSelected) {
            selectedIndices.add(position);
        }
        else {
            selectedIndices.remove(position);
        }

        getLocationsAdapter().notifyDataSetInvalidated();
    }

    protected void clearSelected() {
        selectedIndices.clear();
        getLocationsAdapter().notifyDataSetInvalidated();
    }

    protected boolean isSelected(int position) {
        return selectedIndices.contains(position);
    }

    protected List<Long> getSelectedIds() {
        ArrayAdapter<LocationModel> adapter = getLocationsAdapter();
        List<Long> selectedIds = new ArrayList<Long>();
        for(Integer i : selectedIndices) {
            selectedIds.add(adapter.getItemId(i));
        }
        return selectedIds;
    }

    protected List<LocationModel> getSelections() {
        ArrayAdapter<LocationModel> adapter = getLocationsAdapter();
        List<LocationModel> selections = new ArrayList<LocationModel>();
        for(Integer i : selectedIndices) {
            selections.add(adapter.getItem(i));
        }
        return selections;
    }

    // This class is used to display locations in a list view.
    private class LocationListAdapter extends ArrayAdapter<LocationModel> {

        private LayoutInflater inflater;

        public LocationListAdapter() {
            super(getActivity(), R.layout.list_item_view);
            inflater = getActivity().getLayoutInflater();
        }

        @Override
        public void notifyDataSetChanged() {
            showMapMarkers = preferences.getBoolean(Strings.PREF_SHOW_MAP_MARKERS, true);
            super.notifyDataSetChanged();
        }

        @Override
        public void notifyDataSetInvalidated() {
            showMapMarkers = preferences.getBoolean(Strings.PREF_SHOW_MAP_MARKERS, true);
            super.notifyDataSetInvalidated();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View locationItemView;
            LocationListHolder holder;
            if(convertView != null) {
                locationItemView = convertView;
                holder = (LocationListHolder) locationItemView.getTag();
            }
            else {
                locationItemView = inflater.inflate(R.layout.list_item_view, parent, false);
                holder = new LocationListHolder(locationItemView);
                locationItemView.setTag(holder);
            }

            holder.selectedView.setVisibility(isSelected(position) ? View.VISIBLE : View.GONE);

            final LocationModel locationModel = locations.get(position);

            holder.titleText.setText(locationModel.getTitleForDisplay(showClosestAddress));

            List<PhotoInfo> photoInfoList = locationModel.getPhotoInfoList();
            if(photoInfoList != null && !photoInfoList.isEmpty() && usingImagesInListView) {
                holder.imageView.setVisibility(View.VISIBLE);

                PhotoLoader.loadIntoImageView(getActivity(), photoInfoList.get(0), holder.imageView);
            }
            else {
                holder.imageView.setVisibility(View.GONE);
            }

            if(enableDates) {
                holder.dateText.setVisibility(View.VISIBLE);
                holder.dateText.setText(DateFormat.getDateFormat(getActivity()).format(locationModel.getDate()));
            }
            else {
                holder.dateText.setVisibility(View.GONE);
            }

            String tagsStr = locationModel.getTagsStr();
            if(tagsStr == null) {
                holder.tagsIcon.setVisibility(View.GONE);
                holder.tagsText.setVisibility(View.GONE);
            }
            else {
                holder.tagsIcon.setVisibility(View.VISIBLE);
                holder.tagsText.setVisibility(View.VISIBLE);

                boolean setTagsIcon = false;
                if(showMapMarkers) {
                    if(locationModel.getIconPath() != null) {
                        File iconFile = new File(locationModel.getIconPath());
                        if(iconFile.exists()) {
                            Glide.with(getActivity())
                                    .load(Uri.fromFile(iconFile))
                                    .into(holder.tagsIcon);
                            setTagsIcon = true;
                        }
                    }
                    else if(locationModel.getColor() != 0) {
                        GradientDrawable colorPreviewDrawable = (GradientDrawable) getResources().getDrawable(R.drawable.mini_color_preview);
                        if(colorPreviewDrawable != null) {
                            colorPreviewDrawable.setColor(locationModel.getColor());
                            holder.tagsIcon.setImageDrawable(colorPreviewDrawable);
                            setTagsIcon = true;
                        }
                    }
                }

                if(!setTagsIcon) {
                    holder.tagsIcon.setImageDrawable(getResources().getDrawable(listTagImage.resourceId));
                }
                holder.tagsText.setText(tagsStr);
            }

            if(locationModel.getDistance() != null) {
                holder.distanceText.setText(getDistanceStr(locationModel.getDistance()));
            }

            return locationItemView;
        }

        @Override
        public int getCount() {
            return locations != null ? locations.size() : 0;
        }

        @Override
        public long getItemId(int position) {
            return locations.get(position).getLocalId();
        }

        @Override
        public LocationModel getItem(int position) { return locations.get(position); }
    }

    class LocationListHolder {

        TextView titleText;
        ImageView imageView;
        TextView dateText;
        ImageView tagsIcon;
        TextView tagsText;
        TextView distanceText;

        View selectedView;

        public LocationListHolder(View locationItemView) {
            titleText = (TextView) locationItemView.findViewById(R.id.list_title);
            imageView = (ImageView) locationItemView.findViewById(R.id.list_image);
            dateText = (TextView) locationItemView.findViewById(R.id.list_date);
            tagsIcon = (ImageView) locationItemView.findViewById(R.id.list_tag_icon);
            tagsText = (TextView) locationItemView.findViewById(R.id.list_tags);
            distanceText = (TextView) locationItemView.findViewById(R.id.list_distance);

            selectedView = locationItemView.findViewById(R.id.selected_view);
        }
    }

    // This class is used to display locations in a grid view.
    private class LocationGridAdapter extends ArrayAdapter<LocationModel> {

        private LayoutInflater inflater;

        public LocationGridAdapter() {
            super(getActivity(), R.layout.gallery_image_view);
            inflater = LayoutInflater.from(getActivity());
        }

        @Override
        public void notifyDataSetChanged() {
            showMapMarkers = preferences.getBoolean(Strings.PREF_SHOW_MAP_MARKERS, true);
            super.notifyDataSetChanged();
        }

        @Override
        public void notifyDataSetInvalidated() {
            showMapMarkers = preferences.getBoolean(Strings.PREF_SHOW_MAP_MARKERS, true);
            super.notifyDataSetInvalidated();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final GalleryImageView view;
            if(convertView != null) {
                view = (GalleryImageView) convertView;
            }
            else {
                view = (GalleryImageView) inflater.inflate(R.layout.gallery_image_view, parent, false);
            }

            View selectedView = view.findViewById(R.id.selected_view);
            selectedView.setVisibility(isSelected(position) ? View.VISIBLE : View.GONE);

            LocationModel locationModel = locations.get(position);
            view.setText(locationModel.getTitleForDisplay(showClosestAddress));
            if(showMapMarkers) {
                view.setMapMarker(locationModel.getIconPath(), locationModel.getColor());
            }
            else {
                view.setMapMarker(null, 0);
            }

            List<PhotoInfo> photoInfoList = locationModel.getPhotoInfoList();
            if(photoInfoList != null && !photoInfoList.isEmpty()) {
                view.setPhoto(photoInfoList.get(0));
            }
            else {
                view.setPhoto(null);
            }

            return view;
        }

        @Override
        public int getCount() {
            return locations != null ? locations.size() : 0;
        }

        @Override
        public long getItemId(int position) {
            return locations.get(position).getLocalId();
        }

        @Override
        public LocationModel getItem(int position) { return locations.get(position); }

    }

    class LocationsItemClickListener implements AdapterView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            tracker.send(new HitBuilders.EventBuilder()
                    .setCategory(Strings.CAT_UI_ACTION)
                    .setAction(Strings.ACT_CLICK_LIST_ITEM)
                    .setLabel(Strings.LBL_PLACE)
                    .build());
            if (isAdded()) {
                ((MainActivity) getActivity()).openLocationDetail(id);
            }
        }
    }

    class LocationsMultiChoiceModeListener implements AbsListView.MultiChoiceModeListener {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.contextual_menu, menu);
            swipeAdapter.getTouchListener().setEnabled(false);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

        @Override
        public boolean onActionItemClicked(final ActionMode mode, MenuItem item) {
            switch(item.getItemId()) {
                case R.id.menu_finish_selection:
                    mode.finish();
                    return true;
                case R.id.menu_select_all:
                    for(int i = 0; i < getLocationsAdapter().getCount(); ++i) {
                        getLocationsView().setItemChecked(i, true);
                    }
                    return true;
                case R.id.menu_delete_selected:
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_DELETE_PLACES)
                            .build());

                    final Snackbar snackbar = ((MainActivity) getActivity()).getSnackbar();
                    snackbar.expire();

                    final List<Long> tentativelyDeletedLocationIds = getSelectedIds();
                    DatabaseHelper.getInstance(getActivity()).tentativelyDeleteLocations(tentativelyDeletedLocationIds, new DatabaseQueryListener() {
                        @Override
                        public void onQueryExecuted(DatabaseQueryResult result) {
                            if(!isAdded()) {
                                return;
                            }

                            broadcastManager.sendBroadcastSync(new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST));

                            Snackbar.ShowConfig config = snackbar.new ShowConfig();
                            config.text = getResources().getQuantityString(R.plurals.places_deleted_snackbar, tentativelyDeletedLocationIds.size(), tentativelyDeletedLocationIds.size());
                            config.listener = snackbar.new Listener() {
                                @Override
                                public void onExpired() {
                                    DatabaseHelper.getInstance(getActivity()).deleteLocations(tentativelyDeletedLocationIds, null);
                                }

                                @Override
                                public void onButtonClicked() {
                                    tracker.send(new HitBuilders.EventBuilder()
                                            .setCategory(Strings.CAT_UI_ACTION)
                                            .setAction(Strings.ACT_CLICK_BUTTON)
                                            .setLabel(Strings.LBL_DELETE_PLACES_UNDO)
                                            .build());

                                    // Undo deleting these locations.
                                    DatabaseHelper.getInstance(getActivity()).undeleteLocations(tentativelyDeletedLocationIds, new DatabaseQueryListener() {
                                        @Override
                                        public void onQueryExecuted(DatabaseQueryResult result) {
                                            broadcastManager.sendBroadcastSync(new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST));
                                        }
                                    });
                                }
                            };
                            snackbar.show(config);
                        }
                    });
                    mode.finish();
                    return true;
                case R.id.menu_edit_tags: {
                    Intent intent = new Intent(getActivity(), BulkEditTagsActivity.class);
                    intent.putExtra(Strings.PARAM_SELECTED_LOCATION_IDS, MiscUtils.convertLongListToArray(getSelectedIds()));
                    startActivityForResult(intent, RequestCodes.BULK_EDIT_TAGS_ACTIVITY);
                    mode.finish();
                    return true;
                }
                case R.id.menu_export_gpx: {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_EXPORT_PLACES_GPX)
                            .build());

                    Intent intent = new Intent(getActivity(), ExportService.class);
                    ArrayList<Integer> selectedIds = new ArrayList<>();
                    for (LocationModel model : getSelections()) {
                        selectedIds.add(model.getLocalId().intValue());
                    }
                    intent.putIntegerArrayListExtra(Strings.LOCATION_IDS, selectedIds);
                    intent.putExtra(Strings.EXPORT_TYPE, RequestCodes.EXPORT_TYPE_GPX);
                    getActivity().startService(intent);

                    mode.finish();
                    return true;
                }
                case R.id.menu_export_csv: {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_EXPORT_PLACES_CSV)
                            .build());

                    Intent intent = new Intent(getActivity(), CSVConfigurationActivity.class);
                    ArrayList<Integer> selectedIds = new ArrayList<>();
                    for (LocationModel model : getSelections()) {
                        selectedIds.add(model.getLocalId().intValue());
                    }
                    intent.putIntegerArrayListExtra(Strings.LOCATION_IDS, selectedIds);
                    startActivity(intent);

                    mode.finish();
                    return true;
                }
            }

            return false;
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
            setSelected(position, checked);
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            clearSelected();
            swipeAdapter.getTouchListener().setEnabled(true);
        }

    }

    class BroadcastReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST.equals(action)) {
                int requestCode = intent.getIntExtra("requestCode", -1);
                switch(requestCode) {
                    case RequestCodes.SYNC_STARTED:
                        isSyncing = true;
                        swipeRefreshLayout.setRefreshing(true);
                        refreshDismissHandler.removeCallbacksAndMessages(null);
                        break;
                    case RequestCodes.SYNC_INVALID_ACCOUNT_ERROR:
                        isSyncing = false;
                        SyncSettingsFragment.promptAccountPicker(getActivity());

                        // Remove broadcast from all receivers.
                        broadcastManager.removeLastBroadcast(action);
                        break;
                    case RequestCodes.SYNC_RECOVERABLE_AUTH_EXCEPTION:
                        isSyncing = false;
                        if(isAdded()) {
                            Intent authExceptionIntent = intent.getParcelableExtra("authExceptionIntent");
                            startActivityForResult(authExceptionIntent, RequestCodes.REQUEST_AUTHORIZATION);
                            broadcastManager.removeLastBroadcast(action);
                        }
                        break;
                    case RequestCodes.SYNC_STORAGE_LIMIT_REACHED_ERROR:
                        isSyncing = false;
                        SyncSettingsFragment.showStorageFullDialog(getActivity(), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                tracker.send(new HitBuilders.EventBuilder()
                                        .setCategory(Strings.CAT_UI_ACTION)
                                        .setAction(Strings.ACT_CLICK_BUTTON)
                                        .setLabel(Strings.LBL_GDRIVE_STORAGE_FULL_OK)
                                        .build());

                                broadcastManager.removeLastBroadcast(action);
                            }
                        });

                        break;
                    case RequestCodes.SYNC_PHOTO_FILE_CREATE_EXCEPTION:
                        isSyncing = false;
                        String folder = intent.getStringExtra("folder");
                        SyncSettingsFragment.showPhotoFileCreateErrorDialog(getActivity(), folder, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                broadcastManager.removeLastBroadcast(action);
                            }
                        });

                        break;
                    case RequestCodes.SYNC_CANNOT_REACH_SERVER_ERROR:
                    case RequestCodes.SYNC_AUTH_EXCEPTION:
                    case RequestCodes.SYNC_IO_EXCEPTION:
                    case RequestCodes.SYNC_FINISHED:
                        isSyncing = false;

                        Long totalSyncTime = intent.getLongExtra(Strings.PARAM_TOTAL_SYNC_TIME, 0);
                        if(totalSyncTime > 0) {
                            timingTracker.send(new HitBuilders.TimingBuilder()
                                    .setCategory(Strings.CAT_SYNC)
                                    .setVariable(Strings.VAR_TOTAL_SYNC_TIME)
                                    .setValue(totalSyncTime)
                                    .build());
                        }

                        // If we don't remove this broadcast when the sync is finished, it will enter this code
                        // from onResume() which sends the last broadcast for DRIVE_SYNC_ADAPTER_BROADCAST (to
                        // support showing the refresh icon for SYNC_STARTED). This results in an unnecessary call
                        // to fetchLocations().
                        broadcastManager.removeLastBroadcast(ListFragment.class.getCanonicalName(), action);

                        // Broadcast that the locations have been changed to both ListFragment and ListMapFragment.
                        broadcastManager.sendBroadcastSync(new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST));
                        break;
                }
            }
            else if(Application.LOCATION_UPDATE_BROADCAST.equals(action)) {
                renderDistances((Location) intent.getParcelableExtra("location"));
                if(fetchingLocationForPlacesSort) {
                    fetchingLocationForPlacesSort = false;
                    fetchMoreLocations();
                }
            }
            else if(MainActivity.LOCATIONS_CHANGED_BROADCAST.equals(action)) {
                fetchLocations();

                // We remove the broadcast for ListFragment so that on the next resume it doesn't
                // call fetchMoreLocations() again unnecessarily.
                broadcastManager.removeLastBroadcast(ListFragment.class.getCanonicalName(), action);
            }
            else if(ExportService.EXPORT_BROADCAST.equals(action)) {
                if (isAdded()) {
                    int requestCode = intent.getIntExtra("requestCode", -1);
                    switch (requestCode) {
                        case RequestCodes.EXPORT_STARTED:
                            exportProgressDialog = ProgressDialog.show(getActivity(), getString(R.string.export_notification_title), null, true);
                            break;
                        case RequestCodes.EXPORT_FINISHED:
                            if (exportProgressDialog != null) {
                                exportProgressDialog.dismiss();
                            }
                            broadcastManager.removeLastBroadcast(action);

                            Intent shareIntent = new Intent();
                            shareIntent.setAction(Intent.ACTION_SEND);
                            shareIntent.setType(intent.getStringExtra("exportedFileType"));
                            shareIntent.putExtra(Intent.EXTRA_STREAM, intent.getParcelableExtra("exportedFileUri"));
                            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_share_intent_title)));
                            break;
                        case RequestCodes.EXPORT_FAILED:
                            if (exportProgressDialog != null) {
                                exportProgressDialog.dismiss();
                            }
                            broadcastManager.removeLastBroadcast(action);

                            new AlertDialog.Builder(getActivity())
                                    .setTitle(R.string.export_failed_title)
                                    .setMessage(R.string.export_failed_message)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .setCancelable(false)
                                    .show();
                            break;
                    }
                }
            }
        }
    }

    class AmnesticBroadcastReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if(Application.LOCATION_FAILED_BROADCAST.equals(action)) {
                if(fetchingLocationForPlacesSort) {
                    swipeRefreshLayout.setRefreshing(false);
                    fetchingLocationForPlacesSort = false;

                    new AlertDialog.Builder(getActivity())
                            .setTitle(R.string.location_request_for_places_failed_title)
                            .setMessage(R.string.location_request_for_places_failed_message)
                            .setPositiveButton(R.string.try_again, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    tracker.send(new HitBuilders.EventBuilder()
                                            .setCategory(Strings.CAT_UI_ACTION)
                                            .setAction(Strings.ACT_CLICK_BUTTON)
                                            .setLabel(Strings.LBL_TRY_CLOSEST_SORT_AGAIN)
                                            .build());

                                    fetchLocations();
                                }
                            })
                            .setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    tracker.send(new HitBuilders.EventBuilder()
                                            .setCategory(Strings.CAT_UI_ACTION)
                                            .setAction(Strings.ACT_CLICK_BUTTON)
                                            .setLabel(Strings.LBL_CANCEL_CLOSEST_SORT)
                                            .build());
                                }
                            })
                            .show();
                }
            }
        }
    }

}
