package com.sndurkin.locationscout;

import android.os.Parcel;
import android.os.Parcelable;

import com.sndurkin.locationscout.storage.SyncableModel;
import com.sndurkin.locationscout.util.MiscUtils;

import java.io.File;

// Used to hold all the information for storing photos.
public class PhotoInfo extends SyncableModel implements Parcelable {

    public String path;
    public String notes;
    public Integer sortNum;

    private File localFile;

    // This field holds the location title, when relevant.
    public String title;

    public PhotoInfo() { }

    public PhotoInfo(String path) {
        this.path = path;
    }

    public PhotoInfo(Long id, String path, String notes, Integer sortNum, String remoteId) {
        this.id = id;
        this.path = path;
        this.notes = notes;
        this.sortNum = sortNum;
        this.remoteId = remoteId;
    }

    public void mergeFrom(PhotoInfo photo, boolean copyMetadata) {
        if(photo.id != null) {
            this.id = photo.id;
        }
        if(photo.path != null) {
            this.path = photo.path;
        }
        if(photo.notes != null) {
            this.notes = photo.notes;
        }
        if(photo.sortNum != null) {
            this.sortNum = photo.sortNum;
        }
        if(photo.remoteId != null) {
            this.remoteId = photo.remoteId;
        }

        if(copyMetadata) {
            if(photo.lastModifiedDate != null) {
                this.lastModifiedDate = photo.lastModifiedDate;
            }
            if(photo.deleted != null) {
                this.deleted = photo.deleted;
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if(o == null || !(o instanceof PhotoInfo)) {
            return false;
        }

        if(o == this) {
            return true;
        }

        PhotoInfo pi = (PhotoInfo) o;

        if(!MiscUtils.equals(id, pi.id)) {
            return false;
        }
        if(!MiscUtils.equals(path, pi.path)) {
            return false;
        }
        if(!MiscUtils.equals(notes, pi.notes)) {
            return false;
        }
        if(!MiscUtils.equals(sortNum, pi.sortNum)) {
            return false;
        }
        if(!MiscUtils.equals(remoteId, pi.remoteId)) {
            return false;
        }

        return true;
    }

    @Override
    public Long getLastModifiedDate() {
        if(lastModifiedDate == null) {
            if(path != null) {
                File photoFile = new File(path);
                if(photoFile.exists()) {
                    lastModifiedDate = photoFile.lastModified();
                }
            }
        }

        return lastModifiedDate;
    }

    public boolean existsLocally() {
        return path != null && getLocalFile().exists();
    }

    public File getLocalFile() {
        if(localFile == null && path != null) {
            localFile = new File(path);
        }

        return localFile;
    }

    // Parcelable methods
    private PhotoInfo(Parcel source) {
        this(
                (Long) source.readValue(Long.class.getClassLoader()),
                source.readString(),
                source.readString(),
                (Integer) source.readValue(Integer.class.getClassLoader()),
                source.readString());
    }

    public static final Creator<PhotoInfo> CREATOR = new Creator<PhotoInfo>() {
        @Override
        public PhotoInfo createFromParcel(Parcel source) {
            return new PhotoInfo(source);
        }

        @Override
        public PhotoInfo[] newArray(int size) {
            return new PhotoInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeValue(id);
        dest.writeString(path);
        dest.writeString(notes);
        dest.writeValue(sortNum);
        dest.writeString(remoteId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

}
