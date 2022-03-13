package com.sndurkin.locationscout;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import java.util.ArrayList;
import java.util.List;


public class SelectLinkedLocationsActivity extends AppCompatActivity implements AbsListView.OnScrollListener,
                                                                                TextWatcher {

    private static final int LOAD_TOLERANCE_AMOUNT = 20;
    private static final int LOAD_CHUNK_AMOUNT = 100;

    private int currentNumLoaded;
    private int numLoadedBeforeRotate;
    private boolean loadedTotal;
    private boolean fetchingLocations = false;

    private SharedPreferences preferences;
    private Tracker tracker;
    private boolean activityJustCreated;

    private Long locationId;

    private EditText filterText;

    private List<LocationModel> linkableLocations;
    protected boolean linkedLocationsChanged = false;

    private ListView linkableLocationsListView;
    private List<Long> selectedIds = new ArrayList<>();
    private ArrayList<String> selectedTitles = new ArrayList<>();
    private ArrayList<String> selectedAddresses = new ArrayList<>();
    private LinkableLocationsListAdapter listAdapter;
    private Parcelable locationsState;

    private boolean showClosestAddress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));

        super.onCreate(savedInstanceState);

        setContentView(R.layout.select_linked_locations_activity);
        setTitle(R.string.select_linked_places_title);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        tracker = Application.getInstance().getTracker();
        tracker.setScreenName(Strings.SELECT_LINKED_PLACES_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        setupBottomBar();

        filterText = (EditText) findViewById(R.id.filter_text);
        filterText.addTextChangedListener(this);

        linkableLocationsListView = (ListView) findViewById(R.id.linkable_locations_list_view);
        linkableLocationsListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                linkedLocationsChanged = true;

                CheckBox checkBox = (CheckBox) view.findViewById(R.id.linkable_location_checkbox);
                checkBox.setChecked(!checkBox.isChecked());
                if(checkBox.isChecked()) {
                    selectedIds.add(id);

                    LocationModel locationModel = listAdapter.getItem(position);
                    String title = locationModel.getTitleForDisplay(showClosestAddress);
                    selectedTitles.add(title);

                    if(locationModel.hasLocation()) {
                        String address = locationModel.getLocationInfo().getAddressForDisplay(title, showClosestAddress);
                        selectedAddresses.add(address);
                    }
                    else {
                        selectedAddresses.add(getString(R.string.no_location_set));
                    }
                }
                else {
                    int idx = selectedIds.indexOf(id);
                    selectedIds.remove(idx);
                    selectedTitles.remove(idx);
                    selectedAddresses.remove(idx);
                }
            }
        });
        linkableLocationsListView.setOnScrollListener(this);
        linkableLocationsListView.setEmptyView(findViewById(R.id.empty_locations_view));
        linkableLocationsListView.setAdapter(listAdapter = new LinkableLocationsListAdapter());

        if(savedInstanceState != null) {
            numLoadedBeforeRotate = savedInstanceState.getInt("numLoadedBeforeRotate", 0);
            locationsState = savedInstanceState.getParcelable(Strings.PARAM_LOCATIONS_STATE);
            if(locationsState != null) {
                linkableLocationsListView.onRestoreInstanceState(locationsState);
            }
            selectedIds = MiscUtils.convertLongArrayToList(savedInstanceState.getLongArray(Strings.LOCATION_IDS));
            selectedTitles = savedInstanceState.getStringArrayList(Strings.LOCATION_TITLES);
            selectedAddresses = savedInstanceState.getStringArrayList(Strings.LOCATION_ADDRESSES);
            linkedLocationsChanged = savedInstanceState.getBoolean(Strings.PARAM_DATA_CHANGED);
        }

        if(getIntent() != null) {
            Bundle extras = getIntent().getExtras();
            if(extras != null) {
                locationId = extras.getLong(Strings.LOCATION_ID);
            }
        }

        activityJustCreated = true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        showClosestAddress = preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true);

        if(activityJustCreated) {
            // There are 2 cases where we want to fetch the locations on fragment resume:
            //  1) the fragment was just created
            //  2) a preference was changed in the settings while this fragment was paused
            fetchLocations();
            activityJustCreated = false;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("numLoadedBeforeRotate", currentNumLoaded);
        outState.putParcelable(Strings.PARAM_LOCATIONS_STATE, linkableLocationsListView.onSaveInstanceState());
        outState.putLongArray(Strings.LOCATION_IDS, MiscUtils.convertLongListToArray(selectedIds));
        outState.putStringArrayList(Strings.LOCATION_TITLES, selectedTitles);
        outState.putStringArrayList(Strings.LOCATION_ADDRESSES, selectedAddresses);
        outState.putBoolean(Strings.PARAM_DATA_CHANGED, linkedLocationsChanged);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                cancelWithWarning();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        cancelWithWarning();
    }

    protected void cancelWithWarning() {
        if(linkedLocationsChanged && preferences.getBoolean(Strings.PREF_NOTIFY_UNSAVED_CHANGES, true)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.notify_unsaved_tags_dialog_title)
                    .setMessage(R.string.notify_unsaved_tags_dialog_message)
                    .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_CANCEL_LINKED_PLACES_YES)
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
                                    .setLabel(Strings.LBL_CANCEL_LINKED_PLACES_NO)
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
                                    .setLabel(Strings.LBL_CANCEL_LINKED_PLACES_NEVER_AGAIN)
                                    .build());

                            SharedPreferences.Editor editor = preferences.edit();
                            editor.putBoolean(Strings.PREF_NOTIFY_UNSAVED_CHANGES, false);
                            editor.apply();

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
        setResult(RESULT_CANCELED, null);
        finish();
    }

    protected void saveAndFinish() {
        Intent data = new Intent();
        data.putExtra(Strings.LOCATION_IDS, MiscUtils.convertLongListToArray(selectedIds));
        data.putExtra(Strings.LOCATION_TITLES, selectedTitles);
        data.putExtra(Strings.LOCATION_ADDRESSES, selectedAddresses);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    @Override
    public void afterTextChanged(Editable s) {
        fetchLocations();
    }

    protected void fetchLocations() {
        linkableLocations = new ArrayList<>();
        currentNumLoaded = numLoadedBeforeRotate = 0;
        loadedTotal = false;
        fetchMoreLocations();
    }

    protected void fetchMoreLocations() {
        if(fetchingLocations) {
            return;
        }
        fetchingLocations = true;

        int numToLoad = (currentNumLoaded == 0 && numLoadedBeforeRotate > 0) ? numLoadedBeforeRotate
                                                                             : LOAD_CHUNK_AMOUNT;

        DatabaseHelper.getInstance(this).fetchLinkableLocations(locationId, filterText.getText().toString(), currentNumLoaded, numToLoad, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                Cursor cursor = result.cursor;
                if (cursor.getCount() == 0) {
                    loadedTotal = true;
                } else {
                    currentNumLoaded += cursor.getCount();
                    if (cursor.getCount() < LOAD_CHUNK_AMOUNT) {
                        loadedTotal = true;
                    }

                    while (!cursor.isAfterLast()) {
                        LocationModel location = ModelUtils.createLocationFromCursorForDisplay(cursor);
                        linkableLocations.add(location);
                        boolean isLinked = MiscUtils.getBoolean(cursor, DatabaseHelper.TRANSIENT_IS_LINKED_COLUMN, false);
                        if (isLinked && !selectedIds.contains(location.getLocalId())) {
                            selectedIds.add(location.getLocalId());
                            String title = location.getTitleForDisplay(showClosestAddress);
                            selectedTitles.add(title);

                            if(location.hasLocation()) {
                                selectedAddresses.add(location.getLocationInfo().getAddressForDisplay(title, showClosestAddress));
                            }
                            else {
                                selectedAddresses.add(getString(R.string.no_location_set));
                            }
                        }

                        cursor.moveToNext();
                    }
                    cursor.close();
                }

                refreshLocations();
                fetchingLocations = false;
            }
        });
    }

    public void refreshLocations() {
        if(linkableLocations.isEmpty()) {
            // Set the empty list view text depending on whether a query is set or not.
            TextView emptyListText = (TextView) findViewById(R.id.empty_list_text);
            emptyListText.setText(R.string.empty_places_text);
        }

        // Display the locations in the list.
        listAdapter.notifyDataSetChanged();
        if(locationsState != null) {
            linkableLocationsListView.onRestoreInstanceState(locationsState);
            locationsState = null;
        }
        linkableLocationsListView.invalidateViews();
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

    protected void setupBottomBar() {
        RelativeLayout cancelButton = (RelativeLayout) findViewById(R.id.action_cancel);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_CANCEL_LINKED_PLACES)
                        .build());
                cancelAndFinish();
            }
        });

        RelativeLayout saveButton = (RelativeLayout) findViewById(R.id.action_save);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_SAVE_LINKED_PLACES)
                        .build());
                saveAndFinish();
            }
        });
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) { }

    private class LinkableLocationsListAdapter extends ArrayAdapter<LocationModel> {

        private LayoutInflater inflater;

        public LinkableLocationsListAdapter() {
            super(SelectLinkedLocationsActivity.this, R.layout.linkable_location_item_view);
            inflater = getLayoutInflater();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View locationItemView;
            LinkableLocationListHolder holder;
            if(convertView != null) {
                locationItemView = convertView;
                holder = (LinkableLocationListHolder) locationItemView.getTag();
            }
            else {
                locationItemView = inflater.inflate(R.layout.linkable_location_item_view, parent, false);
                holder = new LinkableLocationListHolder(locationItemView);
                locationItemView.setTag(holder);
            }

            final LocationModel locationModel = linkableLocations.get(position);

            String locationTitle = locationModel.getTitleForDisplay(showClosestAddress);
            holder.titleText.setText(locationTitle);
            if(locationModel.hasLocation()) {
                holder.addressText.setText(locationModel.getLocationInfo().getAddressForDisplay(locationTitle, showClosestAddress));
            }
            else {
                holder.addressText.setText(R.string.no_location_set);
            }
            holder.checkBox.setChecked(selectedIds.contains(locationModel.getLocalId()));

            return locationItemView;
        }

        @Override
        public int getCount() {
            return linkableLocations != null ? linkableLocations.size() : 0;
        }

        @Override
        public long getItemId(int position) {
            return linkableLocations.get(position).getLocalId();
        }

        @Override
        public LocationModel getItem(int position) { return linkableLocations.get(position); }
    }

    private class LinkableLocationListHolder {

        public TextView titleText;
        public TextView addressText;
        public CheckBox checkBox;

        public LinkableLocationListHolder(View locationItemView) {
            titleText = (TextView) locationItemView.findViewById(R.id.linkable_location_title);
            addressText = (TextView) locationItemView.findViewById(R.id.linkable_location_address);
            checkBox = (CheckBox) locationItemView.findViewById(R.id.linkable_location_checkbox);
        }

    }

}
