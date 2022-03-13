package com.sndurkin.locationscout.test;


import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.PhotoInfoList;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.TagModel;

import junit.framework.Assert;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

public class SyncLocationsTest extends SyncTest {

    public void testCreateLocationLocally() {
        // Create location locally
        LocationModel localLocation = createDummyLocation();
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(1, fetchSyncedLocations().length);

        Assert.assertEquals(1, syncer.fetchLocationsForTest().length);
        LocationModel remoteLocation = syncer.fetchLocationsForTest()[0];

        Assert.assertEquals(localLocation.getTitle(), remoteLocation.getTitle());
        Assert.assertEquals(localLocation.getDate().getTime(), remoteLocation.getDate().getTime());
        Assert.assertEquals(localLocation.getNotes(), remoteLocation.getNotes());
        Assert.assertEquals(localLocation.getLocationInfo().addressStr, remoteLocation.getLocationInfo().addressStr);
    }

    public void testCreateLocationRemotely() {
        // Create location remotely
        LocationModel remoteLocation = createDummyLocation();
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(1, fetchSyncedLocations().length);
        LocationModel localLocation = fetchSyncedLocations()[0];

        Assert.assertEquals(1, syncer.fetchLocationsForTest().length);

        Assert.assertEquals(localLocation.getTitle(), remoteLocation.getTitle());
        Assert.assertEquals(localLocation.getDate().getTime(), remoteLocation.getDate().getTime());
        Assert.assertEquals(localLocation.getNotes(), remoteLocation.getNotes());
        Assert.assertEquals(localLocation.getLocationInfo().addressStr, remoteLocation.getLocationInfo().addressStr);
    }

    public void testUpdateLocationLocally() {
        // Create location remotely
        LocationModel remoteLocation = createDummyLocation();
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Update location locally
        LocationModel localLocation = fetchSyncedLocations()[0];
        localLocation.setTitle("updated");
        localLocation.setNotes("updated notes");
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        // Verification
        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals("updated", remoteLocation.getTitle());
        Assert.assertEquals("updated notes", remoteLocation.getNotes());
    }

    public void testUpdateLocationRemotely() {
        // Create location locally
        LocationModel localLocation = createDummyLocation();
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        // Update location remotely
        LocationModel remoteLocation = syncer.fetchLocationsForTest()[0];
        remoteLocation.setTitle("updated");
        remoteLocation.setNotes("updated notes");
        syncer.updateLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Verification
        localLocation = fetchSyncedLocations()[0];
        Assert.assertEquals("updated", localLocation.getTitle());
        Assert.assertEquals("updated notes", localLocation.getNotes());
    }

    public void testAddTagLocallyAndRemoveTagRemotely() {
        // Create tag locally
        Long tagId = createTagLocally("test");

        // Create location with tag locally
        LocationModel localLocation = createDummyLocation();
        localLocation.setTagIds(Collections.singletonList(tagId));
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        // Verification
        LocationModel remoteLocation = syncer.fetchLocationsForTest()[0];
        TagModel remoteTag = syncer.fetchTagsForTest()[0];
        Assert.assertEquals(1, remoteLocation.getRemoteTagIds().size());
        Assert.assertEquals(remoteTag.getRemoteId(), remoteLocation.getRemoteTagIds().get(0));

        // Remove tag from location remotely
        remoteLocation.getRemoteTagIds().clear();
        syncer.updateLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Verification
        localLocation = fetchSyncedLocations()[0];
        Assert.assertEquals(0, localLocation.getTagIds().size());
    }

    public void testAddTagRemotelyAndRemoveTagLocally() {
        // Create tag remotely
        String remoteTagId = createTagRemotely("test");

        // Create location with tag remotely
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setRemoteTagIds(Collections.singletonList(remoteTagId));
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Verification
        TagModel localTag = fetchSyncedTags()[0];
        LocationModel localLocation = fetchSyncedLocations()[0];
        Assert.assertEquals(1, localLocation.getTagIds().size());
        Assert.assertEquals(localTag.getLocalId(), localLocation.getTagIds().get(0));

        // Remove tag from location locally
        localLocation.getTagIds().clear();
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        // Verification
        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals(0, remoteLocation.getRemoteTagIds().size());
    }

