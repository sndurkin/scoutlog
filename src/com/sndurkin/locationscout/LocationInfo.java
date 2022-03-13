package com.sndurkin.locationscout;

import android.content.SharedPreferences;
import android.location.Address;
import android.os.Parcel;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import com.crashlytics.android.Crashlytics;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.Strings;

import org.json.JSONException;
import org.json.JSONObject;

public class LocationInfo implements Parcelable {

    public LatLng location;

    // This can be null when a location exists but the device cannot do
    // a reverse lookup for the address.
    public String addressStr;

    public boolean isAddressDerived;

    public LocationInfo() { }

    public LocationInfo(LocationInfo locationInfo) {
        this(locationInfo.location, locationInfo.addressStr, locationInfo.isAddressDerived);
    }

    public LocationInfo(LatLng location, String addressStr, boolean isAddressDerived) {
        this.location = location;
        this.addressStr = addressStr;
        this.isAddressDerived = isAddressDerived;
    }

    public LocationInfo(Address address) {
        this.location = new LatLng(address.getLatitude(), address.getLongitude());
        this.addressStr = MiscUtils.serializeAddress(address);
        this.isAddressDerived = false;
    }

    public LocationInfo(JSONObject jsonObj) {
        try {
            this.location = MiscUtils.deserializeLatLng(jsonObj.getString("location"));
            this.addressStr = jsonObj.optString("addressStr");
            this.isAddressDerived = jsonObj.getBoolean("isAddressDerived");
        }
        catch(JSONException e) {
            Crashlytics.logException(new RuntimeException("LocationInfo could not be created from JSON: " + jsonObj.toString(), e));
        }
    }

    public boolean hasAddress() {
        return addressStr != null && !addressStr.isEmpty();
    }

    public String getAddressForDisplay(String title) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Application.getInstance());
        return getAddressForDisplay(title, preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true));
    }

    // Returns either the LocationInfo's address or coordinates, depending on the user's preferences.
    public String getAddressForDisplay(String title, boolean showClosestAddress) {
        String snippet;
        if(!isAddressDerived || showClosestAddress) {
            if(hasAddress()) {
                String addressStr = this.addressStr;
                if(!TextUtils.isEmpty(title) && addressStr.toLowerCase().startsWith(title.toLowerCase() + "\n")) {
                    addressStr = addressStr.substring(addressStr.indexOf("\n") + 1);
                }
                snippet = addressStr;
            }
            else {
                snippet = Application.getInstance().getString(R.string.address_unavailable);
            }
        }
        else {
            snippet = MiscUtils.latLngToString(location);
        }

        return snippet;
    }

    public String getAddressHeader() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Application.getInstance());
        return getAddressHeader(preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true));
    }

    // Returns a header for the address (for use in the detail screens).
    public String getAddressHeader(boolean showClosestAddress) {
        if(isAddressDerived) {
            if(showClosestAddress) {
                return Application.getInstance().getString(R.string.appx_address_title);
            }
            else {
                return Application.getInstance().getString(R.string.coordinates_title);
            }
        }
        else {
            return Application.getInstance().getString(R.string.address_title);
        }
    }

    public JSONObject toJSON() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("location", MiscUtils.serializeLatLng(location));
            obj.put("addressStr", addressStr);
            obj.put("isAddressDerived", isAddressDerived);
        }
        catch(JSONException e) {
            Crashlytics.logException(e);
        }

        return obj;
    }

    @Override
    public boolean equals(Object o) {
        if(o == null || !(o instanceof LocationInfo)) {
            return false;
        }

        if(o == this) {
            return true;
        }

        LocationInfo li = (LocationInfo) o;

        if(location != null) {
            if(!location.equals(li.location)) {
                return false;
            }
        }
        else if(li.location != null) {
            if(!li.location.equals(location)) {
                return false;
            }
        }

        if(addressStr != null) {
            if(!addressStr.equals(li.addressStr)) {
                return false;
            }
        }
        else if(li.addressStr != null) {
            if(!li.addressStr.equals(addressStr)) {
                return false;
            }
        }

        if(isAddressDerived != li.isAddressDerived) {
            return false;
        }

        return true;
    }

    // Parcelable methods
    private LocationInfo(Parcel source) {
        this.location = new LatLng(source.readDouble(), source.readDouble());
        this.addressStr = source.readString();
        this.isAddressDerived = source.readInt() == 1;
    }

    public static final Creator<LocationInfo> CREATOR = new Creator<LocationInfo>() {
        @Override
        public LocationInfo createFromParcel(Parcel source) {
            return new LocationInfo(source);
        }

        @Override
        public LocationInfo[] newArray(int size) {
            return new LocationInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeDouble(location.latitude);
        dest.writeDouble(location.longitude);
        dest.writeString(addressStr);
        dest.writeInt(isAddressDerived ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

}
