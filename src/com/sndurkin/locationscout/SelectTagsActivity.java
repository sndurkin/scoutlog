package com.sndurkin.locationscout;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.storage.TagModel;
import com.sndurkin.locationscout.storage.TagWithCountModel;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;
import com.sndurkin.locationscout.util.Versions;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SelectTagsActivity extends AppCompatActivity {

    protected Tracker tracker;
    protected SharedPreferences preferences;
    protected DatabaseHelper database;

    protected EditText selectedTagsText;
    protected Map<String, TagWithCountModel> tagsMap = new LinkedHashMap<>();
    protected List<TagWithCountModel> tagsList = new ArrayList<>();

    private boolean fetchingTags = false;
    private Listener tagsFetchedListener = null;

    protected Set<Long> selectedTagIds = new LinkedHashSet<Long>();
    protected List<Integer> selectedTagIndices = new ArrayList<Integer>();
    protected List<Integer> selectedTagLengths = new ArrayList<Integer>();

    protected boolean selectedTagsChanged = false;

    protected RelativeLayout helpScreenView;
    protected ViewPager helpScreenViewPager;
    protected ViewPagerAdapter helpScreenPagerAdapter;
    protected CircleIndicatorView helpScreenPageIndicator;
    protected Button helpScreenButton;

    protected ListView tagsListView;
    protected TextView emptyTagsText;
    protected Button addTagButton;

    protected Snackbar snackbar;
    protected TagWithCountModel tentativelyDeletedTag;
    protected int tentativelyDeletedTagIdx;

    protected String filteredText;
    protected boolean tagNameChanged = false;
    protected boolean tagColorChanged = false;

    protected TagListAdapter tagListAdapter;

    private int chipResId;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));

        super.onCreate(savedInstanceState);

        tracker = ((Application) getApplication()).getTracker();
        tracker.setScreenName(Strings.SELECT_TAGS_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        database = DatabaseHelper.getInstance(this);

        setContentView(R.layout.select_tags_activity);
        setTitle(R.string.tags_title);

        setupBottomBar();
        initHelpScreen();

        // Initialize the chip drawable depending on the theme.
        if("0".equals(preferences.getString(Strings.PREF_THEME, "0"))) {
            chipResId = R.drawable.chip_light;
        }
        else {
            chipResId = R.drawable.chip_dark;
        }

        tagsListView = (ListView) findViewById(R.id.tags);
        tagsListView.setEmptyView(findViewById(R.id.empty_tags_view));
        emptyTagsText = (TextView) findViewById(R.id.empty_tags_text);

        addTagButton = (Button) findViewById(R.id.tag_add_button);
        addTagButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(hasFilteredText() && !filteredText.trim().isEmpty()) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_ADD_TAG_FROM_SEARCH)
                            .build());

                    addTagFromSearch();
                }
                else {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_ADD_TAG)
                            .build());

                    addTag();
                }
            }
        });

        snackbar = (Snackbar) findViewById(R.id.snackbar);

        // EditText should:
        //  - support adding chips via list view
        //  - prevent text selection
        //
        //  - support filtering of the list view by entering custom text
        //  - clear the custom text when a chip is added
        //  - allow removing chips by a single backspace (TODO: look into delete key for custom keyboard implementations)
        //  - maintain selected chips and custom text (and ideally cursor position) across device rotate
        //
        selectedTagsText = (EditText) findViewById(R.id.text);
        selectedTagsText.setLongClickable(false);           // Prevent text selection.
        selectedTagsText.addTextChangedListener(new SelectedTagsTextWatcher());

        if (selectedTagIds.isEmpty()) {
            // Fetch the selected tags (if any) passed in from the detail activity.
            Bundle extras = getIntent().getExtras();
            if(extras != null) {
                ArrayList<Long> tagIds = (ArrayList<Long>) extras.getSerializable(Strings.PARAM_SELECTED_TAG_IDS);
                if(tagIds != null) {
                    selectedTagIds = new LinkedHashSet<Long>(tagIds);
                }
            }
        }


        tagsListView.setAdapter(tagListAdapter = new TagListAdapter());
        fetchingTags = true;
        database.fetchTags(null, false, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                tagsMap.clear();
                Cursor cursor = result.cursor;
                while (!cursor.isAfterLast()) {
                    TagModel tag = ModelUtils.createTagFromCursor(cursor);
                    String name = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TAG_NAME_COLUMN));
                    Integer count = cursor.getInt(cursor.getColumnIndex(DatabaseHelper.TRANSIENT_COUNT_COLUMN));
                    tagsMap.put(name, new TagWithCountModel(tag, count));
                    cursor.moveToNext();
                }

                fetchingTags = false;
                if (tagsFetchedListener != null) {
                    tagsFetchedListener.onTagsFetched();
                    tagsFetchedListener = null;
                }

                setTagSelections(false);
                updateTagsView();
            }
        });
    }

    @Override
    protected void onPause() {
        snackbar.hide(false);
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.tag_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                cancelWithWarning();
                return true;
            case R.id.menu_add_tag:
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_MENU_ITEM)
                        .setLabel(Strings.LBL_ADD_TAG)
                        .build());
                addTag();
                return true;
            case R.id.menu_help:
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_MENU_ITEM)
                        .setLabel(Strings.LBL_HELP)
                        .build());
                showHelpScreen();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        cancelWithWarning();
    }

    protected void initHelpScreen() {
        helpScreenView = (RelativeLayout) findViewById(R.id.help_screen);
        helpScreenViewPager = (ViewPager) findViewById(R.id.help_screen_viewpager);
        helpScreenPageIndicator = (CircleIndicatorView) findViewById(R.id.help_screen_page_indicator);
        helpScreenPagerAdapter = new ViewPagerAdapter();
        helpScreenButton = (Button) findViewById(R.id.help_screen_button);

        helpScreenPagerAdapter.addView((ViewGroup) getLayoutInflater().inflate(R.layout.select_tags_help_screen_1, null, false));
        helpScreenPagerAdapter.addView((ViewGroup) getLayoutInflater().inflate(R.layout.select_tags_help_screen_2, null, false));
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
                    Versions.update(preferences, Strings.SELECT_TAGS_HELP_VERSION, Versions.Defaults.SELECT_TAGS_HELP_VERSION);
                    helpScreenView.setVisibility(View.GONE);
                    getSupportActionBar().show();
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

        Versions.check(preferences, Strings.SELECT_TAGS_HELP_VERSION, Versions.Defaults.SELECT_TAGS_HELP_VERSION, new Versions.Listener() {
            @Override
            public void onFirstVersion() {
                helpScreenView.setVisibility(View.VISIBLE);
                getSupportActionBar().hide();
            }

            @Override
            public void onUpdateVersion() {
                // Nothing here yet.
            }
        });
    }

    protected void showHelpScreen() {
        helpScreenViewPager.setCurrentItem(0);
        helpScreenView.setVisibility(View.VISIBLE);
        getSupportActionBar().hide();
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        selectedTagIds = new LinkedHashSet<Long>();
        for(Integer i : savedInstanceState.getIntegerArrayList(Strings.PARAM_SELECTED_TAG_IDS)) {
            selectedTagIds.add(i.longValue());
        }
        filteredText = savedInstanceState.getString(Strings.PARAM_FILTERED_TEXT);
        tagNameChanged = savedInstanceState.getBoolean(Strings.PARAM_TAG_NAME_CHANGED);
        tagColorChanged = savedInstanceState.getBoolean(Strings.PARAM_TAG_COLOR_CHANGED);
        selectedTagsChanged = savedInstanceState.getBoolean(Strings.PARAM_DATA_CHANGED);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        ArrayList<Integer> tagIdsList = new ArrayList<Integer>();
        for(Long l : selectedTagIds) {
            tagIdsList.add(l.intValue());
        }
        outState.putIntegerArrayList(Strings.PARAM_SELECTED_TAG_IDS, tagIdsList);
        outState.putString(Strings.PARAM_FILTERED_TEXT, filteredText);
        outState.putBoolean(Strings.PARAM_TAG_NAME_CHANGED, tagNameChanged);
        outState.putBoolean(Strings.PARAM_TAG_COLOR_CHANGED, tagColorChanged);
        outState.putBoolean(Strings.PARAM_DATA_CHANGED, selectedTagsChanged);
    }

    protected void cancelWithWarning() {
        if(selectedTagsChanged && preferences.getBoolean(Strings.PREF_NOTIFY_UNSAVED_CHANGES, true)) {
            new AlertDialog.Builder(SelectTagsActivity.this)
                    .setTitle(R.string.notify_unsaved_tags_dialog_title)
                    .setMessage(R.string.notify_unsaved_tags_dialog_message)
                    .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_CANCEL_TAG_SELECTIONS_YES)
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
                                    .setLabel(Strings.LBL_CANCEL_TAG_SELECTIONS_NO)
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
                                    .setLabel(Strings.LBL_CANCEL_TAG_SELECTIONS_NEVER_AGAIN)
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
        Intent intent = new Intent();
        intent.putExtra(Strings.PARAM_TAG_NAMES, getSelectedTagNames());
        TagModel firstTag = getFirstTag();
        if(firstTag != null) {
            intent.putExtra(Strings.PARAM_COLOR, firstTag.color);
            intent.putExtra(Strings.PARAM_ICON_PATH, firstTag.iconPath);
        }
        intent.putExtra(Strings.PARAM_TAG_NAME_CHANGED, tagNameChanged);
        intent.putExtra(Strings.PARAM_TAG_COLOR_CHANGED, tagColorChanged);
        setResult(RESULT_CANCELED, intent);
        finish();
    }

    protected void saveAndFinish() {
        // Pass the selected tags back to the detail activity.
        Intent intent = new Intent();
        intent.putExtra(Strings.PARAM_SELECTED_TAG_IDS, new ArrayList<>(selectedTagIds));
        intent.putExtra(Strings.PARAM_TAG_NAMES, getSelectedTagNames());
        TagModel firstTag = getFirstTag();
        if(firstTag != null) {
            intent.putExtra(Strings.PARAM_COLOR, firstTag.color);
            intent.putExtra(Strings.PARAM_ICON_PATH, firstTag.iconPath);
        }
        intent.putExtra(Strings.PARAM_TAG_NAME_CHANGED, tagNameChanged);
        intent.putExtra(Strings.PARAM_TAG_COLOR_CHANGED, tagColorChanged);
        setResult(RESULT_OK, intent);
        finish();
    }

    protected ArrayList<String> getSelectedTagNames() {
        ArrayList<String> selectedTagNames = new ArrayList<String>();
        for (Long selectedTagId : selectedTagIds) {
            for (TagModel tagModel : tagsMap.values()) {
                if (selectedTagId.equals(tagModel.getLocalId())) {
                    selectedTagNames.add(tagModel.name);
                    break;
                }
            }
        }
        return selectedTagNames;
    }

    protected boolean hasFilteredText() {
        return filteredText != null && !filteredText.isEmpty();
    }

    // This function displays tags in the ListView, filtered by any
    // custom text that the user has entered in the EditText.
    protected void updateTagsView() {
        tagsList.clear();

        if(hasFilteredText()) {
            // Filter the tags list by the entered text.
            for(TagWithCountModel tag : tagsMap.values()) {
                if(tag.name.toLowerCase().startsWith(filteredText.toLowerCase())) {
                    tagsList.add(tag);
                }
            }
        }
        else {
            tagsList.addAll(tagsMap.values());
        }

        if(hasFilteredText()) {
            emptyTagsText.setText(R.string.empty_filtered_tags_text);
            addTagButton.setText(getString(R.string.add_tag_from_search, filteredText));
        }
        else {
            emptyTagsText.setText(R.string.empty_tags_text);
            addTagButton.setText(R.string.add_tag);
        }

        tagListAdapter.notifyDataSetChanged();
    }

    protected TagModel getFirstTag() {
        if(!selectedTagIds.isEmpty()) {
            Long selectedTagId = selectedTagIds.toArray(new Long[0])[0];
            for(TagModel tagModel : tagsMap.values()) {
                if(selectedTagId.equals(tagModel.getLocalId())) {
                    return tagModel;
                }
            }
        }

        return null;
    }

    // This function adds chips to the EditText for all currently selected tags.
    protected void setTagSelections(boolean preserveEnteredText) {
        selectedTagIndices.clear();
        selectedTagLengths.clear();
        selectedTagsText.setText("");

        for(Long selectedTagId : selectedTagIds) {
            for(TagModel tagModel : tagsMap.values()) {
                if(selectedTagId.equals(tagModel.getLocalId())) {
                    selectTag(tagModel);
                }
            }
        }

        if(preserveEnteredText && hasFilteredText()) {
            selectedTagsText.append(filteredText);
        }
        else {
            // Clear out the filtered text.
            filteredText = null;
        }
        updateTagsView();
    }

    protected Bitmap createBitmapForTag(TagModel tagModel) {
        // Create TextView displaying tag name, tag color and background chip.
        TextView textView = new TextView(this);
        textView.setText(tagModel.name);
        textView.setTextSize(16);
        textView.setBackgroundResource(chipResId);

        if(tagModel.hasColor()) {
            GradientDrawable tagColorDrawable = (GradientDrawable) getResources().getDrawable(R.drawable.color_preview);
            tagColorDrawable.setColor(tagModel.color);
            textView.setCompoundDrawablesWithIntrinsicBounds(tagColorDrawable, null, null, null);
            textView.setCompoundDrawablePadding(UIUtils.dpToPx(this, 4));
        }

        // Create Bitmap from TextView.
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        textView.measure(spec, spec);
        textView.layout(0, 0, textView.getMeasuredWidth(), textView.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(textView.getMeasuredWidth(), textView.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-textView.getScrollX(), -textView.getScrollY());
        textView.draw(c);
        textView.setDrawingCacheEnabled(true);
        Bitmap cacheBitmap = textView.getDrawingCache();
        Bitmap textViewBitmap = cacheBitmap.copy(Bitmap.Config.ARGB_8888, true);
        textView.destroyDrawingCache();
        return textViewBitmap;
    }

    protected void selectTag(TagModel tagModel) {
        selectedTagIndices.add(selectedTagsText.length());
        selectedTagLengths.add(tagModel.name.length());

        final SpannableStringBuilder sb = new SpannableStringBuilder();
        Bitmap bitmap = createBitmapForTag(tagModel);
        BitmapDrawable bd = new BitmapDrawable(getResources(), bitmap);
        bd.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());

        sb.append(tagModel.name + " ");
        sb.setSpan(new ImageSpan(bd), 0, tagModel.name.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        selectedTagsText.append(sb);
    }

    protected void addTagFromSearch() {
        // Create a new tag.
        database.saveTag(new TagModel(null, filteredText), new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                Long tagId = result.id;

                TagWithCountModel tag = new TagWithCountModel(new TagModel(tagId, filteredText), 0);
                tagsMap.put(filteredText, tag);

                selectedTagsChanged = true;
                selectedTagIds.add(tagId);
                setTagSelections(false);
                updateTagsView();

                UIUtils.hideKeyboard(SelectTagsActivity.this);
            }
        });
    }

    protected void addTag() {
        openTagEditor(null);
    }

    protected void deleteTag(final String tagName) {
        TagModel tagModel = tagsMap.get(tagName);
        if(tagModel == null) {
            CrashlyticsCore.getInstance().logException(new IllegalStateException("There was no matching tag instance with the provided tag name"));
            return;
        }

        tracker.send(new HitBuilders.EventBuilder()
                .setCategory(Strings.CAT_UI_ACTION)
                .setAction(Strings.ACT_CLICK_BUTTON)
                .setLabel(Strings.LBL_DELETE_TAG)
                .build());

        snackbar.expire();

        final Long tagId = tagModel.getLocalId();
        database.tentativelyDeleteTag(tagId, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                int idx = 0;
                for (TagModel tagModel : tagsMap.values()) {
                    if (tagModel.name.equals(tagName)) {
                        break;
                    }
                    ++idx;
                }
                tentativelyDeletedTagIdx = idx;
                tentativelyDeletedTag = tagsMap.remove(tagName);
                if (selectedTagIds.contains(tagId)) {
                    selectedTagsChanged = true;
                    selectedTagIds.remove(tagId);
                    setTagSelections(true);
                } else {
                    updateTagsView();
                }

                Snackbar.ShowConfig config = snackbar.new ShowConfig();
                config.text = getString(R.string.tag_deleted_snackbar);
                config.listener = snackbar.new Listener() {
                    @Override
                    public void onExpired() {
                        tentativelyDeletedTag = null;
                        database.deleteTag(tagId, null);
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
                                // Reassemble [tagsMap] in the same order before the tag was deleted.
                                TagWithCountModel[] tags = tagsMap.values().toArray(new TagWithCountModel[0]);
                                tagsMap.clear();
                                int i = 0;
                                for (; i < tentativelyDeletedTagIdx; ++i) {
                                    tagsMap.put(tags[i].name, tags[i]);
                                }
                                tagsMap.put(tentativelyDeletedTag.name, tentativelyDeletedTag);
                                for (; i < tags.length; ++i) {
                                    tagsMap.put(tags[i].name, tags[i]);
                                }
                                updateTagsView();
                            }
                        });
                    }
                };
                snackbar.show(config);
            }
        });
    }

    protected void openTagEditor(final String existingTagName) {
        final AlertDialog alertDialog = new AlertDialog.Builder(this)
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

                            if (tagsMap.containsKey(existingTagName)) {
                                // Edit an existing tag.
                                Long tagId = tagsMap.get(existingTagName).getLocalId();
                                database.saveTag(new TagModel(tagId, newTagName), new DatabaseQueryListener() {
                                    @Override
                                    public void onQueryExecuted(DatabaseQueryResult result) {
                                        TagWithCountModel tag = tagsMap.get(existingTagName);
                                        tag.name = newTagName;
                                        tagsMap.put(newTagName, tag);
                                        tagsMap.remove(existingTagName);
                                        setTagSelections(true);
                                        UIUtils.hideKeyboard(SelectTagsActivity.this);
                                        tagNameChanged = true;
                                    }
                                });
                            }
                            else {
                                // Create a new tag.
                                database.saveTag(new TagModel(null, newTagName), new DatabaseQueryListener() {
                                    @Override
                                    public void onQueryExecuted(DatabaseQueryResult result) {
                                        Long tagId = result.id;
                                        tagsMap.put(newTagName, new TagWithCountModel(new TagModel(tagId, newTagName), 0));
                                        updateTagsView();
                                        UIUtils.hideKeyboard(SelectTagsActivity.this);
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
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
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
                setTagSelections(true);
            }
        });
    }

    private interface Listener {
        void onTagsFetched();
    }

    protected void setupBottomBar() {
        RelativeLayout cancelButton = (RelativeLayout) findViewById(R.id.action_cancel);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_CANCEL_TAG_SELECTIONS)
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
                        .setLabel(Strings.LBL_SAVE_TAG_SELECTIONS)
                        .build());

                saveAndFinish();
            }
        });
    }

    private class SelectedTagsTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if(count == before - 1) {
                // 1 character was deleted from the EditText, so check if we can delete a tag.
                for(int i = 0; i < selectedTagLengths.size(); ++i) {
                    int tagStartIdx = selectedTagIndices.get(i);
                    int tagEndIdx = selectedTagIndices.get(i) + selectedTagLengths.get(i);
                    if(start >= tagStartIdx && start <= tagEndIdx) {
                        Long id = selectedTagIds.toArray(new Long[0])[i];
                        selectedTagIds.remove(id);
                        selectedTagsChanged = true;
                        setTagSelections(false);
                        updateTagsView();
                        return;
                    }
                }

                // If we get to this point, we didn't delete a tag; we deleted one or more characters
                // from the filter text, so re-filter the list of tags.
                filteredText = s.toString().substring(start);
                updateTagsView();
            }
            else if(count > before) {
                // One or more characters were added to the EditText, so check to make sure
                // it's not a tag.
                for(int i = 0; i < selectedTagLengths.size(); ++i) {
                    int tagStartIdx = selectedTagIndices.get(i);
                    int tagEndIdx = selectedTagIndices.get(i) + selectedTagLengths.get(i);
                    if((start + count) >= tagStartIdx && (start + count) <= (tagEndIdx + 1)) {
                        return;
                    }
                }

                // If we get to this point, we didn't add a tag; we added one or more characters
                // to the filter text, so re-filter the list of tags.
                int filteredTextStart = 0;
                if(!selectedTagIndices.isEmpty()) {
                    int lastIdx = selectedTagIndices.size() - 1;
                    filteredTextStart = selectedTagIndices.get(lastIdx) + selectedTagLengths.get(lastIdx) + 1;
                }
                filteredText = s.toString().substring(filteredTextStart);
                updateTagsView();
            }
        }

        @Override
        public void afterTextChanged(Editable s) {

        }
    }

    private class TagListAdapter extends ArrayAdapter<TagModel> {

        public TagListAdapter() {
            super(SelectTagsActivity.this, R.layout.tag_item_view);
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View tagRowView = convertView;
            if(tagRowView == null) {
                tagRowView = getLayoutInflater().inflate(R.layout.tag_item_view, parent, false);
            }

            final TagModel tag = tagsList.get(position);

            final CheckBox checkBox = (CheckBox) tagRowView.findViewById(R.id.tag_item_checkbox);
            checkBox.setClickable(false);
            checkBox.setChecked(selectedTagIds.contains(tag.getLocalId()));

            final TextView tagText = (TextView) tagRowView.findViewById(R.id.tag_item_text);
            tagText.setText(tag.name);

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

            File iconFile;
            if(tag.hasIcon() && (iconFile = new File(tag.iconPath)).exists()) {
                tagIconImageView.setVisibility(View.VISIBLE);
                Glide.with(SelectTagsActivity.this)
                        .load(Uri.fromFile(iconFile))
                        .fitCenter()
                        .skipMemoryCache(true)
                        .placeholder(R.color.gallery_placeholder)
                        .into(tagIconImageView);
            }
            else {
                tagIconImageView.setVisibility(View.GONE);
            }

            final ImageButton menuButton = (ImageButton) tagRowView.findViewById(R.id.tag_item_menu_button);
            menuButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTagItemMenu(v, tag);
                }
            });

            tagRowView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(!selectedTagIds.contains(tag.getLocalId())) {
                        selectedTagIds.add(tag.getLocalId());
                    }
                    else {
                        selectedTagIds.remove(tag.getLocalId());
                    }
                    selectedTagsChanged = true;
                    setTagSelections(false);
                    checkBox.toggle();
                }
            });

            return tagRowView;
        }

        protected void showTagItemMenu(final View v, final TagModel tagModel) {
            PopupMenu menu = new PopupMenu(SelectTagsActivity.this, v);
            menu.inflate(R.menu.tag_item_menu);
            menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    switch (item.getItemId()) {
                        case R.id.menu_edit_tag:
                            openTagEditor(tagModel.name);
                            break;
                        case R.id.menu_set_map_marker:
                            openSetMapMarkerScreen(tagModel);
                            break;
                        case R.id.menu_delete_tag:
                            deleteTag(tagModel.name);
                            break;
                    }

                    return true;
                }
            });
            menu.show();
        }

        protected void openSetMapMarkerScreen(final TagModel tag) {
            Intent intent = new Intent(SelectTagsActivity.this, SetMapMarkerActivity.class);
            intent.putExtra(Strings.TAG_ID, tag.getLocalId());
            startActivityForResult(intent, RequestCodes.SET_MAP_MARKER_ACTIVITY);
        }

        @Override
        public int getCount() {
            return tagsList.size();
        }

        @Override
        public long getItemId(int position) {
            return tagsList.get(position).getLocalId();
        }

        @Override
        public TagModel getItem(int position) {
            return tagsList.get(position);
        }

    }

}
