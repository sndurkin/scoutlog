package com.sndurkin.locationscout.storage;

import android.database.Cursor;

import java.util.List;

// This is the result returned from a query executed by DatabaseHelper. It's passed
// into the DatabaseQueryListener.onQueryExecuted(), and only one of its members
// will be populated (which one is populated depends on the query).
public class DatabaseQueryResult {

    public Long id;
    public List<Long> ids;
    public Cursor cursor;
    public Boolean result;

    public DatabaseQueryResult() { }

    public DatabaseQueryResult(Long id) {
        this.id = id;
    }

    public DatabaseQueryResult(List<Long> ids) {
        this.ids = ids;
    }

    public DatabaseQueryResult(Cursor cursor) {
        this.cursor = cursor;
    }

    public DatabaseQueryResult(Boolean result) { this.result = result; }
}