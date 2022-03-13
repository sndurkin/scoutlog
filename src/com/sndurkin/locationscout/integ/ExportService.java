package com.sndurkin.locationscout.integ;

import android.app.IntentService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Xml;

import com.crashlytics.android.core.CrashlyticsCore;
import com.sndurkin.locationscout.GlobalBroadcastManager;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.util.FileUtils;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;

import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;

public class ExportService extends IntentService {

    public static final String EXPORT_BROADCAST = "com.sndurkin.locationscout.EXPORT_BROADCAST";

    private DatabaseHelper database;
    private GlobalBroadcastManager broadcastManager;
    private SharedPreferences preferences;

    private boolean showClosestAddress;

    public ExportService() {
        super("ExportService");
    }

    protected void init() {
        database = DatabaseHelper.getInstance(this);
        broadcastManager = GlobalBroadcastManager.getInstance(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        showClosestAddress = preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        init();

        MiscUtils.logd("Export started - sent");
        Intent broadcastIntent = new Intent(EXPORT_BROADCAST);
        broadcastIntent.putExtra("requestCode", RequestCodes.EXPORT_STARTED);
        broadcastManager.sendBroadcast(broadcastIntent);

        ArrayList<Integer> placeIds = intent.getIntegerArrayListExtra(Strings.LOCATION_IDS);
        final int exportType = intent.getIntExtra(Strings.EXPORT_TYPE, 0);
        if(placeIds == null || placeIds.isEmpty() || exportType == 0) {
            sendFailedBroadcast();
            return;
        }

        File exportedFile = null;
        String exportedFileType = null;
        switch(exportType) {
            case RequestCodes.EXPORT_TYPE_GPX:
                exportedFile = createGPXFile(placeIds);
                exportedFileType = "application/gpx";
                break;
            case RequestCodes.EXPORT_TYPE_KML:
                exportedFile = createKMLFile(placeIds);
                exportedFileType = "application/kml";
                break;
            case RequestCodes.EXPORT_TYPE_CSV:
                ArrayList<String> fieldNames = intent.getStringArrayListExtra(Strings.FIELD_NAMES);
                boolean includeHeader = intent.getBooleanExtra(Strings.INCLUDE_HEADER, true);
                exportedFile = createCSVFile(placeIds, fieldNames, includeHeader);
                exportedFileType = "text/csv";
                break;
        }

        if(exportedFile == null) {
            sendFailedBroadcast();
            return;
        }

        // Update the notification and set the click handler which will launch a share intent.
        MiscUtils.logd("Export finished - sent");
        broadcastIntent = new Intent(EXPORT_BROADCAST);
        broadcastIntent.putExtra("requestCode", RequestCodes.EXPORT_FINISHED);
        broadcastIntent.putExtra("exportedFileUri", Uri.fromFile(exportedFile));
        broadcastIntent.putExtra("exportedFileType", exportedFileType);
        broadcastManager.sendBroadcast(broadcastIntent);
    }

    private File createGPXFile(List<Integer> placeIds) {
        XmlSerializer serializer = Xml.newSerializer();
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        File gpxFile = new File(FileUtils.getSDCardDir(), getString(R.string.app_name) + ".gpx");
        FileWriter writer = null;
        try {
            writer = new FileWriter(gpxFile);
            serializer.setOutput(writer);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument("UTF-8", true);
            serializer.startTag(null, "gpx");

            Cursor cursor = database.fetchLocationsByIds(placeIds);
            while(!cursor.isAfterLast()) {
                LocationModel locationModel = ModelUtils.createLocationFromCursorForDisplay(cursor);

                // A <wpt> is only added to the GPX file if the place has a lat/lon set.
                if(locationModel.hasLocation()) {
                    String firstTagName = MiscUtils.getString(cursor, "first_tag_name");
                    LocationInfo locationInfo = locationModel.getLocationInfo();

                    serializer.startTag(null, "wpt");

                    // Set the lat/lon.
                    serializer.attribute(null, "lat", MiscUtils.coordToString(locationInfo.location.latitude));
                    serializer.attribute(null, "lon", MiscUtils.coordToString(locationInfo.location.longitude));

                    // Set the date.
                    serializer.startTag(null, "time");
                    serializer.text(dateFormat.format(locationModel.getDate()));
                    serializer.endTag(null, "time");

                    // Set the title.
                    String title = locationModel.getTitle();
                    if(!TextUtils.isEmpty(title)) {
                        serializer.startTag(null, "name");
                        serializer.text(title);
                        serializer.endTag(null, "name");
                    }

                    // Set the notes.
                    String notes = locationModel.getNotes();
                    if(!TextUtils.isEmpty(notes)) {
                        serializer.startTag(null, "desc");
                        serializer.text(notes);
                        serializer.endTag(null, "desc");
                    }

                    // Set the first tag.
                    if(firstTagName != null) {
                        serializer.startTag(null, "type");
                        serializer.text(firstTagName);
                        serializer.endTag(null, "type");
                    }

                    serializer.endTag(null, "wpt");
                }

                cursor.moveToNext();
            }
            cursor.close();

            serializer.endTag(null, "gpx");
            serializer.endDocument();
            serializer.flush();
            writer.close();
        }
        catch(Exception e) {
            CrashlyticsCore.getInstance().logException(e);
            return null;
        }
        finally {
            if(writer != null) {
                try { writer.close(); } catch(IOException e) { }
            }
        }

        return gpxFile;
    }

    private File createKMLFile(List<Integer> placeIds) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        File kmlFile = new File(FileUtils.getSDCardDir(), getString(R.string.app_name) + ".kml");
        return kmlFile;
    }

