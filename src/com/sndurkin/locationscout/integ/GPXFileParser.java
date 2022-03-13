package com.sndurkin.locationscout.integ;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.TagModel;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Date;
import java.text.*;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;


public class GPXFileParser extends FileParser {

    // We don't use namespaces
    private static final String ns = null;

    private static final String TAG_GPX = "gpx";
    private static final String TAG_WAYPOINT = "wpt";
    private static final String ATTR_LATITUDE = "lat";
    private static final String ATTR_LONGITUDE = "lon";
    private static final String TAG_NAME = "name";
    private static final String TAG_TYPE = "type";
    private static final String TAG_DESCRIPTION = "desc";
    private static final String TAG_TIME = "time";

    private SimpleDateFormat[] timeDateFormats;

    public GPXFileParser(Context context, DatabaseHelper database, SharedPreferences preferences) {
        super(context, database, preferences);
    }

    public ParseResult parse(final InputStream in) {
        timeDateFormats = new SimpleDateFormat[] {
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()),
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        };

        ParseResult parseResult = new ParseResult();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null);
            parser.nextTag();
            readGPX(parser, parseResult);
            parseResult.success = true;
        }
        catch(Exception e) {
            CrashlyticsCore.getInstance().logException(e);
        }

        return parseResult;
    }

    private void readGPX(XmlPullParser parser, ParseResult parseResult) throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, ns, TAG_GPX);
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();
            if (name.equals(TAG_WAYPOINT)) {
                LocationModel location = readWaypoint(parser);
                if(location != null) {
                    if(reachedLocationsLimit || (!UIUtils.hasUserUnlockedApp(context) && database.fetchLocationsCount() >= 15)) {
                        reachedLocationsLimit = true;
                        parseResult.locationsIgnored++;
                        continue;
                    }

                    if(preferences.getBoolean(Strings.PREF_IGNORE_IMPORTED_DUPLICATES, true)) {
                        if(database.locationAlreadyExists(location.getLocationInfo().location)) {
                            parseResult.locationsIgnored++;
                            continue;
                        }
                    }

                    Long tagId = null;
                    if(location.getTagIds() != null && !location.getTagIds().isEmpty()) {
                        tagId = location.getTagIds().get(0);
                        location.setTagIds(null);
                    }

                    Long locationId = database.saveLocation(location);
                    parseResult.locationsImported++;
                    if(tagId != null) {
                        database.addTagToLocation(locationId, tagId);
                        parseResult.tagsImported++;
                    }
                }
            }
            else {
                skip(parser);
            }
        }
    }

    // Reads a <wpt> element.
    private LocationModel readWaypoint(XmlPullParser parser) throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, ns, TAG_WAYPOINT);

        Double latitude = null;
        Double longitude = null;
        for(int i = 0; i < parser.getAttributeCount(); ++i) {
            if(parser.getAttributeName(i).equals(ATTR_LATITUDE)) {
                latitude = Double.parseDouble(parser.getAttributeValue(i));
            }
            else if(parser.getAttributeName(i).equals(ATTR_LONGITUDE)) {
                longitude = Double.parseDouble(parser.getAttributeValue(i));
            }
        }

        if(latitude == null || longitude == null) {
            CrashlyticsCore.getInstance().logException(new XmlPullParserException("Invalid <wpt> format: " + parser.getText()));
            skip(parser);
            return null;
        }

        LocationModel location = new LocationModel();
        String addressStr = null;
        if(preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true)) {
            addressStr = MiscUtils.getClosestAddressFromLocation(context, latitude, longitude);
        }
        location.setLocationInfo(new LocationInfo(new LatLng(latitude, longitude), addressStr, true));

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();
            if (name.equals(TAG_NAME)) {
                location.setTitle(readName(parser));
            }
            else if(name.equals(TAG_TYPE)) {
                String tagName = readType(parser);
                Long tagId = database.saveTag(new TagModel(null, tagName));
                location.setTagIds(new ArrayList<>(Arrays.asList(tagId)));
            }
            else if(name.equals(TAG_TIME)) {
                location.setDate(readTime(parser));
            }
            else if(name.equals(TAG_DESCRIPTION)) {
                location.setNotes(readDescription(parser));
            }
            else {
                skip(parser);
            }
        }

        location.getDate();            // Sets a date on the location if it isn't already set.
        return location;
    }

    private String readName(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, TAG_NAME);
        String name = readText(parser);
        parser.require(XmlPullParser.END_TAG, ns, TAG_NAME);
        return name;
    }

    private String readType(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, TAG_TYPE);
        String name = readText(parser);
        parser.require(XmlPullParser.END_TAG, ns, TAG_TYPE);
        return name;
    }

    private Date readTime(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, TAG_TIME);
        String dateStr = readText(parser);
        Date date = null;
        for(SimpleDateFormat timeDateFormat : timeDateFormats) {
            try {
                date = new Date(timeDateFormat.parse(dateStr).getTime());
                break;
            }
            catch(java.text.ParseException e) { /* Exception is handled below */ }
        }
        if(date == null) {
            CrashlyticsCore.getInstance().logException(new ParseException("Unparseable date: " + dateStr, -1));
        }
        parser.require(XmlPullParser.END_TAG, ns, TAG_TIME);
        return date;
    }

    private String readDescription(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, TAG_DESCRIPTION);
        String desc = readText(parser);
        parser.require(XmlPullParser.END_TAG, ns, TAG_DESCRIPTION);
        return desc;
    }

    // For the tags title and summary, extracts their text values.
    private String readText(XmlPullParser parser) throws IOException, XmlPullParserException {
        String result = "";
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.getText();
            parser.nextTag();
        }
        return result;
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

}
