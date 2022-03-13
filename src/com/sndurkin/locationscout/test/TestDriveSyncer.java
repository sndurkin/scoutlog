package com.sndurkin.locationscout.test;

import android.support.annotation.NonNull;

import com.google.api.client.util.DateTime;
import com.google.api.services.drive.model.File;
import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.storage.DriveSyncer;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.storage.SyncListener;
import com.sndurkin.locationscout.storage.SyncableModel;
import com.sndurkin.locationscout.storage.TagModel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


// This class is used to mimic DriveSyncer to help test the sync algorithm.
public class TestDriveSyncer extends DriveSyncer {

    protected Map<String, File> fileByIdMap;
    protected Map<File, Set<String>> fileIdsByParentFolderMap;
    protected Map<String, String> fileContentByIdMap;

    private Integer uniqueId = 0;

    private boolean failDuringPhotoSync = false;

    public TestDriveSyncer(SyncListener syncListener) {
        super(syncListener);

        tagsFolder = new File();
        setUniqueIdOnDriveFile(tagsFolder);
        photosFolder = new File();
        setUniqueIdOnDriveFile(photosFolder);
        locationsFolder = new File();
        setUniqueIdOnDriveFile(locationsFolder);
        hiddenTagsFolder = new File();
        setUniqueIdOnDriveFile(hiddenTagsFolder);
        metadataFolder = new File();
        setUniqueIdOnDriveFile(metadataFolder);
        contentFolders = new File[] { tagsFolder, photosFolder, locationsFolder, hiddenTagsFolder };

        changedFiles = new HashMap<>();
        fileByIdMap = new HashMap<>();
        fileIdsByParentFolderMap = new HashMap<>();
        fileContentByIdMap = new HashMap<>();

        for(File folder : contentFolders) {
            changedFiles.put(folder, new HashMap<String, File>());
            fileIdsByParentFolderMap.put(folder, new HashSet<String>());
        }
    }

    @Override
    public void preSync() {
        newLastModifiedTime = new Date().getTime();
        if(lastModifiedTimeSinceLastSync == null) {
            lastModifiedTimeSinceLastSync = -1L;
        }
    }

    @Override
    public void postSync() {
        lastModifiedTimeSinceLastSync = newLastModifiedTime;
    }

    @Override
    public Long getLastModifiedTimeSinceLastSync() {
        return lastModifiedTimeSinceLastSync;
    }

    @Override
    public int getNumFilesChanged() {
        return 0;
    }

    @Override
    public TagModel getChangedTag(TagModel localTag) throws IOException {
        TagModel remoteTag = super.getChangedTag(localTag);

        if(remoteTag != null && changedFiles.get(tagsFolder).containsKey(remoteTag.getRemoteId())) {
            File file = changedFiles.get(tagsFolder).remove(remoteTag.getRemoteId());
            if(file != null) {
                fileByIdMap.put(remoteTag.getRemoteId(), file);
                fileIdsByParentFolderMap.get(tagsFolder).add(remoteTag.getRemoteId());
            }
        }

        return remoteTag;
    }

    @NonNull
    @Override
    public List<TagModel> getNewTags(List<String> remoteTagIdsAlreadyLocal) throws IOException {
        List<TagModel> tags = super.getNewTags(remoteTagIdsAlreadyLocal);

        for(TagModel tag : tags) {
            File file = changedFiles.get(tagsFolder).remove(tag.getRemoteId());
            fileByIdMap.put(tag.getRemoteId(), file);
            fileIdsByParentFolderMap.get(tagsFolder).add(tag.getRemoteId());
        }

        return tags;
    }

    @Override
    public boolean downloadTagIcon(TagModel remoteTag) throws IOException {
        File tagIconFile = getFile(remoteTag.getRemoteIconId());
        if(tagIconFile == null) {
            return false;
        }
        remoteTag.setIconPath(getFileContent(tagIconFile));
        return true;
    }

    @Override
    public TagModel uploadTagIcon(TagModel localTag) throws IOException {
        File driveFile = new File();
        insertFile(driveFile, localTag.getIconPath());

        TagModel remoteTag = new TagModel();
        remoteTag.setRemoteIconId(driveFile.getId());
        return remoteTag;
    }

    @Override
    public void deleteTagIcon(String remoteId, boolean isIconId) throws IOException {
        String remoteIconId;
        if(isIconId) {
            remoteIconId = remoteId;
        }
        else {
            File tagFile = getFile(remoteId);
            String fileContent = getFileContent(tagFile);
            TagModel tag = ModelUtils.createTagFromDriveFile(tagFile, fileContent);
            remoteIconId = tag.getRemoteIconId();
        }

        if(remoteIconId != null) {
            File tagIconFile = new File();
            tagIconFile.setId(remoteIconId);
            deleteFile(tagIconFile);
        }
    }

