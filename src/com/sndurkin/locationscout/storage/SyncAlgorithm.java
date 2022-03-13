package com.sndurkin.locationscout.storage;


import android.content.SharedPreferences;
import android.database.Cursor;
import android.support.annotation.NonNull;

import com.sndurkin.locationscout.util.FileUtils;
import com.sndurkin.locationscout.util.PhotoFileCreateException;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.util.Strings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// This class holds the logic for the sync algorithm. It utilizes a Syncer instance
// and DatabaseHelper to achieve its goals, but it doesn't know details of either
// the database or Google Drive (or other data storage services).
public class SyncAlgorithm {

    public static void execute(Syncer syncer, DatabaseHelper database, SharedPreferences preferences, @NonNull SyncListener listener) {
        listener.onStarted();

        try {
            syncer.preSync();

            List<Long> hiddenTagIdsToCheck = new ArrayList<>();
            syncTags(syncer, database, preferences, listener);
            syncPhotos(syncer, database, preferences, listener);
            syncLocations(syncer, database, preferences, hiddenTagIdsToCheck, listener);
            syncHiddenTags(syncer, database, preferences, hiddenTagIdsToCheck, listener);

            // TODO: make sure tags are being updated after sync
            database.updateRecordsAfterSync();

            syncer.postSync();
            listener.onFinished();
        }
        catch(Exception e) {
            listener.onException(e);
        }
    }

