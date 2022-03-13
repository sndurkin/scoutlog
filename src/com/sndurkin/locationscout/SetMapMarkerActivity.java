package com.sndurkin.locationscout;

import android.accounts.Account;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
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
import com.sndurkin.locationscout.util.FileUtils;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import java.io.File;

public class SetMapMarkerActivity extends AppCompatActivity {

    protected Tracker tracker;
    protected SharedPreferences preferences;
    protected DatabaseHelper database;

    protected TagModel tag;

    protected RadioGroup markerRadioGroup;
    protected RadioButton defaultMarkerRadio;
    protected RadioButton customMarkerRadio;

    protected LinearLayout selectColorButton;
    protected ImageView colorPreview;
    protected TextView colorText;

    protected LinearLayout selectImageButton;
    protected ImageView imagePreview;

    protected LinearLayout errorContainer;
    protected TextView errorText;
    protected LinearLayout syncErrorContainer;
    protected Button syncNowButton;
    protected ProgressBar syncProgressBar;
    protected Account account;

    protected boolean userMadeChanges = false;

    private BroadcastReceiver broadcastReceiver;
    private GlobalBroadcastManager broadcastManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));

        super.onCreate(savedInstanceState);

        tracker = ((Application) getApplication()).getTracker();
        tracker.setScreenName(Strings.SET_MAP_MARKER_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        database = DatabaseHelper.getInstance(this);

        broadcastReceiver = new BroadcastReceiver();
        broadcastManager = GlobalBroadcastManager.getInstance(this);

        setContentView(R.layout.set_map_marker_activity);
        setTitle(R.string.set_map_marker);
        setupBottomBar();

        markerRadioGroup = (RadioGroup) findViewById(R.id.marker_radio_group);
        defaultMarkerRadio = (RadioButton) findViewById(R.id.default_marker_type_radio);
        customMarkerRadio = (RadioButton) findViewById(R.id.custom_marker_type_radio);

        selectColorButton = (LinearLayout) findViewById(R.id.select_color_btn);
        colorPreview = (ImageView) findViewById(R.id.color_preview);
        colorText = (TextView) findViewById(R.id.color_text);

        selectImageButton = (LinearLayout) findViewById(R.id.select_image_btn);
        imagePreview = (ImageView) findViewById(R.id.image_preview);

        errorContainer = (LinearLayout) findViewById(R.id.error_container);
        errorText = (TextView) findViewById(R.id.error_text);
        syncErrorContainer = (LinearLayout) findViewById(R.id.sync_error_container);
        syncNowButton = (Button) findViewById(R.id.sync_now_button);
        syncProgressBar = (ProgressBar) findViewById(R.id.sync_progress_bar);

        selectColorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openColorPickerDialog();
            }
        });
        selectImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFilePicker();
            }
        });
        syncNowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SyncSettingsFragment.forceSync(account);
            }
        });

        markerRadioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                switch(checkedId) {
                    case R.id.default_marker_type_radio:
                        if(tag.hasIcon()) {
                            userMadeChanges = true;
                        }
                        selectColorButton.setVisibility(View.VISIBLE);
                        selectImageButton.setVisibility(View.GONE);
                        updateMarkerColor();
                        break;
                    case R.id.custom_marker_type_radio:
                        if(!tag.hasIcon()) {
                            userMadeChanges = true;
                        }
                        selectColorButton.setVisibility(View.GONE);
                        selectImageButton.setVisibility(View.VISIBLE);
                        updateMarkerIcon();
                        break;
                }
            }
        });

        if(savedInstanceState != null) {
            tag = new TagModel();
            tag.setLocalId(savedInstanceState.getLong("id"));
            tag.name = savedInstanceState.getString("name");
            tag.color = savedInstanceState.getInt("color");
            if(savedInstanceState.containsKey("iconPath")) {
                tag.iconPath = savedInstanceState.getString("iconPath");
            }
            userMadeChanges = savedInstanceState.getBoolean("userMadeChanges", false);
            onTagFetched();
        }
        else {
            Bundle extras = getIntent().getExtras();
            if(extras != null) {
                if(extras.containsKey(Strings.TAG_ID)) {
                    Long tagId = extras.getLong(Strings.TAG_ID);
                    database.fetchTag(tagId, new DatabaseQueryListener() {
                        @Override
                        public void onQueryExecuted(DatabaseQueryResult result) {
                            tag = ModelUtils.createTagFromCursor(result.cursor);
                            onTagFetched();
                        }
                    });
                }
            }
        }
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

    @Override
    public void onBackPressed() {
        cancelWithWarning();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("id", tag.getLocalId());
        outState.putString("name", tag.name);
        outState.putInt("color", tag.hasColor() ? tag.color : 0);
        if(tag.hasIcon()) {
            outState.putString("iconPath", tag.iconPath);
        }
        outState.putBoolean("userMadeChanges", userMadeChanges);
    }

    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST);
        broadcastManager.registerReceiver(SetMapMarkerActivity.class.getCanonicalName(), broadcastReceiver, intentFilter, true);
    }

    @Override
    public void onPause() {
        broadcastManager.unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    protected void onTagFetched() {
        if(tag.hasIcon()) {
            // Custom marker
            markerRadioGroup.check(R.id.custom_marker_type_radio);
        }
        else {
            // Default marker
            markerRadioGroup.check(R.id.default_marker_type_radio);
        }
    }

    protected void updateMarkerColor() {
        hideError();

        GradientDrawable colorPreviewBkgrnd = ((GradientDrawable) colorPreview.getBackground());
        if(tag.hasColor()) {
            // Custom marker color
            colorText.setText(R.string.custom_color);
            colorPreviewBkgrnd.setColor(tag.color);
        }
        else {
            // Default marker color
            colorText.setText(R.string.default_color);
            colorPreviewBkgrnd.setColor(preferences.getInt(Strings.PREF_MAP_MARKER_COLOR, Color.RED));
        }
        colorPreview.postInvalidate();
    }

    protected void updateMarkerIcon() {
        hideError();

        if(tag.hasIcon()) {
            File iconFile = new File(tag.iconPath);
            if(iconFile.exists()) {
                Glide.with(this)
                        .load(Uri.fromFile(iconFile))
                        .fitCenter()
                        .placeholder(R.color.gallery_placeholder)
                        .into(imagePreview);
            }
            else {
                Glide.with(this)
                        .load(R.drawable.missing_icon)
                        .fitCenter()
                        .into(imagePreview);

                errorContainer.setVisibility(View.VISIBLE);
                errorText.setText(R.string.custom_icon_not_found_error);

                String accountName = preferences.getString(Strings.PREF_ACCOUNT, null);
                if(accountName != null) {
                    account = new GoogleAccountManager(this).getAccountByName(accountName);
                    if (account != null) {
                        syncErrorContainer.setVisibility(View.VISIBLE);
                        return;
                    }
                };
            }
        }
    }

    protected void hideError() {
        errorContainer.setVisibility(View.GONE);
        syncErrorContainer.setVisibility(View.GONE);
    }

    protected void openColorPickerDialog() {
        int tagColor = tag.hasColor() ? tag.color
                                      : preferences.getInt(Strings.PREF_MAP_MARKER_COLOR, Color.RED);
        final ColorPickerDialog colorPickerDialog = new ColorPickerDialog(this, tagColor);
        colorPickerDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                colorPickerDialog.dismiss();
            }
        });
        colorPickerDialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.use_default), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_USE_DEFAULT_COLOR)
                        .build());

                colorPickerDialog.dismiss();
                tag.color = 0;
                userMadeChanges = true;
                updateMarkerColor();
            }
        });
        colorPickerDialog.setButton(AlertDialog.BUTTON_POSITIVE, this.getString(R.string.select), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_SELECT_TAG_COLOR)
                        .build());

                colorPickerDialog.dismiss();
                tag.color = colorPickerDialog.getColor();
                userMadeChanges = true;
                updateMarkerColor();
            }
        });
        colorPickerDialog.show();
    }

    protected void openFilePicker() {
        Intent filePickerIntent = new Intent(Intent.ACTION_GET_CONTENT);
        filePickerIntent.setType("image/*");
        filePickerIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        startActivityForResult(filePickerIntent, RequestCodes.SELECT_IMAGE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch(requestCode) {
            case RequestCodes.SELECT_IMAGE:
                if(resultCode == Activity.RESULT_OK) {
                    // Set the image preview.
                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        ClipData clipData = data.getClipData();
                        if(clipData != null && clipData.getItemCount() > 0) {
                            Uri imageUri = clipData.getItemAt(0).getUri();
                            setImageFromUri(imageUri);
                            return;
                        }
                    }

                    if(data.getData() != null) {
                        setImageFromUri(data.getData());
                    }
                }
                break;
        }
    }

    protected void setImageFromUri(Uri imageUri) {
        String filePath = FileUtils.getFilePathFromUri(imageUri, true);
        if(filePath == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.photo_not_loaded_title)
                    .setMessage(R.string.photo_not_loaded_message)
                    .setNeutralButton(android.R.string.ok, null)
                    .show();
            return;
        }
        tag.iconPath = Uri.parse(filePath).getPath();
        userMadeChanges = true;
        updateMarkerIcon();
    }

    protected void setupBottomBar() {
        ActionBar actionBar = getSupportActionBar();
        if(actionBar == null) {
            return;
        }
        actionBar.setDisplayHomeAsUpEnabled(true);

        RelativeLayout cancelButton = (RelativeLayout) findViewById(R.id.action_cancel);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_CANCEL_MAP_MARKER_EDIT)
                        .build());
                cancelAndFinish();
            }
        });

        RelativeLayout saveButton = (RelativeLayout) findViewById(R.id.action_save);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndFinish();
            }
        });
    }

    protected void cancelWithWarning() {
        if(userMadeChanges && preferences.getBoolean(Strings.PREF_NOTIFY_UNSAVED_CHANGES, true)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.notify_unsaved_tags_dialog_title)
                    .setMessage(R.string.notify_unsaved_tags_dialog_message)
                    .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_CANCEL_MAP_MARKER_EDIT_YES)
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
                                    .setLabel(Strings.LBL_CANCEL_MAP_MARKER_EDIT_NO)
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
                                    .setLabel(Strings.LBL_CANCEL_MAP_MARKER_EDIT_NEVER_AGAIN)
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
        setResult(RESULT_CANCELED, null);
        finish();
    }

    protected void saveAndFinish() {
        if(markerRadioGroup.getCheckedRadioButtonId() == R.id.custom_marker_type_radio && tag.iconPath == null) {
            tracker.send(new HitBuilders.EventBuilder()
                    .setCategory(Strings.CAT_UI_ACTION)
                    .setAction(Strings.ACT_CLICK_BUTTON)
                    .setLabel(Strings.LBL_SAVE_MAP_MARKER_EDIT_NO_IMAGE)
                    .build());

            errorContainer.setVisibility(View.VISIBLE);
            errorText.setText(R.string.missing_selected_image_error);
            return;
        }

        String label = tag.hasIcon() ? Strings.LBL_SAVE_MAP_MARKER_EDIT_WITH_ICON
                                     : Strings.LBL_SAVE_MAP_MARKER_EDIT_WITH_COLOR;
        tracker.send(new HitBuilders.EventBuilder()
                .setCategory(Strings.CAT_UI_ACTION)
                .setAction(Strings.ACT_CLICK_BUTTON)
                .setLabel(label)
                .build());

        Intent data = new Intent();
        data.putExtra("name", tag.name);
        switch(markerRadioGroup.getCheckedRadioButtonId()) {
            case R.id.default_marker_type_radio:
                tag.iconPath = "";
                tag.remoteIconId = "";
                break;
            case R.id.custom_marker_type_radio:
                tag.color = 0;
                break;
        }
        data.putExtra("color", tag.color);
        data.putExtra("iconPath", tag.iconPath);
        setResult(RESULT_OK, data);
        finish();
    }

    private class BroadcastReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST.equals(action)) {
                int requestCode = intent.getIntExtra("requestCode", -1);
                if(requestCode == RequestCodes.SYNC_STARTED) {
                    syncProgressBar.setVisibility(View.VISIBLE);
                }
                else {
                    if(requestCode == RequestCodes.SYNC_FINISHED) {
                        database.fetchTag(tag.getLocalId(), new DatabaseQueryListener() {
                            @Override
                            public void onQueryExecuted(DatabaseQueryResult result) {
                                tag = ModelUtils.createTagFromCursor(result.cursor);
                                updateMarkerIcon();
                            }
                        });
                    }

                    syncProgressBar.setVisibility(View.GONE);
                    broadcastManager.removeLastBroadcast(SetMapMarkerActivity.class.getCanonicalName(), action);
                }
            }
        }
    }

}
