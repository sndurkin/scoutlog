package com.sndurkin.locationscout;

import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.TypedValue;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.util.Strings;

public class ListSuggestionProvider extends ContentProvider {

    private static final String[] SEARCH_SUGGEST_COLUMNS = {
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA,
    };

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String query = uri.getLastPathSegment().toLowerCase();
        if(query.equals(SearchManager.SUGGEST_URI_PATH_QUERY)) {
            // I don't know why the query can equal this value, but it can and I think I should ignore it.
            return null;
        }

        MatrixCursor matrixCursor = new MatrixCursor(SEARCH_SUGGEST_COLUMNS, 1);

        // Add a raw search option.
        TypedValue out = new TypedValue();
        getContext().getTheme().resolveAttribute(R.attr.iconSearch, out, true);
        matrixCursor.addRow(new Object[] { 0, out.resourceId, "Search for " + query, query, Strings.SUGGESTION_TYPE_RAW });

        // Add tags from the database.
        out = new TypedValue();
        getContext().getTheme().resolveAttribute(R.attr.iconTag, out, true);
        Cursor cursor = DatabaseHelper.getInstance(getContext()).fetchTagsByStr(query);
        while(!cursor.isAfterLast()) {
            String tagName = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TAG_NAME_COLUMN));
            matrixCursor.addRow(new Object[] { 0, out.resourceId, tagName, tagName, Strings.SUGGESTION_TYPE_TAG });
            cursor.moveToNext();
        }
        cursor.close();

        // TODO: Add searches by date

        return matrixCursor;
    }

    @Override
    public String getType(Uri uri) {
        return SearchManager.SUGGEST_MIME_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

}
