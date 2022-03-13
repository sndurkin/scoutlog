package com.sndurkin.locationscout.test;


import android.net.Uri;
import android.test.AndroidTestCase;
import android.util.Pair;

import com.sndurkin.locationscout.integ.ImportGeoPlaceAsyncTask;
import com.sndurkin.locationscout.integ.ImportURLPlaceAsyncTask;
import com.sndurkin.locationscout.integ.ParseException;
import com.sndurkin.locationscout.integ.PlaceResult;

import junit.framework.Assert;

import java.io.IOException;

public class ImportTest extends AndroidTestCase {

    // Test url: https://goo.gl/VXwz3y (-33.275, 18.675)
    public void testGoogleMapsImport() throws IOException, ParseException {
        PlaceResult placeResult = ImportURLPlaceAsyncTask.importPlace("https://goo.gl/VXwz3y");
        Assert.assertNull(placeResult.errorMsg);
        Assert.assertEquals(-33.275, placeResult.latitude);
        Assert.assertEquals(18.675, placeResult.longitude);
    }

    // Test url: http://her.is/1OddtPc (-33.275, 18.675)
    public void testHereMapsImport() throws IOException, ParseException {
        PlaceResult placeResult = ImportURLPlaceAsyncTask.importPlace("http://her.is/1OddtPc");
        Assert.assertNull(placeResult.errorMsg);
        Assert.assertEquals(-33.275, placeResult.latitude);
        Assert.assertEquals(18.675, placeResult.longitude);
    }

    public void testGeoImport1() {
        PlaceResult placeResult = ImportGeoPlaceAsyncTask.importPlace(Uri.parse("geo:-33.275,18.675"));
        Assert.assertNull(placeResult.errorMsg);
        Assert.assertEquals(-33.275, placeResult.latitude);
        Assert.assertEquals(18.675, placeResult.longitude);
    }

    public void testGeoImport2() {
        PlaceResult placeResult = ImportGeoPlaceAsyncTask.importPlace(Uri.parse("geo:-33.275,18.675?q=-33.275,18.675"));
        Assert.assertNull(placeResult.errorMsg);
        Assert.assertEquals(-33.275, placeResult.latitude);
        Assert.assertEquals(18.675, placeResult.longitude);
    }

    public void testGeoImport3() {
        PlaceResult placeResult = ImportGeoPlaceAsyncTask.importPlace(Uri.parse("geo:-33,18?q=-33,18 (test name)"));
        Assert.assertNull(placeResult.errorMsg);
        Assert.assertEquals(-33.0, placeResult.latitude);
        Assert.assertEquals(18.0, placeResult.longitude);
        Assert.assertEquals("test name", placeResult.title);
    }

}
