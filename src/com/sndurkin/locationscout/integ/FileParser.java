package com.sndurkin.locationscout.integ;


import android.content.Context;
import android.content.SharedPreferences;

import com.sndurkin.locationscout.storage.DatabaseHelper;

import java.io.InputStream;

public abstract class FileParser {

    protected Context context;
    protected DatabaseHelper database;
    protected SharedPreferences preferences;

    protected boolean reachedLocationsLimit = false;

    public FileParser(Context context, DatabaseHelper database, SharedPreferences preferences) {
        this.context = context;
        this.database = database;
        this.preferences = preferences;
    }

    public abstract ParseResult parse(final InputStream inputStream);

}
