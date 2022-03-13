package com.sndurkin.locationscout;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.util.TypedValue;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.api.client.googleapis.extensions.android.accounts.GoogleAccountManager;
import com.sndurkin.locationscout.settings.SyncSettingsFragment;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.DriveSyncAdapter;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.storage.TagModel;
import com.sndurkin.locationscout.storage.TagWithCountModel;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.Versions;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BrowseByTagsFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener,
                                                              SwipeRefreshLayout.OnChildScrollUpListener{

    private View view;

    protected Tracker tracker;
    protected SharedPreferences preferences;
    protected DatabaseHelper database;

    private GlobalBroadcastManager broadcastManager;
    private BroadcastReceiver broadcastReceiver;

    private SwipeRefreshLayout swipeRefreshLayout;
    private Account account;
    private boolean isSyncing = false;

    private Handler refreshDismissHandler;

    protected Map<String, TagWithCountModel> tagsMap = new LinkedHashMap<>();
    protected List<TagWithCountModel> tagsList = new ArrayList<>();
    private DatabaseHelper.TagsQuerySortType sortType;

    private boolean fetchingTags = false;
    private Listener tagsFetchedListener = null;

    protected ListView tagsListView;
    protected TextView emptyTagsText;
    protected Button addTagButton;

    protected Snackbar snackbar;

    protected boolean tagColorChanged = false;

    protected TagListAdapter tagListAdapter;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setView(inflater, R.layout.browse_by_tags_fragment, container);
        getActivity().setTitle(R.string.browse_by_tags_title);

        tracker = ((Application) getActivity().getApplication()).getTracker();
        tracker.setScreenName(Strings.BROWSE_BY_TAGS_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        database = DatabaseHelper.getInstance(getActivity());

        broadcastManager = GlobalBroadcastManager.getInstance(getActivity().getApplicationContext());
        broadcastReceiver = new BroadcastReceiver();

        setHasOptionsMenu(true);

        swipeRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.swipe_refresh_layout);
        swipeRefreshLayout.setOnRefreshListener(this);
        swipeRefreshLayout.setOnChildScrollUpListener(this);
        swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_green_dark,
                android.R.color.holo_red_dark,
                android.R.color.holo_blue_dark,
                android.R.color.holo_orange_dark
        );

        refreshDismissHandler = new Handler();

        tagsListView = (ListView) view.findViewById(R.id.tags);
        tagsListView.setEmptyView(view.findViewById(R.id.empty_tags_view));
        emptyTagsText = (TextView) view.findViewById(R.id.empty_tags_text);

        addTagButton = (Button) view.findViewById(R.id.tag_add_button);
        addTagButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_ADD_TAG)
                        .build());
                addTag();
            }
        });

        snackbar = (Snackbar) view.findViewById(R.id.snackbar);

        int sortTypeIdx = preferences.getInt(Strings.PREF_TAGS_SORT, DatabaseHelper.TagsQuerySortType.SORT_ALPHABETICAL.ordinal());
        sortType = DatabaseHelper.TagsQuerySortType.values()[sortTypeIdx];

        tagsListView.setAdapter(tagListAdapter = new TagListAdapter());
        fetchTags();

        Versions.checkAndUpdate(preferences, Strings.BROWSE_BY_TAGS_SCREEN_VERSION, Versions.Defaults.BROWSE_BY_TAGS_SCREEN_VERSION, null);

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

    @Override
    public void onResume() {
        super.onResume();

        // Register broadcasts for this fragment.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST);
        broadcastManager.registerReceiver(BrowseByTagsFragment.class.getCanonicalName(), broadcastReceiver, intentFilter, true);

        boolean shouldEnableSwipeRefresh = false;
        String accountName = preferences.getString(Strings.PREF_ACCOUNT, null);
        if(accountName != null) {
            account = new GoogleAccountManager(getActivity()).getAccountByName(accountName);
            if(account != null) {
                shouldEnableSwipeRefresh = true;
            }
        }
        swipeRefreshLayout.setEnabled(shouldEnableSwipeRefresh);
    }

    @Override
    public void onPause() {
        super.onPause();

        snackbar.hide(false);
        broadcastManager.unregisterReceiver(broadcastReceiver);
        if(swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
            swipeRefreshLayout.destroyDrawingCache();
            swipeRefreshLayout.clearAnimation();

            refreshDismissHandler.removeCallbacksAndMessages(null);
        }
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
        return tagsListView.getFirstVisiblePosition() > 0 ||
                tagsListView.getChildAt(0) == null ||
                tagsListView.getChildAt(0).getTop() < 0;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        menu.clear();
        inflater.inflate(R.menu.browse_by_tags_menu, menu);

        int sortTypeIdx = preferences.getInt(Strings.PREF_TAGS_SORT, DatabaseHelper.TagsQuerySortType.SORT_ALPHABETICAL.ordinal());
        sortType = DatabaseHelper.TagsQuerySortType.values()[sortTypeIdx];
        MenuItem sortMenuItem = null;
        switch(sortType) {
            case SORT_ALPHABETICAL:
                sortMenuItem = menu.findItem(R.id.menu_sort_alpha);
                break;
            case SORT_REVERSE_ALPHABETICAL:
                sortMenuItem = menu.findItem(R.id.menu_sort_reverse_alpha);
                break;
            case SORT_MOST_LOCATIONS_TAGGED:
                sortMenuItem = menu.findItem(R.id.menu_sort_most_locations);
                break;
            case SORT_FEWEST_LOCATIONS_TAGGED:
                sortMenuItem = menu.findItem(R.id.menu_sort_fewest_locations);
                break;
        }

        sortMenuItem.setChecked(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_add_tag:
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_MENU_ITEM)
                        .setLabel(Strings.LBL_ADD_TAG)
                        .build());
                addTag();
            case R.id.menu_sort_alpha:
            case R.id.menu_sort_reverse_alpha:
            case R.id.menu_sort_most_locations:
            case R.id.menu_sort_fewest_locations:
                String sortTypeStr = null;
                switch(item.getItemId()) {
                    case R.id.menu_sort_alpha:
                        sortType = DatabaseHelper.TagsQuerySortType.SORT_ALPHABETICAL;
                        sortTypeStr = Strings.LBL_SORT_ALPHABETICAL;
                        break;
                    case R.id.menu_sort_reverse_alpha:
                        sortType = DatabaseHelper.TagsQuerySortType.SORT_REVERSE_ALPHABETICAL;
                        sortTypeStr = Strings.LBL_SORT_REVERSE_ALPHABETICAL;
                        break;
                    case R.id.menu_sort_most_locations:
                        sortType = DatabaseHelper.TagsQuerySortType.SORT_MOST_LOCATIONS_TAGGED;
                        sortTypeStr = Strings.LBL_SORT_MOST_PLACES_TAGGED;
                        break;
                    case R.id.menu_sort_fewest_locations:
                        sortType = DatabaseHelper.TagsQuerySortType.SORT_FEWEST_LOCATIONS_TAGGED;
                        sortTypeStr = Strings.LBL_SORT_FEWEST_PLACES_TAGGED;
                        break;
                }

                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CHANGE_TAG_SORT)
                        .setLabel(sortTypeStr)
                        .build());

                item.setChecked(true);

                SharedPreferences.Editor editor = preferences.edit();
                editor.putInt(Strings.PREF_TAGS_SORT, sortType.ordinal());
                editor.commit();

                fetchTags();
                break;
        }

        return true;
    }
    /*
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        tagColorChanged = savedInstanceState.getBoolean(Strings.PARAM_TAG_COLOR_CHANGED);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(Strings.PARAM_TAG_COLOR_CHANGED, tagColorChanged);
    }

    @Override
    public void finish() {
        Intent data = new Intent();
        data.putExtra(Strings.PARAM_TAG_COLOR_CHANGED, tagColorChanged);
        setResult(RESULT_OK, data);

        super.finish();
    }
    */
    public void fetchTags() {
        fetchingTags = true;
        database.fetchTags(sortType, true, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                tagsMap.clear();

                TagWithCountModel untaggedModel = null;

                Cursor cursor = result.cursor;
                while (!cursor.isAfterLast()) {
                    if (!cursor.isNull(cursor.getColumnIndex(DatabaseHelper.ID_COLUMN))) {
                        String name = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TAG_NAME_COLUMN));
                        Integer count = cursor.getInt(cursor.getColumnIndex(DatabaseHelper.TRANSIENT_COUNT_COLUMN));
                        TagModel tag = ModelUtils.createTagFromCursor(cursor);
                        tagsMap.put(name, new TagWithCountModel(tag, count));
                    } else {
                        Integer count = cursor.getInt(cursor.getColumnIndex(DatabaseHelper.TRANSIENT_COUNT_COLUMN));
                        if (count > 0) {
                            untaggedModel = new TagWithCountModel(null, count);
                        }
                    }
                    cursor.moveToNext();
                }

                // Untagged locations should be appended to the end of the list, regardless of the sort type used.
                if (untaggedModel != null) {
                    tagsMap.put(null, untaggedModel);
                }

                // Hide the refresh icon after we're done loading the tags from the DB.
                swipeRefreshLayout.setRefreshing(isSyncing);

                fetchingTags = false;
                if (tagsFetchedListener != null) {
                    tagsFetchedListener.onTagsFetched();
                    tagsFetchedListener = null;
                }

                updateTagsView();
            }
        });
    }

    // This function displays tags in the ListView, filtered by any
    // custom text that the user has entered in the EditText.
    protected void updateTagsView() {
        tagsList.clear();
        tagsList.addAll(tagsMap.values());
        tagListAdapter.notifyDataSetChanged();
    }

    protected void addTag() {
        openTagEditor(null);
    }

    protected void deleteTag(final String tagName) {
        tracker.send(new HitBuilders.EventBuilder()
                .setCategory(Strings.CAT_UI_ACTION)
                .setAction(Strings.ACT_CLICK_BUTTON)
                .setLabel(Strings.LBL_DELETE_TAG)
                .build());

        snackbar.expire();

        final Long tagId = tagsMap.get(tagName).getLocalId();
        database.tentativelyDeleteTag(tagId, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                fetchTags();

                Snackbar.ShowConfig config = snackbar.new ShowConfig();
                config.text = getString(R.string.tag_deleted_snackbar);
                config.listener = snackbar.new Listener() {
                    @Override
                    public void onExpired() {
                        database.deleteTag(tagId, new DatabaseQueryListener() {
                            @Override
                            public void onQueryExecuted(DatabaseQueryResult result) {
                                fetchTags();
                            }
                        });
                    }

                    @Override
                    public void onButtonClicked() {
                        tracker.send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_BUTTON)
                                .setLabel(Strings.LBL_DELETE_TAG_UNDO)
                                .build());

                        // Undo deleting this tag.
                        database.undeleteTag(tagId, new DatabaseQueryListener() {
                            @Override
                            public void onQueryExecuted(DatabaseQueryResult result) {
                                fetchTags();
                            }
                        });
                    }
                };
                snackbar.show(config);
            }
        });
    }

    protected void openTagEditor(final String existingTagName) {
        final AlertDialog alertDialog = new AlertDialog.Builder(getActivity())
                .setTitle(R.string.tag_edit_dialog_title)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        tracker.send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_BUTTON)
                                .setLabel(Strings.LBL_CANCEL_TAG_EDIT)
                                .build());
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        tracker.send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_BUTTON)
                                .setLabel(Strings.LBL_CANCEL_TAG_EDIT)
                                .build());
                    }
                })
                .create();

        ViewGroup editTextCt = (ViewGroup) alertDialog.getLayoutInflater().inflate(R.layout.dialog_edit_text, null);
        alertDialog.setView(editTextCt);

        // Set an EditText to get user input.
        final EditText newTagEditText = (EditText) editTextCt.findViewById(R.id.edit_text);
        newTagEditText.setText(existingTagName);
        newTagEditText.setSelection(newTagEditText.getText().length());

        alertDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                Button button = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // If the input is empty and the user presses OK, then:
                        //  1) If the tag was just added, then we can quietly delete it.
                        //  2) If the tag previously had a name, then warn the user before deleting it.
                        final String newTagName = newTagEditText.getText().toString();
                        if (newTagName.isEmpty()) {
                            deleteTag(existingTagName);
                        } else if (newTagName.equals(existingTagName)) {
                            // Do nothing; dismiss dialog.
                        } else if (tagsMap.containsKey(newTagName)) {
                            newTagEditText.setError(getString(R.string.duplicate_tag_error));
                            return;
                        } else {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_SAVE_TAG_EDIT)
                                    .build());

                            if (existingTagName != null && tagsMap.containsKey(existingTagName)) {
                                // Edit an existing tag.
                                Long tagId = tagsMap.get(existingTagName).getLocalId();
                                database.saveTag(new TagModel(tagId, newTagName), new DatabaseQueryListener() {
                                    @Override
                                    public void onQueryExecuted(DatabaseQueryResult result) {
                                        fetchTags();
                                    }
                                });
                            } else {
                                // Create a new tag.
                                database.saveTag(new TagModel(null, newTagName), new DatabaseQueryListener() {
                                    @Override
                                    public void onQueryExecuted(DatabaseQueryResult result) {
                                        // We need to re-fetch tags here so the sorting preferences are applied.
                                        fetchTags();
                                    }
                                });
                            }
                        }

                        alertDialog.dismiss();
                    }
                });
            }
        });
        alertDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        alertDialog.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch(requestCode) {
            case RequestCodes.SET_MAP_MARKER_ACTIVITY:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    if(fetchingTags) {
                        tagsFetchedListener = new Listener() {
                            @Override
                            public void onTagsFetched() {
                                updateTagMarker(data);
                            }
                        };
                    }
                    else {
                        updateTagMarker(data);
                    }
                }
                break;
        }
    }

    protected void updateTagMarker(Intent data) {
        final TagWithCountModel tag = tagsMap.get(data.getStringExtra("name"));
        final TagModel changedTag = new TagModel();
        changedTag.setLocalId(tag.getLocalId());
        changedTag.color = data.getIntExtra("color", 0);
        if(data.hasExtra("iconPath")) {
            changedTag.iconPath = data.getStringExtra("iconPath");
        }

        database.saveTag(changedTag, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                tag.mergeFrom(changedTag, false);
                updateTagsView();
            }
        });
    }

    private class TagListAdapter extends ArrayAdapter<TagWithCountModel> {

        public TagListAdapter() {
            super(getActivity(), R.layout.tag_item_view);
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View tagRowView = convertView;
            if(tagRowView == null) {
                tagRowView = getActivity().getLayoutInflater().inflate(R.layout.browse_by_tags_item_view, parent, false);
            }

            final TagWithCountModel tag = tagsList.get(position);

            final TextView tagText = (TextView) tagRowView.findViewById(R.id.tag_item_text);
            int textColorAttr;
            if(tag.name != null) {
                tagText.setText(tag.name);
                textColorAttr = R.attr.textColorPrimary;
            }
            else {
                tagText.setText(R.string.untagged_places);
                textColorAttr = R.attr.textColorTertiary;
            }

            TypedValue textColorValue = new TypedValue();
            getContext().getTheme().resolveAttribute(textColorAttr, textColorValue, true);
            tagText.setTextColor(getResources().getColor(textColorValue.resourceId));

            final ImageView tagColorImageView = (ImageView) tagRowView.findViewById(R.id.tag_item_color);
            final ImageView tagIconImageView = (ImageView) tagRowView.findViewById(R.id.tag_item_icon);
            final GradientDrawable tagColorImageBackground = (GradientDrawable) tagColorImageView.getBackground();
            if(tag.hasColor()) {
                tagColorImageBackground.setColor(tag.color);
                tagColorImageView.setVisibility(View.VISIBLE);
            }
            else {
                tagColorImageView.setVisibility(View.GONE);
            }

            if(tag.hasIcon()) {
                tagIconImageView.setVisibility(View.VISIBLE);
                Glide.with(getActivity())
                        .load(Uri.fromFile(new File(tag.iconPath)))
                        .fitCenter()
                        .placeholder(R.color.gallery_placeholder)
                        .into(tagIconImageView);
            }
            else {
                tagIconImageView.setVisibility(View.GONE);
            }

            final TextView locationsText = (TextView) tagRowView.findViewById(R.id.tag_item_locations_text);
            locationsText.setText(getResources().getQuantityString(R.plurals.num_places_tagged_text, tag.count, tag.count));

            final ImageButton menuButton = (ImageButton) tagRowView.findViewById(R.id.tag_item_menu_button);
            menuButton.setVisibility(tag.name != null ? View.VISIBLE : View.GONE);
            menuButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTagItemMenu(v, tag);
                }
            });

            tagRowView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_LIST_ITEM)
                            .setLabel(tag.name != null ? Strings.LBL_TAG : Strings.LBL_UNTAGGED_PLACES)
                            .build());

                    if(!isAdded()) { return; }
                    ((MainActivity) getActivity()).browseByTag(tag.name);
                }
            });

            return tagRowView;
        }

        protected void showTagItemMenu(final View v, final TagWithCountModel tag) {
            PopupMenu menu = new PopupMenu(getActivity(), v);
            menu.inflate(R.menu.tag_item_menu);
            menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    switch (item.getItemId()) {
                        case R.id.menu_edit_tag:
                            openTagEditor(tag.name);
                            break;
                        case R.id.menu_set_map_marker:
                            openSetMapMarkerScreen(tag);
                            break;
                        case R.id.menu_delete_tag:
                            deleteTag(tag.name);
                            break;
                    }

                    return true;
                }
            });
            menu.show();
        }

        protected void openSetMapMarkerScreen(final TagWithCountModel tag) {
            Intent intent = new Intent(getActivity(), SetMapMarkerActivity.class);
            intent.putExtra(Strings.TAG_ID, tag.getLocalId());
            startActivityForResult(intent, RequestCodes.SET_MAP_MARKER_ACTIVITY);
        }

        @Override
        public int getCount() {
            return tagsList.size();
        }

        @Override
        public long getItemId(int position) {
            Long id = tagsList.get(position).getLocalId();
            return id != null ? id : -1L;
        }

        @Override
        public TagWithCountModel getItem(int position) {
            return tagsList.get(position);
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
                        swipeRefreshLayout.setRefreshing(true);
                        isSyncing = true;
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

                        // If we don't remove this broadcast when the sync is finished, it will enter this code
                        // from onResume() which sends the last broadcast for DRIVE_SYNC_ADAPTER_BROADCAST (to
                        // support showing the refresh icon for SYNC_STARTED). This results in an unnecessary call
                        // to fetchLocations().
                        broadcastManager.removeLastBroadcast(ListFragment.class.getCanonicalName(), action);

                        fetchTags();
                        break;
                }
            }
        }
    }

    private interface Listener {
        void onTagsFetched();
    }

}