    public static void syncTags(Syncer syncer, DatabaseHelper database, SharedPreferences preferences, @NonNull SyncListener listener) throws IOException, PhotoFileCreateException {
        Cursor cursor = database.fetchTagsForSync(true);
        List<String> remoteTagIdsAlreadyLocal = new ArrayList<>();
        while(!cursor.isAfterLast()) {
            TagModel localTag = ModelUtils.createTagFromCursor(cursor);
            remoteTagIdsAlreadyLocal.add(localTag.getRemoteId());

            MiscUtils.logv("Tag \"" + localTag.name + "\" is being checked for changes");

            // Temporarily nullify the local tag's icon values because Syncer.getChangedTag()
            // merges the local tag's values to the remote tag. The issue is that the icon values
            // can be different between the local and remote versions of the tag, so we need to
            // ensure that the remote tag's values are preserved.
            String localTagIconId = localTag.getRemoteIconId();
            String localTagIconPath = localTag.getIconPath();
            localTag.setRemoteIconId(null);
            localTag.setIconPath(null);
            TagModel remoteTag = syncer.getChangedTag(localTag);
            localTag.setRemoteIconId(localTagIconId);
            localTag.setIconPath(localTagIconPath);

            if(remoteTag != null && remoteTag.isDeleted()) {
                MiscUtils.logv(" - it has been deleted remotely so it will be deleted locally");

                syncer.deleteTagIcon(localTag.getRemoteIconId(), true);
                database.permanentlyDeleteTagIcon(localTag);

                database.permanentlyDeleteTag(localTag.getLocalId());
                listener.onModelDeleted();
            }
            else if(localTag.isDeleted()) {
                MiscUtils.logv(" - it has been deleted locally so it will be deleted remotely");

                if(localTag.getIconPath() != null || localTag.getRemoteIconId() != null) {
                    syncer.deleteTagIcon(localTag.getRemoteId(), false);
                    database.permanentlyDeleteTagIcon(localTag);
                }

                syncer.deleteTag(localTag.getRemoteId());
                database.permanentlyDeleteTag(localTag.getLocalId());
                listener.onModelDeleted();
            }
            else {
                boolean remoteFileNeedsUpdate = false,
                        localFileNeedsUpdate = false;

                // NOTE:
                //
                // syncUtil.getLastModifiedTimeSinceLastSync() should not be null at this point in the code,
                // because no lastLocalModifiedTime means we've never synced, and there wouldn't be
                // any records returned from the query because all DRIVE_FILE_IDs would be null.

                Long localLastModifiedDate = localTag.getLastModifiedDate();
                if(remoteTag == null) {
                    if(localLastModifiedDate > syncer.getLastModifiedTimeSinceLastSync()) {
                        remoteFileNeedsUpdate = true;
                    }
                    else if(localTag.hasIcon() && !(new File(localTag.getIconPath()).exists())) {
                        localFileNeedsUpdate = true;
                    }
                    else {
                        MiscUtils.logv(" - neither file has been updated");
                    }
                }
                else {
                    if(localLastModifiedDate > syncer.getLastModifiedTimeSinceLastSync()) {
                        // Both files have been updated, so pick the most recently changed one.
                        if(remoteTag.getLastModifiedDate() > localLastModifiedDate) {
                            localFileNeedsUpdate = true;
                        }
                        else if(remoteTag.getLastModifiedDate() < localLastModifiedDate) {
                            remoteFileNeedsUpdate = true;
                        }
                        else {
                            // Both files have the same last modified date so neither needs to be updated!
                            MiscUtils.logv(" - both files have the same last modified date and don't need to be updated");
                        }
                    }
                    else {
                        localFileNeedsUpdate = true;
                    }
                }

                if (remoteFileNeedsUpdate) {
                    MiscUtils.logv(" - it has been updated locally so it will be updated remotely");

                    if(localTag.getRemoteIconId() == null) {
                        syncer.deleteTagIcon(localTag.getRemoteId(), false);
                        database.deleteTagIconFile(localTag);

                        if(localTag.hasIcon()) {
                            TagModel tagWithIcon = syncer.uploadTagIcon(localTag);
                            if(tagWithIcon != null) {
                                localTag.setRemoteIconId(tagWithIcon.getRemoteIconId());
                            }
                        }
                    }

                    Long newLastModifiedDate = syncer.updateTag(localTag);
                    database.updateSyncPropertiesForTag(localTag.getLocalId(), newLastModifiedDate, null, localTag.getRemoteIconId());
                    listener.onModelUpdated();
                }
                else if(localFileNeedsUpdate) {
                    MiscUtils.logv(" - it has been updated remotely so it will be updated locally");

                    if(remoteTag == null) {
                        remoteTag = new TagModel();
                        remoteTag.setLocalId(localTag.getLocalId());
                        remoteTag.setRemoteId(localTag.getRemoteId());

                        if(localTag.getRemoteIconId() != null) {
                            remoteTag.setRemoteIconId(localTag.getRemoteIconId());
                            syncer.downloadTagIcon(remoteTag);
                        }
                    }
                    else if(remoteTag.getRemoteIconId() != null && !remoteTag.getRemoteIconId().equals(localTag.getRemoteIconId())) {
                        database.permanentlyDeleteTagIcon(localTag);
                        syncer.downloadTagIcon(remoteTag);
                    }

                    database.updateTag(remoteTag);
                    listener.onModelUpdated();
                }
                else {
                    listener.onModelSkipped();
                }
            }

            cursor.moveToNext();
        }
        cursor.close();

        List<TagModel> newRemoteTags = syncer.getNewTags(remoteTagIdsAlreadyLocal);
        for(TagModel remoteTag : newRemoteTags) {
            MiscUtils.logv("Tag \"" + remoteTag.name + "\" has been created remotely");

            // There is a rare case where a tag exists remotely and another
            // with the exact same name exists locally (which hasn't
            // yet been synced remotely). In this case we want to merge
            // the two tags by saving the drive file id to the local record.
            if(database.duplicateTagExistsLocally(remoteTag.name)) {
                MiscUtils.logv(" - it also exists locally so they will be merged");

                database.mergeDuplicateLocalTag(remoteTag);
                listener.onModelUpdated();
            }
            else {
                MiscUtils.logv(" - it will be created locally");

                syncer.downloadTagIcon(remoteTag);
                database.createTag(remoteTag);
                listener.onModelCreated();
            }
        }

        // Fetch any newly created local files and save them remotely.
        cursor = database.fetchTagsForSync(false);
        while(!cursor.isAfterLast()) {
            TagModel localTag = ModelUtils.createTagFromCursor(cursor);

            if(localTag.hasIcon()) {
                MiscUtils.logv("Icon for tag \"" + localTag.name + "\" has been created locally and will be created remotely");

                TagModel remoteTag = syncer.uploadTagIcon(localTag);
                if(remoteTag != null) {
                    localTag.setRemoteIconId(remoteTag.getRemoteIconId());
                }
            }

            MiscUtils.logv("Tag \"" + localTag.name + "\" has been created locally and will be created remotely");

            TagModel remoteTag = syncer.createTag(localTag);
            database.updateSyncPropertiesForTag(remoteTag.getLocalId(), remoteTag.getLastModifiedDate(), remoteTag.getRemoteId(), localTag.getRemoteIconId());
            listener.onModelCreated();
            cursor.moveToNext();
        }
        cursor.close();
    }

