package com.sndurkin.locationscout.storage;


import android.content.ContentValues;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import com.sndurkin.locationscout.Application;
import com.sndurkin.locationscout.LocationInfo;
import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.util.Strings;

import org.json.JSONException;
import org.json.JSONObject;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;


public class LocationModel extends SyncableModel {

    public String title;
    public String notes;
    public Date date;

    private String tagsStr;
    private List<String> remoteTagIds;
    private List<Long> tagIds;
    private List<PhotoInfo> photoInfoList;

    // This can be null when there is no location set!
    private LocationInfo locationInfo;

    // Temporary field used to save the current distance from this
    // location to the user.
    private Float distance;

    // Field used to save the first tag's color or icon.
    private Integer color;
    private String iconPath;

    public LocationModel() { }

    public LocationModel(Long id) {
        this.id = id;
    }

    public void mergeFrom(LocationModel location, boolean copyMetadata) {
        if(location.id != null) {
            this.id = location.id;
        }
        if(location.title != null) {
            this.title = location.title;
        }
        if(location.notes != null) {
            this.notes = location.notes;
        }
        if(location.date != null) {
            this.date = location.date;
        }
        if(location.remoteId != null) {
            this.remoteId = location.remoteId;
        }
        if (location.getLocationInfo() != null) {
            this.setLocationInfo(location.getLocationInfo());
        }
        if (location.getPhotoInfoList() != null) {
            this.setPhotoInfoList(location.getPhotoInfoList());
        }
        if(location.getRemoteTagIds() != null) {
            this.setRemoteTagIds(location.getRemoteTagIds());
        }

        if(copyMetadata) {
            if(location.lastModifiedDate != null) {
                this.lastModifiedDate = location.lastModifiedDate;
            }
            if(location.deleted != null) {
                this.deleted = location.deleted;
            }
        }
    }

    public String getTitle() {
        if(title == null) {
            title = "";
        }
        return title;
    }

    public String getTitleForDisplay(boolean showClosestAddress) {
        String addressStr = null;
        boolean isAddressDerived = true;
        if(locationInfo != null) {
            isAddressDerived = locationInfo.isAddressDerived;
            addressStr = locationInfo.addressStr;
        }

        return getTitleForDisplay(title, addressStr, isAddressDerived, showClosestAddress);
    }

    public static String getTitleForDisplay(String title, String addressStr, boolean isAddressDerived) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Application.getInstance());
        boolean showClosestAddress = preferences.getBoolean(Strings.PREF_SHOW_CLOSEST_ADDRESS, true);
        return getTitleForDisplay(title, addressStr, isAddressDerived, showClosestAddress);
    }

    public static String getTitleForDisplay(String title, String addressStr, boolean isAddressDerived, boolean showClosestAddress) {
        if (TextUtils.isEmpty(title)) {
            if(!TextUtils.isEmpty(addressStr) && (!isAddressDerived || showClosestAddress)) {
                int idx = addressStr.indexOf("\n");
                return idx > 0 ? addressStr.substring(0, idx) : addressStr;
            }

            return Application.getInstance().getString(R.string.untitled);
        }

        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getNotes() {
        if(notes == null) {
            notes = "";
        }
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Date getDate() {
        // Note: Don't remove this auto-set functionality without checking callers, some of them
        // depend on it to set the current date on the location before saving.
        if(date == null) {
            date = new Date(Calendar.getInstance().getTimeInMillis());
        }
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }

    public List<String> getRemoteTagIds() {
        return remoteTagIds;
    }

    public void setRemoteTagIds(List<String> remoteTagIds) {
        this.remoteTagIds = remoteTagIds;
    }

    public List<Long> getTagIds() {
        return tagIds;
    }

    public void setTagIds(List<Long> tagIds) {
        this.tagIds = tagIds;
    }

    public void setTagsStr(String tagsStr) {
        this.tagsStr = tagsStr;
    }

    public String getTagsStr() {
        return tagsStr;
    }

    public List<PhotoInfo> getPhotoInfoList() {
        return photoInfoList;
    }

    public void setPhotoInfoList(List<PhotoInfo> photoInfoList) {
        this.photoInfoList = photoInfoList;
    }

    public void addPhotoInfo(PhotoInfo photoInfo) {
        if(photoInfoList == null) {
            photoInfoList = new ArrayList<>();
        }
        photoInfoList.add(photoInfo);
    }

    public boolean hasLocation() {
        return locationInfo != null;
    }

    public LocationInfo getLocationInfo() {
        return locationInfo;
    }

    public void setLocationInfo(LocationInfo locationInfo) {
        this.locationInfo = locationInfo;
    }

    public Float getDistance() {
        return distance;
    }

    public void setDistance(float distance) {
        this.distance = distance;
    }

    public int getColor() {
        return color != null ? color : 0;
    }

    public String getIconPath() {
        return iconPath;
    }

    public void setColor(Integer color) {
        this.color = color;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
    }

    // TODO: add ability to nullify fields
    public ContentValues createContentValues() {
        ContentValues values = new ContentValues();
        Long currentTime = new java.util.Date().getTime();

        if(title != null) {
            values.put(DatabaseHelper.LOCATION_TITLE_COLUMN, title);
        }
        if(notes != null) {
            values.put(DatabaseHelper.LOCATION_NOTES_COLUMN, notes);
        }
        if(date != null) {
            values.put(DatabaseHelper.LOCATION_DATE_COLUMN, date.getTime());
        }

        if(locationInfo != null) {
            values.put(DatabaseHelper.LOCATION_LATITUDE_COLUMN, locationInfo.location.latitude);
            values.put(DatabaseHelper.LOCATION_LONGITUDE_COLUMN, locationInfo.location.longitude);
            values.put(DatabaseHelper.LOCATION_ADDRESS_STR_COLUMN, locationInfo.addressStr);
            values.put(DatabaseHelper.LOCATION_ADDRESS_DERIVED_COLUMN, locationInfo.isAddressDerived);
        }

        values.put(DatabaseHelper.LAST_MODIFIED_DATE_COLUMN, currentTime);

        return values;
    }

}