    @Override
    public PhotoInfo getChangedPhoto(PhotoInfo localPhoto) throws IOException {
        PhotoInfo remotePhoto = super.getChangedPhoto(localPhoto);

        if(remotePhoto != null && changedFiles.get(photosFolder).containsKey(remotePhoto.getRemoteId())) {
            File file = changedFiles.get(photosFolder).remove(remotePhoto.getRemoteId());
            if(file != null) {
                fileByIdMap.put(remotePhoto.getRemoteId(), file);
                fileIdsByParentFolderMap.get(photosFolder).add(remotePhoto.getRemoteId());
            }
        }

        return remotePhoto;
    }

    @NonNull
    @Override
    public List<PhotoInfo> getNewPhotos(List<String> remotePhotoIdsAlreadyLocal) throws IOException {
        List<PhotoInfo> photos = super.getNewPhotos(remotePhotoIdsAlreadyLocal);

        for(PhotoInfo photo : photos) {
            File file = changedFiles.get(photosFolder).remove(photo.getRemoteId());
            fileByIdMap.put(photo.getRemoteId(), file);
            fileIdsByParentFolderMap.get(photosFolder).add(photo.getRemoteId());
        }

        return photos;
    }

    @Override
    public String getMD5Checksum(String id) throws IOException {
        // Do nothing.
        return null;
    }

    @Override
    public boolean downloadPhoto(PhotoInfo remotePhoto) throws IOException {
        // Do nothing.
        return true;
    }

    @Override
    public PhotoInfo uploadPhoto(PhotoInfo localPhoto) throws IOException {
        PhotoInfo remotePhoto = new PhotoInfo();
        remotePhoto.mergeFrom(localPhoto, true);
        if(remotePhoto.getRemoteId() == null) {
            setUniqueIdOnModel(remotePhoto);
        }

        File driveFile = new File();
        driveFile.setId(remotePhoto.getRemoteId());
        setLastModifiedDateOnDriveFile(driveFile);
        remotePhoto.setLastModifiedDate(driveFile.getModifiedDate().getValue());

        fileByIdMap.put(remotePhoto.getRemoteId(), driveFile);
        fileIdsByParentFolderMap.get(photosFolder).add(remotePhoto.getRemoteId());

        return remotePhoto;
    }

    @Override
    public void deletePhoto(String remotePhotoId) throws IOException {
        fileIdsByParentFolderMap.get(photosFolder).remove(remotePhotoId);
        super.deletePhoto(remotePhotoId);
    }

    @Override
    public LocationModel getChangedLocation(LocationModel localLocation) throws IOException {
        LocationModel remoteLocation = super.getChangedLocation(localLocation);

        if(remoteLocation != null && changedFiles.get(locationsFolder).containsKey(remoteLocation.getRemoteId())) {
            File file = changedFiles.get(locationsFolder).remove(remoteLocation.getRemoteId());
            if(file != null) {
                fileByIdMap.put(remoteLocation.getRemoteId(), file);
                fileIdsByParentFolderMap.get(locationsFolder).add(remoteLocation.getRemoteId());
            }
        }

        return remoteLocation;
    }

    @NonNull
    @Override
    public List<LocationModel> getNewLocations(List<String> remoteLocationIdsAlreadyLocal) throws IOException {
        if(failDuringPhotoSync) {
            throw new IOException("Failed during photo sync");
        }

        List<LocationModel> locations = super.getNewLocations(remoteLocationIdsAlreadyLocal);

        for(LocationModel location : locations) {
            File file = changedFiles.get(locationsFolder).remove(location.getRemoteId());
            fileByIdMap.put(location.getRemoteId(), file);
            fileIdsByParentFolderMap.get(locationsFolder).add(location.getRemoteId());
        }

        return locations;
    }

    @Override
    public TagModel getChangedHiddenTag(TagModel localTag) throws IOException {
        TagModel remoteTag = super.getChangedHiddenTag(localTag);

        if(remoteTag != null && changedFiles.get(hiddenTagsFolder).containsKey(remoteTag.getRemoteId())) {
            File file = changedFiles.get(hiddenTagsFolder).remove(remoteTag.getRemoteId());
            if(file != null) {
                fileByIdMap.put(remoteTag.getRemoteId(), file);
                fileIdsByParentFolderMap.get(hiddenTagsFolder).add(remoteTag.getRemoteId());
            }
        }

        return remoteTag;
    }

