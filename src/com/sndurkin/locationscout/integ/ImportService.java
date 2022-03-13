package com.sndurkin.locationscout.integ;


import android.app.IntentService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.webkit.MimeTypeMap;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.Application;
import com.sndurkin.locationscout.GlobalBroadcastManager;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.MainActivity;
import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.util.FileUtils;
import com.sndurkin.locationscout.util.PhotoFileCreateException;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class ImportService extends IntentService {

    private DatabaseHelper database;
    private SharedPreferences preferences;
    private GlobalBroadcastManager broadcastManager;

    private FileParser[] fileParsers;
    private String[] fileImportMessages;

    public ImportService() {
        super("ImportService");
    }

    protected void init() {
        database = DatabaseHelper.getInstance(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        broadcastManager = GlobalBroadcastManager.getInstance(this);

        fileParsers = new FileParser[] {
                new GPXFileParser(this, database, preferences),
                new GoogleBookmarksFileParser(this, database, preferences)
        };

        fileImportMessages = new String[] {
                Strings.LBL_IMPORT_GPX,
                Strings.LBL_IMPORT_GOOGLE_BOOKMARKS,
                Strings.LBL_IMPORT_KML
        };
    }

    @Override
    protected void onHandleIntent(Intent importIntent) {
        init();

        Intent intent = importIntent.getParcelableExtra(Strings.PARAM_INTENT);
        if(intent == null) {
            return;
        }
        Bundle extras = intent.getExtras();
        if(extras == null) {
            return;
        }

        Object stream = extras.get(Intent.EXTRA_STREAM);
        String mimeType = intent.getType();
        if(mimeType != null && mimeType.startsWith("image/")) {
            handleImageIntent(stream, mimeType);
        }
        else {
            if(stream instanceof Uri) {
                Uri uri = (Uri) stream;

                showInitialNotification(getString(R.string.import_notification_title), getString(R.string.import_notification_message));

                ParseResult parseResult = null;
                InputStream inputStream = null;
                try {
                    for(int i = 0; i < fileParsers.length; ++i) {
                        FileParser parser = fileParsers[i];
                        inputStream = getContentResolver().openInputStream(uri);
                        if(inputStream == null) {
                            break;
                        }

                        parseResult = parser.parse(inputStream);
                        FileUtils.closeStream(inputStream);
                        if(parseResult.success) {
                            Application.getInstance().getTracker().send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_IMPORT)
                                    .setLabel(fileImportMessages[i])
                                    .build());

                            break;
                        }
                    }
                }
                catch(Exception e) {
                    CrashlyticsCore.getInstance().logException(e);
                }
                finally {
                    FileUtils.closeStream(inputStream);
                }

                if(parseResult != null && parseResult.success) {
                    showCompleteNotification(getString(R.string.import_complete_notification_title), parseResult.toString(this));
                    broadcastManager.sendBroadcast(new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST));
                }
                else {
                    showErrorNotification(getString(R.string.import_failed_title), getString(R.string.import_failed_message));
                }
            }
        }
    }

    private static final int GROUP_ONE_PHOTO_UNDER_ONE_PLACE = 0;
    private static final int GROUP_ALL_PHOTOS_UNDER_ONE_PLACE = 1;
    private static final int GROUP_PHOTOS_BY_PLACE_LOCATION = 2;

    private int photosImported;
    private int photosIgnored;
    private boolean reachedLocationsLimit;

    private boolean ignoreImportedDuplicatesPref;

    private void handleImageIntent(Object stream, String mimeType) {
        reachedLocationsLimit = false;
        photosImported = 0;
        photosIgnored = 0;

        int importMultiplePhotosPref = Integer.valueOf(preferences.getString(Strings.PREF_IMPORT_MULTIPLE_PHOTOS, "2"));
        ignoreImportedDuplicatesPref = preferences.getBoolean(Strings.PREF_IGNORE_IMPORTED_DUPLICATES, true);

        LocationModel existingLocation = null;
        List<LocationModel> existingLocations = null;

        if(importMultiplePhotosPref == GROUP_PHOTOS_BY_PLACE_LOCATION) {
            existingLocations = new ArrayList<>(10);
        }

        ArrayList<Uri> uriList = new ArrayList<>(10);
        if(stream instanceof Uri) {
            uriList.add((Uri) stream);
        }
        else if(stream instanceof ArrayList<?>) {
            uriList.addAll((ArrayList<Uri>) stream);
        }
        else {
            CrashlyticsCore.getInstance().logException(new IllegalArgumentException("Unrecognized input type: " + stream.getClass().toString()));
        }

        String content = getResources().getQuantityString(R.plurals.import_photos_notification_message, uriList.size(), uriList.size());
        showInitialNotification(getString(R.string.import_photos_notification_title), content);

        for(Uri uri : uriList) {
            String path = FileUtils.getFilePathFromUri(uri, false);
            if(path == null) {
                // The photo doesn't exist on the filesystem, so it should be downloaded.
                try {
                    InputStream inputStream = getContentResolver().openInputStream(uri);

                    // Write the stream to a new file.
                    String extension = "jpg";
                    if(MimeTypeMap.getSingleton().hasMimeType(mimeType)) {
                        extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                    }
                    File file = FileUtils.createPhotoFile(this, "." + extension);
                    FileUtils.writeStreamToFile(inputStream, file);

                    // Add the photo file to the gallery.
                    FileUtils.scanGalleryPhoto(this, file);

                    path = file.getAbsolutePath();
                }
                catch(IOException e) {
                    CrashlyticsCore.getInstance().logException(e);
                    ++photosIgnored;
                    continue;
                }
                catch(PhotoFileCreateException e) {
                    CrashlyticsCore.getInstance().logException(e);
                    ++photosIgnored;
                    continue;
                }
            }

            File file = new File(path);
            if(!file.exists()) {
                CrashlyticsCore.getInstance().logException(new FileNotFoundException("File doesn't exist"));
                ++photosIgnored;
                continue;
            }
            else if(!file.canRead()) {
                CrashlyticsCore.getInstance().logException(new IllegalAccessException("Cannot read file"));
                ++photosIgnored;
                continue;
            }

            Date date = FileUtils.getDateForPhoto(path);
            LatLng latLng;
            try {
                latLng = FileUtils.getLatLngForPhoto(path);
            }
            catch(IOException e) {
                CrashlyticsCore.getInstance().logException(e);
                ++photosIgnored;
                continue;
            }

            Long locationId = null;
            if(importMultiplePhotosPref == GROUP_ONE_PHOTO_UNDER_ONE_PLACE) {
                LocationModel locationModel = createNewLocation(latLng, date);
                if(locationModel == null) {
                    continue;
                }
                locationId = locationModel.getLocalId();
            }
            else if(importMultiplePhotosPref == GROUP_ALL_PHOTOS_UNDER_ONE_PLACE) {
                if(existingLocation == null) {
                    existingLocation = createNewLocation(latLng, date);
                    if(existingLocation == null) {
                        continue;
                    }
                }

                locationId = existingLocation.getLocalId();
            }
            else if(importMultiplePhotosPref == GROUP_PHOTOS_BY_PLACE_LOCATION) {
                existingLocation = null;
                if(latLng != null) {
                    for(int i = 0; i < existingLocations.size(); ++i) {
                        LocationModel locationModel = existingLocations.get(i);
                        if(locationModel.hasLocation() && locationsAreEqual(locationModel.getLocationInfo().location, latLng)) {
                            existingLocation = locationModel;
                            break;
                        }
                    }
                }

                if(existingLocation == null) {
                    existingLocation = createNewLocation(latLng, date);
                    if(existingLocation == null) {
                        continue;
                    }
                    existingLocations.add(existingLocation);
                }

                locationId = existingLocation.getLocalId();
            }

            PhotoInfo photoInfo = new PhotoInfo(path);
            photoInfo.sortNum = database.fetchPhotosForLocation(locationId).getCount();
            database.savePhotoInfos(Collections.singletonList(photoInfo), locationId);
            ++photosImported;
        }

        Application.getInstance().getTracker().send(new HitBuilders.EventBuilder()
                .setCategory(Strings.CAT_UI_ACTION)
                .setAction(Strings.ACT_IMPORT)
                .setLabel(Strings.LBL_IMPORT_PHOTOS)
                .build());

        StringBuffer sb = new StringBuffer();
        sb.append(getResources().getQuantityString(R.plurals.import_photos_complete_notification_message_imported, photosImported, photosImported));
        sb.append("; ");
        sb.append(getResources().getQuantityString(R.plurals.import_photos_complete_notification_message_ignored, photosIgnored, photosIgnored));
        showCompleteNotification(getString(R.string.import_complete_notification_title), sb.toString());
        broadcastManager.sendBroadcast(new Intent(MainActivity.LOCATIONS_CHANGED_BROADCAST));
    }

    private LocationModel createNewLocation(LatLng latLng, Date date) {
        if(reachedLocationsLimit || (!UIUtils.hasUserUnlockedApp(this) && database.fetchLocationsCount() >= 15)) {
            reachedLocationsLimit = true;
            ++photosIgnored;
            return null;
        }

        if(latLng != null && ignoreImportedDuplicatesPref && database.locationAlreadyExists(latLng)) {
            ++photosIgnored;
            return null;
        }

        LocationModel locationModel = new LocationModel();
        if(date != null) {
            locationModel.setDate(new java.sql.Date(date.getTime()));
        }
        locationModel.getDate();            // Sets a date on the location if it isn't already set.
        if(latLng != null) {
            String addressStr = null;
            if(preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true)) {
                addressStr = MiscUtils.getClosestAddressFromLocation(this, latLng.latitude, latLng.longitude);
            }
            locationModel.setLocationInfo(new LocationInfo(latLng, addressStr, true));
        }
        locationModel.setLocalId(database.saveLocation(locationModel));
        return locationModel;
    }

    // 4 decimal places is 11m of accuracy, so we'll double and assume
    // that under 20m is the same area: http://gis.stackexchange.com/a/8674
    //
    // Note that we're not using the Haversine formula here to determine
    // distance, but that's okay because it doesn't need to be too accurate.
    private boolean locationsAreEqual(LatLng latLng1, LatLng latLng2) {
        return Math.abs(latLng1.latitude - latLng2.latitude) < 0.0002
                && Math.abs(latLng1.longitude - latLng2.longitude) < 0.0002;
    }

    // Creates the first notification.
    private void showInitialNotification(String title, String content) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this);
        notificationBuilder.setContentTitle(title);
        if(content != null) {
            notificationBuilder = notificationBuilder.setContentText(content);
        }
        notificationBuilder = notificationBuilder
                .setSmallIcon(R.drawable.launcher_white)
                .setOngoing(true)
                .setProgress(0, 0, true);
        notificationManager.notify(RequestCodes.IMPORT_NOTIFICATION_ID, notificationBuilder.build());
    }

    // Updates the notification and sets the click handler which will launch the app.
    private void showCompleteNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.launcher_white)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
        notificationManager.notify(RequestCodes.IMPORT_NOTIFICATION_ID, notificationBuilder.build());
    }

    private void showErrorNotification(String title, String content) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("message/rfc822");
        shareIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{"scoutlogapp@gmail.com"});
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Help with importing files into ScoutLog");
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, Intent.createChooser(shareIntent, getString(R.string.send_email)), 0);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.launcher_white)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setContentIntent(contentIntent);
        notificationManager.notify(RequestCodes.IMPORT_NOTIFICATION_ID, notificationBuilder.build());
    }

}
