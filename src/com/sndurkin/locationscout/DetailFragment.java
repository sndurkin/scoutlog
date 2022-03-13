package com.sndurkin.locationscout;

import android.Manifest;
import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.view.ActionMode;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.LocationModelChanges;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;
import com.sndurkin.locationscout.util.Versions;
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;


public class DetailFragment extends Fragment implements OnMapReadyCallback {

    private GlobalBroadcastManager broadcastManager;
    private DetailFragmentBroadcastReceiver broadcastReceiver;

    private Tracker tracker;

    private SharedPreferences preferences;
    private boolean showClosestAddress;

    private Long locationId;
    private boolean justCreatedLocation;
    private boolean showAddressHelpText;

    private boolean detailsLoaded;
    private LatLng currentLatLng;

    private ViewGroup displayTitleCt;
    private TextView titleText;
    private EditText titleEditText;

    private ViewGroup saveTitleCt;
    private Button editTitleButton;

    private TextView tagsText;
    private CardView notesCard;
    private TextView notesText;

    private List<Long> tagIds;

    private TextView dateText;
    private Time dateTime;

    private GoogleMap map;
    private ImageButton navigateButton;

    private TextView addressTitleText;
    private TextView addressText;
    private ImageButton addressMenuButton;

    private LocationInfo locationInfo;
    private Marker currentMarker;

    private CardView linkedLocationsCard;
    private TextView emptyLinkedLocationsText;
    private LinearLayout linkedLocationsList;

    private List<Long> linkedLocationIds = new ArrayList<>();
    private ArrayList<String> linkedLocationTitles;
    private ArrayList<String> linkedLocationAddresses;

    private LocationModelChanges locationChanges;
    private boolean tagNamesChanged = false;
    private boolean tagColorsChanged = false;
    private int firstTagColor = 0;
    private String firstTagIconPath = null;

    private View view;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setView(inflater, R.layout.detail_fragment, container);

        broadcastManager = GlobalBroadcastManager.getInstance(getActivity());
        broadcastReceiver = new DetailFragmentBroadcastReceiver();

