package com.sndurkin.locationscout;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.data.DataFetcher;
import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;


public class DriveDataFetcher implements DataFetcher<InputStream> {

    private final Drive driveService;
    private final String fileId;

    private boolean useThumbnail;

    private boolean cancelled = false;

    private InputStream inputStream;

    public DriveDataFetcher(Drive driveService, String fileId, boolean useThumbnail) {
        this.driveService = driveService;
        this.fileId = fileId;
        this.useThumbnail = useThumbnail;
    }

    @Override
    public String getId() {
        return fileId;
    }

    @Override
    public InputStream loadData(Priority priority) {
        if (cancelled || driveService == null) {
            return null;
        }

        try {
            if(useThumbnail) {
                File file = driveService.files().get(fileId).setFields("thumbnail").execute();
                if(file.getThumbnail() != null) {
                    return new ByteArrayInputStream(file.getThumbnail().decodeImage());
                }
            }

            return driveService.files().get(fileId).executeMediaAsInputStream();
        }
        catch(IOException e) {
            // Ignore for now.
        }

        return null;
    }

    @Override
    public void cancel() {
        cancelled = true;
        cleanup();
    }

    @Override
    public void cleanup() {
        if (inputStream != null) {
            try {
                inputStream.close();
            }
            catch(IOException e) {
                // Ignore for now.
            }
            finally {
                inputStream = null;
            }
        }
    }

}