    public static void syncPhotos(Syncer syncer, DatabaseHelper database, SharedPreferences preferences, @NonNull SyncListener listener) throws IOException, PhotoFileCreateException {
        Cursor cursor = database.fetchPhotosForSync(true);
        List<String> remotePhotoIdsAlreadyLocal = new ArrayList<>();
        while(!cursor.isAfterLast()) {
            PhotoInfo localPhoto = ModelUtils.createPhotoFromCursor(cursor);
            remotePhotoIdsAlreadyLocal.add(localPhoto.getRemoteId());

            MiscUtils.logv("Photo \"" + localPhoto.title + "\" is being checked for changes");

            PhotoInfo remotePhoto = syncer.getChangedPhoto(localPhoto);
            listener.beforePhotosSynced(localPhoto, remotePhoto);

            if(remotePhoto != null && remotePhoto.isDeleted()) {
                MiscUtils.logv(" - it has been deleted remotely so it will be deleted locally");

                database.permanentlyDeletePhoto(localPhoto, true);
                listener.onModelDeleted();
            }
            else if(localPhoto.isDeleted()) {
                MiscUtils.logv(" - it has been deleted locally so it will be deleted remotely");

                syncer.deletePhoto(localPhoto.getRemoteId());
                database.permanentlyDeletePhoto(localPhoto, true);
                listener.onModelDeleted();
            }
            // We add the check for the PREF_PHOTO_STORAGE because we don't want to try to update
            // photos if we're not storing them locally.
            else if(preferences.getString(Strings.PREF_PHOTO_STORAGE, "0").equals("0")) {
                boolean remoteFileNeedsUpdate = false,
                        localFileNeedsUpdate = false;

                // NOTE:
                //
                // syncUtil.getLastModifiedTimeSinceLastSync() should not be null at this point in the code,
                // because no lastLocalModifiedTime means we've never synced, and there wouldn't be
                // any records returned from the query because all DRIVE_FILE_IDs would be null.

                Long localLastModifiedDate = localPhoto.getLastModifiedDate();
                if(localLastModifiedDate == null) {
                    // The local file does not exist, so it needs to be replaced with the remote version.
                    localFileNeedsUpdate = true;

                    remotePhoto = new PhotoInfo();
                    remotePhoto.setLocalId(localPhoto.getLocalId());
                    remotePhoto.setRemoteId(localPhoto.getRemoteId());
                }
                else if(remotePhoto == null) {
                    if(localLastModifiedDate > syncer.getLastModifiedTimeSinceLastSync()) {
                        remoteFileNeedsUpdate = true;
                    }
                    else {
                        MiscUtils.logv(" - neither file has been updated");
                    }
                }
                else {
                    if(localLastModifiedDate > syncer.getLastModifiedTimeSinceLastSync()) {
                        // Both files have been updated, so pick the most recently changed one.
                        if(remotePhoto.getLastModifiedDate() > localLastModifiedDate) {
                            localFileNeedsUpdate = true;
                        }
                        else if(remotePhoto.getLastModifiedDate() < localLastModifiedDate) {
                            remoteFileNeedsUpdate = true;
                        }
                        else {
                            // Both files have the same last modified date so neither needs to be updated!
                            MiscUtils.logv(" - both files have the same last modified date and don't need to be updated");
                        }
                    }
                    else {
                        localFileNeedsUpdate = true;
                    }
                }

                // File.setLastModifiedDate() doesn't work on Android, so we have to use the md5 checksum
                // to see if the files have changed. If they're different, we go with the most recently modified.
                if(remoteFileNeedsUpdate || localFileNeedsUpdate) {
                    long startTime = new Date().getTime();

                    String remotePhotoId = remotePhoto != null ? remotePhoto.getRemoteId() : localPhoto.getRemoteId();
                    String remoteMD5Checksum = syncer.getMD5Checksum(remotePhotoId);
                    String localMD5Checksum = FileUtils.calculateMD5(localPhoto.path);
                    if(!MiscUtils.checkMD5(remoteMD5Checksum, localMD5Checksum)) {
                        if(remoteFileNeedsUpdate) {
                            MiscUtils.logv(" - it has been updated locally so it will be uploaded to Drive");

                            remotePhoto = syncer.uploadPhoto(localPhoto);
                            database.updateSyncPropertiesForPhoto(localPhoto.getLocalId(), remotePhoto.getLastModifiedDate(), null);
                            listener.onModelUpdated();
                        }
                        else {
                            MiscUtils.logv(" - it has been updated remotely so it will be downloaded again");

                            if(syncer.downloadPhoto(remotePhoto)) {
                                database.updatePhoto(remotePhoto);
                                listener.onModelUpdated();
                            }
                            else {
                                // The download has failed because, for some reason, the photo doesn't exist.
                                // I'm not sure how this can happen because there exists a change id for it.
                                MiscUtils.logv(" - actually, for some reason it doesn't exist remotely so it will be deleted locally");

                                database.permanentlyDeletePhoto(localPhoto, true);
                                listener.onModelDeleted();
                            }
                        }
                    }
                    else {
                        // Files are the same, so there's no need to update.
                        MiscUtils.logv(" - the last modified times are different but the file content is equivalent (" + (new Date().getTime() - startTime) + "ms to perform md5)");
                        listener.onModelSkipped();
                    }
                }
                else {
                    listener.onModelSkipped();
                }
            }

            cursor.moveToNext();
        }
        cursor.close();

        List<PhotoInfo> newRemotePhotos = syncer.getNewPhotos(remotePhotoIdsAlreadyLocal);
        for(PhotoInfo remotePhoto : newRemotePhotos) {
            MiscUtils.logv("Photo \"" + remotePhoto.title + "\" has been created remotely and will be created locally");

            if(preferences.getString(Strings.PREF_PHOTO_STORAGE, "0").equals("0")) {
                MiscUtils.logv(" - it will also be downloaded locally as per user preferences");

                // Only download the photo if the user wants to keep a local copy of all photos.
                if(!syncer.downloadPhoto(remotePhoto)) {
                    MiscUtils.logv(" - actually, for some reason it didn't exist remotely so it will not be downloaded");
                }
            }
            database.createPhoto(remotePhoto);
            listener.onModelCreated();
        }

        // Fetch any newly created local files and save them remotely.
        cursor = database.fetchPhotosForSync(false);
        while(!cursor.isAfterLast()) {
            PhotoInfo localPhoto = ModelUtils.createPhotoFromCursor(cursor);

            MiscUtils.logv("Photo \"" + localPhoto.title + "\" has been created locally and will be created remotely");

            PhotoInfo remotePhoto = syncer.uploadPhoto(localPhoto);
            if(remotePhoto != null) {
                database.updateSyncPropertiesForPhoto(remotePhoto.getLocalId(), remotePhoto.getLastModifiedDate(), remotePhoto.getRemoteId());
                if(!preferences.getString(Strings.PREF_PHOTO_STORAGE, "0").equals("0")) {
                    MiscUtils.logv(" - it will also be deleted locally as per user preferences");

                    // Delete the photo file if the user doesn't want to keep local copies of his photos.
                    if(database.deleteFile(localPhoto.path)) {
                        database.nullifyPathForPhoto(localPhoto.getLocalId());
                    }
                }
                listener.onModelCreated();
            }
            else {
                database.deletePhotoRecord(database.getWritableDatabase(), localPhoto.getLocalId());
            }

            cursor.moveToNext();
        }
        cursor.close();
    }

