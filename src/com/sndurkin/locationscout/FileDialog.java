package com.sndurkin.locationscout;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileDialog {

    public interface FileSelectedListener {
        void fileSelected(File file);
    }
    private FileSelectedListener listener = null;

    private final Context context;
    private int titleResId;
    private String fileExtension;
    private AlertDialog dialog;

    public FileDialog(Activity context, int titleResId, String fileExtension, FileSelectedListener listener) {
        this.context = context;
        this.titleResId = titleResId;
        this.fileExtension = fileExtension.toLowerCase();
        this.listener = listener;
    }

    public Dialog show() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(titleResId);
        builder.setItems(new String[] { context.getString(R.string.import_loading_files) }, null);
        dialog = builder.show();
        new AddFilesTask().execute();
        return dialog;
    }

    class AddFilesTask extends AsyncTask<Void, Void, Void> {

        private List<String> filenames = new ArrayList<String>();
        private List<String> filePaths = new ArrayList<String>();

        @Override
        protected Void doInBackground(Void... params) {
            addFilesInDirectory(Environment.getExternalStorageDirectory());
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            dialog.getListView().setAdapter(new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, filenames));
            dialog.getListView().setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    File selectedFile = new File(filePaths.get(position));
                    if (listener != null) {
                        listener.fileSelected(selectedFile);
                    }
                    dialog.dismiss();
                }
            });
        }

        private void addFilesInDirectory(File currentDir) {
            if(currentDir.exists()) {
                for(File file : currentDir.listFiles()) {
                    if(!file.canRead()) {
                        continue;
                    }

                    if(file.isDirectory()) {
                        addFilesInDirectory(file);
                    }
                    else if(file.getName().toLowerCase().endsWith(fileExtension)) {
                        filenames.add(file.getName());
                        filePaths.add(file.getAbsolutePath());
                    }
                }
            }
        }

    }

}