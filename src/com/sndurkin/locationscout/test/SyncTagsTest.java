package com.sndurkin.locationscout.test;

import android.content.ContentValues;

import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.ModelUtils;
import com.sndurkin.locationscout.storage.TagModel;

import junit.framework.Assert;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;


public class SyncTagsTest extends SyncTest {

    public void testCreateTagLocally() {
        // Create tag locally
        createTagLocally("test");
        executeSyncAlgorithm();

        // Verification
        TagModel[] tags = syncer.fetchTagsForTest();
        Assert.assertEquals(1, tags.length);
        Assert.assertEquals("test", tags[0].name);
    }

    public void testCreateTagRemotely() {
        // Create tag remotely
        createTagRemotely("test");
        executeSyncAlgorithm();

        // Verification
        TagModel[] tags = fetchSyncedTags();
        Assert.assertEquals(1, tags.length);
        TagModel localTag = tags[0];
        Assert.assertNotNull(localTag.getRemoteId());
        Assert.assertEquals("test", localTag.name);
    }

    public void testUpdateTagNameLocally() {
        // Create tag locally
        Long tagId = createTagLocally("test");
        executeSyncAlgorithm();

        // Update tag locally
        database.saveTag(new TagModel(tagId, "updated"));
        executeSyncAlgorithm();

        // Verification
        TagModel[] tags = syncer.fetchTagsForTest();
        Assert.assertEquals(1, tags.length);
        Assert.assertEquals("updated", tags[0].name);
    }

    public void testUpdateTagNameRemotely() {
        // Create tag locally
        createTagLocally("test");
        executeSyncAlgorithm();

        // Update tag remotely
        TagModel[] tags = fetchSyncedTags();
        Assert.assertEquals(1, tags.length);
        TagModel remoteTag = tags[0];
        remoteTag.name = "updated";
        syncer.updateTagForTest(remoteTag);
        executeSyncAlgorithm();

        // Verification
        tags = fetchSyncedTags();
        Assert.assertEquals(1, tags.length);
        Assert.assertEquals("updated", tags[0].name);
    }

    public void testUpdateTagColorLocally() {
        // Create tag remotely
        createTagRemotely("test");
        executeSyncAlgorithm();

        // Update tag locally
        TagModel localTag = fetchSyncedTags()[0];
        database.saveTag(new TagModel(localTag.getLocalId(), 5, null));
        executeSyncAlgorithm();

        // Verification
        TagModel[] tags = syncer.fetchTagsForTest();
        Assert.assertEquals(1, tags.length);
        Assert.assertEquals(5, tags[0].color.intValue());
    }

    public void testUpdateTagColorRemotely() {
        // Create tag locally
        createTagLocally("test");
        executeSyncAlgorithm();

        // Update tag remotely
        TagModel[] tags = fetchSyncedTags();
        Assert.assertEquals(1, tags.length);
        TagModel remoteTag = tags[0];
        remoteTag.color = 5;
        syncer.updateTagForTest(remoteTag);
        executeSyncAlgorithm();

        // Verification
        tags = fetchSyncedTags();
        Assert.assertEquals(1, tags.length);
        Assert.assertEquals(5, tags[0].color.intValue());
    }

    public void testDeleteTagLocally() {
        // Create tag locally
        Long tagId = createTagLocally("test");
        executeSyncAlgorithm();

        // Delete tag locally
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.IS_DELETED_COLUMN, true);
        database.getWritableDatabase().update(DatabaseHelper.TAG_TABLE, values, "_id = ?", new String[] { tagId.toString() });
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(0, syncer.fetchTagsForTest().length);
        Assert.assertEquals(0, fetchSyncedTags().length);
    }

    public void testDeleteTagRemotely() {
        // Create tag locally
        createTagLocally("test");
        executeSyncAlgorithm();

        // Delete tag remotely
        TagModel localTag = fetchSyncedTags()[0];
        syncer.deleteTagForTest(localTag.getRemoteId());
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(0, syncer.fetchTagsForTest().length);
        Assert.assertEquals(0, fetchSyncedTags().length);
    }

    public void testTagForwardCompatibility() throws JSONException {
        // Create tag remotely
        String remoteTagId = createTagRemotely("test");
        executeSyncAlgorithm();

        // Update tag with unused property
        JSONObject tagObj = syncer.fetchTagJSONForTest()[0];
        tagObj.put("unused", 10);
        syncer.setTagJSONForTest(remoteTagId, tagObj);
        executeSyncAlgorithm();

        // Update tag locally
        TagModel localTag = fetchSyncedTags()[0];
        database.saveTag(new TagModel(localTag.getLocalId(), 5, null));
        executeSyncAlgorithm();

        // Verification
        Assert.assertEquals(5, syncer.fetchTagsForTest()[0].color.intValue());
        tagObj = syncer.fetchTagJSONForTest()[0];
        Assert.assertEquals(10, tagObj.getInt("unused"));
    }

}
