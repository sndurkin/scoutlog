package com.sndurkin.locationscout;

import android.location.Address;
import android.location.Geocoder;
import android.os.AsyncTask;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.util.MiscUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

// This class isn't being used currently because the logic is being handled
// by the Maps API (see DetailMapActivity).
public class MapAddressAutocompleteTask extends AsyncTask<String, Void, LocationInfo> {

    private final WeakReference<DetailMapActivity> mapDetailActivityRef;

    private MapAddressAutocompleteTask(DetailMapActivity detailMapActivity) {
        // Use a WeakReference to ensure the GoogleMap can be garbage collected.
        mapDetailActivityRef = new WeakReference<DetailMapActivity>(detailMapActivity);
    }

    public static void loadLocation(DetailMapActivity detailMapActivity, String reference) {
        new MapAddressAutocompleteTask(detailMapActivity).execute(reference);
    }

    @Override
    protected LocationInfo doInBackground(String... params) {
        String reference = params[0];

        String responseBody = null;
        try {
            StringBuilder sb = new StringBuilder("https://maps.googleapis.com/maps/api/place/details/json");
            sb.append("?sensor=false");
            sb.append("&reference=" + reference);
            sb.append("&key=" + MiscUtils.WEB_API_KEY);

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
            CrashlyticsCore.getInstance().logException(e);
            return null;
        }

        try {
            // TODO: determine if i need to add handling for denied requests, etc
            JSONObject jsonObj = new JSONObject(responseBody);
            JSONObject locationObj = jsonObj.getJSONObject("result").getJSONObject("geometry").getJSONObject("location");

            LocationInfo locationInfo = new LocationInfo();
            locationInfo.location = new LatLng(locationObj.getDouble("lat"), locationObj.getDouble("lng"));

            DetailMapActivity detailMapActivity = mapDetailActivityRef.get();
            if(detailMapActivity != null) {
                try {
                    String name = jsonObj.getJSONObject("result").getString("name");
                    String formattedAddressStr = jsonObj.getJSONObject("result").getString("formatted_address");

                    Geocoder geocoder = new Geocoder(detailMapActivity);
                    List<Address> addresses = geocoder.getFromLocationName(name + ", " + formattedAddressStr, 1);
                    if(addresses.isEmpty()) {
                        addresses = geocoder.getFromLocationName(formattedAddressStr, 1);
                    }

                    if(!addresses.isEmpty()) {
                        Address address = addresses.get(0);
                        if(!address.getAddressLine(0).startsWith(name)) {
                            locationInfo.addressStr = name + "\n" + MiscUtils.serializeAddress(address);
                        }
                        else {
                            locationInfo.addressStr = MiscUtils.serializeAddress(address);
                        }
                    }
                }
                catch(IOException e) {
                    // Ignore this exception completely for now.
                }
                catch(Exception e) {
                    // We can ignore exceptions because we'll derive the address from the LatLng later.
                    CrashlyticsCore.getInstance().logException(e);
                }
            }

            return locationInfo;
        }
        catch (JSONException e) {
            CrashlyticsCore.getInstance().logException(new RuntimeException("Cannot process JSON: " + responseBody, e));
            return null;
        }
    }

    // Once complete, see if the GoogleMap instance is still around and move to the location.
    @Override
    protected void onPostExecute(LocationInfo locationInfo) {
        DetailMapActivity detailMapActivity = mapDetailActivityRef.get();
        if (detailMapActivity != null && locationInfo != null) {
            detailMapActivity.locationChanged = true;
            detailMapActivity.setCurrentAddress(locationInfo, true);
        }
    }

}
