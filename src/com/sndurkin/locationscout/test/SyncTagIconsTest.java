package com.sndurkin.locationscout.test;

import com.sndurkin.locationscout.storage.TagModel;

import junit.framework.Assert;


public class SyncTagIconsTest extends SyncTest {

    public void testCreateTagWithIconLocally() {
        // Create tag locally
        createTagWithIconLocally("test", "1.jpg");
        executeSyncAlgorithm();

        // Verification
        TagModel remoteTag = syncer.fetchTagsForTest()[0];
        syncer.touchTagForTest(remoteTag.getRemoteId());
        clearLocalData();
        executeSyncAlgorithm();
        TagModel[] tags = fetchSyncedTags();
        Assert.assertNotNull(tags[0].getRemoteIconId());
        Assert.assertEquals("1.jpg", tags[0].getIconPath());
    }

    public void testCreateTagWithIconRemotely() {
        // Create tag remotely
        createTagWithIconRemotely("name", "1.jpg");
        executeSyncAlgorithm();

        // Verification
        TagModel[] tags = fetchSyncedTags();
        Assert.assertNotNull(tags[0].getRemoteIconId());
        Assert.assertEquals("1.jpg", tags[0].getIconPath());
    }

    public void testUpdateTagIconLocally() {
        // Create tag remotely
        createTagWithIconRemotely("test", "1.jpg");
        executeSyncAlgorithm();

        // Update tag locally
        TagModel localTag = fetchSyncedTags()[0];
        database.saveTag(new TagModel(localTag.getLocalId(), null, "2.jpg"));
        executeSyncAlgorithm();

        // Verification
        TagModel remoteTag = syncer.fetchTagsForTest()[0];
        syncer.touchTagForTest(remoteTag.getRemoteId());
        clearLocalData();
        executeSyncAlgorithm();
        TagModel[] tags = fetchSyncedTags();
        Assert.assertNotNull(tags[0].getRemoteIconId());
        Assert.assertEquals("2.jpg", tags[0].getIconPath());
    }

    public void testUpdateTagIconRemotely() {
        // Create tag locally
        createTagWithIconLocally("test", "1.jpg");
        executeSyncAlgorithm();

        // Update tag remotely
        TagModel[] tags = fetchSyncedTags();
        TagModel remoteTag = tags[0];
        remoteTag.setIconPath("2.jpg");
        syncer.updateTagIconForTest(remoteTag);
        executeSyncAlgorithm();

        // Verification
        tags = fetchSyncedTags();
        Assert.assertNotNull(tags[0].getRemoteIconId());
        Assert.assertEquals("2.jpg", tags[0].getIconPath());
    }

    public void testReplaceTagIconWithColorLocally() {
        // Create tag remotely
        createTagWithIconRemotely("test", "1.jpg");
        executeSyncAlgorithm();

        // Update tag locally with color
        TagModel localTag = fetchSyncedTags()[0];
        database.saveTag(new TagModel(localTag.getLocalId(), null, 5, false));
        executeSyncAlgorithm();

        // Verification
        TagModel remoteTag = syncer.fetchTagsForTest()[0];
        syncer.touchTagForTest(remoteTag.getRemoteId());
        clearLocalData();
        executeSyncAlgorithm();
        TagModel[] tags = fetchSyncedTags();
        Assert.assertNull(tags[0].getRemoteIconId());
        Assert.assertNull(tags[0].getIconPath());
        Assert.assertEquals(5, tags[0].color.intValue());
    }

    public void testReplaceTagIconWithColorRemotely() {
        // Create tag locally
        createTagWithIconLocally("test", "1.jpg");
        executeSyncAlgorithm();

        // Update tag remotely with color
        TagModel[] tags = syncer.fetchTagsForTest();
        TagModel remoteTag = tags[0];
        remoteTag.color = 5;
        syncer.deleteTagIconForTest(remoteTag);
        executeSyncAlgorithm();

        // Verification
        tags = fetchSyncedTags();
        Assert.assertNull(tags[0].getRemoteIconId());
        Assert.assertNull(tags[0].getIconPath());
        Assert.assertEquals(5, tags[0].color.intValue());
    }

    public void testDeleteTagWithIconLocally() {
        // Create tag remotely
        createTagWithIconRemotely("test", "1.jpg");
        executeSyncAlgorithm();

        // Delete tag locally
        TagModel localTag = fetchSyncedTags()[0];
        database.deleteTag(localTag.getLocalId());
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(0, syncer.fetchTagsForTest().length);
        Assert.assertEquals(0, syncer.fetchNumberOfFilesForTest());
    }

    public void testDeleteTagWithIconRemotely() {
        // Create tag locally
        createTagWithIconLocally("test", "1.jpg");
        executeSyncAlgorithm();

        // Delete tag remotely
        TagModel[] tags = syncer.fetchTagsForTest();
        syncer.deleteTagForTest(tags[0].getRemoteId());
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(0, fetchSyncedTags().length);
    }

    protected void createTagWithIconLocally(String name, String iconPath) {
        TagModel localTag = new TagModel();
        localTag.name = name;
        localTag.setIconPath(iconPath);
        database.saveTag(localTag);
    }

    protected void createTagWithIconRemotely(String name, String iconPath) {
        TagModel remoteTag = new TagModel();
        remoteTag.name = "test";
        remoteTag.setRemoteIconId(syncer.createTagIconForTest("1.jpg"));
        syncer.createTagForTest(remoteTag);
    }

}
