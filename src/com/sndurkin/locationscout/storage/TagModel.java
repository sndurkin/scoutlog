package com.sndurkin.locationscout.storage;

import android.content.ContentValues;

import java.util.Date;
import java.util.List;

public class TagModel extends SyncableModel {

    public String name;

    // Tags are represented in the map either by a default
    // marker or a custom marker. If it's a default marker,
    // it can have a custom color; in that case, the color
    // property will be set. If the tag has a custom marker,
    // the iconPath property will be set.
    public Integer color;
    public String iconPath;
    public String remoteIconId;

    // Holds hidden functionality; this may one day
    // be separated into its own model class, because hidden
    // tags don't have a name, color or icon.
    public Boolean hidden;
    private List<Long> locationIds;
    private List<String> remoteLocationIds;

    public TagModel() { }

    public TagModel(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public TagModel(Long id, Integer color, String iconPath) {
        this.id = id;
        this.color = color;
        this.iconPath = iconPath;
    }

    public TagModel(Long id, String name, Integer color, Boolean hidden) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.hidden = hidden;
    }

    public void mergeFrom(TagModel tag, boolean copyMetadata) {
        if(tag == null) {
            return;
        }

        if(tag.id != null) {
            this.id = tag.id;
        }
        if(tag.name != null) {
            this.name = tag.name;
        }
        if(tag.color != null) {
            this.color = tag.color;
        }
        if(tag.iconPath != null) {
            this.iconPath = tag.iconPath;
        }
        if(tag.remoteIconId != null) {
            this.remoteIconId = tag.remoteIconId;
        }
        if(tag.hidden != null) {
            this.hidden = tag.hidden;
        }
        if(tag.getRemoteLocationIds() != null) {
            this.setRemoteLocationIds(tag.getRemoteLocationIds());
        }

        if(tag.remoteId != null) {
            this.remoteId = tag.remoteId;
        }

        if(copyMetadata) {
            if(tag.lastModifiedDate != null) {
                this.lastModifiedDate = tag.lastModifiedDate;
            }
            if(tag.deleted != null) {
                this.deleted = tag.deleted;
            }
        }
    }

    public boolean hasColor() {
        return color != null && color != 0;
    }

    public boolean hasIcon() {
        return iconPath != null && !iconPath.isEmpty();
    }

    public String getIconPath() {
        return iconPath;
    }

    public void setIconPath(java.lang.String iconPath) {
        this.iconPath = iconPath;
    }

    public String getRemoteIconId() {
        return remoteIconId;
    }

    public void setRemoteIconId(String remoteIconId) {
        this.remoteIconId = remoteIconId;
    }

    public boolean isHidden() { return hidden != null && hidden; }

    public List<String> getRemoteLocationIds() {
        return remoteLocationIds;
    }

    public void setRemoteLocationIds(List<String> remoteLocationIds) {
        this.remoteLocationIds = remoteLocationIds;
    }

    public List<Long> getLocationIds() {
        return locationIds;
    }

    public void setLocationIds(List<Long> locationIds) {
        this.locationIds = locationIds;
    }

}
