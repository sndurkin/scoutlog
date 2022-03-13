package com.sndurkin.locationscout;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.util.SortedList;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.util.SortedListAdapterCallback;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.util.FileUtils;
import com.sndurkin.locationscout.util.PhotoFileCreateException;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.MoveFilesAsyncTask;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import java.io.File;


// Allows the user to select a folder; used to choose the photo storage folder.
public class SelectFolderActivity extends AppCompatActivity
                                  implements LoaderManager.LoaderCallbacks<SortedList<File>> {

    protected Tracker tracker;
    protected SharedPreferences preferences;
    protected GlobalBroadcastManager broadcastManager;
    protected BroadcastReceiver broadcastReceiver;

    protected RecyclerView recyclerView;
    protected RecyclerView.LayoutManager layoutManager;
    protected FolderItemAdapter adapter;

    protected LinearLayout createFolderButton;
    protected View afterCreateFolderBorder;

    protected FloatingActionButton selectButton;

    protected ProgressDialog movingPhotosDialog;

    protected TextView currentPathText;
    protected File currentFolder;
    protected boolean isLoading;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));

        super.onCreate(savedInstanceState);

        tracker = ((Application) getApplication()).getTracker();
        tracker.setScreenName(Strings.SELECT_FOLDER_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        broadcastManager = GlobalBroadcastManager.getInstance(this);
        broadcastReceiver = new BroadcastReceiver();

        setContentView(R.layout.select_folder_activity);
        setTitle(R.string.select_folder_title);

        recyclerView = (RecyclerView) findViewById(R.id.folder_list);
        if(recyclerView != null) {
            recyclerView.setHasFixedSize(true);

            layoutManager = new LinearLayoutManager(this);
            recyclerView.setLayoutManager(layoutManager);

            adapter = new FolderItemAdapter();
            recyclerView.setAdapter(adapter);
        }

        currentPathText = (TextView) findViewById(R.id.current_path_text);
        createFolderButton = (LinearLayout) findViewById(R.id.create_folder_action);
        afterCreateFolderBorder = findViewById(R.id.after_create_folder_border);

        createFolderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_CREATE_APP_FOLDER)
                        .build());

                File appFolder = new File(currentFolder, MiscUtils.APP_FOLDER);
                if(!appFolder.mkdir()) {
                    CrashlyticsCore.getInstance().logException(new RuntimeException("Could not create new directory at " + currentFolder.getAbsolutePath()));
                    Toast.makeText(SelectFolderActivity.this, R.string.cannot_create_folder, Toast.LENGTH_LONG).show();
                    return;
                }

                navigateToFolder(appFolder);
            }
        });

        setupActionBar();
        setupBottomBar();

        if(savedInstanceState != null) {
            String currentPath = savedInstanceState.getString("currentPath");
            if(currentPath != null) {
                currentFolder = new File(currentPath);
                navigateToFolder(currentFolder);
                return;
            }
        }

        File photoDir;
        try {
            photoDir = FileUtils.getPhotoDirChecked(this);
        }
        catch(PhotoFileCreateException e) {
            photoDir = new File("/");
        }

        navigateToFolder(photoDir);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MoveFilesAsyncTask.FILES_MOVE_COMPLETED);
        broadcastManager.registerReceiver(SelectFolderActivity.class.getCanonicalName(), broadcastReceiver, intentFilter, false);
    }

    @Override
    protected void onPause() {
        super.onPause();

        broadcastManager.unregisterReceiver(broadcastReceiver);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString("currentPath", currentFolder.getAbsolutePath());
    }

    protected void saveAndFinish() {
        File pathToSave = currentFolder;
        if(!pathToSave.getName().equals(MiscUtils.APP_FOLDER)) {
            File appFolder = new File(pathToSave.getAbsolutePath(), MiscUtils.APP_FOLDER);
            appFolder.mkdir();
            pathToSave = appFolder;
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(Strings.PREF_PHOTOS_LOCATION, pathToSave.getAbsolutePath());
        editor.apply();

        Intent data = new Intent();
        data.putExtra(Strings.PREF_PHOTOS_LOCATION, pathToSave.getAbsolutePath());
        setResult(RESULT_OK, data);

        finish();
    }

    protected void movePhotosAndSaveAndFinish(String oldFolder) {
        movingPhotosDialog = ProgressDialog.show(this, getString(R.string.moving_photos_message), null, true);
        new MoveFilesAsyncTask(this).execute(oldFolder, currentFolder.getAbsolutePath());
    }

    protected void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.setHomeAsUpIndicator(R.drawable.close_dark);
        }
    }

    protected void setupBottomBar() {
        selectButton = (FloatingActionButton) findViewById(R.id.action_select);
        if(selectButton != null) {
            selectButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_SELECT_FOLDER)
                            .build());

                    final String oldFolderPath = preferences.getString(Strings.PREF_PHOTOS_LOCATION, FileUtils.getDefaultPhotoDir().getAbsolutePath());
                    if(!oldFolderPath.equals(currentFolder.getAbsolutePath())) {
                        File oldFolder = new File(oldFolderPath);
                        File[] files = oldFolder.listFiles();
                        if(files != null && files.length > 0) {
                            new AlertDialog.Builder(SelectFolderActivity.this)
                                    .setTitle(R.string.move_photos_title)
                                    .setMessage(R.string.move_photos_message)
                                    .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            movePhotosAndSaveAndFinish(oldFolderPath);
                                        }
                                    })
                                    .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            saveAndFinish();
                                        }
                                    })
                                    .show();
                        }
                        else {
                            saveAndFinish();
                        }
                    }
                    else {
                        saveAndFinish();
                    }
                }
            });
        }
    }

    protected void navigateToFolder(File path) {
        if(isLoading) {
            return;
        }
        isLoading = true;

        currentFolder = path;
        currentPathText.setText(currentFolder.getAbsolutePath());

        // Display the FAB depending on whether or not we can write in the current directory.
        if(currentFolder.canWrite()) {
            selectButton.show();
        }
        else {
            selectButton.hide();
        }

        getSupportLoaderManager().restartLoader(0, null, this);
    }

    @Override
    public Loader<SortedList<File>> onCreateLoader(int id, Bundle args) {
        return new AsyncTaskLoader<SortedList<File>>(this) {

            @Override
            public SortedList<File> loadInBackground() {
                File[] listFiles = currentFolder.listFiles();
                final int numFiles = listFiles == null ? 0 : listFiles.length;

                SortedList<File> files = new SortedList<>(File.class, new SortedListAdapterCallback<File>(new FolderItemAdapter()) {
                    @Override
                    public int compare(File lhs, File rhs) {
                        if(lhs == null) {
                            return -1;
                        }
                        if(rhs == null) {
                            return 1;
                        }

                        return lhs.getName().compareToIgnoreCase(rhs.getName());
                    }

                    @Override
                    public boolean areContentsTheSame(File file, File file2) {
                        return file.getAbsolutePath().equals(file2.getAbsolutePath())
                               && (file.isFile() == file2.isFile());
                    }

                    @Override
                    public boolean areItemsTheSame(File file, File file2) {
                        return areContentsTheSame(file, file2);
                    }
                }, numFiles);

                files.beginBatchedUpdates();
                if (listFiles != null) {
                    for(File f : listFiles) {
                        if(f.isDirectory()) {
                            files.add(f);
                        }
                    }
                }
                files.endBatchedUpdates();

                return files;
            }

            @Override
            protected void onStartLoading() {
                super.onStartLoading();

                if(currentFolder == null) {
                    currentFolder = new File("/");
                }

                forceLoad();
            }

        };
    }

    @Override
    public void onLoaderReset(Loader<SortedList<File>> loader) {
        isLoading = false;
        adapter.setFolderList(null);
    }

    @Override
    public void onLoadFinished(Loader<SortedList<File>> loader, SortedList<File> data) {
        isLoading = false;
        adapter.setFolderList(data);
        layoutManager.scrollToPosition(0);

        // Display the create folder button depending on whether or not the ScoutLog folder
        // already exists in the current directory.
        boolean shouldShowCreateFolderButton = currentFolder.canWrite();
        if(shouldShowCreateFolderButton) {
            if(currentFolder.getName().equals(MiscUtils.APP_FOLDER)) {
                shouldShowCreateFolderButton = false;
            }
            else {
                for(int i = 0; i < data.size(); ++i) {
                    File file = data.get(i);
                    if(file.getName().equals(MiscUtils.APP_FOLDER)) {
                        shouldShowCreateFolderButton = false;
                        break;
                    }
                }
            }
        }

        createFolderButton.setVisibility(shouldShowCreateFolderButton ? View.VISIBLE : View.GONE);
        afterCreateFolderBorder.setVisibility(shouldShowCreateFolderButton ? View.VISIBLE : View.GONE);
    }

    class BroadcastReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if(MoveFilesAsyncTask.FILES_MOVE_COMPLETED.equals(action)) {
                if(intent.getBooleanExtra("success", true)) {
                    movingPhotosDialog.dismiss();
                    movingPhotosDialog = null;

                    saveAndFinish();
                }
                else {
                    new AlertDialog.Builder(SelectFolderActivity.this)
                            .setTitle(R.string.move_photos_error_title)
                            .setMessage(R.string.move_photos_error_message)
                            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    saveAndFinish();
                                }
                            })
                            .show();
                }
            }
        }
    }

    private class FolderItemAdapter extends RecyclerView.Adapter<FolderItemAdapter.ViewHolder> {

        protected SortedList<File> folderList;

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.folder_item_view, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, final int position) {
            if(folderList == null) {
                return;
            }

            if(!isCurrentPathRoot()) {
                if(position == 0) {
                    holder.folderIcon.setImageResource(R.drawable.navigate_up_folder);
                    holder.folderText.setText(R.string.navigate_up_folder);
                    holder.folderItem.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if(currentFolder.getAbsolutePath().equals(Environment.getExternalStorageDirectory().getAbsolutePath()) ||
                               currentFolder.getAbsolutePath().equals("/")) {
                                navigateToFolder(new File("/"));
                            }
                            else {
                                navigateToFolder(currentFolder.getParentFile());
                            }
                        }
                    });
                }
                else {
                    holder.folderIcon.setImageResource(R.drawable.folder);
                    holder.folderText.setText(folderList.get(position - 1).getName());
                    holder.folderItem.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            navigateToFolder(folderList.get(position - 1));
                        }
                    });
                }
            }
            else {
                holder.folderIcon.setImageResource(R.drawable.folder);
                holder.folderText.setText(folderList.get(position).getName());
                holder.folderItem.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        navigateToFolder(folderList.get(position));
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            int numFolders = folderList != null ? folderList.size() : 0;
            if(!isCurrentPathRoot()) {
                // The extra element is for the navigate-to-parent list item.
                return numFolders + 1;
            }
            else {
                return numFolders;
            }
        }

        protected boolean isCurrentPathRoot() {
            return currentFolder != null && currentFolder.getAbsolutePath().equals("/");
        }

        public void setFolderList(SortedList<File> folderList) {
            this.folderList = folderList;
            notifyDataSetChanged();

        }

        public class ViewHolder extends RecyclerView.ViewHolder {

            public View folderItem;
            public ImageView folderIcon;
            public TextView folderText;

            public ViewHolder(View view) {
                super(view);

                folderItem = view.findViewById(R.id.folder_item);
                folderIcon = (ImageView) view.findViewById(R.id.folder_icon);
                folderText = (TextView) view.findViewById(R.id.folder_text);
            }
        }
    }

}
