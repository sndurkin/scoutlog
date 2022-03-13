package com.sndurkin.locationscout.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.text.TextUtils;

import com.crashlytics.android.Crashlytics;
import com.google.android.gms.maps.model.LatLng;
import com.google.api.services.drive.model.File;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.util.Strings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

// Utility class that contains functions for converting Models to ContentValues
// and from Cursor, as well as to/from Drive.File.
public class ModelUtils {

    // ------------------------------------------------------------------------
    // TAGS
    // ------------------------------------------------------------------------

    public static TagModel createTagFromCursor(Cursor cursor) {
        TagModel tag = new TagModel(
                MiscUtils.getLong(cursor, DatabaseHelper.ID_COLUMN),
                MiscUtils.getString(cursor, DatabaseHelper.TAG_NAME_COLUMN),
                MiscUtils.getInt(cursor, DatabaseHelper.TAG_COLOR_COLUMN),
                MiscUtils.getBoolean(cursor, DatabaseHelper.TAG_IS_HIDDEN_COLUMN)
        );

        if(!MiscUtils.isNull(cursor, DatabaseHelper.ICON_PATH_COLUMN)) {
            tag.iconPath = MiscUtils.getString(cursor, DatabaseHelper.ICON_PATH_COLUMN);
            tag.remoteIconId = MiscUtils.getString(cursor, DatabaseHelper.ICON_DRIVE_FILE_ID_COLUMN);
        }

        tag.setLastModifiedDate(MiscUtils.getLong(cursor, DatabaseHelper.LAST_MODIFIED_DATE_COLUMN));
        tag.setRemoteId(MiscUtils.getString(cursor, DatabaseHelper.DRIVE_FILE_ID_COLUMN));
        tag.setDeleted(MiscUtils.getBoolean(cursor, DatabaseHelper.IS_DELETED_COLUMN));

        return tag;
    }

    public static TagModel createHiddenTagFromCursor(Cursor cursor) {
        TagModel tag = createTagFromCursor(cursor);

        String remoteLocationIdsStr = cursor.getString(cursor.getColumnIndex("location_drive_ids"));
        if(remoteLocationIdsStr != null) {
            tag.setRemoteLocationIds(Arrays.asList(remoteLocationIdsStr.split(",")));
        }

        return tag;
    }

    public static ContentValues createContentValuesForTagCreate(TagModel tag) {
        ContentValues values = createContentValuesForTagUpdate(tag);
        if(tag.getLastModifiedDate() != null) {
            values.put(DatabaseHelper.CREATED_DATE_COLUMN, tag.getLastModifiedDate());
        }
        return values;
    }

    public static ContentValues createContentValuesForTagUpdate(TagModel tag) {
        ContentValues values = new ContentValues();
        if(tag.name != null) {
            values.put(DatabaseHelper.TAG_NAME_COLUMN, tag.name);
        }
        if(tag.getLastModifiedDate() != null) {
            values.put(DatabaseHelper.LAST_MODIFIED_DATE_COLUMN, tag.getLastModifiedDate());
        }
        if(tag.color != null) {
            if(tag.color == 0) {
                values.putNull(DatabaseHelper.TAG_COLOR_COLUMN);
            }
            else {
                values.put(DatabaseHelper.TAG_COLOR_COLUMN, tag.color);
            }
            values.putNull(DatabaseHelper.ICON_PATH_COLUMN);
            values.putNull(DatabaseHelper.ICON_DRIVE_FILE_ID_COLUMN);
        }
        if(tag.iconPath != null) {
            if(!tag.iconPath.isEmpty()) {
                values.put(DatabaseHelper.ICON_PATH_COLUMN, tag.iconPath);
                values.putNull(DatabaseHelper.TAG_COLOR_COLUMN);
            }
            else {
                values.putNull(DatabaseHelper.ICON_PATH_COLUMN);
            }
            values.putNull(DatabaseHelper.ICON_DRIVE_FILE_ID_COLUMN);
        }
        if(tag.remoteIconId != null) {
            values.put(DatabaseHelper.ICON_DRIVE_FILE_ID_COLUMN, tag.remoteIconId);
        }
        if(tag.hidden != null) {
            values.put(DatabaseHelper.TAG_IS_HIDDEN_COLUMN, tag.hidden);
        }
        if(tag.getRemoteId() != null) {
            values.put(DatabaseHelper.DRIVE_FILE_ID_COLUMN, tag.getRemoteId());
        }
        return values;
    }

