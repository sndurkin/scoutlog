package com.sndurkin.locationscout.integ;

import android.content.Context;
import android.content.SharedPreferences;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.TagModel;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Date;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


public class GoogleBookmarksFileParser extends FileParser {

    public static final Pattern[] GOOGLE_MAPS_URL_PATTERNS = new Pattern[] {
            Pattern.compile("https?://goo\\.gl.*$"),
            Pattern.compile("https?://maps\\.google\\.com.*$"),
            Pattern.compile("https?://google\\.com\\\\maps\\\\.*$")
    };

    public GoogleBookmarksFileParser(Context context, DatabaseHelper database, SharedPreferences preferences) {
        super(context, database, preferences);
    }

    @Override
    public ParseResult parse(InputStream inputStream) {
        ParseResult parseResult = new ParseResult();

        try {
            Document doc = Jsoup.parse(inputStream, "UTF-8", "");
            if(doc == null) {
                return parseResult;
            }

            boolean isGoogleBookmarksFile = false;
            for(Node node : doc.childNodes()) {
                if(node instanceof DocumentType) {
                    DocumentType docType = (DocumentType) node;
                    if(docType.toString().contains("netscape-bookmark-file")) {
                        isGoogleBookmarksFile = true;
                        break;
                    }
                }
            }

            if(!isGoogleBookmarksFile) {
                return parseResult;
            }

            OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS);
            clientBuilder.interceptors().add(new Interceptor() {
                @Override
                public Response intercept(Interceptor.Chain chain) throws IOException {
                    Request request = chain.request();
                    Response response = null;
                    for(int tryCount = 0; tryCount < 3; ++tryCount) {
                        try {
                            response = chain.proceed(request);
                            if(response.isSuccessful()) {
                                break;
                            }
                        }
                        catch(Exception e) {
                            CrashlyticsCore.getInstance().logException(e);
                        }
                    }

                    return response;
                }
            });
            OkHttpClient client = clientBuilder.build();

            if(doc.select("body").isEmpty()) {
                return parseResult;
            }
            Element bodyEl = doc.select("body").first();

            for(Element dl : bodyEl.children()) {
                if(!"dl".equalsIgnoreCase(dl.tagName())) {
                    continue;
                }

                Long currentTagId = null;
                for(Element dlChildEl : dl.children()) {
                    if("dt".equalsIgnoreCase(dlChildEl.tagName())) {
                        currentTagId = null;
                        for(Element dtChildEl : dlChildEl.children()) {
                            if("h3".equalsIgnoreCase(dtChildEl.tagName())) {
                                // We found the label for this group of bookmarks.
                                String labelName = dtChildEl.text();
                                if(!"Unlabeled".equalsIgnoreCase(labelName)) {
                                    // Try to add a tag with the label name to the database.
                                    TagModel tag = new TagModel();
                                    tag.name = labelName;
                                    currentTagId = database.saveTag(tag);
                                }
                            }
                            else if("dl".equalsIgnoreCase(dtChildEl.tagName())) {
                                // We found a group of bookmarks under a label.
                                Long currentLocationId = null;
                                for(Element innerDLChildEl : dtChildEl.children()) {
                                    if("dt".equalsIgnoreCase(innerDLChildEl.tagName())) {
                                        // We found a new bookmark, so reset the current location id
                                        // and determine if it's a Google Maps url.
                                        currentLocationId = null;

                                        Elements linkEls = innerDLChildEl.select("a");
                                        if(linkEls.isEmpty()) {
                                            continue;
                                        }
                                        Element linkEl = linkEls.first();

                                        String url = linkEl.attr("href");
                                        boolean isGoogleMapsUrl = false;
                                        for(Pattern pattern : GOOGLE_MAPS_URL_PATTERNS) {
                                            Matcher matcher = pattern.matcher(url);
                                            if(matcher.find()) {
                                                isGoogleMapsUrl = true;
                                                break;
                                            }
                                        }

                                        if(!isGoogleMapsUrl) {
                                            continue;
                                        }

                                        if(reachedLocationsLimit || (!UIUtils.hasUserUnlockedApp(context) && database.fetchLocationsCount() >= 15)) {
                                            reachedLocationsLimit = true;
                                            parseResult.locationsIgnored++;
                                            continue;
                                        }

                                        String title = linkEl.text();
                                        String millisStr = linkEl.attr("add_date");
                                        Date date = null;
                                        try {
                                            date = new Date(Long.parseLong(millisStr) / 1000L);
                                        }
                                        catch(NumberFormatException e) {
                                            CrashlyticsCore.getInstance().logException(e);
                                        }

                                        currentLocationId = fetchAndSaveLocation(parseResult, client, url, title, date, currentTagId);
                                    }
                                    else if("dd".equalsIgnoreCase(innerDLChildEl.tagName())) {
                                        if(currentLocationId != null) {
                                            // We found a note for one of the bookmarks.
                                            LocationModel locationModel = new LocationModel();
                                            locationModel.setLocalId(currentLocationId);
                                            locationModel.setNotes(innerDLChildEl.text());
                                            database.saveLocation(locationModel);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        catch(IOException e) {
            CrashlyticsCore.getInstance().logException(new ParseException("Could not parse file", e));
            return parseResult;
        }

        parseResult.success = true;
        return parseResult;
    }

    private Long fetchAndSaveLocation(ParseResult parseResult, OkHttpClient client, String url, String title, Date date, Long currentTagId)
            throws IOException {
        Request request = new Request.Builder().url(url).build();
        Response response = client.newCall(request).execute();
        Response networkResponse = response.networkResponse();
        if(networkResponse.code() == 200) {
            String html = response.body().string();
            Matcher matcher = ImportURLPlaceAsyncTask.GOOGLE_COORDS_PATTERN.matcher(html);
            while(matcher.find()) {
                try {
                    JSONArray arr = new JSONArray(html.substring(matcher.start(), matcher.end()));
                    Double latitude = arr.getDouble(0);
                    Double longitude = arr.getDouble(1);
                    if(latitude >= 90.0 || latitude <= -90 || longitude >= 180.0 || longitude <= -180.0) {
                        continue;
                    }

                    if(preferences.getBoolean(Strings.PREF_IGNORE_IMPORTED_DUPLICATES, true)) {
                        if(database.locationAlreadyExists(new LatLng(latitude, longitude))) {
                            parseResult.locationsIgnored++;
                            return null;
                        }
                    }

                    LocationModel locationModel = new LocationModel();
                    String addressStr = null;
                    if(preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true)) {
                        addressStr = MiscUtils.getClosestAddressFromLocation(context, latitude, longitude);
                    }
                    locationModel.setLocationInfo(new LocationInfo(new LatLng(latitude, longitude), addressStr, true));

                    if(url.contains("?cid=") || url.contains("&cid=")) {
                        locationModel.setTitle(title);
                    }

                    locationModel.setDate(date);
                    locationModel.getDate();            // Sets a date on the location if it isn't already set.

                    if(currentTagId != null) {
                        locationModel.setTagIds(Collections.singletonList(currentTagId));
                    }

                    Long locationId = database.saveLocation(locationModel);
                    parseResult.locationsImported++;
                    return locationId;
                }
                catch(JSONException e) {
                    CrashlyticsCore.getInstance().logException(new ParseException("Failed to parse the response body for coordinates: " + url, e));
                }
            }
        }
        else {
            CrashlyticsCore.getInstance().logException(new ParseException("Network response code was: " + networkResponse.code()));
        }

        return null;
    }

}