    @NonNull
    @Override
    public List<TagModel> getNewHiddenTags(List<String> remoteTagIdsAlreadyLocal) throws IOException {
        List<TagModel> tags = super.getNewHiddenTags(remoteTagIdsAlreadyLocal);

        for(TagModel tag : tags) {
            File file = changedFiles.get(hiddenTagsFolder).remove(tag.getRemoteId());
            fileByIdMap.put(tag.getRemoteId(), file);
            fileIdsByParentFolderMap.get(hiddenTagsFolder).add(tag.getRemoteId());
        }

        return tags;
    }

    @Override
    protected File getFile(String fileId) throws IOException {
        return fileByIdMap.get(fileId);
    }

    @Override
    protected String getFileContent(File driveFile) throws IOException {
        return fileContentByIdMap.get(driveFile.getId());
    }

    @Override
    public TagModel createTag(TagModel localTag) throws IOException {
        TagModel remoteTag = super.createTag(localTag);
        fileIdsByParentFolderMap.get(tagsFolder).add(remoteTag.getRemoteId());
        return remoteTag;
    }

    @Override
    public void deleteTag(String remoteTagId) throws IOException {
        fileIdsByParentFolderMap.get(tagsFolder).remove(remoteTagId);
        super.deleteTag(remoteTagId);
    }

    @Override
    public LocationModel createLocation(LocationModel localLocation) throws IOException {
        LocationModel remoteLocation = super.createLocation(localLocation);
        fileIdsByParentFolderMap.get(locationsFolder).add(remoteLocation.getRemoteId());
        return remoteLocation;
    }

    @Override
    public void deleteLocation(String remoteLocationId) throws IOException {
        fileIdsByParentFolderMap.get(locationsFolder).remove(remoteLocationId);
        super.deleteLocation(remoteLocationId);
    }

    @Override
    public TagModel createHiddenTag(TagModel localTag) throws IOException {
        TagModel remoteTag = super.createHiddenTag(localTag);
        fileIdsByParentFolderMap.get(hiddenTagsFolder).add(remoteTag.getRemoteId());
        return remoteTag;
    }

    @Override
    public void deleteHiddenTag(String remoteTagId) throws IOException {
        fileIdsByParentFolderMap.get(hiddenTagsFolder).remove(remoteTagId);
        super.deleteHiddenTag(remoteTagId);
    }

    @Override
    protected File insertFile(File file, String fileContent) throws IOException {
        setUniqueIdOnDriveFile(file);
        setLastModifiedDateOnDriveFile(file);
        fileByIdMap.put(file.getId(), file);
        fileContentByIdMap.put(file.getId(), fileContent);
        return file;
    }

    @Override
    protected File updateFile(File file, String fileContent) throws IOException {
        setLastModifiedDateOnDriveFile(file);
        fileByIdMap.put(file.getId(), file);
        fileContentByIdMap.put(file.getId(), fileContent);
        return file;
    }

    @Override
    protected void deleteFile(File file) throws IOException {
        fileByIdMap.remove(file.getId());
        fileContentByIdMap.remove(file.getId());
    }

    // ----------------------------------------------------------------------------------
    // TEST METHODS
    //
    // The methods in this section are used to simulate changes made directly on Drive.
    // ----------------------------------------------------------------------------------

    public void createTagForTest(TagModel tag) {
        setUniqueIdOnModel(tag);

        File driveFile = ModelUtils.createDriveFileForTag(tag);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(tagsFolder).put(tag.getRemoteId(), driveFile);

        String fileContent = ModelUtils.createJSONForTag(tag).toString();
        fileContentByIdMap.put(tag.getRemoteId(), fileContent);
    }

    public String createTagIconForTest(String iconPath) {
        File tagIconFile = new File();
        try {
            tagIconFile = insertFile(tagIconFile, iconPath);
        }
        catch(IOException e) { }

        return tagIconFile.getId();
    }

    public void createPhotoForTest(PhotoInfo photo) {
        setUniqueIdOnModel(photo);

        File driveFile = new File();
        driveFile.setId(photo.getRemoteId());
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(photosFolder).put(photo.getRemoteId(), driveFile);
    }

    public void createLocationForTest(LocationModel location) {
        setUniqueIdOnModel(location);

        File driveFile = ModelUtils.createDriveFileForLocation(location);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(locationsFolder).put(location.getRemoteId(), driveFile);

        String fileContent = ModelUtils.createJSONForLocation(location).toString();
        fileContentByIdMap.put(location.getRemoteId(), fileContent);
    }

