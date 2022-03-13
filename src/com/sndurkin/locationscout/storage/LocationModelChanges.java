package com.sndurkin.locationscout.storage;

import android.os.Parcel;
import android.os.Parcelable;

// This class is used to document what has changed for a location when the user
// visits and returns from the detail screen.
public class LocationModelChanges implements Parcelable {

    public boolean title;
    public boolean date;
    public boolean color;
    public boolean latLng;
    public boolean address;
    public boolean notes;
    public boolean photo;

    public LocationModelChanges() { }

    // Parcelable methods
    private LocationModelChanges(Parcel source) {
        title = source.readByte() != 0;
        date = source.readByte() != 0;
        color = source.readByte() != 0;
        latLng = source.readByte() != 0;
        address = source.readByte() != 0;
        notes = source.readByte() != 0;
        photo = source.readByte() != 0;
    }

    // This method merges the values from [other] into [this]; it's used to combine
    // the LocationModelChanges instances from DetailFragment and DetailPhotoFragment.
    public void mergeWith(LocationModelChanges other) {
        title |= other.title;
        date |= other.date;
        color |= other.color;
        latLng |= other.latLng;
        address |= other.address;
        notes |= other.notes;
        photo |= other.photo;
    }

    public boolean hasAnythingChanged() {
        return title || date || color || latLng || address || notes || photo;
    }

    public static final Creator<LocationModelChanges> CREATOR = new Creator<LocationModelChanges>() {
        @Override
        public LocationModelChanges createFromParcel(Parcel source) {
            return new LocationModelChanges(source);
        }

        @Override
        public LocationModelChanges[] newArray(int size) {
            return new LocationModelChanges[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (title ? 1 : 0));
        dest.writeByte((byte) (date ? 1 : 0));
        dest.writeByte((byte) (color ? 1 : 0));
        dest.writeByte((byte) (latLng ? 1 : 0));
        dest.writeByte((byte) (address ? 1 : 0));
        dest.writeByte((byte) (notes ? 1 : 0));
        dest.writeByte((byte) (photo ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