    public void testDeleteTagLocallyBeforeSync() {
        // Create tag locally
        Long tagId = createTagLocally("test");

        // Create location with tag locally
        LocationModel localLocation = createDummyLocation();
        localLocation.setTagIds(Collections.singletonList(tagId));
        database.saveLocation(localLocation);

        // Delete tag locally
        database.deleteTag(tagId);
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(0, syncer.fetchLocationsForTest()[0].getRemoteTagIds().size());
    }

    public void testDeleteTagLocallyAfterSync() {
        // Create tag locally
        Long tagId = createTagLocally("test");

        // Create location with tag locally & sync
        LocationModel localLocation = createDummyLocation();
        localLocation.setTagIds(Collections.singletonList(tagId));
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        // Delete tag locally
        database.deleteTag(tagId);
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(0, syncer.fetchLocationsForTest()[0].getRemoteTagIds().size());
    }

    public void testAddPhotoLocallyAndRemovePhotoRemotely() {
        // Create location locally
        LocationModel localLocation = createDummyLocation();
        database.saveLocation(localLocation);
        Long locationId = fetchUnsyncedLocations()[0].getLocalId();

        // Create photo locally
        PhotoInfo localPhoto = new PhotoInfo();
        localPhoto.path = "whatever";
        localPhoto.sortNum = 0;
        localPhoto.notes = "test notes";
        database.savePhotoInfos(Collections.singletonList(localPhoto), locationId);
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(1, fetchSyncedPhotos().length);
        LocationModel remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals(1, remoteLocation.getPhotoInfoList().size());
        PhotoInfo remotePhoto = remoteLocation.getPhotoInfoList().get(0);
        Assert.assertEquals(0, remotePhoto.sortNum.intValue());
        Assert.assertEquals("test notes", remotePhoto.notes);

        // Remove photo remotely
        syncer.deletePhotoForTest(remotePhoto.getRemoteId());
        remoteLocation.getPhotoInfoList().clear();
        syncer.updateLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Verification
        localLocation = fetchSyncedLocations()[0];
        Assert.assertEquals(0, localLocation.getPhotoInfoList().size());
        Assert.assertEquals(0, fetchSyncedPhotos().length);
    }

    public void testAddPhotoRemotelyAndRemovePhotoLocally() {
        // Create location and photo remotely
        PhotoInfo remotePhoto = new PhotoInfo();
        remotePhoto.path = "whatever";
        remotePhoto.sortNum = 0;
        remotePhoto.notes = "test notes";
        syncer.createPhotoForTest(remotePhoto);
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setPhotoInfoList(Collections.singletonList(remotePhoto));
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Verification
        LocationModel localLocation = fetchSyncedLocations()[0];
        Assert.assertEquals(1, localLocation.getPhotoInfoList().size());
        PhotoInfo localPhoto = localLocation.getPhotoInfoList().get(0);
        Assert.assertEquals(0, localPhoto.sortNum.intValue());
        Assert.assertEquals("test notes", remotePhoto.notes);

        // Remove photo locally
        database.deletePhotoInfo(localPhoto, new PhotoInfoList(), localLocation.getLocalId());
        executeSyncAlgorithm();

        // Verification
        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals(0, remoteLocation.getPhotoInfoList().size());
        Assert.assertEquals(0, syncer.fetchPhotoIdsForTest().length);
        Assert.assertEquals(0, fetchSyncedPhotos().length);
    }

