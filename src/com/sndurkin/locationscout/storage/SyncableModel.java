package com.sndurkin.locationscout.storage;


import org.json.JSONObject;

// This class helps enforce a standard interface that can be used across the storage system,
// although every class that uses the interface also knows about its subclasses.
public abstract class SyncableModel {

    protected Long id;
    protected String remoteId;

    // Metadata for the tag.
    protected Long lastModifiedDate;
    protected Boolean deleted;

    public void setLocalId(Long id) { this.id = id; }
    public Long getLocalId() { return id; }

    public void setRemoteId(String remoteId) { this.remoteId = remoteId; }
    public String getRemoteId() { return remoteId; }

    public void setLastModifiedDate(Long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
    public Long getLastModifiedDate() { return lastModifiedDate; }

    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public boolean isDeleted() { return deleted != null ? deleted : false; }

}
