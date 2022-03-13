package com.sndurkin.locationscout.test;


import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.SyncableModel;
import com.sndurkin.locationscout.storage.TagModel;

import junit.framework.Assert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;

public class SyncHiddenTagsTest extends SyncTest {

    public void testCreateHiddenTagLocally() throws JSONException {
        // Create 2 locations locally and link them together, then sync.
        Long id1 = database.saveLocation(createDummyLocation());
        Long id2 = database.saveLocation(createDummyLocation());
        database.saveLinkedLocations(Arrays.asList(id1, id2));
        executeSyncAlgorithm();

        String[] remoteLocationIds = getRemoteIdsOrderedByLocalIds(fetchSyncedLocations(), new Long[]{id1, id2});

        // Verify the hidden tag exists and it has references to its locations in the correct order.
        JSONObject[] hiddenTagJSON = syncer.fetchHiddenTagJSONForTest();
        Assert.assertEquals(1, hiddenTagJSON.length);
        Assert.assertEquals(true, hiddenTagJSON[0].getBoolean("hidden"));
        JSONArray locationDriveIds = hiddenTagJSON[0].getJSONArray("locationDriveIds");
        Assert.assertEquals(2, locationDriveIds.length());
        Assert.assertEquals(remoteLocationIds[0], locationDriveIds.getString(0));
        Assert.assertEquals(remoteLocationIds[1], locationDriveIds.getString(1));
        Assert.assertEquals(0, syncer.fetchLocationJSONForTest()[0].getJSONArray("tagDriveIds").length());
    }

    public void testCreateHiddenTagRemotely() {
        // Create 2 locations remotely and link them together, then sync.
        LocationModel l1 = createDummyLocation();
        syncer.createLocationForTest(l1);
        LocationModel l2 = createDummyLocation();
        syncer.createLocationForTest(l2);

        String[] remoteLocationIds = new String[2];
        remoteLocationIds[0] = l1.getRemoteId();
        remoteLocationIds[1] = l2.getRemoteId();

        TagModel remoteTag = new TagModel();
        remoteTag.hidden = true;
        remoteTag.setRemoteLocationIds(Arrays.asList(remoteLocationIds));
        syncer.createHiddenTagForTest(remoteTag);
        executeSyncAlgorithm();

        Long[] localLocationIds = getLocalIdsOrderedByRemoteIds(fetchSyncedLocations(), remoteLocationIds);

        // Verify that there is 1 hidden tag & 2 location_tag_pairs records in the DB.
        TagModel[] tags = fetchSyncedHiddenTags();
        Assert.assertEquals(1, tags.length);
        Assert.assertEquals(true, tags[0].isHidden());

        Assert.assertEquals(localLocationIds[0], tags[0].getLocationIds().get(0));
        Assert.assertEquals(localLocationIds[1], tags[0].getLocationIds().get(1));
        Assert.assertEquals(0, fetchSyncedLocations()[0].getTagIds().size());
    }

    public void testDeleteHiddenTagLocally() {
        // Create 2 locations remotely and link them together, then sync.
        LocationModel l1 = createDummyLocation();
        syncer.createLocationForTest(l1);
        LocationModel l2 = createDummyLocation();
        syncer.createLocationForTest(l2);

        String[] remoteLocationIds = new String[2];
        remoteLocationIds[0] = l1.getRemoteId();
        remoteLocationIds[1] = l2.getRemoteId();

        TagModel remoteTag = new TagModel();
        remoteTag.hidden = true;
        remoteTag.setRemoteLocationIds(Arrays.asList(remoteLocationIds));
        syncer.createHiddenTagForTest(remoteTag);
        executeSyncAlgorithm();

        // Unlink them locally, then sync.
        database.saveLinkedLocations(Arrays.asList(fetchSyncedLocations()[0].getLocalId()));
        executeSyncAlgorithm();

        // Verify that there are no hidden tags.
        Assert.assertEquals(0, syncer.fetchHiddenTagsForTest().length);
    }

    public void testDeleteHiddenTagRemotely() {
        // Create 2 locations locally and link them together, then sync.
        Long id1 = database.saveLocation(createDummyLocation());
        Long id2 = database.saveLocation(createDummyLocation());
        database.saveLinkedLocations(Arrays.asList(id1, id2));
        executeSyncAlgorithm();

        // Delete the remote tag, then sync.
        syncer.deleteHiddenTagForTest(syncer.fetchHiddenTagsForTest()[0].getRemoteId());
        executeSyncAlgorithm();

        // Verify that there are no hidden tags & neither location has tag references.
        Assert.assertEquals(0, fetchSyncedHiddenTags().length);
    }

    // This function doesn't sync, it's just local.
    public void testDeleteHiddenTagByDeletingLocation() {
        // Create 2 locations and link them together.
        Long id1 = database.saveLocation(createDummyLocation());
        Long id2 = database.saveLocation(createDummyLocation());
        database.saveLinkedLocations(Arrays.asList(id1, id2));
        Assert.assertEquals(1, fetchUnsyncedHiddenTags().length);

        // Delete one of the locations.
        database.deleteLocation(id1);

        // Verify that there are no hidden tags.
        Assert.assertEquals(0, fetchUnsyncedHiddenTags().length);
    }

