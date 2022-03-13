package com.sndurkin.locationscout.util;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.Nullable;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.Application;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class FileUtils {

    public static File createPhotoFile(Context context, String extension) throws IOException, PhotoFileCreateException {
        String timestamp  = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File photoDir = FileUtils.getPhotoDirChecked(context);
        try {
            return File.createTempFile("IMG_" + timestamp, extension, photoDir);
        }
        catch(IOException e) {
            if(e.getMessage() != null && e.getMessage().contains("EACCES")) {
                throw new PhotoFileCreateException(photoDir.getAbsolutePath());
            }
            else {
                throw e;
            }
        }
    }

    public static File getPhotoDirChecked(Context context) throws PhotoFileCreateException {
        File photoDir = getPhotoDir(context);
        if(photoDir.exists() || photoDir.mkdirs()) {
            return photoDir;
        }
        else {
            throw new PhotoFileCreateException(photoDir.getAbsolutePath());
        }
    }

    public static File getPhotoDir(Context context) {
        String photosLocationPath = PreferenceManager.getDefaultSharedPreferences(context).getString(Strings.PREF_PHOTOS_LOCATION, null);
        if(photosLocationPath != null) {
            return new File(photosLocationPath);
        }
        else {
            // Default photo dir
            return getDefaultPhotoDir();
        }
    }

    public static File getDefaultPhotoDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), MiscUtils.APP_FOLDER);
    }

    public static void writeStreamToFile(InputStream inputStream, File file) throws IOException {
        OutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int numBytesRead;
            while((numBytesRead = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, numBytesRead);
            }
        }
        finally {
            if(inputStream != null) {
                inputStream.close();
            }
            if(outputStream != null) {
                outputStream.close();
            }
        }
    }

    // Copied from: https://github.com/iPaulPro/aFileChooser/blob/master/aFileChooser/src/com/ipaulpro/afilechooser/utils/FileUtils.java
    @TargetApi(19)
    @Nullable
    public static String getFilePathFromUri(Uri uri, boolean reportFailure) {
        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
        boolean isDocumentUri = false;
        Boolean isExternalStorageDoc = null, isMediaDoc = null;

        // DocumentProvider
        if(isKitKat && (isDocumentUri = DocumentsContract.isDocumentUri(Application.getInstance(), uri))) {
            if(isExternalStorageDoc = isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }
            }
            else if(isMediaDoc = isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                else if("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                }
                else if("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[] { split[1] };

                return getDataColumn(contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if("content".equalsIgnoreCase(uri.getScheme())) {
            // Return the remote address
            if(isGooglePhotosUri(uri)) {
                return uri.getLastPathSegment();
            }

            return getDataColumn(uri, null, null);
        }
        // File
        else if("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        if(reportFailure) {
            CrashlyticsCore.getInstance().logException(new RuntimeException(
                    "Could not find filepath from URI: "
                            + uri.getAuthority() + ", " + uri.getFragment() + ", "
                            + uri.getPort() + ", " + uri.getQuery() + ", "
                            + uri.getScheme() + ", " + uri.getHost() + ", "
                            + uri.getPathSegments().toString() + " | "
                            + isKitKat + ", " + isDocumentUri + ", "
                            + isExternalStorageDoc + ", "
                            + (isExternalStorageDoc ? (DocumentsContract.getDocumentId(uri) + ", ") : "")
                            + isMediaDoc
            ));
        }

        return null;
    }

    private static String getDataColumn(Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = { column };

        try {
            cursor = Application.getInstance().getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if(cursor != null && cursor.moveToFirst()) {
                final int columnIndex = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(columnIndex);
            }
        }
        catch(IllegalArgumentException e) { /* Do nothing. */ }
        finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        return null;
    }

    private static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    private static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    private static boolean isGooglePhotosUri(Uri uri) {
        return "com.google.android.apps.photos.content".equals(uri.getAuthority());
    }

    // Add or remove the photo to/from the gallery.
    public static void scanGalleryPhoto(Context context, File photoFile) {
        if(!photoFile.exists() && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // The file has to specifically deleted instead of just re-scanned in
            // Android Kitkat and below.
            context.getContentResolver().delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.ImageColumns.DATA + "=?",
                    new String[]{ photoFile.getAbsolutePath() });
        }
        else {
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaScanIntent.setData(Uri.fromFile(photoFile));
            context.sendBroadcast(mediaScanIntent);
        }
    }

    public static LatLng getLatLngForPhoto(String path) throws IOException {
        if(path != null) {
            File file = new File(path);
            if(file.exists() && file.canRead()) {
                ExifInterface exif = new ExifInterface(path);
                float[] latLongValues = new float[2];
                if(exif.getLatLong(latLongValues)) {
                    return new LatLng(latLongValues[0], latLongValues[1]);
                }
            }
        }

        return null;
    }

    private static final String[] DATETIME_FORMATS = {
            "yyyy:MM:dd HH:mm:ss",
            "dd/MM/yyyy HH:mm"
    };
    public static Date getDateForPhoto(String path) {
        if(path == null) {
            return null;
        }

        File file = new File(path);
        if(file.exists() && file.canRead()) {
            try {
                ExifInterface exif = new ExifInterface(path);
                String photoDateStr = exif.getAttribute(ExifInterface.TAG_DATETIME);
                if (photoDateStr != null) {
                    for(String datetimeFormat : DATETIME_FORMATS) {
                        try {
                            return new SimpleDateFormat(datetimeFormat, Locale.getDefault()).parse(photoDateStr);
                        }
                        catch (ParseException e) {
                            // Ignore, continue trying with the next datetime format.
                        }
                    }

                    CrashlyticsCore.getInstance().logException(new RuntimeException("Datetime from photo not able to be parsed: " + photoDateStr));
                }
            }
            catch(IOException e) {
                CrashlyticsCore.getInstance().logException(e);
            }
        }

        return null;
    }

    public static File getSDCardDir() {
        File photoDir = new File(Environment.getExternalStorageDirectory(), MiscUtils.APP_FOLDER);
        if(!photoDir.exists()) {
            File deprecatedPhotoDir = new File(Environment.getExternalStorageDirectory(), MiscUtils.APP_FOLDER_DEPRECATED);
            if(!deprecatedPhotoDir.exists()) {
                if(!photoDir.mkdirs()) {
                    throw new RuntimeException("Could not create \"" + MiscUtils.APP_FOLDER + "\" directory in external storage. External storage state: " + Environment.getExternalStorageState());
                }
            }
            else {
                photoDir = deprecatedPhotoDir;
            }
        }

        return photoDir;
    }

    public static String calculateMD5(String path) {
        if(path == null) {
            return null;
        }
        File file = new File(path);
        if(!file.exists()) {
            return null;
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        }
        catch (NoSuchAlgorithmException e) {
            CrashlyticsCore.getInstance().logException(e);
            return null;
        }

        InputStream is;
        try {
            is = new FileInputStream(file);
        }
        catch (FileNotFoundException e) {
            CrashlyticsCore.getInstance().logException(e);
            return null;
        }

        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            BigInteger bigInt = new BigInteger(1, md5sum);
            String output = bigInt.toString(16);
            output = String.format("%32s", output).replace(' ', '0');
            return output;
        }
        catch (IOException e) {
            CrashlyticsCore.getInstance().logException(e);
        }
        finally {
            try {
                is.close();
            }
            catch (IOException e) {
                CrashlyticsCore.getInstance().logException(e);
            }
        }

        return null;
    }

    public static void closeStream(InputStream stream) {
        try {
            if(stream != null) stream.close();
        }
        catch(IOException e) {
            CrashlyticsCore.getInstance().logException(e);
        }
    }
}
