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
import android.support.v7.app.ActionBar;
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
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.storage.TagModel;
import com.sndurkin.locationscout.storage.TagWithCountModel;
import com.sndurkin.locationscout.util.MiscUtils;
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

public class BulkEditTagsActivity extends AppCompatActivity {

    protected Tracker tracker;
    protected SharedPreferences preferences;
    protected DatabaseHelper database;

    protected EditText selectedTagsText;
    protected Map<String, TagWithCountModel> tagsMap = new LinkedHashMap<>();
    protected List<TagWithCountModel> tagsList = new ArrayList<>();

    private boolean fetchingTags = false;
    private Listener tagsFetchedListener = null;

    protected EditText selectedAfterTagsText;
    protected View afterTagsTextBorder;

    protected List<Long> selectedLocationIds;

    protected Set<Long> selectedTagIds = new LinkedHashSet<Long>();
    protected List<Integer> selectedTagIndices = new ArrayList<Integer>();
    protected List<Integer> selectedTagLengths = new ArrayList<Integer>();

    protected Set<Long> selectedAfterTagIds = new LinkedHashSet<Long>();
    protected List<Integer> selectedAfterTagIndices = new ArrayList<Integer>();
    protected List<Integer> selectedAfterTagLengths = new ArrayList<Integer>();

    protected boolean ignoreTextChanges = false;

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

    protected String filteredText = "";
    protected String filteredAfterText = "";

    protected boolean tagColorChanged = false;

    protected TagListAdapter tagListAdapter;

