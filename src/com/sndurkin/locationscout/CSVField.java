package com.sndurkin.locationscout;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import com.sndurkin.locationscout.util.Strings;

public class CSVField implements Parcelable {

    public String name;
    public boolean selected = false;

    public CSVField(String name) {
        this.name = name;
    }
    public CSVField(String name, boolean selected) {
        this.name = name;
        this.selected = selected;
    }

    public String getDisplayName(Context context) {
        if(Strings.CSV_EXPORT_HEADER_TITLE.equals(name)) {
            return context.getString(R.string.title);
        }
        else if(Strings.CSV_EXPORT_HEADER_DATE.equals(name)) {
            return context.getString(R.string.date_title);
        }
        else if(Strings.CSV_EXPORT_HEADER_ADDRESS.equals(name)) {
            return context.getString(R.string.address_title);
        }
        else if(Strings.CSV_EXPORT_HEADER_LATITUDE.equals(name)) {
            return context.getString(R.string.latitude_title);
        }
        else if(Strings.CSV_EXPORT_HEADER_LONGITUDE.equals(name)) {
            return context.getString(R.string.longitude_title);
        }
        else if(Strings.CSV_EXPORT_HEADER_COORDINATES.equals(name)) {
            return context.getString(R.string.coordinates_title);
        }
        else if(Strings.CSV_EXPORT_HEADER_NOTES.equals(name)) {
            return context.getString(R.string.notes_title);
        }

        return name;
    }

    // Parcelable methods
    private CSVField(Parcel source) {
        this(
                source.readString(),
                (Boolean) source.readValue(Boolean.class.getClassLoader()));
    }

    public static final Creator<CSVField> CREATOR = new Creator<CSVField>() {
        @Override
        public CSVField createFromParcel(Parcel source) {
            return new CSVField(source);
        }

        @Override
        public CSVField[] newArray(int size) {
            return new CSVField[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeValue(Boolean.valueOf(selected));
    }

    @Override
    public int describeContents() {
        return 0;
    }
}