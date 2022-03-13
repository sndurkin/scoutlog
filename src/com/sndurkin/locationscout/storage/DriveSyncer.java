package com.sndurkin.locationscout.storage;


import android.accounts.Account;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;
import com.sndurkin.locationscout.BuildConfig;
import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.util.FileUtils;
import com.sndurkin.locationscout.util.PhotoFileCreateException;
import com.sndurkin.locationscout.util.InvalidAccountException;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.Strings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DriveSyncer implements Syncer {

    protected static final String MIME_TYPE_FOLDER = "application/vnd.google-apps.folder";
    protected static final String MIME_TYPE_PHOTO = "application/vnd.google-apps.photo";
    protected static final String MIME_TYPE_TEXT = "text/plain";

    protected static final int CONNECT_TIMEOUT = 60000;   // 1 minute
    protected static final int READ_TIMEOUT = 60000;

    protected Context context;
    protected Account account;
    protected SyncListener syncListener;
    protected SharedPreferences preferences;
    protected Drive service;

    // These properties are used to identify which files have been touched
    // on Drive and locally since the last sync.
    protected Long driveChangeIdSinceLastSync;
    protected Long lastModifiedTimeSinceLastSync;

    protected Long newDriveChangeId;
    protected Long newLastModifiedTime;

    protected File rootFolder;
    protected File tagsFolder;
    protected File photosFolder;
    protected File locationsFolder;
    protected File hiddenTagsFolder;
    protected File metadataFolder;

    protected File[] contentFolders;

    protected Map<File, Map<String, File>> changedFiles;
    protected int numFilesChanged;


    public DriveSyncer(SyncListener syncListener) {
        this.syncListener = syncListener;
    }

    public DriveSyncer(Context context, Account account, SyncListener syncListener) {
        this.context = context;
        this.account = account;
        this.syncListener = syncListener;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Override
    public void preSync() throws IOException, GoogleAuthException, InvalidAccountException {
        initDriveService();

        driveChangeIdSinceLastSync = preferences.getLong(Strings.PREF_LAST_DRIVE_CHANGE_ID_PREFIX + account.name, -1);
        lastModifiedTimeSinceLastSync = preferences.getLong(Strings.PREF_LAST_LOCAL_MODIFIED_TIME, -1);

        initFolders();

        // Get the largest change id first to avoid race conditions.
        newDriveChangeId = getLargestDriveChangeId();
        newLastModifiedTime = new Date().getTime();

        changedFiles = fetchChangedFiles();
        numFilesChanged = changedFiles.get(tagsFolder).size()
                        + changedFiles.get(photosFolder).size()
                        + changedFiles.get(locationsFolder).size()
                        + changedFiles.get(hiddenTagsFolder).size();

        if(BuildConfig.DEBUG) {
            MiscUtils.logv("lastDriveChangeId: " + driveChangeIdSinceLastSync);
            MiscUtils.logv("newDriveChangeId: " + newDriveChangeId);
            MiscUtils.logv("Number of files changed on Drive: " + numFilesChanged);
        }
    }

    @Override
    public int getNumFilesChanged() {
        return numFilesChanged;
    }

    protected Long getLargestDriveChangeId() throws IOException {
        About about = service.about().get().setFields("largestChangeId").execute();
        return about.getLargestChangeId();
    }

    protected void initDriveService() throws IOException, GoogleAuthException, InvalidAccountException {
        final GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(context, Arrays.asList(DriveScopes.DRIVE));
        credential.setSelectedAccountName(account.name);
        if(credential.getSelectedAccountName() == null) {
            throw new InvalidAccountException();
        }

        credential.setBackOff(new ExponentialBackOff());
        credential.getToken();      // This should throw a GoogleAuthException if we are not authorized.

        HttpRequestInitializer initializer = new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest httpRequest) throws IOException {
                credential.initialize(httpRequest);
                httpRequest.setConnectTimeout(CONNECT_TIMEOUT);
                httpRequest.setReadTimeout(READ_TIMEOUT);
            }
        };

        service = new Drive.Builder(AndroidHttp.newCompatibleTransport(), new GsonFactory(), initializer).build();
    }

    protected void initFolders() throws IOException {
        FileList fileList = service.files().list().setQ("mimeType = '" + MIME_TYPE_FOLDER + "' and title = '" + MiscUtils.APP_FOLDER + "' and trashed = false").execute();
        if(!fileList.getItems().isEmpty()) {
            rootFolder = fileList.getItems().get(0);
        }
        else {
            fileList = service.files().list().setQ("mimeType = '" + MIME_TYPE_FOLDER + "' and title = '" + MiscUtils.APP_FOLDER_DEPRECATED + "' and trashed = false").execute();
            if(!fileList.getItems().isEmpty()) {
                // The folder name is deprecated, so we'll try to rename it to the current one.
                rootFolder = fileList.getItems().get(0);

                File file = new File();
                file.setTitle(MiscUtils.APP_FOLDER);
                Drive.Files.Patch patchRequest = service.files().patch(rootFolder.getId(), file);
                patchRequest.setFields("title");

                try {
                    patchRequest.execute();
                }
                catch(IOException e) {
                    // Ignore, we can try to rename it later.
                    CrashlyticsCore.getInstance().logException(e);
                }
            }
            else {
                rootFolder = new File();
                rootFolder.setTitle(MiscUtils.APP_FOLDER);
                rootFolder.setMimeType(MIME_TYPE_FOLDER);
                rootFolder = service.files().insert(rootFolder).execute();
            }
        }

        fileList = service.files().list().setQ("mimeType = '" + MIME_TYPE_FOLDER + "' and '" + rootFolder.getId() + "' in parents and trashed = false").execute();
        for(File folder : fileList.getItems()) {
            if(folder.getTitle().equals(DatabaseHelper.TAG_TABLE)) {
                tagsFolder = folder;
            }
            else if(folder.getTitle().equals(DatabaseHelper.PHOTO_TABLE)) {
                photosFolder = folder;
            }
            else if(folder.getTitle().equals(DatabaseHelper.LOCATION_TABLE)) {
                locationsFolder = folder;
            }
            else if(folder.getTitle().equals("hidden_tags")) {
                hiddenTagsFolder = folder;
            }
            else if(folder.getTitle().equals("metadata")) {
                metadataFolder = folder;
            }
        }

        if(tagsFolder == null) {
            tagsFolder = new File();
            tagsFolder.setTitle(DatabaseHelper.TAG_TABLE);
            tagsFolder.setMimeType(MIME_TYPE_FOLDER);

            ParentReference parentReference = new ParentReference();
            parentReference.setId(rootFolder.getId());
            tagsFolder.setParents(Arrays.asList(parentReference));

            tagsFolder = service.files().insert(tagsFolder).execute();
        }

        if(photosFolder == null) {
            photosFolder = new File();
            photosFolder.setTitle(DatabaseHelper.PHOTO_TABLE);
            photosFolder.setMimeType(MIME_TYPE_FOLDER);

            ParentReference parentReference = new ParentReference();
            parentReference.setId(rootFolder.getId());
            photosFolder.setParents(Arrays.asList(parentReference));

            photosFolder = service.files().insert(photosFolder).execute();
        }

        if(locationsFolder == null) {
            locationsFolder = new File();
            locationsFolder.setTitle(DatabaseHelper.LOCATION_TABLE);
            locationsFolder.setMimeType(MIME_TYPE_FOLDER);

            ParentReference parentReference = new ParentReference();
            parentReference.setId(rootFolder.getId());
            locationsFolder.setParents(Arrays.asList(parentReference));

            locationsFolder = service.files().insert(locationsFolder).execute();
        }

        if(hiddenTagsFolder == null) {
            hiddenTagsFolder = new File();
            hiddenTagsFolder.setTitle("hidden_tags");
            hiddenTagsFolder.setMimeType(MIME_TYPE_FOLDER);

            ParentReference parentReference = new ParentReference();
            parentReference.setId(rootFolder.getId());
            hiddenTagsFolder.setParents(Arrays.asList(parentReference));

            hiddenTagsFolder = service.files().insert(hiddenTagsFolder).execute();
        }

        if(metadataFolder == null) {
            metadataFolder = new File();
            metadataFolder.setTitle("metadata");
            metadataFolder.setMimeType(MIME_TYPE_FOLDER);

            ParentReference parentReference = new ParentReference();
            parentReference.setId(rootFolder.getId());
            metadataFolder.setParents(Arrays.asList(parentReference));

            metadataFolder = service.files().insert(metadataFolder).execute();
        }

        contentFolders = new File[] { tagsFolder, photosFolder, locationsFolder, hiddenTagsFolder };
    }

    @Override
    public Long getLastModifiedTimeSinceLastSync() {
        return lastModifiedTimeSinceLastSync;
    }

    @Override
    public void postSync() {
        if(numFilesChanged > 0) {
            setDriveChangeIdSinceLastSync(newDriveChangeId + 1);
        }
        setLastModifiedTimeSinceLastSync(newLastModifiedTime);
    }

    protected void setDriveChangeIdSinceLastSync(Long changeId) {
        driveChangeIdSinceLastSync = changeId;

        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(Strings.PREF_LAST_DRIVE_CHANGE_ID_PREFIX + account.name, driveChangeIdSinceLastSync);
        editor.commit();
    }

    protected void setLastModifiedTimeSinceLastSync(Long lastModifiedTimeSinceLastSync) {
        this.lastModifiedTimeSinceLastSync = lastModifiedTimeSinceLastSync;

        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(Strings.PREF_LAST_LOCAL_MODIFIED_TIME, lastModifiedTimeSinceLastSync);
        editor.commit();
    }

    @Override
    public TagModel getChangedTag(TagModel localTag) throws IOException {
        if(changedFiles.get(tagsFolder).containsKey(localTag.getRemoteId())) {
            TagModel remoteTag = new TagModel();
            remoteTag.mergeFrom(localTag, false);

            File driveFile = changedFiles.get(tagsFolder).get(localTag.getRemoteId());
            if(driveFile != null) {
                String fileContent = getFileContent(driveFile);
                remoteTag.mergeFrom(ModelUtils.createTagFromDriveFile(driveFile, fileContent), true);
            }
            else {
                // File has been deleted/trashed.
                remoteTag.setDeleted(true);
            }

            return remoteTag;
        }

        return null;
    }

    @Override
     public TagModel createTag(TagModel localTag) throws IOException {
        return createTag(localTag, tagsFolder);
    }

    protected TagModel createTag(TagModel localTag, File folder) throws IOException {
        File file = ModelUtils.createDriveFileForTag(localTag);
        ParentReference parentReference = new ParentReference();
        parentReference.setId(folder.getId());
        file.setParents(Arrays.asList(parentReference));

        file = insertFile(file, ModelUtils.createJSONForTag(localTag).toString());

        TagModel remoteTag = new TagModel();
        remoteTag.setLocalId(localTag.getLocalId());
        remoteTag.setRemoteId(file.getId());
        remoteTag.setLastModifiedDate(file.getModifiedDate().getValue());
        return remoteTag;
    }

    @Override
    public Long updateTag(TagModel localTag) throws IOException {
        File driveFile = ModelUtils.createDriveFileForTag(localTag);
        String fileContent;

        // See FORWARD COMPATIBILITY comment.
        JSONObject localTagObj = ModelUtils.createJSONForTag(localTag);
        try {
            String oldFileContent = getFileContent(driveFile);
            if(!TextUtils.isEmpty(oldFileContent)) {
                JSONObject remoteTagObj = new JSONObject(oldFileContent);
                ModelUtils.mergeJSON(remoteTagObj, localTagObj);
                fileContent = remoteTagObj.toString();
            }
            else {
                fileContent = localTagObj.toString();
            }
        }
        catch(JSONException e) {
            Crashlytics.getInstance().core.logException(e);
            fileContent = localTagObj.toString();
        }

        driveFile = updateFile(driveFile, fileContent);
        return driveFile.getModifiedDate().getValue();
    }

    @Override
    public void deleteTag(String remoteTagId) throws IOException {
        File driveFile = new File();
        driveFile.setId(remoteTagId);
        deleteFile(driveFile);
    }

    @NonNull
    @Override
    public List<TagModel> getNewTags(List<String> remoteTagIdsAlreadyLocal) throws IOException {
        List<TagModel> newTagsList = new ArrayList<>();
        for(Map.Entry<String, File> entry : changedFiles.get(tagsFolder).entrySet()) {
            String driveFileId = entry.getKey();
            File file = entry.getValue();
            if(!remoteTagIdsAlreadyLocal.contains(driveFileId) && file != null) {
                String fileContent = getFileContent(file);

                TagModel remoteTag = ModelUtils.createTagFromDriveFile(file, fileContent);
                newTagsList.add(remoteTag);
            }
        }

        return newTagsList;
    }

    @Override
    public void deleteTagIcon(String remoteId, boolean isIconId) throws IOException {
        String remoteIconId = null;
        if(isIconId) {
            remoteIconId = remoteId;
        }
        else {
            File tagDriveFile = getFile(remoteId);
            if(tagDriveFile != null) {
                try {
                    TagModel remoteTag = ModelUtils.createTagFromJSON(new JSONObject(getFileContent(tagDriveFile)));
                    remoteIconId = remoteTag.getRemoteIconId();
                }
                catch(JSONException e) { }
            }
        }

        if(remoteIconId != null) {
            File iconDriveFile = new File();
            iconDriveFile.setId(remoteIconId);
            deleteFile(iconDriveFile);
        }
    }

    @Override
    public TagModel uploadTagIcon(TagModel localTag) throws IOException {
        java.io.File localFile = new java.io.File(localTag.iconPath);
        if(!localFile.exists()) {
            // The user set the icon for the tag and then deleted the icon file.
            return null;
        }

        FileContent fileContent = new FileContent("image/jpeg", localFile);
        File driveFile = new File();
        if(localTag.getRemoteIconId() != null) {
            driveFile.setId(localTag.getRemoteIconId());
            driveFile.setTitle(localTag.name);
            driveFile = updateFile(driveFile, fileContent);
        }
        else {
            ParentReference parentReference = new ParentReference();
            parentReference.setId(metadataFolder.getId());
            driveFile.setParents(Arrays.asList(parentReference));
            driveFile.setTitle(localTag.name);
            driveFile.setMimeType("image/jpeg");
            driveFile = insertFile(driveFile, fileContent);
        }

        TagModel remoteTag = new TagModel();
        remoteTag.setLocalId(localTag.getLocalId());
        remoteTag.setRemoteIconId(driveFile.getId());
        remoteTag.setLastModifiedDate(driveFile.getModifiedDate().getValue());
        return remoteTag;
    }

    @Override
    public boolean downloadTagIcon(TagModel remoteTag) throws IOException, PhotoFileCreateException {
        if(remoteTag.getRemoteIconId() == null) {
            return false;
        }
        File driveFile = getFile(remoteTag.getRemoteIconId());
        if(driveFile == null) {
            return false;
        }

        // Download the image to the local file.
        if(remoteTag.iconPath != null) {
            java.io.File localFile = new java.io.File(remoteTag.iconPath);
            if(localFile.exists()) {
                java.io.File tempLocalFile = createTempFile(driveFile.getTitle());
                try {
                    if(!downloadFile(driveFile, tempLocalFile)) {
                        return false;
                    }

                    // Copy the temp file to the real file, then delete the temp. This is done
                    // because if the process of downloading the photo is aborted for any reason,
                    // the original file won't get partially overwritten.
                    FileChannel src = new FileInputStream(tempLocalFile).getChannel();
                    FileChannel dest = new FileOutputStream(localFile).getChannel();
                    dest.transferFrom(src, 0 , src.size());
                    src.close(); dest.close();

                    return true;
                }
                catch(IOException ioe) {
                    localFile.delete();
                    throw ioe;
                }
                finally {
                    tempLocalFile.delete();
                }
            }
        }

        // This covers the usecase that the user deletes the photo and then tries to sync; the photo
        // will be re-downloaded from Drive and added to the ScoutLog folder.
        java.io.File localFile = createTempFile(driveFile.getTitle());
        try {
            if(!downloadFile(driveFile, localFile)) {
                return false;
            }
        }
        catch(IOException ioe) {
            // Photo was only partially downloaded, so clean up by deleting it.
            localFile.delete();
            throw ioe;
        }

        // Add the photo to the gallery.
        FileUtils.scanGalleryPhoto(context, localFile);

        remoteTag.iconPath = localFile.getAbsolutePath();
        return true;
    }

    @Override
    public PhotoInfo getChangedPhoto(PhotoInfo localPhoto) throws IOException {
        if(changedFiles.get(photosFolder).containsKey(localPhoto.getRemoteId())) {
            PhotoInfo remotePhoto = new PhotoInfo();
            remotePhoto.setLocalId(localPhoto.getLocalId());
            remotePhoto.setRemoteId(localPhoto.getRemoteId());

            File driveFile = changedFiles.get(photosFolder).get(localPhoto.getRemoteId());
            if(driveFile != null) {
                remotePhoto.setLastModifiedDate(driveFile.getModifiedDate().getValue());
            }
            else {
                // File has been deleted/trashed.
                remotePhoto.setDeleted(true);
            }

            return remotePhoto;
        }

        return null;
    }

    @NonNull
    @Override
    public List<PhotoInfo> getNewPhotos(List<String> remotePhotoIdsAlreadyLocal) throws IOException {
        List<PhotoInfo> newPhotosList = new ArrayList<>();
        for(Map.Entry<String, File> entry : changedFiles.get(photosFolder).entrySet()) {
            String driveFileId = entry.getKey();
            File driveFile = entry.getValue();
            if(!remotePhotoIdsAlreadyLocal.contains(driveFileId) && driveFile != null) {
                PhotoInfo remotePhoto = ModelUtils.createPhotoFromDriveFile(driveFile);
                newPhotosList.add(remotePhoto);
            }
        }

        return newPhotosList;
    }

    @Override
    public String getMD5Checksum(String id) throws IOException {
        File file = getFile(id);
        if(file != null) {
            return file.getMd5Checksum();
        }
        return null;
    }

    @Override
    public boolean downloadPhoto(PhotoInfo remotePhoto) throws IOException, PhotoFileCreateException {
        File driveFile = getFile(remotePhoto.getRemoteId());
        if(driveFile == null) {
            CrashlyticsCore.getInstance().logException(new RuntimeException("Photo file doesn't exist for some reason. How did this happen??"));
            return false;
        }

        // Download the image to the local file.
        if(remotePhoto.path != null) {
            java.io.File localFile = new java.io.File(remotePhoto.path);
            if(localFile.exists()) {
                java.io.File tempLocalFile = createTempFile(driveFile.getTitle());
                try {
                    if(!downloadFile(driveFile, tempLocalFile)) {
                        return false;
                    }

                    // Copy the temp file to the real file, then delete the temp. This is done
                    // because if the process of downloading the photo is aborted for any reason,
                    // the original file won't get partially overwritten.
                    FileChannel src = new FileInputStream(tempLocalFile).getChannel();
                    FileChannel dest = new FileOutputStream(localFile).getChannel();
                    dest.transferFrom(src, 0 , src.size());
                    src.close(); dest.close();

                    return true;
                }
                catch(IOException ioe) {
                    localFile.delete();
                    throw ioe;
                }
                finally {
                    tempLocalFile.delete();
                }
            }
        }

        // This covers the usecase that the user deletes the photo and then tries to sync; the photo
        // will be re-downloaded from Drive and added to the ScoutLog folder.
        java.io.File localFile = createTempFile(driveFile.getTitle());
        try {
            if(!downloadFile(driveFile, localFile)) {
                return false;
            }
        }
        catch(IOException ioe) {
            // Photo was only partially downloaded, so clean up by deleting it.
            localFile.delete();
            throw ioe;
        }

        // Add the photo to the gallery.
        FileUtils.scanGalleryPhoto(context, localFile);

        remotePhoto.path = localFile.getAbsolutePath();
        return true;
    }

    protected java.io.File createTempFile(String title) throws IOException, PhotoFileCreateException {
        // TODO: Grab extension by the mimetype.
        int idxPeriod = title.lastIndexOf('.');
        String extension = (idxPeriod > -1) ? title.substring(idxPeriod) : ".jpg";
        return FileUtils.createPhotoFile(context, extension);
    }

    protected boolean downloadFile(File driveFile, java.io.File localFile) throws IOException {
        GenericUrl downloadUrl = new GenericUrl(driveFile.getDownloadUrl());
        HttpResponse resp;
        try {
            resp = service.getRequestFactory().buildGetRequest(downloadUrl).execute();
        }
        catch(HttpResponseException e) {
            if(e.getStatusCode() == 404) {
                // There is apparently a case where a photo change record can exist,
                // but the photo itself doesn't exist on the server anymore. I don't
                // know how this case can arise but we can handle it here.
                return false;
            }
            else {
                throw e;
            }
        }

        FileUtils.writeStreamToFile(resp.getContent(), localFile);
        return true;
    }

    @Override
    public PhotoInfo uploadPhoto(PhotoInfo localPhoto) throws IOException {
        java.io.File localFile = new java.io.File(localPhoto.path);
        if(!localFile.exists()) {
            // The user added a photo and then deleted the photo file.
            return null;
        }

        FileContent fileContent = new FileContent("image/jpeg", localFile);
        File driveFile = new File();
        if(localPhoto.getRemoteId() != null) {
            driveFile.setId(localPhoto.getRemoteId());
            driveFile.setTitle(localPhoto.title);
            driveFile = updateFile(driveFile, fileContent);
        }
        else {
            ParentReference parentReference = new ParentReference();
            parentReference.setId(photosFolder.getId());
            driveFile.setParents(Arrays.asList(parentReference));
            driveFile.setTitle(localPhoto.title);
            driveFile.setMimeType("image/jpeg");
            driveFile = insertFile(driveFile, fileContent);
        }

        PhotoInfo remotePhoto = new PhotoInfo();
        remotePhoto.setLocalId(localPhoto.getLocalId());
        remotePhoto.setRemoteId(driveFile.getId());
        remotePhoto.setLastModifiedDate(driveFile.getModifiedDate().getValue());
        return remotePhoto;
    }

    @Override
    public void deletePhoto(String remotePhotoId) throws IOException {
        // Delete the photo file on Drive.
        File driveFile = new File();
        driveFile.setId(remotePhotoId);
        deleteFile(driveFile);
    }

    @Override
    public LocationModel getChangedLocation(LocationModel localLocation) throws IOException {
        if(changedFiles.get(locationsFolder).containsKey(localLocation.getRemoteId())) {
            LocationModel remoteLocation = new LocationModel();
            remoteLocation.mergeFrom(localLocation, false);

            File driveFile = changedFiles.get(locationsFolder).get(localLocation.getRemoteId());
            if(driveFile != null) {
                String fileContent = getFileContent(driveFile);
                remoteLocation.mergeFrom(ModelUtils.createLocationFromDriveFile(driveFile, fileContent), true);
            }
            else {
                // File has been deleted/trashed.
                remoteLocation.setDeleted(true);
            }

            return remoteLocation;
        }

        return null;
    }

    @NonNull
    @Override
    public List<LocationModel> getNewLocations(List<String> remoteLocationIdsAlreadyLocal) throws IOException {
        List<LocationModel> newLocationsList = new ArrayList<>();
        for(Map.Entry<String, File> entry : changedFiles.get(locationsFolder).entrySet()) {
            String driveFileId = entry.getKey();
            File driveFile = entry.getValue();
            if(!remoteLocationIdsAlreadyLocal.contains(driveFileId) && driveFile != null) {
                String fileContent = getFileContent(driveFile);

                LocationModel remoteLocation = ModelUtils.createLocationFromDriveFile(driveFile, fileContent);
                newLocationsList.add(remoteLocation);
            }
        }

        return newLocationsList;
    }

    @Override
    public LocationModel createLocation(LocationModel localLocation) throws IOException {
        File driveFile = ModelUtils.createDriveFileForLocation(localLocation);
        ParentReference parentReference = new ParentReference();
        parentReference.setId(locationsFolder.getId());
        driveFile.setParents(Arrays.asList(parentReference));

        String fileContent = ModelUtils.createJSONForLocation(localLocation).toString();
        driveFile = insertFile(driveFile, fileContent);

        LocationModel remoteLocation = new LocationModel();
        remoteLocation.setLocalId(localLocation.getLocalId());
        remoteLocation.setRemoteId(driveFile.getId());
        remoteLocation.setLastModifiedDate(driveFile.getModifiedDate().getValue());
        return remoteLocation;
    }

    @Override
    public Long updateLocation(LocationModel localLocation) throws IOException {
        File driveFile = ModelUtils.createDriveFileForLocation(localLocation);
        String fileContent;

        // FORWARD COMPATIBILITY
        //
        // This logic merges the local JSONObject of the location with the remote JSONObject,
        // to support forward compatibility. There may be properties in the remote JSONObject
        // that this app's version doesn't support, but we take care not to wipe them when
        // syncing.
        JSONObject localLocationObj = ModelUtils.createJSONForLocation(localLocation);
        try {
            String oldFileContent = getFileContent(driveFile);
            if(!TextUtils.isEmpty(oldFileContent)) {
                JSONObject remoteLocationObj = new JSONObject(oldFileContent);
                ModelUtils.mergeJSON(remoteLocationObj, localLocationObj);
                fileContent = remoteLocationObj.toString();
            }
            else {
                fileContent = localLocationObj.toString();
            }
        }
        catch(JSONException e) {
            Crashlytics.getInstance().core.logException(e);
            fileContent = localLocationObj.toString();
        }

        driveFile = updateFile(driveFile, fileContent);
        return driveFile.getModifiedDate().getValue();
    }

    @Override
    public void deleteLocation(String remoteLocationId) throws IOException {
        File driveFile = new File();
        driveFile.setId(remoteLocationId);
        deleteFile(driveFile);
    }

    @Override
    public TagModel getChangedHiddenTag(TagModel localTag) throws IOException {
        if(changedFiles.get(hiddenTagsFolder).containsKey(localTag.getRemoteId())) {
            TagModel remoteTag = new TagModel();
            remoteTag.mergeFrom(localTag, false);

            File driveFile = changedFiles.get(hiddenTagsFolder).get(localTag.getRemoteId());
            if(driveFile != null) {
                String fileContent = getFileContent(driveFile);
                remoteTag.mergeFrom(ModelUtils.createHiddenTagFromDriveFile(driveFile, fileContent), true);
            }
            else {
                // File has been deleted/trashed.
                remoteTag.setDeleted(true);
            }

            return remoteTag;
        }

        return null;
    }

    @Override
    public TagModel createHiddenTag(TagModel localTag) throws IOException {
        return createTag(localTag, hiddenTagsFolder);
    }

    @Override
    public Long updateHiddenTag(TagModel localTag) throws IOException {
        return updateTag(localTag);
    }

    @Override
    public void deleteHiddenTag(String remoteTagId) throws IOException {
        deleteTag(remoteTagId);
    }

    @NonNull
    @Override
    public List<TagModel> getNewHiddenTags(List<String> remoteTagIdsAlreadyLocal) throws IOException {
        List<TagModel> newTagsList = new ArrayList<>();
        for(Map.Entry<String, File> entry : changedFiles.get(hiddenTagsFolder).entrySet()) {
            String driveFileId = entry.getKey();
            File file = entry.getValue();
            if(!remoteTagIdsAlreadyLocal.contains(driveFileId) && file != null) {
                String fileContent = getFileContent(file);
                TagModel remoteTag = ModelUtils.createHiddenTagFromDriveFile(file, fileContent);
                newTagsList.add(remoteTag);
            }
        }

        return newTagsList;
    }

    // Returns a map of file id to file; contains all changed files in Drive. If a file has been
    // trashed or deleted, it puts null inside the map.
    protected Map<File, Map<String, File>> fetchChangedFiles() throws IOException {
        Map<File, Map<String, File>> resultMap = new HashMap<>();
        resultMap.put(tagsFolder, new HashMap<String, File>());
        resultMap.put(photosFolder, new HashMap<String, File>());
        resultMap.put(locationsFolder, new HashMap<String, File>());
        resultMap.put(hiddenTagsFolder, new HashMap<String, File>());

        if(driveChangeIdSinceLastSync == -1) {
            // Fetch all the files from Drive.
            for(File folder : contentFolders) {
                Drive.Files.List request = service.files().list().setQ("'" + folder.getId() + "' in parents and trashed = false");
                do {
                    FileList fileList = request.execute();
                    for(File file : fileList.getItems()) {
                        resultMap.get(folder).put(file.getId(), file);
                    }
                    request.setPageToken(fileList.getNextPageToken());
                }
                while(request.getPageToken() != null && request.getPageToken().length() > 0);
            }
        }
        else {
            Drive.Changes.List request = service.changes().list().setIncludeSubscribed(false).setStartChangeId(driveChangeIdSinceLastSync);
            do {
                ChangeList changeList = request.execute();
                for(Change change : changeList.getItems()) {
                    if(change.getDeleted()) {
                        resultMap.get(tagsFolder).put(change.getFileId(), null);
                        resultMap.get(photosFolder).put(change.getFileId(), null);
                        resultMap.get(locationsFolder).put(change.getFileId(), null);
                        resultMap.get(hiddenTagsFolder).put(change.getFileId(), null);
                        continue;
                    }

                    // Filter the changes so that we only look at the ones under our folders. Unfortunately,
                    // we can't see where deleted files used to live so we just add them all to the result map.
                    File file = change.getFile();
                    File parentFolder = getParentFolder(file);
                    if(parentFolder != null) {
                        Map<String, File> changes = resultMap.get(parentFolder);
                        if(file.getLabels().getTrashed()) {
                            changes.put(change.getFileId(), null);
                        }
                        else if(!changes.containsKey(change.getFileId())) {
                            changes.put(change.getFileId(), file);
                        }
                    }
                }

                long largestChangeIdInList = changeList.getLargestChangeId();
                if(driveChangeIdSinceLastSync < largestChangeIdInList) {
                    driveChangeIdSinceLastSync = largestChangeIdInList;
                }
                request.setPageToken(changeList.getNextPageToken());
            }
            while(request.getPageToken() != null && request.getPageToken().length() > 0);
        }

        return resultMap;
    }

    protected File getFile(String fileId) throws IOException {
        try {
            File file = service.files().get(fileId).execute();
            if(!file.getLabels().getTrashed()) {
                return file;
            }
        }
        catch(GoogleJsonResponseException e) {
            if(e.getStatusCode() != 404) {
                // 404s are currently ignored.
                throw e;
            }
        }

        return null;
    }

    protected String getFileContent(File driveFile) throws IOException {
        String result = "";

        if(driveFile.getDownloadUrl() != null && driveFile.getDownloadUrl().length() > 0) {
            GenericUrl downloadUrl = new GenericUrl(driveFile.getDownloadUrl());
            HttpResponse resp = service.getRequestFactory().buildGetRequest(downloadUrl).execute();
            InputStream inputStream = null;
            try {
                inputStream = resp.getContent();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                StringBuilder content = new StringBuilder();
                char[] buffer = new char[1024];
                int numBytesRead;
                while((numBytesRead = reader.read(buffer)) > 0) {
                    content.append(buffer, 0, numBytesRead);
                }
                result = content.toString();
            }
            finally {
                if(inputStream != null) {
                    inputStream.close();
                }
            }
        }
        else {
            // The file doesn't have any content stored on Drive; ignoring for now.
        }

        return result;
    }

    protected File getParentFolder(File file) {
        for(ParentReference parentReference : file.getParents()) {
            for(File folder : contentFolders) {
                if(parentReference.getId().equals(folder.getId())) {
                    return folder;
                }
            }
        }

        return null;
    }

    // -----------------------------------------------------------------------------------
    // This section holds methods that make common requests to Google Drive.
    // -----------------------------------------------------------------------------------
    protected File insertFile(File file, String fileContent) throws IOException {
        return insertFile(file, ByteArrayContent.fromString(MIME_TYPE_TEXT, fileContent));
    }

    protected File insertFile(File file, AbstractInputStreamContent fileContent) throws IOException {
        return service.files().insert(file, fileContent).execute();
    }

    protected File updateFile(File file, String fileContent) throws IOException {
        return updateFile(file, ByteArrayContent.fromString(MIME_TYPE_TEXT, fileContent));
    }

    protected File updateFile(File file, AbstractInputStreamContent fileContent) throws IOException {
        return service.files().update(file.getId(), file, fileContent).execute();
    }

    protected void deleteFile(File file) throws IOException {
        try {
            service.files().delete(file.getId()).execute();
        }
        catch(GoogleJsonResponseException e) {
            if(e.getStatusCode() != 404) {
                // 404s are currently ignored.
                throw e;
            }
        }
    }

}
