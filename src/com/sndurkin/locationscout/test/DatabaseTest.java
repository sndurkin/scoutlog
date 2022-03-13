package com.sndurkin.locationscout.test;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.util.MiscUtils;

import junit.framework.Assert;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class DatabaseTest extends AndroidTestCase {

    protected DatabaseHelper database;
    protected RenamingDelegatingContext context;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        context = new RenamingDelegatingContext(getContext(), "test_");
        database = DatabaseHelper.getInstance(context);

        clearLocalData();
    }

    public void testLinkedLocations() {
        List<Long> locationIds = new ArrayList<>();
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor;

        // Create linked locations.
        locationIds.add(createLocation());
        locationIds.add(createLocation());
        locationIds.add(createLocation());
        database.saveLinkedLocations(locationIds);

        // Verify that hidden tag & location_tag_pairs records were created.
        cursor = db.rawQuery("SELECT * FROM tags", null);
        Assert.assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        Assert.assertEquals(true, MiscUtils.getBoolean(cursor, DatabaseHelper.TAG_IS_HIDDEN_COLUMN, false).booleanValue());
        cursor.close();

        cursor = db.rawQuery("SELECT * FROM location_tag_pairs", null);
        Assert.assertEquals(3, cursor.getCount());
        cursor.close();

        // Unlink the locations.
        locationIds = Arrays.asList(locationIds.get(0));
        database.saveLinkedLocations(locationIds);

        // Verify that hidden tag & location_tag_pairs records were deleted.
        cursor = db.rawQuery("SELECT * FROM tags", null);
        Assert.assertEquals(0, cursor.getCount());
        cursor.close();

        cursor = db.rawQuery("SELECT * FROM location_tag_pairs", null);
        Assert.assertEquals(0, cursor.getCount());
        cursor.close();
    }

    protected Long createLocation() {
        LocationModel location = new LocationModel();
        location.setTitle("test");
        location.setDate(new Date(new java.util.Date().getTime()));
        location.setNotes("This is a note");
        LocationInfo locationInfo = new LocationInfo();
        locationInfo.addressStr = "123 Main St";
        locationInfo.isAddressDerived = false;
        locationInfo.location = new LatLng(5, -5);
        location.setLocationInfo(locationInfo);
        return database.saveLocation(location);
    }

    protected void clearLocalData() {
        database.getWritableDatabase().delete(DatabaseHelper.TAG_TABLE, null, null);
        database.getWritableDatabase().delete(DatabaseHelper.PHOTO_TABLE, null, null);
        database.getWritableDatabase().delete(DatabaseHelper.LOCATION_TAG_PAIR_TABLE, null, null);
        database.getWritableDatabase().delete(DatabaseHelper.LOCATION_TABLE, null, null);
    }

}
