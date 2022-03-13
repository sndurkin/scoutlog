package com.sndurkin.locationscout.test;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.preference.PreferenceManager;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.storage.SyncAlgorithm;
import com.sndurkin.locationscout.storage.SyncListener;
import com.sndurkin.locationscout.storage.TagModel;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;


public class SyncTest extends AndroidTestCase {

    protected DatabaseHelper database;
    protected RenamingDelegatingContext context;
    protected SharedPreferences preferences;
    protected TestDriveSyncer syncer;
    protected SyncListener syncListener;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        context = new RenamingDelegatingContext(getContext(), "test_");
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        database = DatabaseHelper.getInstance(context);
        syncListener = new SyncListener() {
            @Override
            public void beforePhotosSynced(PhotoInfo localPhoto, PhotoInfo remotePhoto) {
                if(localPhoto.getLastModifiedDate() == null) {
                    localPhoto.setLastModifiedDate(new java.util.Date().getTime());
                }
            }

            @Override
            public void onException(Exception e) {
                throw new RuntimeException(e);
            }
        };
        syncer = new TestDriveSyncer(syncListener);

        clearLocalData();
    }

    protected Long createTagLocally(String name) {
        return database.saveTag(new TagModel(null, name));
    }

    protected String createTagRemotely(String name) {
        TagModel remoteTag = new TagModel();
        remoteTag.name = name;
        syncer.createTagForTest(remoteTag);
        return remoteTag.getRemoteId();
    }

    protected String createHiddenTagRemotely() {
        TagModel remoteTag = new TagModel();
        remoteTag.hidden = true;
        syncer.createTagForTest(remoteTag);
        return remoteTag.getRemoteId();
    }

    protected LocationModel createDummyLocation() {
        LocationModel location = new LocationModel();
        location.setTitle("test");
        location.setDate(new Date(new java.util.Date().getTime()));
        location.setNotes("This is a note");
        LocationInfo locationInfo = new LocationInfo();
        locationInfo.addressStr = "123 Main St";
        locationInfo.isAddressDerived = false;
        locationInfo.location = new LatLng(5, -5);
        location.setLocationInfo(locationInfo);
        return location;
    }

    protected TagModel[] fetchSyncedTags() {
        return fetchTags(true);
    }

    protected TagModel[] fetchUnsyncedTags() {
        return fetchTags(false);
    }

    private TagModel[] fetchTags(boolean synced) {
        String tagsQuery = "SELECT * FROM tags WHERE (is_hidden IS NULL OR is_hidden = 0)\n";
        if(synced) {
            tagsQuery += "AND drive_file_id IS NOT NULL\n";
        }
        else {
            tagsQuery += "AND drive_file_id IS NULL\n";
        }

        Cursor cursor = database.getReadableDatabase().rawQuery(tagsQuery, null);
        TagModel[] tags = new TagModel[cursor.getCount()];
        cursor.moveToFirst();
        int i = 0;
        while(!cursor.isAfterLast()) {
            tags[i++] = ModelUtils.createTagFromCursor(cursor);
            cursor.moveToNext();
        }
        cursor.close();
        return tags;
    }

    protected PhotoInfo[] fetchSyncedPhotos() {
        return fetchPhotos(true);
    }

    protected PhotoInfo[] fetchUnsyncedPhotos() {
        return fetchPhotos(false);
    }

    protected PhotoInfo[] fetchPhotos(boolean synced) {
        String photosQuery = "SELECT * from photos";
        if(synced) {
            photosQuery += " WHERE drive_file_id IS NOT NULL";
        }
        else {
            photosQuery += " WHERE drive_file_id IS NULL";
        }

        Cursor cursor = database.getReadableDatabase().rawQuery(photosQuery, null);
        List<PhotoInfo> photos = new ArrayList<>(cursor.getCount());
        cursor.moveToFirst();
        while(!cursor.isAfterLast()) {
            photos.add(ModelUtils.createPhotoFromCursor(cursor));
            cursor.moveToNext();
        }
        cursor.close();

        return photos.toArray(new PhotoInfo[0]);
    }

    protected LocationModel[] fetchSyncedLocations() {
        return fetchLocations(true);
    }

    protected LocationModel[] fetchUnsyncedLocations() {
        return fetchLocations(false);
    }

    private LocationModel[] fetchLocations(boolean synced) {
        String locationsQuery = "SELECT * FROM locations";
        if(synced) {
            locationsQuery += " WHERE drive_file_id IS NOT NULL";
        }
        else {
            locationsQuery += " WHERE drive_file_id IS NULL";
        }
        String tagsQuery = "SELECT t._id FROM tags t\n"
                         + "INNER JOIN location_tag_pairs lt ON t._id = lt.tag_id\n"
                         + "WHERE (t.is_hidden IS NULL OR t.is_hidden = 0)\n"
                         + "AND lt.location_id = ?\n"
                         + "ORDER BY lt.location_tag_sort_num\n";
        String photosQuery = "SELECT * from photos where location_id = ?";

        Cursor locationCursor = database.getReadableDatabase().rawQuery(locationsQuery, null);
        List<LocationModel> locations = new ArrayList<>(locationCursor.getCount());
        locationCursor.moveToFirst();
        while(!locationCursor.isAfterLast()) {
            LocationModel location = ModelUtils.createLocationFromCursorForDisplay(locationCursor);
            locations.add(location);

            // Fetch tag ids tied to this location.
            Cursor tagCursor = database.getReadableDatabase().rawQuery(tagsQuery, new String[] { location.getLocalId().toString() });
            location.setTagIds(new ArrayList<Long>(tagCursor.getCount()));
            tagCursor.moveToFirst();
            while(!tagCursor.isAfterLast()) {
                location.getTagIds().add(tagCursor.getLong(tagCursor.getColumnIndex(DatabaseHelper.ID_COLUMN)));
                tagCursor.moveToNext();
            }
            tagCursor.close();

            // Fetch photos tied to this location.
            Cursor photoCursor = database.getReadableDatabase().rawQuery(photosQuery, new String[] { location.getLocalId().toString() });
            location.setPhotoInfoList(new ArrayList<PhotoInfo>(photoCursor.getCount()));
            photoCursor.moveToFirst();
            while(!photoCursor.isAfterLast()) {
                location.getPhotoInfoList().add(ModelUtils.createPhotoFromCursor(photoCursor));
                photoCursor.moveToNext();
            }
            photoCursor.close();

            locationCursor.moveToNext();
        }
        locationCursor.close();

        return locations.toArray(new LocationModel[0]);
    }

    protected TagModel[] fetchSyncedHiddenTags() {
        return fetchHiddenTags(true);
    }

    protected TagModel[] fetchUnsyncedHiddenTags() {
        return fetchHiddenTags(false);
    }

    private TagModel[] fetchHiddenTags(boolean synced) {
        String tagsQuery = "SELECT * FROM tags WHERE is_hidden = 1\n";
        if(synced) {
            tagsQuery += "AND drive_file_id IS NOT NULL\n";
        }
        else {
            tagsQuery += "AND drive_file_id IS NULL\n";
        }

        String locationsQuery = "SELECT l._id FROM locations l\n"
                              + "INNER JOIN location_tag_pairs lt ON l._id = lt.location_id\n"
                              + "AND lt.tag_id = ?\n"
                              + "ORDER BY lt.location_tag_sort_num\n";

        Cursor tagCursor = database.getReadableDatabase().rawQuery(tagsQuery, null);
        List<TagModel> tags = new ArrayList<>(tagCursor.getCount());
        tagCursor.moveToFirst();
        while(!tagCursor.isAfterLast()) {
            TagModel tag = ModelUtils.createTagFromCursor(tagCursor);
            tags.add(tag);

            // Fetch location ids tied to this tag.
            Cursor locationCursor = database.getReadableDatabase().rawQuery(locationsQuery, new String[] { tag.getLocalId().toString() });
            tag.setLocationIds(new ArrayList<Long>(locationCursor.getCount()));
            locationCursor.moveToFirst();
            while(!locationCursor.isAfterLast()) {
                tag.getLocationIds().add(locationCursor.getLong(locationCursor.getColumnIndex(DatabaseHelper.ID_COLUMN)));
                locationCursor.moveToNext();
            }
            locationCursor.close();

            tagCursor.moveToNext();
        }
        tagCursor.close();

        return tags.toArray(new TagModel[0]);
    }

    protected void executeSyncAlgorithm() {
        SyncAlgorithm.execute(syncer, database, preferences, syncListener);
    }

    protected void clearLocalData() {
        database.getWritableDatabase().delete(DatabaseHelper.TAG_TABLE, null, null);
        database.getWritableDatabase().delete(DatabaseHelper.PHOTO_TABLE, null, null);
        database.getWritableDatabase().delete(DatabaseHelper.LOCATION_TAG_PAIR_TABLE, null, null);
        database.getWritableDatabase().delete(DatabaseHelper.LOCATION_TABLE, null, null);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

}