    private File createCSVFile(List<Integer> placeIds, List<String> fieldNames, boolean includeHeader) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        File csvFile = new File(FileUtils.getSDCardDir(), getString(R.string.app_name) + ".csv");
        FileWriter writer = null;
        try {
            writer = new FileWriter(csvFile);

            if(includeHeader) {
                // Write the CSV header.
                List<String> headerLineArr = new ArrayList<>(128);
                for(String fieldName : fieldNames) {
                    if(Strings.CSV_EXPORT_HEADER_TITLE.equals(fieldName)) {
                        headerLineArr.add(getString(R.string.title));
                    }
                    else if(Strings.CSV_EXPORT_HEADER_DATE.equals(fieldName)) {
                        headerLineArr.add(getString(R.string.date_title));
                    }
                    else if(Strings.CSV_EXPORT_HEADER_ADDRESS.equals(fieldName)) {
                        headerLineArr.add(getString(R.string.address_title));
                    }
                    else if(Strings.CSV_EXPORT_HEADER_LATITUDE.equals(fieldName)) {
                        headerLineArr.add(getString(R.string.latitude_title));
                    }
                    else if(Strings.CSV_EXPORT_HEADER_LONGITUDE.equals(fieldName)) {
                        headerLineArr.add(getString(R.string.longitude_title));
                    }
                    else if(Strings.CSV_EXPORT_HEADER_COORDINATES.equals(fieldName)) {
                        headerLineArr.add(getString(R.string.coordinates_title));
                    }
                    else if(Strings.CSV_EXPORT_HEADER_NOTES.equals(fieldName)) {
                        headerLineArr.add(getString(R.string.notes_title));
                    }
                }
                writer.append(processCSVLine(headerLineArr.toArray(new String[0])));
            }

            // Write the CSV data.
            Cursor cursor = database.fetchLocationsByIds(placeIds);
            while(!cursor.isAfterLast()) {
                LocationModel locationModel = ModelUtils.createLocationFromCursorForDisplay(cursor);

                List<String> lineArr = new ArrayList<>(128);
                for(String fieldName : fieldNames) {
                    if(Strings.CSV_EXPORT_HEADER_TITLE.equals(fieldName)) {
                        lineArr.add(locationModel.getTitle());
                    }
                    else if(Strings.CSV_EXPORT_HEADER_DATE.equals(fieldName)) {
                        lineArr.add(dateFormat.format(locationModel.getDate()));
                    }
                    else if(Strings.CSV_EXPORT_HEADER_ADDRESS.equals(fieldName)) {
                        if(locationModel.hasLocation() && locationModel.getLocationInfo().hasAddress()) {
                            String addressStr = locationModel.getLocationInfo().getAddressForDisplay(locationModel.getTitle(), true);
                            lineArr.add(addressStr.replaceAll("\n", ", "));
                        }
                        else {
                            lineArr.add("");
                        }
                    }
                    else if(Strings.CSV_EXPORT_HEADER_LATITUDE.equals(fieldName)) {
                        if(locationModel.hasLocation()) {
                            lineArr.add(MiscUtils.coordToString(locationModel.getLocationInfo().location.latitude));
                        }
                        else {
                            lineArr.add("");
                        }
                    }
                    else if(Strings.CSV_EXPORT_HEADER_LONGITUDE.equals(fieldName)) {
                        if(locationModel.hasLocation()) {
                            lineArr.add(MiscUtils.coordToString(locationModel.getLocationInfo().location.longitude));
                        }
                        else {
                            lineArr.add("");
                        }
                    }
                    else if(Strings.CSV_EXPORT_HEADER_COORDINATES.equals(fieldName)) {
                        if(locationModel.hasLocation()) {
                            lineArr.add(MiscUtils.latLngToString(locationModel.getLocationInfo().location));
                        }
                        else {
                            lineArr.add("");
                        }
                    }
                    else if(Strings.CSV_EXPORT_HEADER_NOTES.equals(fieldName)) {
                        lineArr.add(locationModel.getNotes());
                    }
                }
                writer.append(processCSVLine(lineArr.toArray(new String[0])));

                cursor.moveToNext();
            }
            cursor.close();
        }
        catch(Exception e) {
            CrashlyticsCore.getInstance().logException(e);
            return null;
        }
        finally {
            if(writer != null) {
                try { writer.close(); } catch(IOException e) { }
            }
        }