    // REFERENTIAL INTEGRITY
    //
    // When a user manually deletes a tag remotely, the remote location will have a referential
    // integrity issue because it will still hold a reference to a tag that has been deleted.
    // It should mark the local location for update on the 1st sync so that on the 2nd sync,
    // it will fix the remote location's referential integrity issue.
    public void testTagReferentialIntegrity() {
        // Create tag remotely
        String remoteTagId = createTagRemotely("test");

        // Create location with tag remotely
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setRemoteTagIds(Collections.singletonList(remoteTagId));
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Delete tag remotely
        syncer.deleteTagForTest(remoteTagId);
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(1, syncer.fetchLocationsForTest()[0].getRemoteTagIds().size());
        executeSyncAlgorithm();

        Assert.assertEquals(0, fetchSyncedLocations()[0].getTagIds().size());
        Assert.assertEquals(0, syncer.fetchLocationsForTest()[0].getRemoteTagIds().size());
        Assert.assertEquals(0, syncer.fetchTagsForTest().length);
    }

    // See the REFERENTIAL INTEGRITY comment.
    public void testPhotoReferentialIntegrity() {
        // Create location and photo remotely
        PhotoInfo remotePhoto = new PhotoInfo();
        remotePhoto.path = "whatever";
        remotePhoto.sortNum = 0;
        remotePhoto.notes = "test notes";
        syncer.createPhotoForTest(remotePhoto);
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setPhotoInfoList(Collections.singletonList(remotePhoto));
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Delete photo remotely, and execute the sync algorithm twice;
        // the 2nd time is so that referential integrity will be reestablished.
        syncer.deletePhotoForTest(remotePhoto.getRemoteId());
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(1, syncer.fetchLocationsForTest()[0].getPhotoInfoList().size());
        executeSyncAlgorithm();

        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals(0, remoteLocation.getPhotoInfoList().size());
        Assert.assertEquals(0, syncer.fetchPhotoIdsForTest().length);
        Assert.assertEquals(0, fetchSyncedPhotos().length);
    }

    // COMPROMISED REFERENTIAL INTEGRITY (adds to REFERENTIAL INTEGRITY comment)
    //
    // Before executing the sync algorithm a second time to restore referential integrity
    // for the location, update the location remotely. Then see if the sync algorithm
    // properly handles a location with compromised referential integrity.
    public void testTagCompromisedReferentialIntegrityForCreate() {
        // Create tag remotely
        String remoteTagId = createTagRemotely("test");

        // Create location with tag remotely
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setRemoteTagIds(Collections.singletonList(remoteTagId));
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Delete tag remotely and re-sync with a fresh database
        syncer.deleteTagForTest(remoteTagId);
        syncer.touchLocationForTest(remoteLocation.getRemoteId());
        clearLocalData();
        executeSyncAlgorithm();

        // Verification
        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals(1, remoteLocation.getRemoteTagIds().size());
        Assert.assertEquals(0, syncer.fetchTagsForTest().length);
        Assert.assertEquals(0, fetchSyncedTags().length);

        LocationModel localLocation = fetchSyncedLocations()[0];
        localLocation.setNotes("update");
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals("update", remoteLocation.getNotes());
        Assert.assertEquals(0, remoteLocation.getRemoteTagIds().size());
    }

    // See COMPROMISED REFERENTIAL INTEGRITY comment.
    public void testTagCompromisedReferentialIntegrityForUpdate() {
        // Create tag remotely
        String remoteTagId = createTagRemotely("test");

        // Create location with tag remotely
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setRemoteTagIds(Collections.singletonList(remoteTagId));
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Delete tag remotely
        syncer.deleteTagForTest(remoteTagId);
        executeSyncAlgorithm();

        // Update location remotely
        remoteLocation.setNotes("update 1");
        syncer.updateLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Verification
        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals(1, remoteLocation.getRemoteTagIds().size());
        Assert.assertEquals(0, syncer.fetchTagsForTest().length);
        Assert.assertEquals(0, fetchSyncedTags().length);

        LocationModel localLocation = fetchSyncedLocations()[0];
        Assert.assertEquals("update 1", localLocation.getNotes());
        localLocation.setNotes("update 2");
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals("update 2", remoteLocation.getNotes());
        Assert.assertEquals(0, remoteLocation.getRemoteTagIds().size());
    }

