package com.sndurkin.locationscout;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import java.text.DateFormat;
import java.util.Date;

// This class displays an EditText with Save/Cancel buttons and supports
// sending back the note text to the calling activity.
public class EditNoteActivity extends AppCompatActivity {

    private Tracker tracker;
    private SharedPreferences preferences;

    private Long photoId;
    private EditText noteText;

    private String originalNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));

        super.onCreate(savedInstanceState);

        tracker = Application.getInstance().getTracker();
        tracker.setScreenName(Strings.EDIT_NOTE_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        setTitle(R.string.edit_note);
        setContentView(R.layout.edit_note_activity);

        noteText = (EditText) findViewById(R.id.note_text);

        setupBottomBar();

        // This covers the usecase where the user just opened the activity from the detail screen.
        String note = null;
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            if(extras.containsKey(Strings.PARAM_NOTE)) {
                originalNote = note = extras.getString(Strings.PARAM_NOTE);
            }
            if(extras.containsKey(Strings.PHOTO_ID)) {
                photoId = extras.getLong(Strings.PHOTO_ID);
            }
        }

        // This covers the usecase where the user just rotated his device.
        if(savedInstanceState != null) {
            if(savedInstanceState.containsKey(Strings.PARAM_NOTE)) {
                note = savedInstanceState.getString(Strings.PARAM_NOTE);
            }
            if(savedInstanceState.containsKey(Strings.PHOTO_ID)) {
                photoId = savedInstanceState.getLong(Strings.PHOTO_ID);
            }
            originalNote = savedInstanceState.getString("originalNote");
        }

        if(note != null) {
            noteText.setText(note);
            noteText.setSelection(noteText.length());
        }
        noteText.requestFocus();
    }

    @Override
    public void onBackPressed() {
        cancelWithWarning();
    }

    @Override
    public void onSaveInstanceState(Bundle outState, PersistableBundle outPersistentState) {
        super.onSaveInstanceState(outState, outPersistentState);

        outState.putString("originalNote", originalNote);
        outState.putString(Strings.PARAM_NOTE, noteText.getText().toString());
        if(photoId != null) {
            outState.putLong(Strings.PHOTO_ID, photoId);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.edit_note_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                cancelWithWarning();
                return true;
            case R.id.menu_copy_date:
            case R.id.menu_copy_time:
            case R.id.menu_copy_datetime:
                DateFormat dateFormat;
                if(item.getItemId() == R.id.menu_copy_date) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_MENU_ITEM)
                            .setLabel(Strings.LBL_COPY_DATE)
                            .build());

                    dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM);
                }
                else if(item.getItemId() == R.id.menu_copy_time) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_MENU_ITEM)
                            .setLabel(Strings.LBL_COPY_TIME)
                            .build());

                    dateFormat = DateFormat.getTimeInstance(DateFormat.SHORT);
                }
                else if(item.getItemId() == R.id.menu_copy_datetime) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_MENU_ITEM)
                            .setLabel(Strings.LBL_COPY_DATETIME)
                            .build());

                    dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
                }
                else {
                    return false;
                }

                ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboardManager.setPrimaryClip(ClipData.newPlainText("Current date/time", dateFormat.format(new Date())));
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                return true;
        }

        return false;
    }

    protected void setupBottomBar() {
        RelativeLayout cancelButton = (RelativeLayout) findViewById(R.id.action_cancel);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_CANCEL_NOTE_EDIT)
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
                        .setLabel(Strings.LBL_SAVE_NOTE_EDIT)
                        .build());
                saveAndFinish();
            }
        });
    }

    protected void cancelWithWarning() {
        if(!noteText.getText().toString().equals(originalNote) && preferences.getBoolean(Strings.PREF_NOTIFY_UNSAVED_CHANGES, true)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.notify_unsaved_tags_dialog_title)
                    .setMessage(R.string.notify_unsaved_tags_dialog_message)
                    .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_CANCEL_NOTE_EDIT_YES)
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
                                    .setLabel(Strings.LBL_CANCEL_NOTE_EDIT_NO)
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
                                    .setLabel(Strings.LBL_CANCEL_NOTE_EDIT_NEVER_AGAIN)
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
        Intent data = new Intent();
        data.putExtra(Strings.PARAM_NOTE, noteText.getText().toString());
        if(photoId != null) {
            data.putExtra(Strings.PHOTO_ID, photoId);
        }
        setResult(RESULT_OK, data);
        finish();
    }

}