    public static void syncLocations(Syncer syncer, DatabaseHelper database, SharedPreferences preferences, List<Long> hiddenTagIdsToCheck, @NonNull SyncListener listener) throws IOException {
        Cursor cursor = database.fetchLocationsForSync(true);
        List<String> remoteLocationIdsAlreadyLocal = new ArrayList<>();
        while(!cursor.isAfterLast()) {
            LocationModel localLocation = ModelUtils.createLocationFromCursorForSync(cursor);
            remoteLocationIdsAlreadyLocal.add(localLocation.getRemoteId());

            MiscUtils.logv("Location \"" + localLocation.getTitle() + "\" is being checked for changes");

            LocationModel remoteLocation = syncer.getChangedLocation(localLocation);
            if(remoteLocation != null && remoteLocation.isDeleted()) {
                MiscUtils.logv(" - it has been deleted remotely so it will be deleted locally");

                // Delete any orphaned hidden tags.
                hiddenTagIdsToCheck.addAll(database.fetchHiddenTagIds(localLocation.getLocalId()));
                database.permanentlyDeleteLocation(localLocation);
                listener.onModelDeleted();
            }
            else if(localLocation.isDeleted()) {
                MiscUtils.logv(" - it has been deleted locally so it will be deleted remotely");

                syncer.deleteLocation(localLocation.getRemoteId());
                hiddenTagIdsToCheck.addAll(database.fetchHiddenTagIds(localLocation.getLocalId()));
                database.permanentlyDeleteLocation(localLocation);
                listener.onModelDeleted();
            }
            else {
                boolean remoteFileNeedsUpdate = false,
                        localFileNeedsUpdate = false;

                // NOTE:
                //
                // syncUtil.getLastModifiedTimeSinceLastSync() should not be null at this point in the code,
                // because no lastLocalModifiedTime means we've never synced, and there wouldn't be
                // any records returned from the query because all DRIVE_FILE_IDs would be null.

                Long localLastModifiedDate = localLocation.getLastModifiedDate();
                if(remoteLocation == null) {
                    if(localLastModifiedDate > syncer.getLastModifiedTimeSinceLastSync()) {
                        remoteFileNeedsUpdate = true;
                    }
                    else {
                        MiscUtils.logv(" - neither file has been updated");
                    }
                }
                else {
                    if(localLastModifiedDate > syncer.getLastModifiedTimeSinceLastSync()) {
                        // Both files have been updated, so pick the most recently changed one.
                        if(remoteLocation.getLastModifiedDate() > localLastModifiedDate) {
                            localFileNeedsUpdate = true;
                        }
                        else if(remoteLocation.getLastModifiedDate() < localLastModifiedDate) {
                            remoteFileNeedsUpdate = true;
                        }
                        else {
                            // Both files have the same last modified date so neither needs to be updated!
                            MiscUtils.logv(" - both files have the same last modified date and don't need to be updated");
                        }
                    }
                    else {
                        localFileNeedsUpdate = true;
                    }
                }

                if(remoteFileNeedsUpdate) {
                    // File has been updated locally, so update the file remotely.
                    MiscUtils.logv(" - it has been updated locally so it will be updated remotely");

                    database.populatePhotoInfoForLocation(localLocation);
                    Long newLastModifiedDate = syncer.updateLocation(localLocation);
                    database.updateSyncPropertiesForLocation(localLocation.getLocalId(), newLastModifiedDate, null);
                    listener.onModelUpdated();
                }
                else if(localFileNeedsUpdate) {
                    // File has been updated remotely, so update the row in the DB.
                    MiscUtils.logv(" - it has been updated remotely so it will be updated locally");

                    database.updateLocation(remoteLocation);
                    listener.onModelUpdated();
                }
                else {
                    listener.onModelSkipped();
                }
            }

            cursor.moveToNext();
        }
        cursor.close();

        List<LocationModel> newRemoteLocations = syncer.getNewLocations(remoteLocationIdsAlreadyLocal);
        for(LocationModel remoteLocation : newRemoteLocations) {
            MiscUtils.logv("Location \"" + remoteLocation.getTitle() + "\" has been created remotely and will be created locally");

            database.createLocation(remoteLocation);
            listener.onModelCreated();
        }

        // Fetch any newly created local files and save them remotely.
        cursor = database.fetchLocationsForSync(false);
        while(!cursor.isAfterLast()) {
            LocationModel localLocation = ModelUtils.createLocationFromCursorForSync(cursor);

            MiscUtils.logv("Location \"" + localLocation.getTitle() + "\" has been created locally and will be created remotely");

            database.populatePhotoInfoForLocation(localLocation);
            LocationModel remoteLocation = syncer.createLocation(localLocation);
            database.updateSyncPropertiesForLocation(remoteLocation.getLocalId(), remoteLocation.getLastModifiedDate(), remoteLocation.getRemoteId());
            listener.onModelCreated();
            cursor.moveToNext();
        }
        cursor.close();
    }