    // See COMPROMISED REFERENTIAL INTEGRITY comment.
    public void testPhotoCompromisedReferentialIntegrityForCreate() {
        // Create location and photo remotely
        PhotoInfo remotePhoto = new PhotoInfo();
        remotePhoto.path = "whatever";
        remotePhoto.sortNum = 0;
        remotePhoto.notes = "test notes";
        syncer.createPhotoForTest(remotePhoto);
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setPhotoInfoList(Collections.singletonList(remotePhoto));
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Delete photo remotely and re-sync with a fresh database
        syncer.deletePhotoForTest(remotePhoto.getRemoteId());
        syncer.touchLocationForTest(remoteLocation.getRemoteId());
        clearLocalData();
        executeSyncAlgorithm();

        // Verification
        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals(1, remoteLocation.getPhotoInfoList().size());
        Assert.assertEquals(0, syncer.fetchPhotoIdsForTest().length);
        Assert.assertEquals(0, fetchSyncedPhotos().length);

        LocationModel localLocation = fetchSyncedLocations()[0];
        localLocation.setNotes("update");
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals("update", remoteLocation.getNotes());
        Assert.assertEquals(0, remoteLocation.getPhotoInfoList().size());
    }

    // See COMPROMISED REFERENTIAL INTEGRITY comment.
    public void testPhotoCompromisedReferentialIntegrityForUpdate() {
        // Create location and photo remotely
        PhotoInfo remotePhoto = new PhotoInfo();
        remotePhoto.path = "whatever";
        remotePhoto.sortNum = 0;
        remotePhoto.notes = "test notes";
        syncer.createPhotoForTest(remotePhoto);
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setPhotoInfoList(Collections.singletonList(remotePhoto));
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Delete photo remotely
        syncer.deletePhotoForTest(remotePhoto.getRemoteId());
        executeSyncAlgorithm();

        // Update location remotely
        remoteLocation.setNotes("update 1");
        syncer.updateLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Verification
        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals(1, remoteLocation.getPhotoInfoList().size());
        Assert.assertEquals(0, syncer.fetchPhotoIdsForTest().length);
        Assert.assertEquals(0, fetchSyncedPhotos().length);

        LocationModel localLocation = fetchSyncedLocations()[0];
        Assert.assertEquals("update 1", localLocation.getNotes());
        localLocation.setNotes("update 2");
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        remoteLocation = syncer.fetchLocationsForTest()[0];
        Assert.assertEquals("update 2", remoteLocation.getNotes());
        Assert.assertEquals(0, remoteLocation.getPhotoInfoList().size());
    }

    public void testDeleteLocationLocally() {
        // Create location and photo remotely
        PhotoInfo remotePhoto = new PhotoInfo();
        remotePhoto.path = "whatever";
        remotePhoto.sortNum = 0;
        remotePhoto.notes = "test notes";
        syncer.createPhotoForTest(remotePhoto);
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setPhotoInfoList(Collections.singletonList(remotePhoto));
        syncer.createLocationForTest(remoteLocation);
        executeSyncAlgorithm();

        // Delete location locally
        database.deleteLocation(fetchSyncedLocations()[0].getLocalId());
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(0, syncer.fetchLocationsForTest().length);
        Assert.assertEquals(0, fetchSyncedLocations().length);
        Assert.assertEquals(0, syncer.fetchPhotoIdsForTest().length);
        Assert.assertEquals(0, fetchSyncedPhotos().length);
    }

