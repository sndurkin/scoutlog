package com.sndurkin.locationscout;

import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;

import com.crashlytics.android.core.CrashlyticsCore;
import com.sndurkin.locationscout.util.MiscUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MapAddressSuggestionProvider extends ContentProvider {

    private static final String[] SEARCH_SUGGEST_COLUMNS = {
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA,
            SearchManager.SUGGEST_COLUMN_ICON_1
    };

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String query = uri.getLastPathSegment().toLowerCase();
        if(query.equals(SearchManager.SUGGEST_URI_PATH_QUERY)) {
            // I don't know why the query can equal this value, but it can and I think I should ignore it.
            return null;
        }

        MatrixCursor cursor = new MatrixCursor(SEARCH_SUGGEST_COLUMNS, 1);

        String responseBody = null;
        try {
            StringBuilder sb = new StringBuilder("https://maps.googleapis.com/maps/api/place/autocomplete/json");
            sb.append("?sensor=false");
            sb.append("&key=" + MiscUtils.WEB_API_KEY);
            sb.append("&input=" + URLEncoder.encode(query, "UTF-8"));

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            Response response = client.newCall(new Request.Builder().url(sb.toString()).build()).execute();
            ResponseBody body = response.body();
            responseBody = body.string();
            body.close();
        }
        catch(MalformedURLException e) {
            CrashlyticsCore.getInstance().logException(e);
            return null;
        }
        catch(IOException e) {
            // These exceptions probably don't need to be logged anymore.
            // CrashlyticsCore.getInstance().logException(e);
            return null;
        }

        try {
            JSONObject jsonObj = new JSONObject(responseBody);
            if(jsonObj.has("error_message")) {
                CrashlyticsCore.getInstance().logException(new RuntimeException("Error in JSON: " + responseBody));
                return null;
            }

            JSONArray predictionsArr = jsonObj.getJSONArray("predictions");
            for (int i = 0; i < predictionsArr.length(); ++i) {
                JSONObject predictionObj = predictionsArr.getJSONObject(i);
                JSONArray termsArr = predictionObj.getJSONArray("terms");

                String title = termsArr.getJSONObject(0).getString("value");
                StringBuffer address = new StringBuffer();
                for(int j = 1; j < termsArr.length(); ++j) {
                    if(j > 1) {
                        address.append(", ");
                    }
                    address.append(termsArr.getJSONObject(j).getString("value"));
                }
                String reference = predictionObj.getString("reference");

                cursor.addRow(new Object[] { i, title, address, reference, title + ", " + address, null });
            }
        }
        catch (JSONException e) {
            CrashlyticsCore.getInstance().logException(new RuntimeException("Cannot process JSON: " + responseBody, e));
            return null;
        }

        return cursor;
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
