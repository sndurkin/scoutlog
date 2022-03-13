package com.sndurkin.locationscout;

import android.content.Context;

import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.GenericLoaderFactory;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.stream.StreamModelLoader;
import com.google.api.services.drive.Drive;

import java.io.InputStream;


public class DriveModelLoader implements StreamModelLoader<String> {

    private final Drive driveService;
    private final boolean useThumbnail;

    public DriveModelLoader(Drive driveService, boolean useThumbnail) {
        this.driveService = driveService;
        this.useThumbnail = useThumbnail;
    }

    @Override
    public DataFetcher<InputStream> getResourceFetcher(String fileId, int width, int height) {
        return new DriveDataFetcher(driveService, fileId, useThumbnail);
    }

    public static class Factory implements ModelLoaderFactory<String, InputStream> {

        private final Drive driveService;
        private final boolean useThumbnail;

        public Factory(Drive driveService, boolean useThumbnail) {
            this.driveService = driveService;
            this.useThumbnail = useThumbnail;
        }

        @Override
        public ModelLoader<String, InputStream> build(Context context, GenericLoaderFactory factories) {
            return new DriveModelLoader(driveService, useThumbnail);
        }

        @Override
        public void teardown() {

        }
    }

}
