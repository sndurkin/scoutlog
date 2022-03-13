package com.sndurkin.locationscout.dataload;

import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.LocationModel;

import java.sql.Date;
import java.util.Random;

// This isn't a testcase; it's just an easy way to load data.
public class LoadLargeDataset extends AndroidTestCase {

    protected DatabaseHelper database;
    protected RenamingDelegatingContext context;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        context = new RenamingDelegatingContext(getContext(), "test_");
        database = DatabaseHelper.getInstance(context);
    }

    public void test() {
        Random rand = new Random();
        for(int i = 1; i <= 150; ++i) {
            LocationModel location = new LocationModel();
            location.setTitle("Test" + i);
            location.setDate(new Date(new java.util.Date().getTime()));
            LocationInfo locationInfo = new LocationInfo();
            locationInfo.isAddressDerived = true;
            locationInfo.location = new LatLng(rand.nextInt(181) - 90, rand.nextInt(361) - 180);
            location.setLocationInfo(locationInfo);
            database.saveLocation(location);
        }
    }

}
