package com.sndurkin.locationscout.util;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

// Provides a file given a Uri; useful for sharing with external apps.
public class FileProvider extends ContentProvider {

    // The authority is the symbolic name for the provider class
    public static final String AUTHORITY = "com.sndurkin.locationscout.provider.file";

    // UriMatcher used to match against incoming requests
    private UriMatcher uriMatcher;

    @Override
    public boolean onCreate() {
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

        // Add a Uri to the matcher which will match against the following format:
        //
        //  "content://com.sndurkin.locationscout.provider.file/*"
        //
        // It will return 1 in the case that the incoming Uri matches this pattern.
        uriMatcher.addURI(AUTHORITY, "*", 1);

        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if(uriMatcher.match(uri) == 1) {
            // The file name is specified by the last segment of the path.
            // Ex: "content://com.sndurkin.locationscout.provider.file/sync_errors.txt"
            String path = getContext().getFilesDir() + File.separator + uri.getLastPathSegment();
            return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        else {
            throw new FileNotFoundException("Unsupported uri: " + uri.toString());
        }
    }

    @Override
    public int update(Uri uri, ContentValues contentvalues, String s, String[] as) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String s, String[] as) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues contentvalues) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String s, String[] as1, String s1) {
        return null;
    }

}