    // This function doesn't sync, it's just local.
    public void testDeleteHiddenTagByDeletingLocations() {
        // Create 2 locations and link them together.
        Long id1 = database.saveLocation(createDummyLocation());
        Long id2 = database.saveLocation(createDummyLocation());
        database.saveLinkedLocations(Arrays.asList(id1, id2));
        Assert.assertEquals(1, fetchUnsyncedHiddenTags().length);

        // Delete one of the locations.
        database.deleteLocations(Arrays.asList(id1, id2));

        // Verify that there are no hidden tags.
        Assert.assertEquals(0, fetchUnsyncedHiddenTags().length);
    }

    public void testHiddenTagReferentialIntegrity() {
        // Create 2 locations remotely and link them together
        LocationModel l1 = createDummyLocation();
        syncer.createLocationForTest(l1);
        LocationModel l2 = createDummyLocation();
        syncer.createLocationForTest(l2);

        String[] remoteLocationIds = new String[2];
        remoteLocationIds[0] = l1.getRemoteId();
        remoteLocationIds[1] = l2.getRemoteId();

        TagModel remoteTag = new TagModel();
        remoteTag.hidden = true;
        remoteTag.setRemoteLocationIds(Arrays.asList(remoteLocationIds));
        syncer.createHiddenTagForTest(remoteTag);
        executeSyncAlgorithm();
        Assert.assertEquals(1, syncer.fetchHiddenTagsForTest().length);
        Assert.assertEquals(1, fetchSyncedHiddenTags().length);
        Assert.assertEquals(false, fetchSyncedHiddenTags()[0].isDeleted());

        // Delete one of the locations remotely
        syncer.deleteLocationForTest(syncer.fetchLocationsForTest()[0].getRemoteId());
        executeSyncAlgorithm();

        // Verify that the hidden tag is cleaned up
        Assert.assertEquals(1, syncer.fetchHiddenTagsForTest().length);
        Assert.assertEquals(1, fetchSyncedHiddenTags().length);
        Assert.assertEquals(true, fetchSyncedHiddenTags()[0].isDeleted());
        executeSyncAlgorithm();

        Assert.assertEquals(0, syncer.fetchHiddenTagsForTest().length);
        Assert.assertEquals(0, fetchSyncedHiddenTags().length);
    }

    public void testHiddenTagReferentialIntegrityWithoutCleanup() {
        // Create 2 locations remotely and link them together
        LocationModel l1 = createDummyLocation();
        syncer.createLocationForTest(l1);
        LocationModel l2 = createDummyLocation();
        syncer.createLocationForTest(l2);

        String[] remoteLocationIds = new String[2];
        remoteLocationIds[0] = l1.getRemoteId();
        remoteLocationIds[1] = l2.getRemoteId();

        TagModel remoteTag = new TagModel();
        remoteTag.hidden = true;
        remoteTag.setRemoteLocationIds(new ArrayList<>(Arrays.asList(remoteLocationIds)));
        syncer.createHiddenTagForTest(remoteTag);
        executeSyncAlgorithm();
        Assert.assertEquals(1, syncer.fetchHiddenTagsForTest().length);
        Assert.assertEquals(1, fetchSyncedHiddenTags().length);
        Assert.assertEquals(false, fetchSyncedHiddenTags()[0].isDeleted());

        // Create a new remote location and link it
        LocationModel l3 = createDummyLocation();
        syncer.createLocationForTest(l3);
        remoteTag.getRemoteLocationIds().add(l3.getRemoteId());
        syncer.updateHiddenTagForTest(remoteTag);

        // Delete one of the locations remotely
        syncer.deleteLocationForTest(syncer.fetchLocationsForTest()[0].getRemoteId());
        executeSyncAlgorithm();

        // Verify that the hidden tag is NOT deleted
        Assert.assertEquals(1, syncer.fetchHiddenTagsForTest().length);
        Assert.assertEquals(1, fetchSyncedHiddenTags().length);
        Assert.assertEquals(false, fetchSyncedHiddenTags()[0].isDeleted());
        executeSyncAlgorithm();

        Assert.assertEquals(1, syncer.fetchHiddenTagsForTest().length);
        Assert.assertEquals(1, fetchSyncedHiddenTags().length);
        Assert.assertEquals(false, fetchSyncedHiddenTags()[0].isDeleted());
    }

    public void testHiddenTagCompromisedReferentialIntegrityForCreate() {

    }

    public void testHiddenTagCompromisedReferentialIntegrityForUpdate() {

    }

    private Long[] getLocalIdsOrderedByRemoteIds(SyncableModel[] models, String[] remoteIds) {
        Long[] localIds = new Long[remoteIds.length];
        for(int i = 0; i < remoteIds.length; ++i) {
            for(SyncableModel model : models) {
                if(model.getRemoteId().equals(remoteIds[i])) {
                    localIds[i] = model.getLocalId();
                    break;
                }
            }
        }
        return localIds;
    }

    private String[] getRemoteIdsOrderedByLocalIds(SyncableModel[] models, Long[] localIds) {
        String[] remoteIds = new String[localIds.length];
        for(int i = 0; i < localIds.length; ++i) {
            for(SyncableModel model : models) {
                if(model.getLocalId().equals(localIds[i])) {
                    remoteIds[i] = model.getRemoteId();
                    break;
                }
            }
        }
        return remoteIds;
    }

}