        tracker = Application.getInstance().getTracker();
        preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());

        setupTitle();
        setupDate();
        setupTags();
        setupAddress();
        setupLinkedLocations();
        setupNotes();

        if(savedInstanceState == null) {
            Bundle extras = getActivity().getIntent().getExtras();
            if (extras != null) {
                if(extras.containsKey(Strings.LOCATION_ID)) {
                    locationId = extras.getLong(Strings.LOCATION_ID);
                    loadDetails(locationId);
                }
                justCreatedLocation = extras.getBoolean(Strings.PARAM_JUST_CREATED_LOCATION);
            }

            locationChanges = new LocationModelChanges();
        }
        else {
            onInstanceStateRestored(savedInstanceState);
        }

        // This logic must come after onInstanceStateRestored() is called, because showAddressHelpText
        // is set on device rotate inside that function.
        if(showAddressHelpText) {
            showAddressHelpTextIfApplicable();
        }

        updateLinkedLocations();
        updateMap();    // TODO: is this necessary or is it not even executing because map is not yet ready?

        Versions.checkAndUpdate(preferences, Strings.DETAIL_SCREEN_VERSION, Versions.Defaults.DETAIL_SCREEN_VERSION, null);

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
            Crashlytics.logException(e);
        }
    }

    private void setupTitle() {
        ViewGroup titleCt = (ViewGroup) view.findViewById(R.id.detail_title_ct);
        displayTitleCt = (ViewGroup) view.findViewById(R.id.detail_display_title_ct);
        saveTitleCt = (ViewGroup) view.findViewById(R.id.detail_title_save_ct);
        titleText = (TextView) view.findViewById(R.id.detail_title_text);
        titleEditText = (EditText) view.findViewById(R.id.detail_title_edit_text);

        editTitleButton = (Button) view.findViewById(R.id.detail_edit_title);
        editTitleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                displayTitleCt.setVisibility(View.GONE);
                titleEditText.setText(titleText.getText());
                titleEditText.setVisibility(View.VISIBLE);
                saveTitleCt.setVisibility(View.VISIBLE);
            }
        });

        titleCt.getLayoutTransition().setDuration(100);
        titleCt.getLayoutTransition().addTransitionListener(new LayoutTransition.TransitionListener() {
            @Override public void startTransition(LayoutTransition transition, ViewGroup container, View view, int transitionType) { }

            @Override
            public void endTransition(LayoutTransition transition, ViewGroup container, View view, int transitionType) {
                if (view == titleEditText) {
                    titleEditText.requestFocus();
                    titleEditText.setSelection(0, titleEditText.getText().length());
                    UIUtils.showKeyboard(getActivity(), titleEditText);
                }
            }
        });

        Button saveTitleButton = (Button) view.findViewById(R.id.detail_save_title);
        saveTitleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String title = titleEditText.getText().toString();

                LocationModel locationModel = new LocationModel(locationId);
                locationModel.setTitle(title);
                DatabaseHelper.getInstance(getActivity()).saveLocation(locationModel, new DatabaseQueryListener() {
                    @Override
                    public void onQueryExecuted(DatabaseQueryResult result) {
                        setTitle(title);
                        locationChanges.title = true;
                        closeTitleEdit();
                    }
                });
            }
        });

        Button cancelTitleButton = (Button) view.findViewById(R.id.detail_cancel_title);
        cancelTitleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeTitleEdit();
            }
        });
    }

    private void closeTitleEdit() {
        UIUtils.hideKeyboard(getActivity());
        saveTitleCt.setVisibility(View.GONE);
        titleEditText.setVisibility(View.GONE);
        displayTitleCt.setVisibility(View.VISIBLE);
    }

    private void setupDate() {
        dateTime = new Time();
        dateTime.set(System.currentTimeMillis());

        dateText = (TextView) view.findViewById(R.id.detail_date);
        dateText.setText(DateUtils.formatDateTime(getActivity(), dateTime.toMillis(true), DateUtils.FORMAT_SHOW_YEAR));

        CardView dateCard = (CardView) view.findViewById(R.id.detail_date_card);
        if(preferences.getBoolean(Strings.PREF_ENABLE_DATES, true)) {
            dateCard.setVisibility(View.VISIBLE);
            dateCard.setOnClickListener(new DateClickListener());
        }
    }

    private void setupTags() {
        tagsText = (TextView) view.findViewById(R.id.detail_tags);
        CardView tagsCard = (CardView) view.findViewById(R.id.detail_tags_card);
        tagsCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), SelectTagsActivity.class);
                intent.putExtra(Strings.PARAM_SELECTED_TAG_IDS, new ArrayList<>(getTagIds()));
                startActivityForResult(intent, RequestCodes.SELECT_TAGS_ACTIVITY);
            }
        });
    }

    private void setupAddress() {
        addressText = (TextView) view.findViewById(R.id.detail_address_text);
        addressTitleText = (TextView) view.findViewById(R.id.detail_address_title);
        addressTitleText.setText(R.string.address_title);
        addressMenuButton = (ImageButton) view.findViewById(R.id.detail_address_menu_button);
        addressMenuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PopupMenu popupMenu = new PopupMenu(getActivity(), addressMenuButton);
                popupMenu.getMenuInflater().inflate(R.menu.address_menu, popupMenu.getMenu());
                popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        switch (item.getItemId()) {
                            case R.id.menu_copy_coordinates: {
                                if (locationInfo == null) {
                                    Toast.makeText(getActivity(), R.string.no_location_set_toast, Toast.LENGTH_SHORT).show();
                                    return true;
                                }

                                tracker.send(new HitBuilders.EventBuilder()
                                        .setCategory(Strings.CAT_UI_ACTION)
                                        .setAction(Strings.ACT_CLICK_MENU_ITEM)
                                        .setLabel(Strings.LBL_COPY_COORDINATES)
                                        .build());

                                ClipboardManager clipboardManager = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("ScoutLog coordinates", locationInfo.location.latitude + ", " + locationInfo.location.longitude));
                                Toast.makeText(getActivity(), R.string.copied_coordinates, Toast.LENGTH_SHORT).show();
                                break;
                            }
                            case R.id.menu_copy_address: {
                                if (locationInfo == null) {
                                    Toast.makeText(getActivity(), R.string.no_location_set_toast, Toast.LENGTH_SHORT).show();
                                    return true;
                                }
                                if (!locationInfo.hasAddress()) {
                                    Toast.makeText(getActivity(), R.string.address_not_found, Toast.LENGTH_SHORT).show();
                                    return true;
                                }

                                tracker.send(new HitBuilders.EventBuilder()
                                        .setCategory(Strings.CAT_UI_ACTION)
                                        .setAction(Strings.ACT_CLICK_MENU_ITEM)
                                        .setLabel(Strings.LBL_COPY_ADDRESS)
                                        .build());

                                ClipboardManager clipboardManager = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("ScoutLog address", locationInfo.addressStr));
                                Toast.makeText(getActivity(), R.string.copied_address, Toast.LENGTH_SHORT).show();
                                break;
                            }
                        }

                        return true;
                    }
                });
                popupMenu.show();
            }
        });

        CardView addressCard = (CardView) view.findViewById(R.id.detail_address_card);
        addressCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetailMap();
            }
        });

        View mapPreviewView = view.findViewById(R.id.map_preview);
        mapPreviewView.setClickable(false);

        navigateButton = (ImageButton) view.findViewById(R.id.map_navigate);

        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map_preview);
        mapFragment.getMapAsync(this);
    }

    private void showAddressHelpTextIfApplicable() {
        if(preferences.getBoolean(Strings.PREF_SHOW_ADDRESS_HELP, true) && justCreatedLocation) {
            showAddressHelpText = true;

            final View addressHelpMessage = view.findViewById(R.id.address_help_message);
            addressHelpMessage.setVisibility(View.VISIBLE);

            final Button addressHelpButton = (Button) view.findViewById(R.id.address_help_btn);
            addressHelpButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAddressHelpText = false;

                    addressHelpMessage.setVisibility(View.GONE);

                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putBoolean(Strings.PREF_SHOW_ADDRESS_HELP, false);
                    editor.apply();
                }
            });
        }
    }

    private void setupLinkedLocations() {
        linkedLocationsCard = (CardView) view.findViewById(R.id.detail_linked_locations_card);
        if(preferences.getBoolean(Strings.PREF_ENABLE_LINKED_LOCATIONS, false)) {
            emptyLinkedLocationsText = (TextView) view.findViewById(R.id.detail_linked_location_empty_text);
            linkedLocationsList = (LinearLayout) view.findViewById(R.id.detail_linked_locations_list);

            linkedLocationsCard.setVisibility(View.VISIBLE);
            linkedLocationsCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSelectLinkedLocationsScreen();
                }
            });
        }
    }

    private void openSelectLinkedLocationsScreen() {
        Intent intent = new Intent(getActivity(), SelectLinkedLocationsActivity.class);
        intent.putExtra(Strings.LOCATION_ID, locationId);
        startActivityForResult(intent, RequestCodes.SELECT_LINKED_LOCATIONS_ACTIVITY);
    }

    private void setupNotes() {
        notesText = (TextView) view.findViewById(R.id.detail_notes);
        notesCard = (CardView) view.findViewById(R.id.detail_notes_card);

        Button editButton = (Button) notesCard.findViewById(R.id.card_edit_btn);
        editButton.setText(R.string.edit_note);
        editButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditNotesScreen();
            }
        });
    }

    private void openEditNotesScreen() {
        Intent intent = new Intent(getActivity(), EditNoteActivity.class);
        intent.putExtra(Strings.PARAM_NOTE, notesText.getText().toString());
        startActivityForResult(intent, RequestCodes.EDIT_NOTE_FOR_LOCATION_ACTIVITY);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;
        map.getUiSettings().setAllGesturesEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setMapToolbarEnabled(false);

        navigateButton.setVisibility(View.VISIBLE);
        UIUtils.addHintFunctionalityToView(getActivity(), navigateButton);
        navigateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (locationInfo == null) {
                    return;
                }

                tracker.send(new HitBuilders.EventBuilder()

                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_NAVIGATE)
                        .build());

                UIUtils.openNavigationApp(getActivity(), locationInfo);
            }
        });

        updateMap();
    }

    @Override
    public void onResume() {
        super.onResume();

        showClosestAddress = preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true);

        if(locationInfo == null) {
            IntentFilter intentFilter = new IntentFilter(Application.LOCATION_UPDATE_BROADCAST);
            broadcastManager.registerReceiver(DetailFragment.class.getCanonicalName(), broadcastReceiver, intentFilter, true);

            fetchCurrentLocation();
        }
    }

    @Override
    public void onPause() {
        broadcastManager.unregisterReceiver(broadcastReceiver);
        super.onPause();
        UIUtils.hideKeyboard(getActivity());
    }

    protected void fetchCurrentLocation() {
        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Update the current location if it's more than a minute out-of-date.
            ((Application) getActivity().getApplication()).getCurrentLocation(60000L);
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            switch(requestCode) {
                case RequestCodes.PERMISSION_REQUEST_LOCATION:
                    fetchCurrentLocation();
                    break;
            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putLong(Strings.LOCATION_ID, locationId);
        outState.putString("title", getTitle());
        outState.putString("notes", getNotes());
        outState.putLong("date", getDate().getTime());
        outState.putString("tagsDisplayStr", tagsText.getText().toString());
        outState.putLongArray("tagIds", MiscUtils.convertLongListToArray(getTagIds()));
        outState.putLongArray("linkedLocationIds", MiscUtils.convertLongListToArray(linkedLocationIds));
        outState.putStringArrayList("linkedLocationTitles", linkedLocationTitles);
        outState.putStringArrayList("linkedLocationAddresses", linkedLocationAddresses);
        outState.putParcelable("locationInfo", locationInfo);

        outState.putParcelable(Strings.PARAM_LOCATION_CHANGES, locationChanges);
        outState.putBoolean(Strings.PARAM_TAG_NAME_CHANGED, tagNamesChanged);
        outState.putBoolean(Strings.PARAM_TAG_COLOR_CHANGED, tagColorsChanged);
        outState.putInt(Strings.PARAM_COLOR, firstTagColor);
        outState.putString(Strings.PARAM_ICON_PATH, firstTagIconPath);

        outState.putBoolean(Strings.PARAM_JUST_CREATED_LOCATION, justCreatedLocation);
        outState.putBoolean("showAddressHelpText", showAddressHelpText);
    }

    public void onInstanceStateRestored(Bundle savedInstanceState) {
        locationId = savedInstanceState.getLong(Strings.LOCATION_ID);
        setTitle(savedInstanceState.getString("title"));
        setNotes(savedInstanceState.getString("notes"));
        setDate(savedInstanceState.getLong("date"));
        tagsText.setText(savedInstanceState.getString("tagsDisplayStr"));
        tagIds = MiscUtils.convertLongArrayToList(savedInstanceState.getLongArray("tagIds"));
        linkedLocationIds = MiscUtils.convertLongArrayToList(savedInstanceState.getLongArray("linkedLocationIds"));
        linkedLocationTitles = savedInstanceState.getStringArrayList("linkedLocationTitles");
        linkedLocationAddresses = savedInstanceState.getStringArrayList("linkedLocationAddresses");
        locationInfo = savedInstanceState.getParcelable("locationInfo");

        locationChanges = savedInstanceState.getParcelable(Strings.PARAM_LOCATION_CHANGES);
        tagNamesChanged = savedInstanceState.getBoolean(Strings.PARAM_TAG_NAME_CHANGED);
        tagColorsChanged = savedInstanceState.getBoolean(Strings.PARAM_TAG_COLOR_CHANGED);
        firstTagColor = savedInstanceState.getInt(Strings.PARAM_COLOR);
        firstTagIconPath = savedInstanceState.getString(Strings.PARAM_ICON_PATH);

        justCreatedLocation = savedInstanceState.getBoolean(Strings.PARAM_JUST_CREATED_LOCATION);
        showAddressHelpText = savedInstanceState.getBoolean("showAddressHelpText");
    }

    // These functions are used to fetch the original and current data for the model.
    public String getTitle() {
        return titleText.getText().toString();
    }
    public void setTitle(String title) {
        getActivity().setTitle(title);
        titleText.setText(title);
        editTitleButton.setText(title.isEmpty() ? R.string.set_title : R.string.edit_title);
    }
    public String getNotes() {
        return notesText.getText().toString();
    }

    public void setNotes(String notes) {
        notesText.setText(notes);
        if(!notes.isEmpty()) {
            notesCard.findViewById(R.id.detail_notes_toolbar).setVisibility(View.VISIBLE);

            // This logic is used to hide the text selection mode/popup when the user
            // touches the toolbar.
            notesText.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
                @Override
                public boolean onCreateActionMode(final ActionMode mode, Menu menu) {
                    notesCard.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mode.finish();
                        }
                    });
                    return true;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    return false;
                }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    return false;
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    notesCard.setOnClickListener(null);
                }
            });
        }
        else {
            notesCard.findViewById(R.id.detail_notes_toolbar).setVisibility(View.GONE);
            notesCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEditNotesScreen();
                }
            });
            notesText.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEditNotesScreen();
                }
            });
        }
    }

    public Date getDate() {
        return new Date(dateTime.normalize(true));
    }
    public void setDate(long time) {
        dateTime.set(time);
        dateText.setText(DateUtils.formatDateTime(
                getActivity(),
                dateTime.normalize(true),
                DateUtils.FORMAT_SHOW_YEAR));
    }
    public List<Long> getTagIds() {
        if(tagIds == null) {
            tagIds = new ArrayList<Long>();
        }
        return tagIds;
    }
    public LocationInfo getLocationInfo() {
        return locationInfo;
    }
    public void setLocation(LatLng latLng) {
        locationInfo = new LocationInfo(latLng, null, true);
        updateMap();
    }

    public LocationModelChanges getLocationChanges() { return locationChanges; }
    public boolean haveTagNamesChanged() { return tagNamesChanged; }
    public boolean haveTagColorsChanged() { return tagColorsChanged; }

    protected void loadDetails(long locationId) {
        DatabaseHelper.getInstance(getActivity()).fetchLocation(locationId, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                LocationModel locationModel = ModelUtils.createLocationFromCursorForDisplay(result.cursor);
                result.cursor.close();

                if (!isAdded()) {
                    return;
                }

                setTitle(locationModel.getTitle());
                setNotes(locationModel.getNotes());
                setDate(locationModel.getDate().getTime());

                if (locationModel.getColor() != 0) {
                    firstTagColor = locationModel.getColor();
                }
                if (locationModel.getIconPath() != null) {
                    firstTagIconPath = locationModel.getIconPath();
                }
                if (locationModel.hasLocation()) {
                    locationInfo = locationModel.getLocationInfo();
                    updateMap();
                }
                else if (currentLatLng != null) {
                    // See comments for FLOW L.
                    locationInfo = new LocationInfo(currentLatLng, null, true);
                    saveLocationInfo(new DatabaseQueryListener() {
                        @Override
                        public void onQueryExecuted(DatabaseQueryResult result) {
                            updateMap();
                            showAddressHelpTextIfApplicable();
                        }
                    });
                }

                detailsLoaded = true;
            }
        });

        DatabaseHelper.getInstance(getActivity()).fetchTagsForLocation(locationId, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                Cursor cursor = result.cursor;
                tagIds = new ArrayList<>();
                List<String> tagNames = new ArrayList<String>();
                while (!cursor.isAfterLast()) {
                    tagIds.add(cursor.getLong(cursor.getColumnIndex(DatabaseHelper.ID_COLUMN)));
                    tagNames.add(cursor.getString(cursor.getColumnIndex(DatabaseHelper.TAG_NAME_COLUMN)));
                    cursor.moveToNext();
                }

                cursor.close();
                updateTagDisplay(tagNames);
            }
        });

        if(preferences.getBoolean(Strings.PREF_ENABLE_LINKED_LOCATIONS, false)) {
            DatabaseHelper.getInstance(getActivity()).fetchLinkedLocations(locationId, new DatabaseQueryListener() {
                @Override
                public void onQueryExecuted(DatabaseQueryResult result) {
                    Cursor cursor = result.cursor;
                    linkedLocationIds = new ArrayList<>(cursor.getCount());
                    linkedLocationTitles = new ArrayList<>(cursor.getCount());
                    linkedLocationAddresses = new ArrayList<>(cursor.getCount());
                    while (!cursor.isAfterLast()) {
                        LocationModel locationModel = ModelUtils.createLocationFromCursorForDisplay(cursor);
                        linkedLocationIds.add(locationModel.getLocalId());

                        String title = locationModel.getTitleForDisplay(showClosestAddress);
                        linkedLocationTitles.add(title);

                        if(locationModel.hasLocation()) {
                            String address = locationModel.getLocationInfo().getAddressForDisplay(title, showClosestAddress);
                            linkedLocationAddresses.add(address);
                        }
                        else {
                            linkedLocationAddresses.add(getString(R.string.no_location_set));
                        }

                        cursor.moveToNext();
                    }

                    cursor.close();
                    updateLinkedLocations();
                }
            });
        }
    }

    protected void updateLinkedLocations() {
        if(!preferences.getBoolean(Strings.PREF_ENABLE_LINKED_LOCATIONS, false)) {
            return;
        }

        if(linkedLocationIds == null || linkedLocationIds.isEmpty()) {
            linkedLocationsList.setVisibility(View.GONE);
            emptyLinkedLocationsText.setVisibility(View.VISIBLE);
            linkedLocationsCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSelectLinkedLocationsScreen();
                }
            });

            return;
        }

        emptyLinkedLocationsText.setVisibility(View.GONE);
        linkedLocationsList.setVisibility(View.VISIBLE);
        linkedLocationsCard.setOnClickListener(null);
        linkedLocationsList.removeAllViews();

        LayoutInflater inflater = getActivity().getLayoutInflater();
        for(int i = 0; i < linkedLocationIds.size(); ++i) {
            View view = inflater.inflate(R.layout.linked_location_item_view, linkedLocationsList, false);
            ((TextView) view.findViewById(R.id.linked_location_title)).setText(linkedLocationTitles.get(i));
            ((TextView) view.findViewById(R.id.linked_location_address)).setText(linkedLocationAddresses.get(i));

            final Long newLocationId = linkedLocationIds.get(i);
            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_LIST_ITEM)
                            .setLabel(Strings.LBL_OPEN_LINKED_PLACE)
                            .build());

                    Intent intent = new Intent(getActivity(), DetailActivity.class);
                    intent.putExtra(Strings.LOCATION_ID, newLocationId);
                    getActivity().finish();
                    startActivity(intent);
                }
            });
            linkedLocationsList.addView(view);

            // Add a separator after every linked locations.
            View separatorView = inflater.inflate(R.layout.card_divider, linkedLocationsList, false);
            linkedLocationsList.addView(separatorView);
        }

        // Add a bottom toolbar with an edit button.
        View toolbar = inflater.inflate(R.layout.card_edit_toolbar, linkedLocationsList, false);
        Button editButton = (Button) toolbar.findViewById(R.id.card_edit_btn);
        editButton.setText(R.string.linked_places_edit);
        editButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSelectLinkedLocationsScreen();
            }
        });
        linkedLocationsList.addView(toolbar);
    }

    protected void openDetailMap() {
        Intent intent = new Intent(getActivity(), DetailMapActivity.class);
        if (locationInfo != null) {
            intent.putExtra("locationInfo", locationInfo);
        }
        intent.putExtra(Strings.PARAM_COLOR, firstTagColor);
        intent.putExtra(Strings.PARAM_ICON_PATH, firstTagIconPath);
        startActivityForResult(intent, RequestCodes.DETAIL_MAP_ACTIVITY);
    }

    protected void updateMap() {
        if(map == null || !isAdded()) {
            return;
        }

        if(locationInfo == null) {
            addressText.setText(R.string.no_location_set);
            return;
        }

        CameraPosition.Builder builder = new CameraPosition.Builder();
        builder.zoom(10);
        builder.target(locationInfo.location);
        map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()));
        if(currentMarker != null) {
            currentMarker.remove();
        }
        MarkerOptions markerOptions = new MarkerOptions();
        markerOptions.position(locationInfo.location);
        markerOptions.icon(UIUtils.getMapMarkerIcon(getActivity(), firstTagColor, firstTagIconPath, null));
        currentMarker = map.addMarker(markerOptions);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        final boolean showClosestAddress = preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true);

        addressTitleText.setText(locationInfo.getAddressHeader(showClosestAddress));
        addressText.setText(locationInfo.getAddressForDisplay(null, showClosestAddress));

        // Find and save the closest address.
        if(!locationInfo.hasAddress()) {
            MiscUtils.getClosestAddressFromLocation(getActivity(), locationInfo.location.latitude, locationInfo.location.longitude, new MiscUtils.ClosestAddressListener() {
                @Override
                public void onClosestAddressFound(String addressStr) {
                    // I haven't figured out why, but locationInfo can be null inside this callback
                    // so we add a check for it.
                    if (isAdded() && locationInfo != null) {
                        if (addressStr != null) {
                            locationInfo.addressStr = addressStr;
                            locationChanges.address = true;

                            LocationModel locationModel = new LocationModel(locationId);
                            locationModel.setLocationInfo(locationInfo);
                            DatabaseHelper.getInstance(getActivity()).saveLocation(locationModel, null);

                            addressText.setText(locationInfo.getAddressForDisplay(null, showClosestAddress));
                        }
                    }
                }
            });
        }
    }

    public void updateTagDisplay(List<String> tags) {
        String tagsDisplayStr = "";
        for(int i = 0; i < tags.size(); ++i) {
            if(i > 0) {
                tagsDisplayStr += ", ";
            }
            tagsDisplayStr += tags.get(i);
        }
        tagsText.setText(tagsDisplayStr);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch(requestCode) {
            case RequestCodes.DETAIL_MAP_ACTIVITY:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    locationInfo = data.getParcelableExtra("locationInfo");
                    if(locationInfo != null) {
                        LocationModel locationModel = new LocationModel(locationId);
                        locationModel.setLocationInfo(locationInfo);

                        DatabaseHelper.getInstance(getActivity()).saveLocation(locationModel, new DatabaseQueryListener() {
                            @Override
                            public void onQueryExecuted(DatabaseQueryResult result) {
                                updateMap();
                            }
                        });
                    }
                }
                break;
            case RequestCodes.SELECT_TAGS_ACTIVITY:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    int color = data.getIntExtra(Strings.PARAM_COLOR, 0);
                    String iconPath = data.getStringExtra(Strings.PARAM_ICON_PATH);
                    if(color != firstTagColor || !MiscUtils.equals(iconPath, firstTagIconPath)) {
                        firstTagColor = color;
                        firstTagIconPath = iconPath;
                        locationChanges.color = true;
                        updateMap();
                    }

                    tagNamesChanged |= data.getBooleanExtra(Strings.PARAM_TAG_NAME_CHANGED, false);
                    tagColorsChanged |= data.getBooleanExtra(Strings.PARAM_TAG_COLOR_CHANGED, false);

                    tagIds = (ArrayList<Long>) data.getSerializableExtra(Strings.PARAM_SELECTED_TAG_IDS);
                    final ArrayList<String> tagNames = (ArrayList<String>) data.getSerializableExtra(Strings.PARAM_TAG_NAMES);

                    LocationModel locationModel = new LocationModel(locationId);
                    locationModel.setTagIds(tagIds);
                    DatabaseHelper.getInstance(getActivity()).saveLocation(locationModel, new DatabaseQueryListener() {
                        @Override
                        public void onQueryExecuted(DatabaseQueryResult result) {
                            updateTagDisplay(tagNames);
                        }
                    });
                }
                else if(resultCode == Activity.RESULT_CANCELED && data != null) {
                    int color = data.getIntExtra(Strings.PARAM_COLOR, 0);
                    String iconPath = data.getStringExtra(Strings.PARAM_ICON_PATH);
                    if(color != firstTagColor || !MiscUtils.equals(iconPath, firstTagIconPath)) {
                        firstTagColor = color;
                        firstTagIconPath = iconPath;
                        locationChanges.color = true;
                        updateMap();
                    }

                    tagNamesChanged |= data.getBooleanExtra(Strings.PARAM_TAG_NAME_CHANGED, false);
                    tagColorsChanged |= data.getBooleanExtra(Strings.PARAM_TAG_COLOR_CHANGED, false);

                    if(tagNamesChanged) {
                        ArrayList<String> tagNames = (ArrayList<String>) data.getSerializableExtra(Strings.PARAM_TAG_NAMES);
                        updateTagDisplay(tagNames);
                    }
                }
                break;
            case RequestCodes.SELECT_LINKED_LOCATIONS_ACTIVITY:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    linkedLocationIds = MiscUtils.convertLongArrayToList(data.getLongArrayExtra(Strings.LOCATION_IDS));
                    linkedLocationTitles = data.getStringArrayListExtra(Strings.LOCATION_TITLES);
                    linkedLocationAddresses = data.getStringArrayListExtra(Strings.LOCATION_ADDRESSES);

                    List<Long> locationIdsToLink = MiscUtils.convertLongArrayToList(data.getLongArrayExtra(Strings.LOCATION_IDS));
                    locationIdsToLink.add(0, locationId);
                    DatabaseHelper.getInstance(getActivity()).saveLinkedLocations(locationIdsToLink, new DatabaseQueryListener() {
                        @Override
                        public void onQueryExecuted(DatabaseQueryResult result) {
                            updateLinkedLocations();
                        }
                    });
                }
                break;
            case RequestCodes.EDIT_NOTE_FOR_LOCATION_ACTIVITY:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    final String note = data.getStringExtra(Strings.PARAM_NOTE);
                    if(note != null) {
                        LocationModel locationModel = new LocationModel(locationId);
                        locationModel.setNotes(note);
                        DatabaseHelper.getInstance(getActivity()).saveLocation(locationModel, new DatabaseQueryListener() {
                            @Override
                            public void onQueryExecuted(DatabaseQueryResult result) {
                                setNotes(note);
                                locationChanges.notes = true;
                            }
                        });
                    }
                }
                break;
        }
    }

    private class DateClickListener implements View.OnClickListener {
        public void onClick(View v) {
            DatePickerDialog dpd = DatePickerDialog.newInstance(
                    new DateListener(),
                    dateTime.year,
                    dateTime.month,
                    dateTime.monthDay);
            dpd.show(getActivity().getFragmentManager(), "date_picker");
        }
    }

    private class DateListener implements DatePickerDialog.OnDateSetListener {
        @Override
        public void onDateSet(DatePickerDialog dialog, final int year, final int month, final int monthDay) {
            dateTime.year = year;
            dateTime.month = month;
            dateTime.monthDay = monthDay;
            final long unixTime = dateTime.normalize(true);

            LocationModel locationModel = new LocationModel(locationId);
            locationModel.setDate(new Date(unixTime));
            DatabaseHelper.getInstance(getActivity()).saveLocation(locationModel, new DatabaseQueryListener() {
                @Override
                public void onQueryExecuted(DatabaseQueryResult result) {
                    dateText.setText(DateUtils.formatDateTime(
                            getActivity(),
                            unixTime,
                            DateUtils.FORMAT_SHOW_YEAR));
                    locationChanges.date = true;
                }
            });
        }
    }

    protected void saveLocationInfo(DatabaseQueryListener listener) {
        LocationModel locationModel = new LocationModel(locationId);
        locationModel.setLocationInfo(locationInfo);
        DatabaseHelper.getInstance(getActivity()).saveLocation(locationModel, listener);
    }

    class DetailFragmentBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(Application.LOCATION_UPDATE_BROADCAST.equals(action)) {
                final Location location = intent.getParcelableExtra("location");
                LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

                if (locationInfo == null) {
                    // FLOW L
                    // ---------------------------------------------------------------------------------
                    // There are 2 usecases to cover:
                    //
                    //  1)  The user creates a new location and this location update broadcast fires
                    //      AFTER location details have been loaded. At this point we already know
                    //      if there's no location set and we can set the current coordinates for it.
                    //
                    //  2)  The user creates a new location and this location update broadcast fires
                    //      BEFORE location details have been loaded. In this case, we don't know
                    //      if there's truly no location set yet, so we have to wait until the details
                    //      have been loaded before setting the current coordinates for this location.
                    //
                    //      To cover this usecase, we set a temporary [currentLatLng] variable which
                    //      will be utilized in loadDetails().
                    if(detailsLoaded) {
                        locationInfo = new LocationInfo(latLng, null, true);
                        saveLocationInfo(new DatabaseQueryListener() {
                            @Override
                            public void onQueryExecuted(DatabaseQueryResult result) {
                                updateMap();
                                showAddressHelpTextIfApplicable();
                            }
                        });
                    }
                    else {
                        currentLatLng = latLng;
                    }
                }

                broadcastManager.unregisterReceiver(broadcastReceiver);
            }
        }
    }

}
