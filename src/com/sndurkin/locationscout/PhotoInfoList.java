package com.sndurkin.locationscout;

import android.database.Cursor;
import android.support.annotation.NonNull;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.util.MiscUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PhotoInfoList {

    @NonNull
    private ArrayList<PhotoInfo> list;

    public PhotoInfoList() {
        this.list = new ArrayList<>();
    }

    public PhotoInfoList(ArrayList<PhotoInfo> list) {
        this.list = list != null ? list : new ArrayList<PhotoInfo>();
    }

    public PhotoInfo findById(long photoId) {
        for(PhotoInfo photoInfo : list) {
            if(photoInfo.getLocalId() == photoId) {
                return photoInfo;
            }
        }

        Long[] ids = new Long[list.size()];
        for(int i = 0; i < list.size(); ++i) {
            ids[i] = list.get(i).getLocalId();
        }
        CrashlyticsCore.getInstance().logException(new RuntimeException("Invalid code path! photoId: " + photoId + ", ids: " + ids));
        return null;
    }

    public PhotoInfo get(int idx) {
        return list.get(idx);
    }

    public void add(PhotoInfo newPhotoInfo) {
        list.add(newPhotoInfo);
    }

    public void add(int idx, PhotoInfo newPhotoInfo) {
        list.add(idx, newPhotoInfo);
    }

    public void addAll(List<PhotoInfo> photoInfos) {
        list.addAll(photoInfos);
    }

    // Converts all the records from the Cursor to PhotoInfo instances, and
    // then sorts the list by their sortNums.
    public void addAll(Cursor cursor) {
        while(!cursor.isAfterLast()) {
            PhotoInfo photoInfo = new PhotoInfo(
                    cursor.getLong(cursor.getColumnIndex(DatabaseHelper.ID_COLUMN)),
                    cursor.getString(cursor.getColumnIndex(DatabaseHelper.PHOTO_PATH_COLUMN)),
                    cursor.getString(cursor.getColumnIndex(DatabaseHelper.PHOTO_NOTES_COLUMN)),
                    cursor.getInt(cursor.getColumnIndex(DatabaseHelper.PHOTO_SORT_NUM_COLUMN)),
                    MiscUtils.getString(cursor, DatabaseHelper.DRIVE_FILE_ID_COLUMN)
            );

            list.add(photoInfo);
            cursor.moveToNext();
        }

        sort();
    }

    protected void sort() {
        Collections.sort(list, new Comparator<PhotoInfo>() {
            @Override
            public int compare(PhotoInfo lhs, PhotoInfo rhs) {
                return lhs.sortNum - rhs.sortNum;
            }
        });

        // Ensures the sortNums are organized consecutively, beginning with 0.
        for(int i = 0; i < list.size(); ++i) {
            list.get(i).sortNum = i;
        }
    }

    public PhotoInfo remove(int index) {
        return list.remove(index);
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public int size() {
        return list.size();
    }

    public ArrayList<PhotoInfo> getList() {
        return list;
    }

    public int getNextSortNum() {
        if(list.isEmpty()) {
            return 0;
        }

        return list.get(list.size() - 1).sortNum + 1;
    }

}