        return csvFile;
    }

    private static final char CSV_QUOTE_CHAR = '"';
    private static final char CSV_ESCAPE_CHAR = '"';
    private static final char CSV_SEPARATOR_CHAR = ',';
    private static final String CSV_LINE_END = "\n";

    private String processCSVLine(String[] line) {
        if(line == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(line.length * 2); // This is for the worse case where all elements have to be escaped.
        for(int i = 0; i < line.length; i++) {
            if(i > 0) {
                sb.append(CSV_SEPARATOR_CHAR);
            }

            String element = line[i];
            if (element == null) {
                continue;
            }

            Boolean stringContainsSpecialCharacters = stringContainsSpecialCharacters(element);
            if(stringContainsSpecialCharacters) {
                sb.append(CSV_QUOTE_CHAR).append(processElement(element)).append(CSV_QUOTE_CHAR);
            }
            else {
                sb.append(element);
            }
        }

        sb.append(CSV_LINE_END);
        return sb.toString();
    }

    private boolean stringContainsSpecialCharacters(String line) {
        return line.indexOf(CSV_QUOTE_CHAR) != -1 ||
               line.indexOf(CSV_ESCAPE_CHAR) != -1 ||
               line.indexOf(CSV_SEPARATOR_CHAR) != -1 ||
               line.contains(CSV_LINE_END) || line.contains("\r");
    }

    private StringBuilder processElement(String element) {
        StringBuilder sb = new StringBuilder(element.length() * 2); // this is for the worse case where all elements have to be escaped.
        for (int j = 0; j < element.length(); j++) {
            processCharacter(sb, element.charAt(j));
        }

        return sb;
    }

    private void processCharacter(StringBuilder sb, char ch) {
        if(checkCharacterToEscape(ch)) {
            sb.append(CSV_ESCAPE_CHAR).append(ch);
        }
        else {
            sb.append(ch);
        }
    }

    private boolean checkCharacterToEscape(char ch) {
        return ch == CSV_QUOTE_CHAR || ch == CSV_ESCAPE_CHAR;
    }

    private void sendFailedBroadcast() {
        Intent broadcastIntent = new Intent(EXPORT_BROADCAST);
        broadcastIntent.putExtra("requestCode", RequestCodes.EXPORT_FAILED);
        broadcastManager.sendBroadcast(broadcastIntent);
    }


}