    public void testDeleteLocationRemotely() {
        // Create location locally
        LocationModel localLocation = createDummyLocation();
        database.saveLocation(localLocation);
        Long locationId = fetchUnsyncedLocations()[0].getLocalId();

        // Create photo locally
        PhotoInfo localPhoto = new PhotoInfo();
        localPhoto.path = "whatever";
        localPhoto.sortNum = 0;
        localPhoto.notes = "test notes";
        database.savePhotoInfos(Collections.singletonList(localPhoto), locationId);
        executeSyncAlgorithm();

        // Delete location remotely
        syncer.deleteLocationForTest(syncer.fetchLocationsForTest()[0].getRemoteId());
        executeSyncAlgorithm();

        // Verification; if a user deletes a location file remotely, we ensure that the
        // corresponding photos are also deleted after the 2nd sync.
        Assert.assertEquals(0, syncer.fetchLocationsForTest().length);
        Assert.assertEquals(0, fetchSyncedLocations().length);

        executeSyncAlgorithm();
        Assert.assertEquals(0, fetchSyncedPhotos().length);
        Assert.assertEquals(0, syncer.fetchPhotoIdsForTest().length);
    }

    public void testMergeDuplicateTags() {
        // Create tag remotely
        String remoteTagId = createTagRemotely("test");

        // Create location with tag remotely
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setRemoteTagIds(Collections.singletonList(remoteTagId));
        syncer.createLocationForTest(remoteLocation);

        // Create tag locally
        Long localTagId = createTagLocally("test");

        // Create location with tag locally
        LocationModel localLocation = createDummyLocation();
        localLocation.setTagIds(Collections.singletonList(localTagId));
        database.saveLocation(localLocation);

        // Sync
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(1, fetchSyncedTags().length);
        Assert.assertEquals("test", fetchSyncedTags()[0].name);
        Assert.assertEquals(1, syncer.fetchTagsForTest().length);
        Assert.assertEquals("test", syncer.fetchTagsForTest()[0].name);
        Assert.assertEquals(2, fetchSyncedLocations().length);
        Assert.assertEquals(2, syncer.fetchLocationsForTest().length);
    }

    public void testLocationForwardCompatibility() throws JSONException {
        // Create location locally
        LocationModel localLocation = createDummyLocation();
        database.saveLocation(localLocation);
        executeSyncAlgorithm();
        String remoteLocationId = syncer.fetchLocationsForTest()[0].getRemoteId();

        // Update location with unused property
        JSONObject locationObj = syncer.fetchLocationJSONForTest()[0];
        locationObj.put("__unused__", 10);
        syncer.setTagJSONForTest(remoteLocationId, locationObj);
        executeSyncAlgorithm();

        // Update location locally
        localLocation = fetchSyncedLocations()[0];
        localLocation.setNotes("updated");
        database.saveLocation(localLocation);
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals("updated", syncer.fetchLocationsForTest()[0].getNotes());
        locationObj = syncer.fetchLocationJSONForTest()[0];
        Assert.assertEquals(10, locationObj.getInt("__unused__"));
    }

    public void testFailDuringPhotoSync() {
        // Create location & photo remotely
        PhotoInfo remotePhoto = new PhotoInfo();
        remotePhoto.path = "whatever";
        remotePhoto.sortNum = 0;
        remotePhoto.notes = "test notes";
        syncer.createPhotoForTest(remotePhoto);
        LocationModel remoteLocation = createDummyLocation();
        remoteLocation.setPhotoInfoList(Collections.singletonList(remotePhoto));
        syncer.createLocationForTest(remoteLocation);

        syncer.setFailDuringPhotoSync(true);
        try {
            executeSyncAlgorithm();
        }
        catch(Exception e) {
            // Do nothing.
        }

        Assert.assertEquals(1, fetchSyncedPhotos().length);

        syncer.touchPhotoForTest(remotePhoto.getRemoteId());
        syncer.setFailDuringPhotoSync(false);
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(1, fetchSyncedPhotos().length);
    }

}
