package com.sndurkin.locationscout.test;

import android.test.AndroidTestCase;

import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.util.LatLngParser;

import junit.framework.Assert;


public class MiscUtilsTest extends AndroidTestCase {

    public void testParseLatLngCoords() {
        // DMS variations
        verifyLatLngCoords("51°30'0.5486\" -0°7'34.4503\"", 51.500152388888885, -0.12623619444444445);
        verifyLatLngCoords("51°30'0.5486\"N 0°7'34.4503\"W", 51.500152388888885, -0.12623619444444445);
        verifyLatLngCoords("51° 30' 00.0\" N 0° 09' 00.0\" W", 51.5, -0.15);
        verifyLatLngCoords("51°30', -0°09'", 51.5, -0.15);
        verifyLatLngCoords("51°30'N, 0°09'W", 51.5, -0.15);

        // DMS variations w/o symbols
        verifyLatLngCoords("51 30.5, 0 9.5", 51.50833333333333, 0.15833333333333333);
        verifyLatLngCoords("51 30.5 0 9.5", 51.50833333333333, 0.15833333333333333);
        verifyLatLngCoords("-51 30.5 0 9.5", -51.50833333333333, 0.15833333333333333);
        verifyLatLngCoords("-51 30.5 -0 9.5", -51.50833333333333, -0.15833333333333333);
        verifyLatLngCoords("51 30.5 -0 9.5", 51.50833333333333, -0.15833333333333333);

        // Decimal degrees
        verifyLatLngCoords("+51.500152388888885, -0.12623619444444445", 51.500152388888885, -0.12623619444444445);
        verifyLatLngCoords("+51.500152388888885 -0.12623619444444445", 51.500152388888885, -0.12623619444444445);
        verifyLatLngCoords("-51.500152388888885 +0.12623619444444445", -51.500152388888885, 0.12623619444444445);
        verifyLatLngCoords("51.500152388888885 0.12623619444444445", 51.500152388888885, 0.12623619444444445);
        verifyLatLngCoords("51.500152388888885 -0.12623619444444445", 51.500152388888885, -0.12623619444444445);
    }

    private void verifyLatLngCoords(String input, Double expectedLatitude, Double expectedLongitude) {
        LatLng latLng = LatLngParser.parse(input);
        Assert.assertNotNull(latLng);
        Assert.assertEquals(expectedLatitude, latLng.latitude);
        Assert.assertEquals(expectedLongitude, latLng.longitude);
    }

    private void verifyInvalidLatLngCoords(String input) {
        Assert.assertNull(LatLngParser.parse(input));
    }

}
