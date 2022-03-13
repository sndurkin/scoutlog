package com.sndurkin.locationscout.storage;


import android.support.annotation.NonNull;

import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.util.PhotoFileCreateException;

import java.io.IOException;
import java.util.List;

public interface Syncer {

    void preSync() throws Exception;
    void postSync();

    Long getLastModifiedTimeSinceLastSync();
    int getNumFilesChanged();

    TagModel getChangedTag(TagModel localTag) throws IOException;
    @NonNull List<TagModel> getNewTags(List<String> remoteTagIdsAlreadyLocal) throws IOException;

    // Creates the tag file in the cloud and returns a TagModel with remote information set.
    TagModel createTag(TagModel localTag) throws IOException;

    // Updates the tag file in the cloud and returns the last modified unix time.
    Long updateTag(TagModel localTag) throws IOException;

    // Deletes a tag file in the cloud.
    void deleteTag(String remoteTagId) throws IOException;

    void deleteTagIcon(String remoteId, boolean isIconId) throws IOException;

    TagModel uploadTagIcon(TagModel localTag) throws IOException;

    boolean downloadTagIcon(TagModel remoteTag) throws IOException, PhotoFileCreateException;

    PhotoInfo getChangedPhoto(PhotoInfo localPhoto) throws IOException;
    @NonNull List<PhotoInfo> getNewPhotos(List<String> remotePhotoIdsAlreadyLocal) throws IOException;

    String getMD5Checksum(String id) throws IOException;

    boolean downloadPhoto(PhotoInfo photo) throws IOException, PhotoFileCreateException;

    PhotoInfo uploadPhoto(PhotoInfo localPhoto) throws IOException;

    void deletePhoto(String remotePhotoId) throws IOException;

    LocationModel getChangedLocation(LocationModel localLocation) throws IOException;
    @NonNull List<LocationModel> getNewLocations(List<String> remoteLocationIdsAlreadyLocal) throws IOException;

    LocationModel createLocation(LocationModel localLocation) throws IOException;

    Long updateLocation(LocationModel localLocation) throws IOException;

    void deleteLocation(String remoteLocationId) throws IOException;

    TagModel getChangedHiddenTag(TagModel localTag) throws IOException;
    @NonNull List<TagModel> getNewHiddenTags(List<String> remoteTagIdsAlreadyLocal) throws IOException;

    // Creates the hidden tag file in the cloud and returns a TagModel with remote information set.
    TagModel createHiddenTag(TagModel localTag) throws IOException;

    // Updates the hidden tag file in the cloud and returns the last modified unix time.
    Long updateHiddenTag(TagModel localTag) throws IOException;

    // Deletes an hidden tag file in the cloud.
    void deleteHiddenTag(String remoteTagId) throws IOException;

}
