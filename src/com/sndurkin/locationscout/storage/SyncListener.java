package com.sndurkin.locationscout.storage;


import com.sndurkin.locationscout.PhotoInfo;

public abstract class SyncListener {

    public void onStarted() { }

    // This is only called if the sync finished without exception.
    public void onFinished() { }

    public void onModelCreated() { }

    public void onModelUpdated() { }

    public void onModelDeleted() { }

    public void onModelSkipped() { }

    // This is only used for testing.
    public void beforePhotosSynced(PhotoInfo localPhoto, PhotoInfo remotePhoto) { }

    // This is called instead of onFinished() if there is any exception
    // thrown while syncing.
    public void onException(Exception e) { }

}