    private int chipResId;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));

        super.onCreate(savedInstanceState);

        tracker = ((Application) getApplication()).getTracker();
        tracker.setScreenName(Strings.BULK_EDIT_TAGS_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        database = DatabaseHelper.getInstance(this);

        setContentView(R.layout.bulk_edit_tags_activity);
        setTitle(R.string.bulk_edit_tags_title);
        setupActionBar();
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
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_ADD_TAG)
                        .build());

                if(hasFilteredText() || hasFilteredAfterText()) {
                    addTagFromSearch();
                }
                else {
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
        selectedTagsText.addTextChangedListener(new SelectedTagsTextWatcher(false, selectedTagIds, selectedTagIndices, selectedTagLengths));

        selectedAfterTagsText = (EditText) findViewById(R.id.after_text);
        selectedAfterTagsText.setLongClickable(false);      // Prevent text selection.
        selectedAfterTagsText.addTextChangedListener(new SelectedTagsTextWatcher(true, selectedAfterTagIds, selectedAfterTagIndices, selectedAfterTagLengths));

        afterTagsTextBorder = findViewById(R.id.after_text_border);

        tagsListView.setAdapter(tagListAdapter = new TagListAdapter());
        fetchingTags = true;
        database.fetchTags(null, false, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                tagsMap.clear();
                Cursor cursor = result.cursor;
                while (!cursor.isAfterLast()) {
                    String name = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TAG_NAME_COLUMN));
                    Integer count = cursor.getInt(cursor.getColumnIndex(DatabaseHelper.TRANSIENT_COUNT_COLUMN));
                    TagModel tag = ModelUtils.createTagFromCursor(cursor);
                    tagsMap.put(name, new TagWithCountModel(tag, count));
                    cursor.moveToNext();
                }

                fetchingTags = false;
                if (tagsFetchedListener != null) {
                    tagsFetchedListener.onTagsFetched();
                    tagsFetchedListener = null;
                }

                if (!selectedTagIds.isEmpty()) {
                    setTagSelections(false, false);
                }
                if (!selectedAfterTagIds.isEmpty()) {
                    setTagSelections(false, true);
                }
                updateTagsView();
            }
        });

        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            selectedLocationIds = MiscUtils.convertLongArrayToList(extras.getLongArray(Strings.PARAM_SELECTED_LOCATION_IDS));
        }
        else {
            selectedLocationIds = new ArrayList<>();
        }
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
                finish();
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

    protected void initHelpScreen() {
        helpScreenView = (RelativeLayout) findViewById(R.id.help_screen);
        helpScreenViewPager = (ViewPager) findViewById(R.id.help_screen_viewpager);
        helpScreenPageIndicator = (CircleIndicatorView) findViewById(R.id.help_screen_page_indicator);
        helpScreenPagerAdapter = new ViewPagerAdapter();
        helpScreenButton = (Button) findViewById(R.id.help_screen_button);

        helpScreenPagerAdapter.addView((ViewGroup) getLayoutInflater().inflate(R.layout.bulk_edit_tags_help_screen_1, helpScreenViewPager, false));
        helpScreenPagerAdapter.addView((ViewGroup) getLayoutInflater().inflate(R.layout.bulk_edit_tags_help_screen_2, helpScreenViewPager, false));
        helpScreenPagerAdapter.addView((ViewGroup) getLayoutInflater().inflate(R.layout.bulk_edit_tags_help_screen_3, helpScreenViewPager, false));
        helpScreenViewPager.setAdapter(helpScreenPagerAdapter);
        helpScreenPageIndicator.setViewPager(helpScreenViewPager);

        helpScreenButton.setText(R.string.next);
        helpScreenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int currentPage = helpScreenViewPager.getCurrentItem();
                if (currentPage < helpScreenPagerAdapter.getCount() - 1) {
                    helpScreenViewPager.setCurrentItem(helpScreenViewPager.getCurrentItem() + 1, true);
                }
                else {
                    Versions.update(preferences, Strings.BULK_EDIT_TAGS_HELP_VERSION, Versions.Defaults.BULK_EDIT_TAGS_HELP_VERSION);;
                    helpScreenView.setVisibility(View.GONE);
                    getSupportActionBar().show();
                }
            }
        });
        helpScreenPageIndicator.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) { }

            @Override
            public void onPageSelected(int i) {
                if(i == helpScreenPagerAdapter.getCount() - 1) {
                    helpScreenButton.setText(R.string.got_it);
                }
                else {
                    helpScreenButton.setText(R.string.next);
                }
            }

            @Override
            public void onPageScrollStateChanged(int i) { }
        });

        Versions.check(preferences, Strings.BULK_EDIT_TAGS_HELP_VERSION, Versions.Defaults.BULK_EDIT_TAGS_HELP_VERSION, new Versions.Listener() {
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
        selectedAfterTagIds = new LinkedHashSet<Long>();
        for(Integer i : savedInstanceState.getIntegerArrayList(Strings.PARAM_SELECTED_AFTER_TAG_IDS)) {
            selectedAfterTagIds.add(i.longValue());
        }
        selectedLocationIds = MiscUtils.convertLongArrayToList(savedInstanceState.getLongArray(Strings.PARAM_SELECTED_LOCATION_IDS));

        filteredText = savedInstanceState.getString(Strings.PARAM_FILTERED_TEXT, "");
        filteredAfterText = savedInstanceState.getString(Strings.PARAM_FILTERED_AFTER_TEXT, "");

        tagColorChanged = savedInstanceState.getBoolean(Strings.PARAM_TAG_COLOR_CHANGED);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        ArrayList<Integer> tagIdsList = new ArrayList<>();
        for(Long l : selectedTagIds) {
            tagIdsList.add(l.intValue());
        }
        outState.putIntegerArrayList(Strings.PARAM_SELECTED_TAG_IDS, tagIdsList);

        tagIdsList = new ArrayList<>();
        for(Long l : selectedAfterTagIds) {
            tagIdsList.add(l.intValue());
        }
        outState.putIntegerArrayList(Strings.PARAM_SELECTED_AFTER_TAG_IDS, tagIdsList);

        outState.putLongArray(Strings.PARAM_SELECTED_LOCATION_IDS, MiscUtils.convertLongListToArray(selectedLocationIds));

        outState.putString(Strings.PARAM_FILTERED_TEXT, filteredText);
        outState.putString(Strings.PARAM_FILTERED_AFTER_TEXT, filteredAfterText);
        outState.putBoolean(Strings.PARAM_TAG_COLOR_CHANGED, tagColorChanged);
    }

    protected void saveAndFinish() {
        // Pass the selected tags back to the main activity.
        Intent intent = new Intent();
        intent.putExtra(Strings.PARAM_SELECTED_LOCATION_IDS, new ArrayList<Long>(selectedLocationIds));
        intent.putExtra(Strings.PARAM_SELECTED_TAG_IDS, new ArrayList<Long>(selectedTagIds));
        intent.putExtra(Strings.PARAM_SELECTED_AFTER_TAG_IDS, new ArrayList<Long>(selectedAfterTagIds));
        intent.putExtra(Strings.PARAM_ADDING_TAGS, addingTags());
        intent.putExtra(Strings.PARAM_TAG_COLOR_CHANGED, tagColorChanged);
        setResult(RESULT_OK, intent);
        finish();
    }

    protected boolean hasFilteredText() {
        return !filteredText.isEmpty();
    }

    protected boolean hasFilteredAfterText() {
        return !filteredAfterText.isEmpty();
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
        else if(hasFilteredAfterText()) {
            for(TagWithCountModel tag : tagsMap.values()) {
                if(tag.name.toLowerCase().startsWith(filteredAfterText.toLowerCase())) {
                    tagsList.add(tag);
                }
            }
        }
        else {
            tagsList.addAll(tagsMap.values());
        }

        if(hasFilteredText() || hasFilteredAfterText()) {
            emptyTagsText.setText(R.string.empty_filtered_tags_text);

            String text = hasFilteredText() ? filteredText : filteredAfterText;
            addTagButton.setText(getString(R.string.add_tag_from_search, text));
        }
        else {
            emptyTagsText.setText(R.string.empty_tags_text);
            addTagButton.setText(R.string.add_tag);
        }

        tagListAdapter.notifyDataSetChanged();
    }

    protected void setAllTagSelections(boolean preserveEnteredText) {
        setTagSelections(preserveEnteredText, false);
        setTagSelections(preserveEnteredText, true);
    }

    // This function adds chips to the EditText for all currently selected tags.
    protected void setTagSelections(boolean preserveEnteredText, boolean afterText) {
        ignoreTextChanges = true;
        if(!afterText) {
            if(!preserveEnteredText) {
                filteredText = "";
            }

            selectedTagIndices.clear();
            selectedTagLengths.clear();
            selectedTagsText.setText("");

            for(Long selectedTagId : selectedTagIds) {
                for(TagModel tagModel : tagsMap.values()) {
                    if(selectedTagId.equals(tagModel.getLocalId())) {
                        selectTag(tagModel, false);
                    }
                }
            }

            if(preserveEnteredText) {
                selectedTagsText.append(filteredText);
            }
        }
        else {
            if(!preserveEnteredText) {
                filteredAfterText = "";
            }

            selectedAfterTagIndices.clear();
            selectedAfterTagLengths.clear();
            selectedAfterTagsText.setText("");

            for(Long selectedTagId : selectedAfterTagIds) {
                for(TagModel tagModel : tagsMap.values()) {
                    if(selectedTagId.equals(tagModel.getLocalId())) {
                        selectTag(tagModel, true);
                    }
                }
            }

            if(preserveEnteredText) {
                selectedAfterTagsText.append(filteredAfterText);
            }
        }

        updateTagsView();
        ignoreTextChanges = false;
    }

    protected void selectTag(TagModel tagModel, boolean afterText) {
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        Bitmap bitmap = createBitmapForTag(tagModel);
        BitmapDrawable bd = new BitmapDrawable(bitmap);
        bd.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        sb.append(tagModel.name + " ");
        sb.setSpan(new ImageSpan(bd), 0, tagModel.name.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        if(!afterText) {
            selectedTagIndices.add(selectedTagsText.length());
            selectedTagLengths.add(tagModel.name.length());
            selectedTagsText.append(sb);
        }
        else {
            selectedAfterTagIndices.add(selectedAfterTagsText.length());
            selectedAfterTagLengths.add(tagModel.name.length());
            selectedAfterTagsText.append(sb);
        }
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

    protected void addTagFromSearch() {
        // Create a new tag.
        final String text = hasFilteredText() ? filteredText : filteredAfterText;
        final boolean beforeText = hasFilteredText();
        database.saveTag(new TagModel(null, text), new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                Long tagId = result.id;

                TagWithCountModel tag = new TagWithCountModel(new TagModel(tagId, text), 0);
                tagsMap.put(text, tag);

                if (beforeText) {
                    selectedTagIds.add(tagId);
                    setTagSelections(false, false);
                } else {
                    selectedAfterTagIds.add(tagId);
                    setTagSelections(false, true);
                }
                updateTagsView();

                UIUtils.hideKeyboard(BulkEditTagsActivity.this);
            }
        });
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
                int idx = 0;
                for (TagWithCountModel tag : tagsMap.values()) {
                    if (tag.name.equals(tagName)) {
                        break;
                    }
                    ++idx;
                }
                tentativelyDeletedTagIdx = idx;
                tentativelyDeletedTag = tagsMap.remove(tagName);
                selectedTagIds.remove(tagId);
                setAllTagSelections(true);

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
                                        setAllTagSelections(true);
                                        UIUtils.hideKeyboard(BulkEditTagsActivity.this);
                                    }
                                });
                            } else {
                                // Create a new tag.
                                database.saveTag(new TagModel(null, newTagName), new DatabaseQueryListener() {
                                    @Override
                                    public void onQueryExecuted(DatabaseQueryResult result) {
                                        Long tagId = result.id;
                                        tagsMap.put(newTagName, new TagWithCountModel(new TagModel(tagId, newTagName), 0));
                                        updateTagsView();
                                        UIUtils.hideKeyboard(BulkEditTagsActivity.this);
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
                setAllTagSelections(true);
            }
        });
    }

    private interface Listener {
        void onTagsFetched();
    }

    private class SelectedTagsTextWatcher implements TextWatcher {

        private boolean afterText;

        private Set<Long> tagIds;
        private List<Integer> tagIndices;
        private List<Integer> tagLengths;


        public SelectedTagsTextWatcher(boolean afterText, Set<Long> tagIds, List<Integer> tagIndices, List<Integer> tagLengths) {
            this.afterText = afterText;
            this.tagIds = tagIds;
            this.tagIndices = tagIndices;
            this.tagLengths = tagLengths;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if(ignoreTextChanges) {
                return;
            }

            if(count == before - 1) {
                // 1 character was deleted from the EditText, so check if we can delete a tag.
                for(int i = 0; i < tagLengths.size(); ++i) {
                    int tagStartIdx = tagIndices.get(i);
                    int tagEndIdx = tagIndices.get(i) + tagLengths.get(i);
                    if(start >= tagStartIdx && start <= tagEndIdx) {
                        Long id = tagIds.toArray(new Long[0])[i];
                        tagIds.remove(id);
                        setTagSelections(false, afterText);
                        updateTagsView();
                        return;
                    }
                }

                // If we get to this point, we didn't delete a tag; we deleted one or more characters
                // from the filter text, so re-filter the list of tags.
                if(!afterText) {
                    filteredText = s.toString().substring(start);
                }
                else {
                    filteredAfterText = s.toString().substring(start);
                }
                updateTagsView();
            }
            else if(count > before) {
                // One or more characters were added to the EditText, so check to make sure
                // it's not a tag.
                for(int i = 0; i < tagLengths.size(); ++i) {
                    int tagStartIdx = tagIndices.get(i);
                    int tagEndIdx = tagIndices.get(i) + tagLengths.get(i);
                    if((start + count) >= tagStartIdx && (start + count) <= (tagEndIdx + 1)) {
                        return;
                    }
                }

                // If we get to this point, we didn't add a tag; we added one or more characters
                // to the filter text, so do the following:
                //  - clear out any filter that was set on the other EditText
                //  - re-filter the list of tags
                setTagSelections(false, !afterText);

                int filteredTextStart = 0;
                if(!tagIndices.isEmpty()) {
                    int lastIdx = tagIndices.size() - 1;
                    filteredTextStart = tagIndices.get(lastIdx) + tagLengths.get(lastIdx) + 1;
                }
                if(!afterText) {
                    filteredText = s.toString().substring(filteredTextStart);
                }
                else {
                    filteredAfterText = s.toString().substring(filteredTextStart);
                }
                updateTagsView();
            }
        }

        @Override
        public void afterTextChanged(Editable s) { }
    }

    private class TagListAdapter extends ArrayAdapter<TagModel> {

        public TagListAdapter() {
            super(BulkEditTagsActivity.this, R.layout.tag_item_view);
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
            checkBox.setChecked(selectedTagIds.contains(tag.getLocalId()) || selectedAfterTagIds.contains(tag.getLocalId()));

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
            if(tag.hasIcon()) {
                tagIconImageView.setVisibility(View.VISIBLE);
                Glide.with(BulkEditTagsActivity.this)
                        .load(Uri.fromFile(new File(tag.iconPath)))
                        .fitCenter()
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

            final PopupMenu checkBoxMenu = new PopupMenu(BulkEditTagsActivity.this, checkBox);
            checkBoxMenu.inflate(R.menu.edit_tags_popup_menu);
            checkBoxMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    switch(item.getItemId()) {
                        case R.id.menu_add_before:
                            selectedTagIds.add(tag.getLocalId());

                            setAllTagSelections(false);
                            break;
                        case R.id.menu_add_after:
                            selectedAfterTagIds.add(tag.getLocalId());
                            setAllTagSelections(false);
                            break;
                    }

                    checkBox.toggle();
                    return true;
                }
            });
            tagRowView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(addingTags()) {
                        if(selectedTagIds.contains(tag.getLocalId())) {
                            selectedTagIds.remove(tag.getLocalId());
                            setTagSelections(false, false);
                            checkBox.toggle();
                        }
                        else if(selectedAfterTagIds.contains(tag.getLocalId())) {
                            selectedAfterTagIds.remove(tag.getLocalId());
                            setTagSelections(false, true);
                            checkBox.toggle();
                        }
                        else {
                            checkBoxMenu.show();
                        }
                    }
                    else {
                        if(selectedTagIds.contains(tag.getLocalId())) {
                            selectedTagIds.remove(tag.getLocalId());
                        }
                        else {
                            selectedTagIds.add(tag.getLocalId());
                        }
                        setTagSelections(false, false);
                        checkBox.toggle();
                    }
                }
            });

            return tagRowView;
        }

        protected void showTagItemMenu(final View v, final TagModel tagModel) {
            PopupMenu menu = new PopupMenu(BulkEditTagsActivity.this, v);
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
            Intent intent = new Intent(BulkEditTagsActivity.this, SetMapMarkerActivity.class);
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

    protected boolean addingTags() {
        return getSupportActionBar().getSelectedNavigationIndex() == EditMode.ADD_TAGS.ordinal();
    }

    enum EditMode {
        ADD_TAGS,
        REMOVE_TAGS
    }

    protected void setupActionBar() {
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(
                getSupportActionBar().getThemedContext(),
                R.array.bulk_tag_options,
                android.R.layout.simple_spinner_dropdown_item);

        getSupportActionBar().setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
        getSupportActionBar().setListNavigationCallbacks(spinnerAdapter, new ActionBar.OnNavigationListener() {
            @Override
            public boolean onNavigationItemSelected(int itemPosition, long itemId) {
                if(itemPosition == EditMode.ADD_TAGS.ordinal()) {
                    selectedTagsText.setHint(R.string.add_before_tags_hint_text);
                    selectedAfterTagsText.setVisibility(View.VISIBLE);
                    afterTagsTextBorder.setVisibility(View.VISIBLE);
                }
                else {
                    selectedAfterTagIds.clear();
                    setTagSelections(false, true);

                    selectedTagsText.setHint(R.string.remove_tags_hint_text);
                    selectedAfterTagsText.setVisibility(View.GONE);
                    afterTagsTextBorder.setVisibility(View.GONE);
                }

                return true;
            }
        });
        getSupportActionBar().setDisplayShowTitleEnabled(false);
    }

    protected void setupBottomBar() {
        RelativeLayout cancelButton = (RelativeLayout) findViewById(R.id.action_cancel);
        if(cancelButton != null) {
            cancelButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_CANCEL_BULK_EDIT_TAGS)
                            .build());

                    finish();
                }
            });
        }

        RelativeLayout saveButton = (RelativeLayout) findViewById(R.id.action_save);
        if(saveButton != null) {
            saveButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(addingTags() ? Strings.LBL_SAVE_BULK_ADD_TAGS : Strings.LBL_SAVE_BULK_REMOVE_TAGS)
                            .build());

                    saveAndFinish();
                }
            });
        }
    }

}
