package com.sndurkin.locationscout.dataload;


import android.content.ContentValues;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

import com.sndurkin.locationscout.storage.DatabaseHelper;

// This isn't a testcase; it's just an easy way to set models to tentatively deleted
// so we can test the DatabaseHelper.ensureModelsAreDeleted() function.
public class TentativelyDeleteModels extends AndroidTestCase {

    protected DatabaseHelper database;
    protected RenamingDelegatingContext context;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        context = new RenamingDelegatingContext(getContext(), "test_");
        database = DatabaseHelper.getInstance(context);
    }

    public void test() {
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.IS_TENTATIVELY_DELETED_COLUMN, true);
        database.getWritableDatabase().update(DatabaseHelper.LOCATION_TABLE, values, "is_deleted IS NULL OR is_deleted = 0", null);
        database.getWritableDatabase().update(DatabaseHelper.TAG_TABLE, values, "is_deleted IS NULL OR is_deleted = 0", null);
    }

}