    public static TagModel createTagFromDriveFile(File file, String fileContent) {
        TagModel tag = new TagModel();
        tag.setRemoteId(file.getId());
        tag.setLastModifiedDate(file.getModifiedDate().getValue());

        // Backward compatibility
        tag.name = file.getTitle();

        if(!fileContent.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(fileContent);
                tag.mergeFrom(ModelUtils.createTagFromJSON(obj), false);
            }
            catch(JSONException e) {
                Crashlytics.getInstance().core.logException(e);
            }
        }
        return tag;
    }

    public static TagModel createTagFromJSON(JSONObject obj) {
        TagModel tag = new TagModel();
        try {
            if(obj.has(Strings.PARAM_NAME)) {
                tag.name = obj.getString(Strings.PARAM_NAME);
            }
            if(obj.has(Strings.PARAM_COLOR)) {
                tag.color = obj.getInt(Strings.PARAM_COLOR);
            }
            if(obj.has(Strings.PARAM_ICON_ID)) {
                tag.remoteIconId = obj.getString(Strings.PARAM_ICON_ID);
            }
            if(obj.has(Strings.PARAM_HIDDEN)) {
                tag.hidden = obj.getBoolean(Strings.PARAM_HIDDEN);
            }
        }
        catch(JSONException e) {
            Crashlytics.getInstance().core.logException(e);
        }

        return tag;
    }

    public static TagModel createHiddenTagFromDriveFile(File file, String fileContent) {
        TagModel tag = new TagModel();
        tag.setRemoteId(file.getId());
        tag.setLastModifiedDate(file.getModifiedDate().getValue());

        if(!fileContent.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(fileContent);
                JSONArray locationDriveIdsArr = obj.getJSONArray("locationDriveIds");
                List<String> remoteLocationIds = new ArrayList<>(locationDriveIdsArr.length());
                for(int i = 0; i < locationDriveIdsArr.length(); ++i) {
                    remoteLocationIds.add(locationDriveIdsArr.getString(i));
                }
                tag.setRemoteLocationIds(remoteLocationIds);
                tag.hidden = true;
            }
            catch(JSONException e) {
                Crashlytics.getInstance().core.logException(e);
            }
        }
        return tag;
    }

    public static File createDriveFileForTag(TagModel tag) {
        File driveFile = new File();
        if(tag.getRemoteId() != null) {
            driveFile.setId(tag.getRemoteId());
        }
        driveFile.setTitle(tag.name);
        return driveFile;
    }

    public static JSONObject createJSONForTag(TagModel tag) {
        JSONObject obj = new JSONObject();
        try {
            obj.put(Strings.PARAM_NAME, tag.name);
            obj.put(Strings.PARAM_COLOR, tag.color != null ? tag.color : 0);
            obj.put(Strings.PARAM_ICON_ID, tag.remoteIconId);
            obj.put(Strings.PARAM_HIDDEN, tag.hidden);
            if(tag.getRemoteLocationIds() != null) {
                JSONArray tagsArr = new JSONArray();
                for(String remoteTagId : tag.getRemoteLocationIds()) {
                    tagsArr.put(remoteTagId);
                }
                obj.put("locationDriveIds", tagsArr);
            }
        }
        catch(JSONException e) {
            Crashlytics.getInstance().core.logException(e);
        }
        return obj;
    }

    // ------------------------------------------------------------------------
    // PHOTOS
    // ------------------------------------------------------------------------

    public static PhotoInfo createPhotoFromCursor(Cursor cursor) {
        PhotoInfo photo = new PhotoInfo();

        photo.id = MiscUtils.getLong(cursor, DatabaseHelper.ID_COLUMN);
        photo.path = MiscUtils.getString(cursor, DatabaseHelper.PHOTO_PATH_COLUMN);
        photo.sortNum = MiscUtils.getInt(cursor, DatabaseHelper.PHOTO_SORT_NUM_COLUMN);
        photo.notes = MiscUtils.getString(cursor, DatabaseHelper.PHOTO_NOTES_COLUMN);

        String title = MiscUtils.getString(cursor, DatabaseHelper.LOCATION_TITLE_COLUMN);
        String addressStr = MiscUtils.getString(cursor, DatabaseHelper.LOCATION_ADDRESS_STR_COLUMN);
        boolean isAddressDerived = MiscUtils.getBoolean(cursor, DatabaseHelper.LOCATION_ADDRESS_DERIVED_COLUMN, false);
        photo.title = LocationModel.getTitleForDisplay(title, addressStr, isAddressDerived);

        photo.setRemoteId(MiscUtils.getString(cursor, DatabaseHelper.DRIVE_FILE_ID_COLUMN));
        photo.setDeleted(MiscUtils.getBoolean(cursor, DatabaseHelper.IS_DELETED_COLUMN, false));

        return photo;
    }

    public static ContentValues createContentValuesForPhoto(PhotoInfo photo, Long locationId) {
        ContentValues values = new ContentValues();

        if(locationId != null) {
            values.put(DatabaseHelper.FOREIGN_LOCATION_ID_COLUMN, locationId);
        }
        if(photo.path != null) {
            values.put(DatabaseHelper.PHOTO_PATH_COLUMN, photo.path);
        }
        if(photo.notes != null) {
            values.put(DatabaseHelper.PHOTO_NOTES_COLUMN, photo.notes);
        }
        if(photo.sortNum != null) {
            values.put(DatabaseHelper.PHOTO_SORT_NUM_COLUMN, photo.sortNum);
        }

        if(photo.getRemoteId() != null) {
            values.put(DatabaseHelper.DRIVE_FILE_ID_COLUMN, photo.getRemoteId());
        }

        return values;
    }

    public static PhotoInfo createPhotoFromDriveFile(File file) {
        PhotoInfo photo  = new PhotoInfo();
        photo.setRemoteId(file.getId());
        photo.setLastModifiedDate(file.getModifiedDate().getValue());
        return photo;
    }

    public static JSONObject createJSONForPhoto(PhotoInfo photo) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("driveId", photo.getRemoteId());
            obj.put("notes", photo.notes != null ? photo.notes : "");
            obj.put("sortNum", photo.sortNum != null ? photo.sortNum : 0);
        }
        catch(JSONException e) {
            Crashlytics.getInstance().core.logException(e);
        }
        return obj;
    }

    public static PhotoInfo createPhotoFromJSON(JSONObject photoObj) {
        PhotoInfo photo = new PhotoInfo();
        photo.setRemoteId(photoObj.optString("driveId"));
        photo.notes = photoObj.optString("notes");
        photo.sortNum = photoObj.optInt("sortNum");
        return photo;
    }

    // ------------------------------------------------------------------------
    // LOCATIONS
    // ------------------------------------------------------------------------

    // TODO: refactor to use subclass of LocationModel specific for display
    public static LocationModel createLocationFromCursorForDisplay(Cursor cursor) {
        LocationModel location = createLocationFromCursor(cursor);

        PhotoInfo photoInfo = new PhotoInfo();
        int photoPathIdx = cursor.getColumnIndex(DatabaseHelper.PHOTO_PATH_COLUMN);
        if(photoPathIdx != -1) {
            String imagePath = cursor.getString(photoPathIdx);
            if(!TextUtils.isEmpty(imagePath)) {
                photoInfo.path = imagePath;
            }
        }

        int photoRemoteIdIdx = cursor.getColumnIndex("photo_drive_id");
        if(photoRemoteIdIdx != -1) {
            String remotePhotoId = cursor.getString(photoRemoteIdIdx);
            if(!TextUtils.isEmpty(remotePhotoId)) {
                photoInfo.setRemoteId(remotePhotoId);
            }
        }

        if(photoInfo.path != null || photoInfo.getRemoteId() != null) {
            location.addPhotoInfo(photoInfo);
        }

        int tagsStrIdx = cursor.getColumnIndex("tags_str");
        if(tagsStrIdx != -1) {
            location.setTagsStr(cursor.getString(tagsStrIdx));
        }

        int firstTagColorIdx = cursor.getColumnIndex("first_tag_color");
        if(firstTagColorIdx != -1) {
            location.setColor(cursor.getInt(firstTagColorIdx));
        }

        int firstTagIconPathIdx = cursor.getColumnIndex("first_tag_icon_path");
        if(firstTagIconPathIdx != -1) {
            location.setIconPath(cursor.getString(firstTagIconPathIdx));
        }

        return location;
    }

    public static LocationModel createLocationFromCursorForSync(Cursor cursor) {
        LocationModel location = createLocationFromCursor(cursor);

        String remoteTagIdsStr = cursor.getString(cursor.getColumnIndex("tag_drive_ids"));
        if(remoteTagIdsStr != null) {
            location.setRemoteTagIds(Arrays.asList(remoteTagIdsStr.split(",")));
        }

        return location;
    }

    private static LocationModel createLocationFromCursor(Cursor cursor) {
        LocationModel locationModel = new LocationModel();

        locationModel.setLocalId(MiscUtils.getLong(cursor, DatabaseHelper.ID_COLUMN));
        locationModel.setTitle(MiscUtils.getString(cursor, DatabaseHelper.LOCATION_TITLE_COLUMN));
        locationModel.setNotes(MiscUtils.getString(cursor, DatabaseHelper.LOCATION_NOTES_COLUMN));
        Long datetime = MiscUtils.getLong(cursor, DatabaseHelper.LOCATION_DATE_COLUMN);
        if(datetime != null) {
            locationModel.setDate(new Date(datetime));
        }

        locationModel.setRemoteId(MiscUtils.getString(cursor, DatabaseHelper.DRIVE_FILE_ID_COLUMN));
        locationModel.setLastModifiedDate(MiscUtils.getLong(cursor, DatabaseHelper.LAST_MODIFIED_DATE_COLUMN));
        locationModel.setDeleted(MiscUtils.getBoolean(cursor, DatabaseHelper.IS_DELETED_COLUMN));

        if(!cursor.isNull(cursor.getColumnIndex(DatabaseHelper.LOCATION_LATITUDE_COLUMN))) {
            Double latitude = cursor.getDouble(cursor.getColumnIndex(DatabaseHelper.LOCATION_LATITUDE_COLUMN));
            Double longitude = cursor.getDouble(cursor.getColumnIndex(DatabaseHelper.LOCATION_LONGITUDE_COLUMN));
            String addressStr = cursor.getString(cursor.getColumnIndex(DatabaseHelper.LOCATION_ADDRESS_STR_COLUMN));
            boolean isAddressDerived = cursor.getShort(cursor.getColumnIndex(DatabaseHelper.LOCATION_ADDRESS_DERIVED_COLUMN)) == 1;
            locationModel.setLocationInfo(new LocationInfo(new LatLng(latitude, longitude), addressStr, isAddressDerived));
        }

        return locationModel;
    }

    public static ContentValues createContentValuesForLocation(LocationModel location) {
        ContentValues values = new ContentValues();

        if(location.title != null) {
            values.put(DatabaseHelper.LOCATION_TITLE_COLUMN, location.title);
        }
        if(location.notes != null) {
            values.put(DatabaseHelper.LOCATION_NOTES_COLUMN, location.notes);
        }
        if(location.date != null) {
            values.put(DatabaseHelper.LOCATION_DATE_COLUMN, location.date.getTime());
        }
        if(location.getRemoteId() != null) {
            values.put(DatabaseHelper.DRIVE_FILE_ID_COLUMN, location.getRemoteId());
        }

        if(location.getLocationInfo() != null) {
            values.put(DatabaseHelper.LOCATION_LATITUDE_COLUMN, location.getLocationInfo().location.latitude);
            values.put(DatabaseHelper.LOCATION_LONGITUDE_COLUMN, location.getLocationInfo().location.longitude);
            values.put(DatabaseHelper.LOCATION_ADDRESS_STR_COLUMN, location.getLocationInfo().addressStr);
            values.put(DatabaseHelper.LOCATION_ADDRESS_DERIVED_COLUMN, location.getLocationInfo().isAddressDerived);
        }

        if(location.getLastModifiedDate() != null) {
            values.put(DatabaseHelper.LAST_MODIFIED_DATE_COLUMN, location.getLastModifiedDate());
        }
        else {
            values.put(DatabaseHelper.LAST_MODIFIED_DATE_COLUMN, new java.util.Date().getTime());
        }

        return values;
    }

    public static ContentValues createContentValuesForLocationCreate(LocationModel location) {
        ContentValues values = createContentValuesForLocation(location);
        Long lastModifiedDate = values.getAsLong(DatabaseHelper.LAST_MODIFIED_DATE_COLUMN);
        if(lastModifiedDate != null) {
            values.put(DatabaseHelper.CREATED_DATE_COLUMN, lastModifiedDate);
        }
        return values;
    }

    public static LocationModel createLocationFromDriveFile(File file, String fileContent) {
        LocationModel locationModel = new LocationModel();
        locationModel.setRemoteId(file.getId());
        locationModel.setLastModifiedDate(file.getModifiedDate().getValue());
        if(!fileContent.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(fileContent);
                locationModel.setTitle(obj.optString("title"));
                locationModel.setNotes(obj.optString("notes"));
                if(obj.has("created_date")) {
                    locationModel.setDate(new Date(obj.getLong("created_date")));
                }
                else {
                    // Backward compatibility for v1.0 and below.
                    locationModel.setDate(Date.valueOf(obj.getString("date")));
                }

                if(obj.has("location") && !obj.isNull("location")) {
                    JSONObject locationObj = obj.getJSONObject("location");
                    if(locationObj.has("location") && !locationObj.isNull("location")) {
                        locationModel.setLocationInfo(new LocationInfo(obj.getJSONObject("location")));
                    }
                }

                if(obj.has("tagDriveIds") && !obj.isNull("tagDriveIds")) {
                    JSONArray tagDriveIdsArr = obj.getJSONArray("tagDriveIds");
                    List<String> remoteTagIds = new ArrayList<>(tagDriveIdsArr.length());
                    for(int i = 0; i < tagDriveIdsArr.length(); ++i) {
                        remoteTagIds.add(tagDriveIdsArr.getString(i));
                    }
                    locationModel.setRemoteTagIds(remoteTagIds);
                }

                if(obj.has("photos") && !obj.isNull("photos")) {
                    JSONArray photosArr = obj.getJSONArray("photos");
                    List<PhotoInfo> photos = new ArrayList<>(photosArr.length());
                    for(int i = 0; i < photosArr.length(); ++i) {
                        photos.add(createPhotoFromJSON(photosArr.getJSONObject(i)));
                    }
                    locationModel.setPhotoInfoList(photos);
                }
            }
            catch(JSONException e) {
                // TODO: make this more robust?
                throw new RuntimeException(e);
            }
        }
        return locationModel;
    }

    public static File createDriveFileForLocation(LocationModel location) {
        File driveFile = new File();
        if(location.getRemoteId() != null) {
            driveFile.setId(location.getRemoteId());
        }
        driveFile.setTitle(LocationModel.getTitleForDisplay(location.getTitle(), null, false));
        return driveFile;
    }

    public static JSONObject createJSONForLocation(LocationModel location) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", location.getTitle());
            obj.put("notes", location.getNotes());
            obj.put("created_date", location.getDate().getTime());
            // TODO: This property has been deprecated in v1.1, it should be removed in v1.3.
            obj.put("date", location.getDate());
            if(location.getLocationInfo() != null) {
                obj.put("location", location.getLocationInfo().toJSON());
            }

            JSONArray tagsArr = new JSONArray();
            if(location.getRemoteTagIds() != null) {
                for(String remoteTagId : location.getRemoteTagIds()) {
                    tagsArr.put(remoteTagId);
                }
            }
            obj.put("tagDriveIds", tagsArr);

            JSONArray photosArr = new JSONArray();
            List<PhotoInfo> photos = location.getPhotoInfoList();
            if(photos != null) {
                for(PhotoInfo photo : photos) {
                    photosArr.put(ModelUtils.createJSONForPhoto(photo));
                }
            }
            obj.put("photos", photosArr);
        }
        catch(JSONException e) {
            Crashlytics.getInstance().core.logException(e);
        }
        return obj;
    }

    public static void mergeJSON(JSONObject target, JSONObject source) throws JSONException {
        Iterator<String> iter = source.keys();
        while(iter.hasNext()) {
            String propName = iter.next();
            target.put(propName, source.get(propName));
        }
    }

}
