package com.sndurkin.locationscout.util;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.common.io.Files;
import com.sndurkin.locationscout.GlobalBroadcastManager;
import com.sndurkin.locationscout.storage.DatabaseHelper;

import java.io.File;
import java.io.IOException;


public class MoveFilesAsyncTask extends AsyncTask<String, Void, Boolean> {

    public static final String FILES_MOVE_COMPLETED = "com.sndurkin.locationscout.util.MoveFilesAsyncTask.FILES_MOVE_COMPLETED";

    private Context context;
    private GlobalBroadcastManager broadcastManager;

    public MoveFilesAsyncTask(Context context) {
        this.context = context;
        this.broadcastManager = GlobalBroadcastManager.getInstance(context);
    }

    @Override
    protected Boolean doInBackground(String... params) {
        DatabaseHelper database = DatabaseHelper.getInstance(context);

        String srcFolder = params[0];
        String destFolder = params[1];

        try {
            File[] files = new File(srcFolder).listFiles();
            for(int i = 0; i < files.length; ++i) {
                File srcFile = files[i];
                File destFile = new File(destFolder, srcFile.getName());
                Files.move(srcFile, destFile);
                database.updatePhotoLocation(srcFile.getAbsolutePath(), destFile.getAbsolutePath());
            }

            return true;
        }
        catch(IOException e) {
            CrashlyticsCore.getInstance().logException(e);
            return false;
        }
    }

    @Override
    protected void onPostExecute(Boolean success) {
        Intent intent = new Intent(FILES_MOVE_COMPLETED);
        intent.putExtra("success", success);
        broadcastManager.sendBroadcast(intent);
    }
}
