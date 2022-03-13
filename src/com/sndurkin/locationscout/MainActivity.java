package com.sndurkin.locationscout;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.maps.model.LatLng;
import com.google.api.services.drive.Drive;
import com.sndurkin.locationscout.integ.ImportGeoPlaceAsyncTask;
import com.sndurkin.locationscout.integ.ImportService;
import com.sndurkin.locationscout.integ.ImportURLPlaceAsyncTask;
import com.sndurkin.locationscout.settings.SettingsActivity;
import com.sndurkin.locationscout.settings.SyncSettingsFragment;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.LocationModelChanges;
import com.sndurkin.locationscout.util.PhotoLoader;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;
import com.sndurkin.locationscout.util.UnlockAppDialog;
import com.sndurkin.locationscout.util.Versions;

import java.io.InputStream;
import java.sql.Date;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.regex.Matcher;

// Main activity which displays the current locations as well as a navigation drawer
// allowing the user to toggle between different fragments.
public class MainActivity extends AppCompatActivity implements DriveEnabledScreen {

    public static final String LOCATIONS_CHANGED_BROADCAST = "com.sndurkin.locationscout.LOCATIONS_CHANGED_BROADCAST";

    private String themePrefValue;
    private boolean importStarted;

    private Tracker tracker;

    private Drive driveService;

    private NavigationDrawer navDrawer;
    private DrawerLayout navDrawerLayout;

    private FrameLayout frame;
    private Snackbar snackbar;

    private ListFragment listFragment;
    private ListMapFragment listMapFragment;

    private GlobalBroadcastManager broadcastManager;

    private ProgressDialog importDialog;

    private SearchView searchView;
    private MenuItem searchMenuItem;
    private String currentQuery;
    private String currentQueryType;
    private boolean autoExpandSearch = false;

    private Deque<FragmentFrame> fragmentStack;

    private ActionMode actionMode;

    private SharedPreferences preferences;
    private boolean firstAppLaunch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        // We set the ApplicationContext's theme so that it can be accessed from within the suggestion ContentProviders
        // to set the correct icon for each suggestion depending on the theme.
        themePrefValue = preferences.getString(Strings.PREF_THEME, "0");
        int currentTheme = UIUtils.getCurrentTheme(this);
        setTheme(currentTheme);
        getApplicationContext().getTheme().applyStyle(currentTheme, true);
        PhotoLoader.resetPhotoResources(this);