    public static void syncHiddenTags(Syncer syncer, DatabaseHelper database, SharedPreferences preferences, List<Long> hiddenTagIdsToCheck, @NonNull SyncListener listener) throws IOException {
        Cursor cursor = database.fetchHiddenTagsForSync(true);
        List<String> remoteTagIdsAlreadyLocal = new ArrayList<>();
        while(!cursor.isAfterLast()) {
            TagModel localTag = ModelUtils.createHiddenTagFromCursor(cursor);
            remoteTagIdsAlreadyLocal.add(localTag.getRemoteId());

            MiscUtils.logv("Hidden tag is being checked for changes");

            TagModel remoteTag = syncer.getChangedHiddenTag(localTag);
            if(remoteTag != null && remoteTag.isDeleted()) {
                MiscUtils.logv(" - it has been deleted remotely so it will be deleted locally");

                database.permanentlyDeleteHiddenTag(localTag.getLocalId());
                listener.onModelDeleted();
            }
            else if(localTag.isDeleted()) {
                MiscUtils.logv(" - it has been deleted locally so it will be deleted remotely");

                syncer.deleteHiddenTag(localTag.getRemoteId());
                database.permanentlyDeleteHiddenTag(localTag.getLocalId());
                listener.onModelDeleted();
            }
            else {
                boolean remoteFileNeedsUpdate = false,
                        localFileNeedsUpdate = false;

                // NOTE:
                //
                // syncUtil.getLastModifiedTimeSinceLastSync() should not be null at this point in the code,
                // because no lastLocalModifiedTime means we've never synced, and there wouldn't be
                // any records returned from the query because all DRIVE_FILE_IDs would be null.

                Long localLastModifiedDate = localTag.getLastModifiedDate();
                if(remoteTag == null) {
                    if(localLastModifiedDate > syncer.getLastModifiedTimeSinceLastSync()) {
                        remoteFileNeedsUpdate = true;
                    }
                    else {
                        MiscUtils.logv(" - neither file has been updated");
                    }
                }
                else {
                    if(localLastModifiedDate > syncer.getLastModifiedTimeSinceLastSync()) {
                        // Both files have been updated, so pick the most recently changed one.
                        if(remoteTag.getLastModifiedDate() > localLastModifiedDate) {
                            localFileNeedsUpdate = true;
                        }
                        else if(remoteTag.getLastModifiedDate() < localLastModifiedDate) {
                            remoteFileNeedsUpdate = true;
                        }
                        else {
                            // Both files have the same last modified date so neither needs to be updated!
                            MiscUtils.logv(" - both files have the same last modified date and don't need to be updated");
                        }
                    }
                    else {
                        localFileNeedsUpdate = true;
                    }
                }

                if(remoteFileNeedsUpdate) {
                    MiscUtils.logv(" - it has been updated locally so it will be updated remotely");

                    Long newLastModifiedDate = syncer.updateHiddenTag(localTag);
                    database.updateSyncPropertiesForTag(localTag.getLocalId(), newLastModifiedDate, null, null);
                    listener.onModelUpdated();
                }
                else if(localFileNeedsUpdate) {
                    MiscUtils.logv(" - it has been updated remotely so it will be updated locally");

                    database.updateHiddenTag(remoteTag);
                    listener.onModelUpdated();
                }
                else {
                    listener.onModelSkipped();
                }
            }

            cursor.moveToNext();
        }
        cursor.close();

        List<TagModel> newRemoteTags = syncer.getNewHiddenTags(remoteTagIdsAlreadyLocal);

        // Automatically enable the linked locations option in the settings if there are any
        // remote hidden tags. This is just convenience for users who sync a new device against
        // their existing database.
        if(newRemoteTags.size() > 0 && !preferences.getBoolean(Strings.PREF_ENABLE_LINKED_LOCATIONS, false)) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(Strings.PREF_ENABLE_LINKED_LOCATIONS, true);
            editor.apply();
        }

        for(TagModel remoteTag : newRemoteTags) {
            MiscUtils.logv("Hidden tag has been created remotely and will be created locally");
            database.createHiddenTag(remoteTag);
            listener.onModelCreated();
        }

        // Fetch any newly created local files and save them remotely.
        cursor = database.fetchHiddenTagsForSync(false);
        while(!cursor.isAfterLast()) {
            TagModel localTag = ModelUtils.createHiddenTagFromCursor(cursor);

            MiscUtils.logv("Hidden tag has been created locally and will be created remotely");

            TagModel remoteTag = syncer.createHiddenTag(localTag);
            database.updateSyncPropertiesForTag(remoteTag.getLocalId(), remoteTag.getLastModifiedDate(), remoteTag.getRemoteId(), null);
            listener.onModelCreated();
            cursor.moveToNext();
        }
        cursor.close();

        // Check the referential integrity for the hidden tags.
        database.deleteHiddenTagsIfNecessary(hiddenTagIdsToCheck, true);
    }

}