    public void createHiddenTagForTest(TagModel tag) {
        setUniqueIdOnModel(tag);

        File driveFile = ModelUtils.createDriveFileForTag(tag);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(hiddenTagsFolder).put(tag.getRemoteId(), driveFile);

        String fileContent = ModelUtils.createJSONForTag(tag).toString();
        fileContentByIdMap.put(tag.getRemoteId(), fileContent);
    }

    public void updateTagForTest(TagModel tag) {
        File driveFile = ModelUtils.createDriveFileForTag(tag);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(tagsFolder).put(tag.getRemoteId(), driveFile);

        String fileContent = ModelUtils.createJSONForTag(tag).toString();
        fileContentByIdMap.put(tag.getRemoteId(), fileContent);
    }

    public void updateTagIconForTest(TagModel tag) {
        File tagIconFile = new File();
        tagIconFile.setId(tag.getRemoteIconId());
        try { deleteFile(tagIconFile); } catch(IOException e) { }

        tag.setRemoteIconId(createTagIconForTest(tag.getIconPath()));
        updateTagForTest(tag);
    }

    public void setTagJSONForTest(String remoteTagId, JSONObject obj) {
        File driveFile = fileByIdMap.get(remoteTagId);
        setLastModifiedDateOnDriveFile(driveFile);
        fileContentByIdMap.put(remoteTagId, obj.toString());
    }

    public void updatePhotoForTest(String remotePhotoId) {
        File driveFile = fileByIdMap.get(remotePhotoId);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(photosFolder).put(remotePhotoId, driveFile);
    }

    public void updateLocationForTest(LocationModel location) {
        File driveFile = ModelUtils.createDriveFileForLocation(location);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(locationsFolder).put(location.getRemoteId(), driveFile);

        String fileContent = ModelUtils.createJSONForLocation(location).toString();
        fileContentByIdMap.put(location.getRemoteId(), fileContent);
    }

    public void setLocationJSONForTest(String remoteLocationId, JSONObject obj) {
        File driveFile = fileByIdMap.get(remoteLocationId);
        setLastModifiedDateOnDriveFile(driveFile);
        fileContentByIdMap.put(remoteLocationId, obj.toString());
    }

    public void updateHiddenTagForTest(TagModel tag) {
        File driveFile = ModelUtils.createDriveFileForTag(tag);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(hiddenTagsFolder).put(tag.getRemoteId(), driveFile);

        String fileContent = ModelUtils.createJSONForTag(tag).toString();
        fileContentByIdMap.put(tag.getRemoteId(), fileContent);
    }

    // The touchX() functions are just used to put a model back in the list of changed files,
    // so it can be returned as part of getChangedX() or getNewX().
    public void touchTagForTest(String remoteId) {
        File driveFile = fileByIdMap.get(remoteId);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(tagsFolder).put(remoteId, driveFile);
    }

    public void touchPhotoForTest(String remoteId) {
        File driveFile = fileByIdMap.get(remoteId);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(photosFolder).put(remoteId, driveFile);
    }

    public void touchLocationForTest(String remoteId) {
        File driveFile = fileByIdMap.get(remoteId);
        setLastModifiedDateOnDriveFile(driveFile);
        changedFiles.get(locationsFolder).put(remoteId, driveFile);
    }

    public void deleteTagForTest(String remoteTagId) {
        changedFiles.get(tagsFolder).put(remoteTagId, null);
        fileIdsByParentFolderMap.get(tagsFolder).remove(remoteTagId);
        fileByIdMap.remove(remoteTagId);
        fileContentByIdMap.remove(remoteTagId);
    }

    public void deleteTagIconForTest(TagModel tag) {
        File tagIconFile = new File();
        tagIconFile.setId(tag.getRemoteIconId());
        try { deleteFile(tagIconFile); } catch(IOException e) { }

        tag.setRemoteIconId(null);
        updateTagForTest(tag);
    }

    public void deletePhotoForTest(String remotePhotoId) {
        changedFiles.get(photosFolder).put(remotePhotoId, null);
        fileIdsByParentFolderMap.get(photosFolder).remove(remotePhotoId);
        fileByIdMap.remove(remotePhotoId);
    }

    public void deleteLocationForTest(String remoteLocationId) {
        changedFiles.get(locationsFolder).put(remoteLocationId, null);
        fileIdsByParentFolderMap.get(locationsFolder).remove(remoteLocationId);
        fileByIdMap.remove(remoteLocationId);
        fileContentByIdMap.remove(remoteLocationId);
    }