        if(BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .penaltyDeath()
                    .build());
        }

        super.onCreate(savedInstanceState);

        checkForGooglePlayServices();

        broadcastManager = GlobalBroadcastManager.getInstance(this);
        tracker = ((Application) getApplication()).getTracker();

        setContentView(R.layout.main_activity);

        frame = (FrameLayout) findViewById(R.id.frame);
        snackbar = (Snackbar) findViewById(R.id.snackbar);

        fragmentStack = new ArrayDeque<>();

        Versions.checkAndUpdate(preferences, Strings.APP_VERSION, Versions.Defaults.APP_VERSION, new Versions.Listener() {
            @Override
            public void onFirstVersion() {
                firstAppLaunch = true;
            }

            @Override
            public void onUpdateVersion() {
                // Nothing here yet.
            }
        });

        setupNavDrawer(preferences.getInt(Strings.PREF_INTRINSIC_NAV_ITEM, NavActionType.NAV_LOCATIONS.ordinal()));
        if(savedInstanceState != null) {
            currentQuery = savedInstanceState.getString(Strings.PARAM_QUERY);
            currentQueryType = savedInstanceState.getString(Strings.PARAM_QUERY_TYPE);

            // TODO: FragmentFrame should handle its own serialization.
            ArrayList<String> fragmentClassNames = savedInstanceState.getStringArrayList(Strings.PARAM_FRAGMENT_CLASS_NAMES);
            ArrayList<Integer> intrinsicItemIndices = savedInstanceState.getIntegerArrayList(Strings.PARAM_INTRINSIC_ITEM_INDICES);
            if(fragmentClassNames != null) {
                try {
                    for (int i = 0; i < fragmentClassNames.size(); ++i) {
                        FragmentFrame frame = new FragmentFrame();
                        frame.fragmentClass = (Class<? extends Fragment>) Class.forName(fragmentClassNames.get(i));
                        frame.intrinsicItemIdx = intrinsicItemIndices.get(i);
                        fragmentStack.addLast(frame);
                    }

                    FragmentFrame topFrame = fragmentStack.peek();
                    navDrawer.selectIntrinsicItem(topFrame.intrinsicItemIdx);
                }
                catch(ClassNotFoundException e) {
                    CrashlyticsCore.getInstance().logException(e);
                    setupFragmentStack();
                }
            }
        }
        else {
            setupFragmentStack();
        }

        if(handleIntentForWidget(getIntent())) {
            return;
        }
        if(handleIntentForImport(getIntent(), savedInstanceState)) {
            return;
        }

        if(!UIUtils.showReviewMessageIfApplicable(this, tracker)) {
            UIUtils.showSyncWarningIfApplicable(this, tracker);
        }
    }

    public void updateTitle() {
        if(currentQuery != null) {
            setTitle(currentQuery);
        }
        else if(Strings.SUGGESTION_TYPE_TAG.equals(currentQueryType)) {
            setTitle(R.string.untagged_places_title);
        }
        else {
            setTitle(R.string.places_title);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        ArrayList<String> fragmentClassNames = new ArrayList<String>();
        ArrayList<Integer> intrinsicItemIndices = new ArrayList<Integer>();
        for(FragmentFrame frame : fragmentStack) {
            fragmentClassNames.add(frame.fragmentClass.getCanonicalName());
            intrinsicItemIndices.add(frame.intrinsicItemIdx);
        }
        outState.putStringArrayList(Strings.PARAM_FRAGMENT_CLASS_NAMES, fragmentClassNames);
        outState.putIntegerArrayList(Strings.PARAM_INTRINSIC_ITEM_INDICES, intrinsicItemIndices);
        outState.putString(Strings.PARAM_QUERY, currentQuery);
        outState.putString(Strings.PARAM_QUERY_TYPE, currentQueryType);
        outState.putBoolean(Strings.PARAM_IMPORT_STARTED, importStarted);
    }

    protected void executeSearch(String query, String queryType) {
        currentQuery = query;
        currentQueryType = queryType;
        updateActionBar();

        /*
        // First check to see if the user is trying to search for coordinates on the map view.
        if(Strings.SUGGESTION_TYPE_RAW.equals(queryType) && getLocationsDisplayIdx() == SettingsActivity.LocationsDisplay.MAP_VIEW.ordinal()) {
            LatLng latLng = LatLngParser.parse(query);
            if(latLng != null) {
                // TODO: Implement mapFragment.dropAddPlacePin()
                return;
            }
        }
        */
        // Execute the search.
        broadcastManager.sendBroadcast(new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST));
    }

    // If tagName is null, it searches for all untagged locations.
    protected void browseByTag(String tagName) {
        NavActionParams actionParams = new NavActionParams();
        actionParams.type = NavActionType.EXECUTE_BROWSE;
        actionParams.query = tagName;
        actionParams.queryType = Strings.SUGGESTION_TYPE_TAG;
        executeAction(actionParams);
    }

    protected void clearSearch() {
        currentQuery = currentQueryType = null;
        updateActionBar();

        // Re-execute the search.
        broadcastManager.sendBroadcastSync(new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST));
    }

    public void dropMapPin(LatLng latLng, String title) {
        NavActionParams actionParams = new NavActionParams();
        actionParams.type = NavActionType.DROP_MAP_PIN;
        actionParams.fragmentArgs = new Bundle();
        actionParams.fragmentArgs.putParcelable("droppedPinLatLng", latLng);
        actionParams.fragmentArgs.putString("droppedPinTitle", title);
        executeAction(actionParams);
    }

    public void setupSearchMenu(Menu menu) {
        // Get the SearchView and set the searchable configuration.
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        searchMenuItem = menu.findItem(R.id.menu_search);
        searchMenuItem.setVisible(false);
        if(autoExpandSearch) {
            searchMenuItem.expandActionView();
            autoExpandSearch = false;
        }

        searchView = (SearchView) MenuItemCompat.getActionView(searchMenuItem);
        if(searchView == null) {
            return;
        }

        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        searchView.setIconifiedByDefault(true);
        MenuItemCompat.setOnActionExpandListener(searchMenuItem, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                searchMenuItem.setVisible(true);
                navDrawer.selectIntrinsicItem(NavActionType.NAV_SEARCH.ordinal());
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                searchMenuItem.setVisible(false);
                navDrawer.selectIntrinsicItem(NavActionType.NAV_LOCATIONS.ordinal());
                return true;
            }
        });
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_SEARCH)
                        .setLabel(Strings.LBL_RAW_TEXT)
                        .build());

                // This code is executed when the user enters a search query and executes the search without
                // selecting an autocomplete suggestion.
                NavActionParams actionParams = new NavActionParams();
                actionParams.type = NavActionType.EXECUTE_SEARCH;
                actionParams.query = query;
                actionParams.queryType = Strings.SUGGESTION_TYPE_RAW;
                executeAction(actionParams);
                return true;
            }

            // This override is unused.
            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });
        searchView.setOnSuggestionListener(new SearchView.OnSuggestionListener() {
            @Override
            public boolean onSuggestionClick(int position) {
                Cursor cursor = searchView.getSuggestionsAdapter().getCursor();
                if (cursor != null) {
                    if (cursor.moveToPosition(position)) {
                        tracker.send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_SEARCH)
                                .setLabel(Strings.LBL_AUTOCOMPLETE_RESULT)
                                .build());

                        // This code is executed when the user enters a search query and selects an autocomplete suggestion.
                        NavActionParams actionParams = new NavActionParams();
                        actionParams.type = NavActionType.EXECUTE_SEARCH;
                        actionParams.query = cursor.getString(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA));
                        actionParams.queryType = cursor.getString(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA));
                        executeAction(actionParams);
                    }
                    cursor.close();
                }

                return true;
            }

            // This override is unused.
            @Override
            public boolean onSuggestionSelect(int position) {
                return true;
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Pass the event to ActionBarDrawerToggle; if it returns
        // true, then it has handled the app icon touch event.
        if(navDrawer.getActionBarToggle().onOptionsItemSelected(item)) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        navDrawer.getActionBarToggle().syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        navDrawer.getActionBarToggle().onConfigurationChanged(newConfig);
    }

    protected void setupNavDrawer(int intrinsicItemIdx) {
        navDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        navDrawer = (NavigationDrawer) findViewById(R.id.drawer);
        navDrawer.init(this, navDrawerLayout, intrinsicItemIdx, new NavigationDrawer.NavigationDrawerListener() {
            @Override
            public void onLocationsDisplayChanged(int oldDisplayIdx, int newDisplayIdx) {
                Class<? extends Fragment> oldFragmentClass = getLocationsDisplayFragmentClass(oldDisplayIdx);
                Class<? extends Fragment> newFragmentClass = getLocationsDisplayFragmentClass();

                // If we're currently displaying locations, then swap the fragments (if necessary).
                if (fragmentStack.peek().fragmentClass == oldFragmentClass) {
                    NavActionParams actionParams = new NavActionParams();
                    actionParams.type = NavActionType.NAV_SWITCH_DISPLAY_MODE;
                    executeAction(actionParams);
                }

                // Iterate the entire fragment stack and replace the fragment classes (if necessary).
                if (oldFragmentClass != newFragmentClass) {
                    for (FragmentFrame frame : fragmentStack) {
                        if (frame.fragmentClass == oldFragmentClass) {
                            frame.fragmentClass = newFragmentClass;
                        }
                    }
                }
            }

            @Override
            public void onIntrinsicItemChanged(int oldIdx, int newIdx) {
                if (oldIdx == 0) {
                    listFragment = null;
                }

                if(newIdx == NavActionType.NAV_LOCATIONS.ordinal() || newIdx == NavActionType.NAV_BROWSE.ordinal()) {
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putInt(Strings.PREF_INTRINSIC_NAV_ITEM, newIdx);
                    editor.apply();
                }

                NavActionParams actionParams = new NavActionParams();
                actionParams.intrinsicItemIdx = newIdx;
                actionParams.type = NavActionType.values()[newIdx];
                executeAction(actionParams);
            }

        });
    }

    public NavigationDrawer getNavDrawer() {
        return navDrawer;
    }

    protected void setupFragmentStack() {
        replaceFirstFragment(preferences.getInt(Strings.PREF_INTRINSIC_NAV_ITEM, NavActionType.NAV_LOCATIONS.ordinal()));
        displayFragment(fragmentStack.peek().fragmentClass);
    }

    protected void replaceFirstFragment(int intrinsicItemIdx) {
        fragmentStack.clear();

        FragmentFrame frame = new FragmentFrame();
        frame.intrinsicItemIdx = intrinsicItemIdx;
        if(frame.intrinsicItemIdx == NavActionType.NAV_LOCATIONS.ordinal()) {
            frame.fragmentClass = getLocationsDisplayFragmentClass();
        }
        else if(frame.intrinsicItemIdx == NavActionType.NAV_BROWSE.ordinal()) {
            frame.fragmentClass = BrowseByTagsFragment.class;
        }
        else {
            CrashlyticsCore.getInstance().logException(new RuntimeException("How did we get into this path? frame.intrinsicItemIdx == " + frame.intrinsicItemIdx));
        }
        fragmentStack.push(frame);
    }

    @Override
    public void onBackPressed() {
        if(searchMenuItem != null && searchMenuItem.isActionViewExpanded()) {
            searchMenuItem.collapseActionView();
        }
        else if(fragmentStack.size() > 1) {
            NavActionParams actionParams = new NavActionParams();
            actionParams.type = NavActionType.BACK;
            executeAction(actionParams);
        }
        else {
            super.onBackPressed();
        }
    }

    private int getLocationsDisplayIdx() {
        return Integer.valueOf(preferences.getString(Strings.PREF_LOCATIONS_VIEW, "0"));
    }

    private Class<? extends Fragment> getLocationsDisplayFragmentClass() {
        return getLocationsDisplayFragmentClass(getLocationsDisplayIdx());
    }

    private Class<? extends Fragment> getLocationsDisplayFragmentClass(int displayIdx) {
        if(displayIdx == SettingsActivity.LocationsDisplay.MAP_VIEW.ordinal()) {
            return ListMapFragment.class;
        }
        else {
            return ListFragment.class;
        }
    }

    private boolean currentlyDisplayingLocations() {
        if(fragmentStack.isEmpty()) {
            return false;
        }

        FragmentFrame f = fragmentStack.peek();
        return f.fragmentClass == ListFragment.class
            || f.fragmentClass == ListMapFragment.class;
    }

    private void clearFragmentStack() {
        while(fragmentStack.size() > 1) {
            fragmentStack.pop();
        }
    }

    private void executeAction(NavActionParams actionParams) {
        if(actionParams.type != NavActionType.NAV_SWITCH_DISPLAY_MODE) {
            // We only want to preserve the current search query when switching display modes.
            currentQuery = currentQueryType = null;
        }

        boolean shouldAddFrame = false;
        boolean displayingLocations = currentlyDisplayingLocations();
        switch(actionParams.type) {
            case NAV_LOCATIONS:
                // If we're already displaying locations, then just clearSearch().
                // Otherwise, display locations fragment.
                replaceFirstFragment(actionParams.intrinsicItemIdx);
                if(displayingLocations) {
                    clearSearch();
                }
                else {
                    displayFragment(fragmentStack.peek().fragmentClass);
                }
                break;
            case NAV_BROWSE:
                replaceFirstFragment(actionParams.intrinsicItemIdx);
                displayFragment(actionParams.fragmentClass = BrowseByTagsFragment.class);
                break;
            case NAV_SEARCH:
                if(displayingLocations) {
                    searchMenuItem.expandActionView();
                    searchView.setQuery(null, false);
                }
                else {
                    clearFragmentStack();
                    autoExpandSearch = true;
                    displayFragment(actionParams.fragmentClass = getLocationsDisplayFragmentClass());
                }
                break;
            case NAV_SWITCH_DISPLAY_MODE:
                displayFragment(actionParams.fragmentClass = getLocationsDisplayFragmentClass());
                break;
            case DROP_MAP_PIN:
                // Clear any search query.
                currentQuery = currentQueryType = null;
                updateActionBar();

                // Ensure we're using the map display.
                navDrawer.switchDisplay(SettingsActivity.LocationsDisplay.MAP_VIEW.ordinal());

                // Open the map fragment with the provided arguments.
                clearFragmentStack();
                displayFragment(actionParams.fragmentClass = getLocationsDisplayFragmentClass(), actionParams.fragmentArgs);
                break;
            case EXECUTE_BROWSE:
                shouldAddFrame = true;
                actionParams.intrinsicItemIdx = NavActionType.NAV_BROWSE.ordinal();
                currentQuery = actionParams.query;
                currentQueryType = actionParams.queryType;
                displayFragment(actionParams.fragmentClass = getLocationsDisplayFragmentClass());
                break;
            case EXECUTE_SEARCH:
                clearFragmentStack();
                shouldAddFrame = true;
                actionParams.intrinsicItemIdx = NavActionType.NAV_SEARCH.ordinal();
                actionParams.fragmentClass = getLocationsDisplayFragmentClass();
                executeSearch(actionParams.query, actionParams.queryType);
                break;
            case BACK:
                boolean displayingSearchResults = fragmentStack.peek().intrinsicItemIdx == NavActionType.NAV_SEARCH.ordinal();
                fragmentStack.pop();
                if(displayingSearchResults) {
                    clearSearch();
                }
                else {
                    FragmentFrame topFrame = fragmentStack.peek();
                    displayFragment(topFrame.fragmentClass);
                    actionParams.intrinsicItemIdx = topFrame.intrinsicItemIdx;
                }
                break;
        }

        if(actionParams.intrinsicItemIdx == NavActionType.NAV_SEARCH.ordinal()) {
            actionParams.intrinsicItemIdx = NavActionType.NAV_LOCATIONS.ordinal();
        }
        navDrawer.selectIntrinsicItem(actionParams.intrinsicItemIdx);
        if(shouldAddFrame) {
            FragmentFrame newFrame = new FragmentFrame();
            newFrame.intrinsicItemIdx = actionParams.intrinsicItemIdx;
            newFrame.fragmentClass = actionParams.fragmentClass;
            fragmentStack.push(newFrame);
        }
    }

    private void displayFragment(Class<? extends Fragment> fragmentClass) {
        displayFragment(fragmentClass, null);
    }

    private void displayFragment(Class<? extends Fragment> fragmentClass, Bundle fragmentArgs) {
        try {
            Fragment fragment = fragmentClass.newInstance();
            boolean replaceFragment = true;

            if(fragmentArgs != null) {
                fragment.setArguments(fragmentArgs);
            }

            if(fragmentClass == ListFragment.class) {
                listMapFragment = null;
                if(listFragment == null) {
                    listFragment = (ListFragment) fragment;
                }
                else {
                    listFragment.refreshDisplay();
                    replaceFragment = false;
                }
            }
            else if(fragmentClass == ListMapFragment.class) {
                listFragment = null;
                listMapFragment = (ListMapFragment) fragment;
            }
            else {
                listFragment = null;
                listMapFragment = null;
            }

            if(replaceFragment) {
                if(snackbar.getVisibility() == View.VISIBLE) {
                    snackbar.hide(true);
                }

                android.support.v4.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.frame, fragment);
                transaction.commit();
            }
        }
        catch(Exception e) {
            CrashlyticsCore.getInstance().logException(e);

            clearFragmentStack();

            // Display Locations screen as a fallback.
            navDrawer.selectIntrinsicItem(0);

            listFragment = new ListFragment();
            android.support.v4.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.frame, listFragment);
            transaction.commit();

            Toast.makeText(this, R.string.nav_action_failed, Toast.LENGTH_LONG).show();
        }
    }

    enum NavActionType {
        NAV_LOCATIONS,
        NAV_BROWSE,
        NAV_SEARCH,
        NAV_SWITCH_DISPLAY_MODE,
        DROP_MAP_PIN,
        EXECUTE_SEARCH,
        EXECUTE_BROWSE,
        BACK
    }

    // This is represents a navigation action to be executed.
    class NavActionParams {
        public int intrinsicItemIdx;
        public NavActionType type;

        // Only applicable if type is NAV_SWITCH_DISPLAY_MODE.
        public Class<? extends Fragment> fragmentClass;

        // Only applicable if type is DROP_MAP_PIN.
        public Bundle fragmentArgs;

        // Only applicable if type is anything but NAV_BROWSE.
        public String query;
        public String queryType;

        @Override
        protected Object clone() {
            NavActionParams clonedAction = new NavActionParams();
            clonedAction.intrinsicItemIdx = intrinsicItemIdx;
            clonedAction.type = type;
            clonedAction.fragmentClass = fragmentClass;
            clonedAction.fragmentArgs = fragmentArgs;
            clonedAction.query = query;
            clonedAction.queryType = queryType;
            return clonedAction;
        }
    }

    class FragmentFrame {
        public int intrinsicItemIdx;
        public Class<? extends Fragment> fragmentClass;

        @Override
        protected Object clone() {
            FragmentFrame clonedFrame = new FragmentFrame();
            clonedFrame.intrinsicItemIdx = intrinsicItemIdx;
            clonedFrame.fragmentClass = fragmentClass;
            return clonedFrame;
        }
    }

    public String getCurrentQuery() {
        return currentQuery;
    }

    public String getCurrentQueryType() {
        return currentQueryType;
    }

    public void openLocationDetail(Long locationId) {
        openLocationDetail(locationId, null, null);
    }

    public void openLocationDetail(Long locationId, final LatLng latLng, final String title) {
        if(locationId == null) {
            LocationModel locationModel = new LocationModel();
            locationModel.setDate(new Date(Calendar.getInstance().getTimeInMillis()));
            if(latLng != null) {
                locationModel.setLocationInfo(new LocationInfo(latLng, null, true));
            }
            if(title != null) {
                locationModel.setTitle(title);
            }
            DatabaseHelper.getInstance(this).saveLocation(locationModel, new DatabaseQueryListener() {
                @Override
                public void onQueryExecuted(DatabaseQueryResult result) {
                    openLocationDetail(result.id, true);
                }
            });
        }
        else {
            openLocationDetail(locationId, false);
        }
    }

    protected void openLocationDetail(Long locationId, boolean justCreatedLocation) {
        Intent intent = new Intent(MainActivity.this, DetailActivity.class);
        if(locationId != null) {
            intent.putExtra(Strings.LOCATION_ID, locationId);
        }
        intent.putExtra(Strings.PARAM_JUST_CREATED_LOCATION, justCreatedLocation);
        startActivityForResult(intent, RequestCodes.DETAIL_ACTIVITY);
    }

    public Snackbar getSnackbar() {
        return snackbar;
    }

    @Override
    protected void onPause() {
        snackbar.hide(false);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // This code is necessary because when the user changes the theme preference on the Settings
        // screen, this activity is just paused, so we need to recreate it.
        if(!preferences.getString(Strings.PREF_THEME, "0").equals(themePrefValue)) {
            setTheme(UIUtils.getCurrentTheme(this));
            
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        CreateDriveServiceAsyncTask createDriveServiceTask = new CreateDriveServiceAsyncTask(this, this);
        if(createDriveServiceTask.hasInvalidAccount()) {
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {
                SyncSettingsFragment.promptAccountPicker(this);
            }
            else {
                ActivityCompat.requestPermissions(
                        this,
                        new String[] { Manifest.permission.GET_ACCOUNTS },
                        RequestCodes.PERMISSION_REQUEST_CONTACTS
                );
            }
        }
        else {
            createDriveServiceTask.execute();
        }

        if(firstAppLaunch || navDrawer.isDrawerOpen()) {
            navDrawerLayout.openDrawer(GravityCompat.START);
            navDrawer.onOpenDrawer();
            firstAppLaunch = false;
        }
    }

    public void setDriveService(Drive driveService) {
        this.driveService = driveService;
        Glide.get(this).register(String.class, InputStream.class, new DriveModelLoader.Factory(driveService, true));

        // This is necessary because when we first launch the app, requests are made for the photos
        // before we are connected and authorized to Google Drive, so we refresh all the locations
        // so that the images can properly be fetched. This should be optimized somehow, though,
        // to only try to re-fetch the images.
        if(listFragment != null) {
            listFragment.refreshLocations();
        }
    }

    public Drive getDriveService() {
        return driveService;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch(requestCode) {
            case RequestCodes.CONNECTION_FAILURE_RESOLUTION:
                if(resultCode == Activity.RESULT_OK) {
                    // Check again for the services.
                    checkForGooglePlayServices();
                }
                break;
            case RequestCodes.SELECT_GOOGLE_ACCOUNT:
            case RequestCodes.REQUEST_AUTHORIZATION:
                if(resultCode == Activity.RESULT_OK) {
                    if(data != null) {
                        String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);

                        Intent intent = new Intent(SyncSettingsFragment.ACCOUNT_SELECTED_BROADCAST);
                        intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, accountName);
                        broadcastManager.sendBroadcastSync(intent);
                    }
                    else if(requestCode == RequestCodes.REQUEST_AUTHORIZATION) {
                        SyncSettingsFragment.promptAccountPicker(this);
                    }
                }
                else if(resultCode == Activity.RESULT_CANCELED) {
                    // Remove sync account and alert the user.
                    SyncSettingsFragment.removeGoogleAccount(preferences, true);

                    new AlertDialog.Builder(this)
                            .setTitle(R.string.account_not_found_title)
                            .setMessage(R.string.account_not_found_message)
                            .setCancelable(false)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                else {
                    CrashlyticsCore.getInstance().logException(new RuntimeException("How did we get here? " + requestCode + ", " + resultCode));
                }
                break;
            case RequestCodes.DETAIL_ACTIVITY:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    // If a tag's color has changed, we need to update both map and list/grid.
                    Intent broadcastIntent = new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST);

                    boolean locationChanged = false;
                    boolean forceChange = false;
                    boolean tagNamesChanged = data.getBooleanExtra(Strings.PARAM_TAG_NAME_CHANGED, false);
                    boolean markerColorsChanged = data.getBooleanExtra(Strings.PARAM_TAG_COLOR_CHANGED, false);

                    switch(data.getIntExtra(Strings.PARAM_EXIT_STATE, DetailActivity.EXIT_STATE_NORMAL)) {
                        case DetailActivity.EXIT_STATE_NORMAL:
                            LocationModelChanges locationChanges = data.getParcelableExtra(Strings.PARAM_LOCATION_CHANGES);
                            broadcastIntent.putExtra(Strings.PARAM_LOCATION_CHANGES, locationChanges);

                            forceChange = data.getBooleanExtra(Strings.PARAM_JUST_CREATED_LOCATION, false);
                            locationChanged = forceChange || locationChanges.hasAnythingChanged();
                            markerColorsChanged |= locationChanges.color;
                            break;
                        case DetailActivity.EXIT_STATE_UNDO_CREATED:
                            // Do nothing, because the user has just deleted a location he created by accident,
                            // so nothing has changed.
                            break;
                        case DetailActivity.EXIT_STATE_DELETED:
                            locationChanged = true;
                            forceChange = true;

                            final Long locationId = data.getLongExtra(Strings.LOCATION_ID, -1L);

                            Snackbar.ShowConfig config = snackbar.new ShowConfig();
                            config.text = getString(R.string.place_deleted);
                            config.listener = snackbar.new Listener() {
                                @Override
                                public void onExpired() {
                                    DatabaseHelper.getInstance(MainActivity.this).deleteLocation(locationId, null);
                                }

                                @Override
                                public void onButtonClicked() {
                                    tracker.send(new HitBuilders.EventBuilder()
                                            .setCategory(Strings.CAT_UI_ACTION)
                                            .setAction(Strings.ACT_CLICK_BUTTON)
                                            .setLabel(Strings.LBL_DELETE_PLACE_UNDO)
                                            .build());

                                    DatabaseHelper.getInstance(MainActivity.this).undeleteLocation(locationId, new DatabaseQueryListener() {
                                        @Override
                                        public void onQueryExecuted(DatabaseQueryResult result) {
                                            broadcastManager.sendBroadcast(new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST));
                                        }
                                    });
                                }
                            };
                            snackbar.show(config);
                            break;
                    }

                    // The map marker colors have changed if any of the tag colors have changed or
                    // the current location's color (as set by the first tag) has changed.
                    broadcastIntent.putExtra(Strings.PARAM_TAG_NAME_CHANGED, tagNamesChanged);
                    broadcastIntent.putExtra(Strings.PARAM_MARKER_COLORS_CHANGED, markerColorsChanged);
                    broadcastIntent.putExtra(Strings.PARAM_FORCE_CHANGE, forceChange);

                    if(locationChanged || markerColorsChanged || tagNamesChanged) {
                        // Broadcast that the locations have been changed to both ListFragment and ListMapFragment.
                        broadcastManager.sendBroadcastSync(broadcastIntent);
                    }
                }
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch(requestCode) {
            case RequestCodes.PERMISSION_REQUEST_CONTACTS:
                SyncSettingsFragment.promptAccountPicker(this);
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        super.onActionModeStarted(mode);
        actionMode = mode;
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        super.onActionModeFinished(mode);
        actionMode = null;
    }

    // Used by ListFragment to finish the action mode from outside the MultiChoiceModeListener.
    public void finishActionMode() {
        if(actionMode != null) {
            actionMode.finish();
        }
    }

    // Changes the action bar to reflect the current search.
    protected void updateActionBar() {
        if(currentQuery != null) {
            setTitle(currentQuery);
        }
        else {
            setTitle(R.string.places_title);
        }

        if(searchMenuItem != null) {
            searchMenuItem.collapseActionView();
        }
    }

    protected void checkForGooglePlayServices() {
        ((Application) this.getApplication()).setGooglePlayServicesAvailable(false);
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if(resultCode == ConnectionResult.SUCCESS) {
            ((Application) this.getApplication()).setGooglePlayServicesAvailable(true);
        }
        else if(GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
            GooglePlayServicesUtil.getErrorDialog(resultCode, this, RequestCodes.CONNECTION_FAILURE_RESOLUTION).show();
        }
        else {
            CrashlyticsCore.getInstance().logException(new RuntimeException("Cannot find the Google Play Services app; resultCode = " + resultCode));
            new AlertDialog.Builder(this)
                    .setTitle(R.string.no_google_play_services_title)
                    .setMessage(R.string.no_google_play_services_message)
                    .setNeutralButton(android.R.string.ok, null)
                    .show();
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if(handleIntentForWidget(intent)) {
            return;
        }
        handleIntentForImport(intent, null);
    }

    protected boolean handleIntentForWidget(final Intent intent) {
        if(intent == null) {
            return false;
        }

        // If the user has accessed an instance of the app from recent apps,
        // then we do not try to import again.
        if((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return false;
        }

        if(intent.getBooleanExtra(Strings.PARAM_CREATED_LOCATION_FROM_WIDGET, false)) {
            tracker.send(new HitBuilders.EventBuilder()
                    .setCategory(Strings.CAT_UI_ACTION)
                    .setAction(Strings.ACT_CLICK_BUTTON)
                    .setLabel(Strings.LBL_ADD_PLACE_WIDGET)
                    .build());

            openLocationDetail(null);
            return true;
        }

        return false;
    }

    protected boolean handleIntentForImport(final Intent intent, Bundle savedInstanceState) {
        // Get intent and action.
        if(intent == null) {
            return false;
        }

        // If the user has accessed an instance of the app from recent apps,
        // then we do not try to import again.
        if((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return false;
        }

        String action = intent.getAction();
        if(action == null) {
            return false;
        }

        // Prevent importing the same data more than once when rotating the screen.
        if(savedInstanceState != null) {
            if(importStarted = savedInstanceState.getBoolean(Strings.PARAM_IMPORT_STARTED, false)) {
                return true;
            }
        }

        if(action.equals(Intent.ACTION_SEND) || action.equals(Intent.ACTION_SEND_MULTIPLE) || action.equals(Intent.ACTION_VIEW)) {
            Bundle extras = intent.getExtras();
            if(extras != null) {
                Object stream = extras.get(Intent.EXTRA_STREAM);
                if(stream != null) {
                    showImportWarningDialogIfNecessary(new ImportWarningDialogListener() {
                        @Override
                        public void onStartImport() {
                            // Create a toast indicating to the user that a notification has been created
                            // to monitor progress.
                            Toast.makeText(MainActivity.this, R.string.import_started, Toast.LENGTH_LONG).show();

                            Intent importIntent = new Intent(MainActivity.this, ImportService.class);
                            importIntent.putExtra(Strings.PARAM_INTENT, intent);
                            startService(importIntent);

                            importStarted = true;
                        }
                    });
                    return true;
                }
            }

            final Uri dataUri = intent.getData();
            if(dataUri != null && "geo".equals(dataUri.getScheme())) {
                showImportDialog(R.string.importing_place);
                new ImportGeoPlaceAsyncTask(MainActivity.this).execute(dataUri);
                importStarted = true;
                return true;
            }

            final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            final String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if(text == null) {
                Crashlytics.getInstance().core.logException(new RuntimeException("No file or text was shared to ScoutLog"));
                Toast.makeText(this, R.string.import_data_not_recognized, Toast.LENGTH_LONG).show();
                finish();
                return true;
            }

            final Matcher googleUrlMatcher = ImportURLPlaceAsyncTask.GOOGLE_URL_PATTERN.matcher(text);
            if(googleUrlMatcher.find()) {
                showImportDialog(R.string.importing_place);
                new ImportURLPlaceAsyncTask(MainActivity.this, googleUrlMatcher.group(), subject).execute();
                importStarted = true;
                return true;
            }

            final Matcher hereUrlMatcher = ImportURLPlaceAsyncTask.HERE_URL_PATTERN.matcher(text);
            if(hereUrlMatcher.find()) {
                showImportDialog(R.string.importing_place);
                new ImportURLPlaceAsyncTask(MainActivity.this, hereUrlMatcher.group(), subject).execute();
                importStarted = true;
                return true;
            }
        }

        return false;
    }

    protected void showImportDialog(int importText) {
        importDialog = ProgressDialog.show(this, null, getString(importText), true);
    }

    public void hideImportDialog() {
        importDialog.dismiss();
    }

    public void showImportWarningDialogIfNecessary(final ImportWarningDialogListener callback) {
        if(UIUtils.hasUserUnlockedApp(this)) {
            callback.onStartImport();
        }
        else {
            int numLocations;
            if(listFragment != null && listFragment.isAdded()) {
                numLocations = listFragment.getLocationsAdapter().getCount();
            }
            else if(listMapFragment != null && listMapFragment.isAdded()) {
                numLocations = listMapFragment.getLocationsCount();
            }
            else {
                // We can apparently get into this execution path if the app is not open
                // before the user shares something to it. THis shouldn't happen often,
                // so we'll just fetch the locations count directly from the DB and hope
                // there's no noticeable performance hit on the UI thread.
                numLocations = DatabaseHelper.getInstance(this).fetchLocationsCount();
            }

            // We set the trial extension here because we're alerting them that
            // you can only create 15 places, so there's no point in ever
            // showing them the extend trial dialog.
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(Strings.PREF_EXTENDED_TRIAL, true);
            editor.commit();

            if(UnlockAppDialog.shouldShowDialog(this, numLocations)) {
                // The user has reached the max number of places.
                new UnlockAppDialog(this).show();
            }
            else {
                // The user can still create places, so just display a warning if this is the first
                // time viewing it.
                if(!preferences.getBoolean(Strings.PREF_DISMISSED_IMPORT_WARNING, false)) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.import_warning_title)
                            .setMessage(R.string.import_warning_message)
                            .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    SharedPreferences.Editor editor = preferences.edit();
                                    editor.putBoolean(Strings.PREF_DISMISSED_IMPORT_WARNING, true);
                                    editor.apply();

                                    callback.onStartImport();
                                }
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                }
                else {
                    callback.onStartImport();
                }
            }
        }
    }

    interface ImportWarningDialogListener {
        void onStartImport();
    }

}