    public void deleteHiddenTagForTest(String remoteTagId) {
        changedFiles.get(hiddenTagsFolder).put(remoteTagId, null);
        fileIdsByParentFolderMap.get(hiddenTagsFolder).remove(remoteTagId);
        fileByIdMap.remove(remoteTagId);
        fileContentByIdMap.remove(remoteTagId);
    }

    public TagModel[] fetchTagsForTest() {
        TagModel[] tags = new TagModel[fileIdsByParentFolderMap.get(tagsFolder).size()];
        int i = 0;
        for(String remoteTagId : fileIdsByParentFolderMap.get(tagsFolder)) {
            File file = fileByIdMap.get(remoteTagId);
            String fileContent = fileContentByIdMap.get(remoteTagId);
            tags[i++] = ModelUtils.createTagFromDriveFile(file, fileContent != null ? fileContent : "");
        }
        return tags;
    }

    public JSONObject[] fetchTagJSONForTest() throws JSONException {
        JSONObject[] tagObjs = new JSONObject[fileIdsByParentFolderMap.get(tagsFolder).size()];
        int i = 0;
        for(String remoteTagId : fileIdsByParentFolderMap.get(tagsFolder)) {
            String fileContent = fileContentByIdMap.get(remoteTagId);
            tagObjs[i++] = new JSONObject(fileContent);
        }
        return tagObjs;
    }

    public String[] fetchPhotoIdsForTest() {
        String[] remotePhotoIds = new String[fileIdsByParentFolderMap.get(photosFolder).size()];
        int i = 0;
        for(String remotePhotoId : fileIdsByParentFolderMap.get(photosFolder)) {
            remotePhotoIds[i++] = remotePhotoId;
        }
        return remotePhotoIds;
    }

    public LocationModel[] fetchLocationsForTest() {
        LocationModel[] locations = new LocationModel[fileIdsByParentFolderMap.get(locationsFolder).size()];
        int i = 0;
        for(String remoteLocationId : fileIdsByParentFolderMap.get(locationsFolder)) {
            File file = fileByIdMap.get(remoteLocationId);
            String fileContent = fileContentByIdMap.get(remoteLocationId);
            locations[i++] = ModelUtils.createLocationFromDriveFile(file, fileContent != null ? fileContent : "");
        }
        return locations;
    }

    public JSONObject[] fetchLocationJSONForTest() throws JSONException {
        JSONObject[] locationObjs = new JSONObject[fileIdsByParentFolderMap.get(locationsFolder).size()];
        int i = 0;
        for(String remoteLocationId : fileIdsByParentFolderMap.get(locationsFolder)) {
            String fileContent = fileContentByIdMap.get(remoteLocationId);
            locationObjs[i++] = new JSONObject(fileContent);
        }
        return locationObjs;
    }

    public TagModel[] fetchHiddenTagsForTest() {
        TagModel[] tags = new TagModel[fileIdsByParentFolderMap.get(hiddenTagsFolder).size()];
        int i = 0;
        for(String remoteTagId : fileIdsByParentFolderMap.get(hiddenTagsFolder)) {
            File file = fileByIdMap.get(remoteTagId);
            String fileContent = fileContentByIdMap.get(remoteTagId);
            tags[i++] = ModelUtils.createHiddenTagFromDriveFile(file, fileContent != null ? fileContent : "");
        }
        return tags;
    }

    public JSONObject[] fetchHiddenTagJSONForTest() throws JSONException {
        JSONObject[] tagObjs = new JSONObject[fileIdsByParentFolderMap.get(hiddenTagsFolder).size()];
        int i = 0;
        for(String remoteTagId : fileIdsByParentFolderMap.get(hiddenTagsFolder)) {
            String fileContent = fileContentByIdMap.get(remoteTagId);
            tagObjs[i++] = new JSONObject(fileContent);
        }
        return tagObjs;
    }

    public int fetchNumberOfFilesForTest() {
        return fileByIdMap.size();
    }

    protected void setUniqueIdOnModel(SyncableModel model) {
        model.setRemoteId((uniqueId++).toString());
    }

    protected void setUniqueIdOnDriveFile(File driveFile) {
        driveFile.setId((uniqueId++).toString());
    }

    protected void setLastModifiedDateOnDriveFile(File driveFile) {
        driveFile.setModifiedDate(new DateTime(new Date().getTime()));
    }

    protected void setFailDuringPhotoSync(boolean failDuringPhotoSync) {
        this.failDuringPhotoSync = failDuringPhotoSync;
    }

    // ----------------------------------------------------------------------------------
    // END TEST METHODS
    // ----------------------------------------------------------------------------------

}
