package com.sndurkin.locationscout.storage;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Environment;
import android.text.TextUtils;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.BuildConfig;
import com.sndurkin.locationscout.GlobalBroadcastManager;
import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.PhotoInfoList;
import com.sndurkin.locationscout.util.FileUtils;
import com.sndurkin.locationscout.util.MiscUtils;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "location_scout.db";
    private static final int SCHEMA_VERSION = 6;

    public static final String ID_COLUMN = "_id";
    public static final String TRANSIENT_COUNT_COLUMN = "count";
    public static final String TRANSIENT_IS_LINKED_COLUMN = "is_linked";

    // Column names for communicating with Google Drive
    public static final String DRIVE_FILE_ID_COLUMN = "drive_file_id";
    public static final String CREATED_DATE_COLUMN = "created_date";
    public static final String LAST_MODIFIED_DATE_COLUMN = "last_modified_date";
    public static final String IS_DELETED_COLUMN = "is_deleted";

    // This column is used to specify that the user has deleted the record
    // but can still undo the action.
    //
    // Once the user deletes a location/tag/photo record, this column is set to true.
    // After that, there is a small window of time in which the user can undo the action
    // (which would nullify this column). If the time expires or if the user
    // navigates away from the screen, the IS_DELETED column is set to true and
    // this column is nullified (or the record is just deleted, if it's not yet synced).
    public static final String IS_TENTATIVELY_DELETED_COLUMN = "is_tentatively_deleted";

    // Location table
    public static final String LOCATION_TABLE = "locations";
    public static final String LOCATION_TITLE_COLUMN = "title";
    public static final String LOCATION_NOTES_COLUMN = "notes";
    public static final String LOCATION_DATE_COLUMN = "date";
    public static final String LOCATION_LATITUDE_COLUMN = "latitude";
    public static final String LOCATION_LONGITUDE_COLUMN = "longitude";
    public static final String LOCATION_ADDRESS_STR_COLUMN = "address_str";
    public static final String LOCATION_ADDRESS_DERIVED_COLUMN = "is_address_derived";
    public static final String LOCATION_DISTANCE_COLUMN = "distance";
    public static final String FOREIGN_LOCATION_ID_COLUMN = "location_id";

    // This column is used to fix a race condition that may arise when adding tags/photos
    // to a location during sync. If a new tag/photo is added to a new location between when
    // syncTags()/syncPhotos() and syncLocations() are executed, the new tag/photo won't make it
    // into Google Drive during the sync (or even on the next sync). Here's what happens:
    //
    //  1) The sync process calls syncTags().
    //  2) The user creates a new tag and a location, and adds the tag to the location.
    //  3) Sync process calls syncLocations(), creating that location in Drive. Because the tag
    //     wasn't created in Drive, it has no Drive ID yet so it doesn't get stored in the
    //     location. The local location's LAST_MODIFIED_DATE is updated.
    //
    // As a result, the tag will only be stored in the Drive location the next time it's updated
    // locally. To counter this, we introduce a column in the location table UPDATE_AFTER_SYNC_COLUMN
    // which is set when the user creates/updates a location during the sync process. After sync
    // is completed, it updates the LAST_MODIFIED_DATE for every location that has this field set.
    public static final String UPDATE_AFTER_SYNC_COLUMN = "update_after_sync_column";

    // Photo table
    public static final String PHOTO_TABLE = "photos";
    public static final String PHOTO_PATH_COLUMN = "path";
    public static final String PHOTO_NOTES_COLUMN = "notes";
    public static final String PHOTO_SORT_NUM_COLUMN = "sort_num";

    // Tag table
    public static final String TAG_TABLE = "tags";
    public static final String TAG_NAME_COLUMN = "name";
    public static final String FOREIGN_TAG_ID_COLUMN = "tag_id";
    public static final String TAG_COLOR_COLUMN = "color";
    public static final String TAG_IS_HIDDEN_COLUMN = "is_hidden";
    public static final String ICON_PATH_COLUMN = "icon_path";
    public static final String ICON_DRIVE_FILE_ID_COLUMN = "icon_drive_file_id";

    // Location-Tag pair table
    public static final String LOCATION_TAG_PAIR_TABLE = "location_tag_pairs";
    public static final String LOCATION_TAG_SORT_NUM = "location_tag_sort_num";

    private Context context;

    // Singleton pattern
    private static DatabaseHelper instance;
    public synchronized static DatabaseHelper getInstance(Context context) {
        if(instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());

            if(BuildConfig.DEBUG) {
                try {
                    java.io.File sd = Environment.getExternalStorageDirectory();
                    java.io.File data = Environment.getDataDirectory();

                    if(sd.canWrite()) {
                        java.io.File backupDB = new java.io.File(sd, DATABASE_NAME);
                        if(backupDB.exists()) {
                            backupDB.delete();
                        }
                        backupDB.createNewFile();

                        java.io.File currentDB = new java.io.File(data, "/data/" + context.getPackageName() + "/databases/" + DATABASE_NAME);
                        if(currentDB.exists()) {
                            FileChannel src = new FileInputStream(currentDB).getChannel();
                            FileChannel dst = new FileOutputStream(backupDB).getChannel();
                            dst.transferFrom(src, 0, src.size());
                            src.close();
                            dst.close();
                        }
                    }
                }
                catch (Exception e) {
                    MiscUtils.logd("Cannot copy db to /sdcard", e);
                }
            }
        }

        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, SCHEMA_VERSION);
        this.context = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create the tables.
        db.execSQL("CREATE TABLE " + LOCATION_TABLE + " ("
                + ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + DRIVE_FILE_ID_COLUMN + " TEXT, "
                + CREATED_DATE_COLUMN + " INTEGER, "
                + LAST_MODIFIED_DATE_COLUMN + " INTEGER, "
                + IS_DELETED_COLUMN + " INTEGER, "
                + IS_TENTATIVELY_DELETED_COLUMN + " INTEGER, "
                + UPDATE_AFTER_SYNC_COLUMN + " INTEGER, "
                + LOCATION_TITLE_COLUMN + " TEXT, "
                + LOCATION_NOTES_COLUMN + " TEXT, "
                + LOCATION_DATE_COLUMN + " INTEGER, "
                + LOCATION_LATITUDE_COLUMN + " REAL, "
                + LOCATION_LONGITUDE_COLUMN + " REAL, "
                + LOCATION_ADDRESS_STR_COLUMN + " TEXT, "
                + LOCATION_ADDRESS_DERIVED_COLUMN + " INTEGER, "
                + LOCATION_DISTANCE_COLUMN + " REAL);");

        db.execSQL(new StringBuffer()
                 + "CREATE TABLE " + PHOTO_TABLE + " ("
                 + ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                 + FOREIGN_LOCATION_ID_COLUMN + " INTEGER, "
                 + DRIVE_FILE_ID_COLUMN + " TEXT, "
                 + IS_DELETED_COLUMN + " INTEGER, "
                 + IS_TENTATIVELY_DELETED_COLUMN + " INTEGER, "
                 + PHOTO_PATH_COLUMN + " TEXT, "
                 + PHOTO_NOTES_COLUMN + " TEXT, "
                 + PHOTO_SORT_NUM_COLUMN + " INTEGER);");

        db.execSQL("CREATE TABLE " + TAG_TABLE + " ("
                + ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + DRIVE_FILE_ID_COLUMN + " TEXT, "
                + CREATED_DATE_COLUMN + " INTEGER, "
                + LAST_MODIFIED_DATE_COLUMN + " INTEGER, "
                + IS_DELETED_COLUMN + " INTEGER, "
                + IS_TENTATIVELY_DELETED_COLUMN + " INTEGER, "
                + TAG_NAME_COLUMN + " TEXT, "
                + TAG_COLOR_COLUMN + " INTEGER, "
                + ICON_PATH_COLUMN + " TEXT, "
                + ICON_DRIVE_FILE_ID_COLUMN + " TEXT, "
                + TAG_IS_HIDDEN_COLUMN + " INTEGER, "
                + UPDATE_AFTER_SYNC_COLUMN + " INTEGER);");

        db.execSQL("CREATE TABLE " + LOCATION_TAG_PAIR_TABLE + " ("
                + FOREIGN_LOCATION_ID_COLUMN + " INTEGER, "
                + FOREIGN_TAG_ID_COLUMN + " INTEGER, "
                + LOCATION_TAG_SORT_NUM + " INTEGER, "
                + "PRIMARY KEY (" + FOREIGN_LOCATION_ID_COLUMN + ", " + FOREIGN_TAG_ID_COLUMN + "));");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion < 2 && newVersion >= 2) {
            // Version 1 -> 2:
            //  - Added location_tag_sort_num to location_tag_pairs
            //  - Added color to tags
            db.execSQL("ALTER TABLE " + LOCATION_TAG_PAIR_TABLE + " ADD COLUMN " + LOCATION_TAG_SORT_NUM + " INTEGER;");
            db.execSQL("ALTER TABLE " + TAG_TABLE + " ADD COLUMN " + TAG_COLOR_COLUMN + " INTEGER;");
        }

        if(oldVersion < 3 && newVersion >= 3) {
            // Version 2 -> 3:
            //  - Added is_tentatively_deleted to locations, photos, and tags
            db.execSQL("ALTER TABLE " + LOCATION_TABLE + " ADD COLUMN " + IS_TENTATIVELY_DELETED_COLUMN + " INTEGER;");
            db.execSQL("ALTER TABLE " + TAG_TABLE + " ADD COLUMN " + IS_TENTATIVELY_DELETED_COLUMN + " INTEGER;");
            db.execSQL("ALTER TABLE " + PHOTO_TABLE + " ADD COLUMN " + IS_TENTATIVELY_DELETED_COLUMN + " INTEGER;");
        }

        if(oldVersion < 4 && newVersion >= 4) {
            // Version 3 -> 4:
            //  - Added is_hidden and update_after_sync_column to tags
            db.execSQL("ALTER TABLE " + TAG_TABLE + " ADD COLUMN " + TAG_IS_HIDDEN_COLUMN + " INTEGER;");
            db.execSQL("ALTER TABLE " + TAG_TABLE + " ADD COLUMN " + UPDATE_AFTER_SYNC_COLUMN + " INTEGER;");
        }

        if(oldVersion < 5 && newVersion >= 5) {
            // Version 4 -> 5:
            //  - Added distance to locations
            db.execSQL("ALTER TABLE " + LOCATION_TABLE + " ADD COLUMN " + LOCATION_DISTANCE_COLUMN + " REAL;");
        }

        if(oldVersion < 6 && newVersion >= 6) {
            // Version 5 -> 6:
            //  - Added icon_path and icon_drive_file_id to tags
            db.execSQL("ALTER TABLE " + TAG_TABLE + " ADD COLUMN " + ICON_PATH_COLUMN + " TEXT;");
            db.execSQL("ALTER TABLE " + TAG_TABLE + " ADD COLUMN " + ICON_DRIVE_FILE_ID_COLUMN + " TEXT;");
        }
    }

    // DB interface for executing each asynchronous task.
    public void fetchLocations(String filter, String filterType, LocationsQuerySortType sortType, int start, int count, DatabaseQueryListener listener) {
        new FetchLocationsTask(filter, filterType, sortType, start, count, listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public Cursor fetchLocationsByIds(List<? extends Number> locationIds) {
        String query = "SELECT l.*, (\n"
                     + "  SELECT name FROM tags t\n"
                     + "  INNER JOIN location_tag_pairs lt ON lt.tag_id = t._id\n"
                     + "  WHERE t.is_deleted IS NULL\n"
                     + "  AND t.is_tentatively_deleted IS NULL\n"
                     + "  AND (t.is_hidden IS NULL OR t.is_hidden = 0)\n"
                     + "  AND lt.location_id = l._id\n"
                     + "  ORDER BY lt.location_tag_sort_num\n"
                     + "  LIMIT 1\n"
                     + ") first_tag_name\n"
                     + "FROM locations l\n"
                     + "WHERE l._id IN $LOCATION_IDS$\n";

        SQLParams params = new SQLParams();
        params.put("LOCATION_IDS", locationIds);
        return query(getReadableDatabase(), query, params);
    }

    public enum LocationsQuerySortType {
        SORT_ALPHABETICAL,
        SORT_REVERSE_ALPHABETICAL,
        SORT_NEWEST,
        SORT_OLDEST,
        SORT_CLOSEST
    }

    public void fetchLocation(Long locationId, DatabaseQueryListener listener) {
        new FetchLocationTask(locationId, listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public Cursor fetchPhotosForLocation(Long locationId) {
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT * FROM photos\n");
        sb.append("WHERE location_id = $LOCATION_ID$\n");
        sb.append("AND (is_deleted IS NULL OR is_deleted = 0)\n");
        sb.append("AND (is_tentatively_deleted IS NULL OR is_tentatively_deleted = 0)\n");

        SQLParams sqlParams = new SQLParams();
        sqlParams.put("LOCATION_ID", locationId);

        return query(getReadableDatabase(), sb.toString(), sqlParams);
    }

    public void fetchPhotosForLocation(Long locationId, DatabaseQueryListener listener) {
        new FetchPhotosForLocationTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, locationId);
    }

    // This method is made synchronous because it's only called from a background thread.
    public Cursor fetchPhotosForLocations(List<Long> locationIds) {
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT * FROM photos\n");
        sb.append("WHERE location_id IN $LOCATION_IDS$\n");
        sb.append("AND (is_deleted IS NULL OR is_deleted = 0)\n");
        sb.append("AND (is_tentatively_deleted IS NULL OR is_tentatively_deleted = 0)\n");

        SQLParams sqlParams = new SQLParams();
        sqlParams.put("LOCATION_IDS", locationIds);

        return query(getReadableDatabase(), sb.toString(), sqlParams);
    }

    public void fetchLinkedLocations(Long locationId, DatabaseQueryListener listener) {
        new FetchLinkedLocationsTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, locationId);
    }

    public Cursor fetchLinkedLocations(Long locationId) {
        SQLParams params = new SQLParams();
        params.put("LOCATION_ID", locationId);

        String query = "SELECT l.* FROM locations l\n"
                     + "INNER JOIN location_tag_pairs lt ON lt.location_id = l._id\n"
                     + "WHERE lt.tag_id = (\n"
                     + "  SELECT t2._id FROM tags t2\n"
                     + "  INNER JOIN location_tag_pairs lt2 ON t2._id = lt2.tag_id\n"
                     + "  WHERE lt2.location_id = $LOCATION_ID$\n"
                     + "  AND t2.is_hidden = 1\n"
                     + ")\n"
                     + "AND l._id != $LOCATION_ID$\n"
                     + "ORDER BY lt.location_tag_sort_num\n";

        return query(getWritableDatabase(), query, params);
    }

    public void fetchLinkableLocations(Long locationId, String filter, int start, int count, DatabaseQueryListener listener) {
        new FetchLinkableLocationsTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, locationId, filter, start, count);
    }

    public Cursor fetchLinkableLocations(Long locationId, String filter, int start, int count) {
        SQLParams params = new SQLParams();
        params.put("LOCATION_ID", locationId);
        params.put("FILTERED_QUERY", filter);

        // Select all linked locations for the given location ordered by location_tag_sort_num,
        // and then select all unlinked locations sorted by created date descending.
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT * FROM (\n");
        sb.append("  SELECT * FROM (\n");
        sb.append("    SELECT l.*, 1 is_linked, (0 - lt.location_tag_sort_num) rnum FROM locations l\n");
        sb.append("    INNER JOIN location_tag_pairs lt ON lt.location_id = l._id\n");
        sb.append("    WHERE lt.tag_id = (\n");
        sb.append("      SELECT t2._id FROM tags t2\n");
        sb.append("      INNER JOIN location_tag_pairs lt2 ON t2._id = lt2.tag_id\n");
        sb.append("      WHERE lt2.location_id = $LOCATION_ID$\n");
        sb.append("      AND t2.is_hidden = 1\n");
        sb.append("    )\n");
        sb.append("    AND l._id != $LOCATION_ID$\n");
        if(!TextUtils.isEmpty(filter)) {
            sb.append("    AND (\n");
            sb.append("      (UPPER(l.title) LIKE '%' || UPPER($FILTERED_QUERY$) || '%')\n");
            sb.append("      OR (l.address_str IS NOT NULL AND (UPPER(l.address_str) LIKE '%' || UPPER($FILTERED_QUERY$) || '%'))\n");
            sb.append("      OR (l.notes IS NOT NULL AND (UPPER(l.notes) LIKE '%' || UPPER($FILTERED_QUERY$) || '%'))\n");
            sb.append("    )\n");
        }
        sb.append("    ORDER BY lt.location_tag_sort_num\n");
        sb.append("  )");

        sb.append("  UNION\n");

        sb.append("  SELECT l.*, 0 is_linked, l.created_date rnum FROM locations l\n");
        sb.append("  WHERE NOT EXISTS (\n");
        sb.append("    SELECT lt.* FROM location_tag_pairs lt\n");
        sb.append("    INNER JOIN tags t ON lt.tag_id = t._id\n");
        sb.append("    WHERE lt.location_id = l._id\n");
        sb.append("    AND (t.is_hidden = 1)\n");
        sb.append("  )\n");
        sb.append("  AND l._id != $LOCATION_ID$\n");
        if(!TextUtils.isEmpty(filter)) {
            sb.append("  AND (\n");
            sb.append("    (UPPER(l.title) LIKE '%' || UPPER($FILTERED_QUERY$) || '%')\n");
            sb.append("    OR (l.address_str IS NOT NULL AND (UPPER(l.address_str) LIKE '%' || UPPER($FILTERED_QUERY$) || '%'))\n");
            sb.append("    OR (l.notes IS NOT NULL AND (UPPER(l.notes) LIKE '%' || UPPER($FILTERED_QUERY$) || '%'))\n");
            sb.append("  )\n");
        }
        sb.append("  ORDER BY l.created_date DESC\n");
        sb.append(")\n");
        sb.append("ORDER BY is_linked DESC, rnum DESC\n");

        if(count > 0) {
            sb.append("LIMIT $LIMIT$ OFFSET $OFFSET$\n");
            params.put("LIMIT", count);
            params.put("OFFSET", start);
        }

        return query(getReadableDatabase(), sb.toString(), params);
    }

    public void saveLinkedLocations(List<Long> linkedLocationIds, DatabaseQueryListener listener) {
        new SaveLinkedLocationsTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, linkedLocationIds);
    }

    public void saveLinkedLocations(List<Long> linkedLocationIds) {
        SQLiteDatabase db = getWritableDatabase();

        // Find an existing hidden tag for one of these locations.
        String query = "SELECT l._id location_id, t._id tag_id FROM tags t\n"
                     + "INNER JOIN location_tag_pairs lt ON t._id = lt.tag_id\n"
                     + "INNER JOIN locations l ON lt.location_id = l._id\n"
                     + "WHERE l._id IN $LOCATION_IDS$\n"
                     + "AND t.is_hidden = 1\n"
                     + "AND (t.is_deleted IS NULL OR t.is_deleted = 0)\n"
                     + "LIMIT 1\n";
        SQLParams sqlParams = new SQLParams();
        sqlParams.put("LOCATION_IDS", linkedLocationIds);
        Cursor hiddenTagCursor = query(db, query, sqlParams);
        if(hiddenTagCursor.getCount() > 0) {
            // There's an existing hidden tag for one of the locations.
            Long tagId = MiscUtils.getLong(hiddenTagCursor, "tag_id");
            saveLinkedLocations(db, tagId, linkedLocationIds);
        }
        else {
            try {
                db.beginTransaction();

                // There's no existing hidden tag yet, so:
                //  1) Create the tag record.
                //  2) Create all the location_tag_pairs records.
                //  3) Touch all the linked locations for the next sync.
                TagModel tag = new TagModel();
                tag.hidden = true;

                ContentValues values = ModelUtils.createContentValuesForTagUpdate(tag);
                values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
                values.put(CREATED_DATE_COLUMN, values.getAsLong(LAST_MODIFIED_DATE_COLUMN));
                db.insert(TAG_TABLE, null, values);
                Long tagId = getLastInsertedTagId();

                // See (2) above.
                values = new ContentValues();
                values.put(FOREIGN_TAG_ID_COLUMN, tagId);
                for(int i = 0; i < linkedLocationIds.size(); ++i) {
                    Long locationId = linkedLocationIds.get(i);
                    values.put(FOREIGN_LOCATION_ID_COLUMN, locationId);
                    values.put(LOCATION_TAG_SORT_NUM, i);
                    db.insertWithOnConflict(LOCATION_TAG_PAIR_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                }

                // See (3) above.
                values = new ContentValues();
                values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
                update(db, LOCATION_TABLE, values, "_id IN $LOCATION_IDS$", sqlParams);

                db.setTransactionSuccessful();
            }
            finally {
                db.endTransaction();
            }
        }

        hiddenTagCursor.close();
    }

    public void saveLinkedLocations(SQLiteDatabase db, Long tagId, List<Long> linkedLocationIds) {
        try {
            db.beginTransaction();

            if(linkedLocationIds.size() == 1) {
                // There's only a single location in the list, so the user unselected
                // all the existing linked locations, so:
                //  1) Fetch all the (now) unlinked locations.
                //  2) Touch the unlinked locations for the next sync.
                //  3) Delete the location_tag_pairs records.
                //  4) Delete the existing tag.
                SQLParams sqlParams = new SQLParams();
                sqlParams.put("TAG_ID", tagId);
                String query = "SELECT _id FROM locations l\n"
                             + "INNER JOIN location_tag_pairs lt ON lt.location_id = l._id\n"
                             + "WHERE lt.tag_id = $TAG_ID$\n";
                Cursor cursor = query(db, query, sqlParams);
                Long[] unlinkedLocationIds = new Long[cursor.getCount()];
                int idx = 0;
                while(!cursor.isAfterLast()) {
                    unlinkedLocationIds[idx++] = MiscUtils.getLong(cursor, ID_COLUMN);
                    cursor.moveToNext();
                }
                cursor.close();

                // See (2) above.
                sqlParams = new SQLParams();
                sqlParams.put("LOCATION_IDS", unlinkedLocationIds);
                ContentValues values = new ContentValues();
                values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
                update(db, LOCATION_TABLE, values, "_id IN $LOCATION_IDS$", sqlParams);

                // See (3) above.
                sqlParams = new SQLParams();
                sqlParams.put("TAG_ID", tagId);
                delete(db, LOCATION_TAG_PAIR_TABLE, "tag_id = $TAG_ID$", sqlParams);

                // See (4) above.
                deleteTagRecord(db, tagId);
            }
            else {
                // An existing hidden tag exists and the user selected at least 1 location
                // to link, so:
                //  1) Delete locations that are tied to this tag that are no longer linked.
                //  2) Create location_tag_pairs records for newly linked locations.
                //  3) Touch all the linked locations for the next sync.
                SQLParams sqlParams = new SQLParams();
                sqlParams.put("TAG_ID", tagId);
                sqlParams.put("LOCATION_IDS", linkedLocationIds);
                String whereClause = "tag_id = $TAG_ID$ AND location_id NOT IN $LOCATION_IDS$";
                delete(db, LOCATION_TAG_PAIR_TABLE, whereClause, sqlParams);

                // See (2) above.
                ContentValues values = new ContentValues();
                values.put(FOREIGN_TAG_ID_COLUMN, tagId);
                for(int i = 0; i < linkedLocationIds.size(); ++i) {
                    Long locationId = linkedLocationIds.get(i);
                    values.put(FOREIGN_LOCATION_ID_COLUMN, locationId);
                    values.put(LOCATION_TAG_SORT_NUM, i);
                    db.insertWithOnConflict(LOCATION_TAG_PAIR_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                }

                // See (3) above.
                values = new ContentValues();
                values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
                update(db, LOCATION_TABLE, values, "_id IN $LOCATION_IDS$", sqlParams);
            }

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void saveLocation(LocationModel locationModel, DatabaseQueryListener listener) {
        new SaveLocationTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, locationModel);
    }

    public Long saveLocation(LocationModel locationModel) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = locationModel.createContentValues();

        // If we're currently syncing, set the UPDATE_AFTER_SYNC_COLUMN flag on the location.
        Intent lastBroadcast = GlobalBroadcastManager.getInstance(context).getLastBroadcast(DriveSyncAdapter.DRIVE_SYNC_ADAPTER_BROADCAST);
        if(lastBroadcast != null) {
            int requestCode = lastBroadcast.getIntExtra("requestCode", -1);
            if(requestCode == RequestCodes.SYNC_STARTED) {
                values.put(UPDATE_AFTER_SYNC_COLUMN, true);
            }
        }

        try {
            db.beginTransaction();

            Long locationId = locationModel.getLocalId();
            if(locationId != null) {
                // Update an existing location.
                db.update(LOCATION_TABLE, values, getIdWhereClause(), new String[]{locationId.toString()});
            }
            else {
                // Create a new location.
                if(!values.containsKey(CREATED_DATE_COLUMN)) {
                    values.put(CREATED_DATE_COLUMN, values.getAsLong(LAST_MODIFIED_DATE_COLUMN));
                }
                locationId = db.insertOrThrow(LOCATION_TABLE, null, values);
                locationModel.setLocalId(locationId);
            }

            updateTagsForLocation(db, locationModel);

            db.setTransactionSuccessful();

            return locationId;
        }
        finally {
            db.endTransaction();
        }
    }

    // This function determines if the passed in location already exists.
    // 5 decimal places is 1.1m of accuracy, so under that we can assume it's
    // a duplicate location: http://gis.stackexchange.com/a/8674
    //
    // Note that we're not using the Haversine formula to determine distance,
    // but that's okay because it doesn't need to be too accurate.
    public boolean locationAlreadyExists(LatLng latLng) {
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT * FROM locations l\n");
        sb.append("WHERE abs(l.latitude - $LATITUDE$) < 0.00001\n");
        sb.append("AND abs(l.longitude - $LONGITUDE$) < 0.00001\n");

        SQLParams params = new SQLParams();
        params.put("LATITUDE", latLng.latitude);
        params.put("LONGITUDE", latLng.longitude);

        Cursor cursor = query(getReadableDatabase(), sb.toString(), params);
        boolean locationAlreadyExists = cursor.getCount() > 0;
        cursor.close();
        return locationAlreadyExists;
    }

    public void deleteLocation(Long locationId, DatabaseQueryListener listener) {
        new DeleteLocationTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, locationId);
    }

    public void deleteLocation(Long locationId) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = getDeletedContentValues();
        values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());

        String[] args = new String[] { locationId.toString() };
        String whereClause = getIdWhereClause();
        String whereClauseSuffix = " AND drive_file_id IS NULL";
        String whereClauseSyncedSuffix = " AND drive_file_id IS NOT NULL";

        try {
            db.beginTransaction();

            List<Long> hiddenTagIds = fetchHiddenTagIds(locationId);

            // Delete the location if it hasn't been synced yet.
            int numRowsAffected = db.delete(LOCATION_TABLE, whereClause + whereClauseSuffix, args);
            if(numRowsAffected == 0) {
                // Mark the location as deleted if it's already been synced.
                db.update(LOCATION_TABLE, values, whereClause + whereClauseSyncedSuffix, args);
            }

            // This line is necessary to clear the value for LAST_MODIFIED_DATE_COLUMN that was set above.
            values = getDeletedContentValues();

            // Fetch the photos' paths to see if they only exist locally.
            Cursor cursor = db.rawQuery("SELECT path FROM photos WHERE " + getIdWhereClause(FOREIGN_LOCATION_ID_COLUMN) + whereClauseSuffix, args);
            if(cursor.getCount() > 0) {
                cursor.moveToFirst();
                while(!cursor.isAfterLast()) {
                    // Delete the file.
                    String path = MiscUtils.getString(cursor, PHOTO_PATH_COLUMN);
                    deleteFile(path);

                    cursor.moveToNext();
                }
            }
            cursor.close();

            db.delete(PHOTO_TABLE, getIdWhereClause(FOREIGN_LOCATION_ID_COLUMN) + whereClauseSuffix, args);
            db.update(PHOTO_TABLE, values, getIdWhereClause(FOREIGN_LOCATION_ID_COLUMN) + whereClauseSyncedSuffix, args);

            // Delete all tag references from the location.
            db.delete(LOCATION_TAG_PAIR_TABLE, getIdWhereClause(FOREIGN_LOCATION_ID_COLUMN), args);

            // Delete any orphaned hidden tags.
            deleteHiddenTagsIfNecessary(hiddenTagIds, false);

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void tentativelyDeleteLocation(Long locationId, DatabaseQueryListener listener) {
        new TentativelyDeleteLocationTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, locationId);
    }

    public void undeleteLocation(Long locationId, DatabaseQueryListener listener) {
        new UndeleteLocationTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, locationId);
    }

    public void deleteLocations(List<Long> locationIds, DatabaseQueryListener listener) {
        new DeleteLocationsTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, locationIds);
    }

    public void deleteLocations(List<Long> locationIds) {
        SQLiteDatabase db = getWritableDatabase();

        String whereClause = "_id IN $LOCATION_IDS$ AND drive_file_id IS NULL";
        String whereClauseSynced = "_id IN $LOCATION_IDS$ AND drive_file_id IS NOT NULL";

        SQLParams sqlParams = new SQLParams();
        sqlParams.put("LOCATION_IDS", locationIds);

        try {
            db.beginTransaction();

            List<Long> hiddenTagIds = fetchHiddenTagIds(locationIds.toArray(new Long[0]));

            // Delete the locations that haven't been synced yet.
            delete(db, LOCATION_TABLE, whereClause, sqlParams);

            // Mark locations that have been synced as deleted.
            ContentValues values = getDeletedContentValues();
            values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
            update(db, LOCATION_TABLE, values, whereClauseSynced, sqlParams);

            // Fetch the photos' paths to see if they only exist locally.
            whereClause = "location_id IN $LOCATION_IDS$ AND drive_file_id IS NULL";
            Cursor cursor = query(db, "SELECT path FROM photos WHERE " + whereClause, sqlParams, false);
            if(cursor.getCount() > 0) {
                cursor.moveToFirst();
                while(!cursor.isAfterLast()) {
                    // Delete the file.
                    String path = MiscUtils.getString(cursor, PHOTO_PATH_COLUMN);
                    deleteFile(path);

                    cursor.moveToNext();
                }
            }
            cursor.close();

            // Delete the photos that haven't been synced yet.
            delete(db, PHOTO_TABLE, whereClause, sqlParams);

            // Mark the photos that have been synced as deleted.
            values = getDeletedContentValues();
            whereClauseSynced = "location_id IN $LOCATION_IDS$ AND drive_file_id IS NOT NULL";
            update(db, PHOTO_TABLE, values, whereClauseSynced, sqlParams);

            // Delete all tags from the location.
            whereClause = "location_id IN $LOCATION_IDS$";
            delete(db, LOCATION_TAG_PAIR_TABLE, whereClause, sqlParams);

            // Delete any orphaned hidden tags.
            deleteHiddenTagsIfNecessary(hiddenTagIds, false);

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void tentativelyDeleteLocations(List<Long> locationIds, DatabaseQueryListener listener) {
        new TentativelyDeleteLocationsTask(locationIds, listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void undeleteLocations(List<Long> locationIds, DatabaseQueryListener listener) {
        new UndeleteLocationsTask(locationIds, listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // Permanent delete functions are only called from the sync algorithm.
    public void permanentlyDeleteTagIcon(TagModel tag) {
        deleteTagIconFile(tag);

        ContentValues values = new ContentValues();
        values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
        values.putNull(ICON_PATH_COLUMN);
        values.putNull(ICON_DRIVE_FILE_ID_COLUMN);
        getWritableDatabase().update(TAG_TABLE, values, getIdWhereClause(), new String[]{tag.getLocalId().toString()});
    }

    public void deleteTagIconFile(TagModel tag) {
        deleteFile(tag.iconPath);
    }

    public void createTagIcon(TagModel tag) {
        ContentValues values = new ContentValues();
        values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
        values.put(ICON_PATH_COLUMN, tag.getIconPath());
        values.put(ICON_DRIVE_FILE_ID_COLUMN, tag.getRemoteIconId());
        getWritableDatabase().update(TAG_TABLE, values, getIdWhereClause(), new String[]{ tag.getLocalId().toString() });
    }

    public void updateTagIcon(TagModel tag) {
        ContentValues values = new ContentValues();
        values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
        values.put(ICON_PATH_COLUMN, tag.getIconPath());
        values.put(ICON_DRIVE_FILE_ID_COLUMN, tag.getRemoteIconId());
        getWritableDatabase().update(TAG_TABLE, values, getIdWhereClause(), new String[]{ tag.getLocalId().toString() });
    }

    public void updateSyncPropertiesForTag(Long tagId, Long lastModifiedDate, String remoteId, String remoteIconId) {
        ContentValues values = new ContentValues();
        if(lastModifiedDate != null) {
            values.put(LAST_MODIFIED_DATE_COLUMN, lastModifiedDate);
        }
        if(remoteId != null) {
            values.put(DRIVE_FILE_ID_COLUMN, remoteId);
        }
        if(remoteIconId != null) {
            values.put(ICON_DRIVE_FILE_ID_COLUMN, remoteIconId);
        }
        getWritableDatabase().update(TAG_TABLE, values, getIdWhereClause(), new String[]{tagId.toString()});
    }

    public void updateSyncPropertiesForPhoto(Long photoId, Long lastModifiedDate, String remoteId) {
        SQLiteDatabase db = getWritableDatabase();

        try {
            db.beginTransaction();

            // Fetch the location id from the photo record.
            SQLParams params = new SQLParams();
            params.put("ID", photoId);
            Cursor cursor = query(db, "SELECT location_id FROM photos WHERE _id = $ID$", params, true);
            Long locationId = cursor.getLong(cursor.getColumnIndex(FOREIGN_LOCATION_ID_COLUMN));
            cursor.close();

            // Update the location record with the last modified date.
            LocationModel locationModel = new LocationModel(locationId);
            ContentValues values = locationModel.createContentValues();
            values.put(LAST_MODIFIED_DATE_COLUMN, lastModifiedDate);
            db.update(LOCATION_TABLE, values, getIdWhereClause(), new String[]{ locationId.toString() });

            // Update the photo record with the remote id, if necessary.
            if(remoteId != null) {
                values = new ContentValues();
                values.put(DRIVE_FILE_ID_COLUMN, remoteId);
                db.update(PHOTO_TABLE, values, getIdWhereClause(), new String[] { photoId.toString() });
            }

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void updateSyncPropertiesForLocation(Long locationId, Long lastModifiedDate, String remoteId) {
        ContentValues values = new ContentValues();
        values.put(LAST_MODIFIED_DATE_COLUMN, lastModifiedDate);
        if(remoteId != null) {
            // We only need to set the remote id after the location is first created.
            values.put(DRIVE_FILE_ID_COLUMN, remoteId);
        }
        getWritableDatabase().update(LOCATION_TABLE, values, getIdWhereClause(), new String[]{locationId.toString()});
    }

    public void fetchTag(Long tagId, DatabaseQueryListener listener) {
        new FetchTagTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, tagId);
    }

    public void fetchTags(TagsQuerySortType sortType, boolean includeUntaggedCount, DatabaseQueryListener listener) {
        new FetchTagsTask(sortType, includeUntaggedCount, listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public Cursor fetchTagsForSync(boolean alreadySynced) {
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT * FROM tags\n");
        sb.append("WHERE (is_hidden IS NULL OR is_hidden = 0)\n");
        if(alreadySynced) {
            sb.append("AND drive_file_id IS NOT NULL\n");
        }
        else {
            sb.append("AND drive_file_id IS NULL\n");
        }
        return query(getReadableDatabase(), sb.toString(), null, true);
    }

    public Cursor fetchPhotosForSync(boolean alreadySynced) {
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT p.*, l.title, l.address_str, l.is_address_derived FROM photos p\n");
        sb.append("LEFT OUTER JOIN locations l ON p.location_id = l._id\n");
        if(alreadySynced) {
            sb.append("WHERE p.drive_file_id IS NOT NULL\n");
        }
        else {
            sb.append("WHERE p.drive_file_id IS NULL\n");
        }
        return query(getReadableDatabase(), sb.toString(), null, true);
    }

    public Cursor fetchLocationsForSync(boolean alreadySynced) {
        // Note: We don't need to fetch photo drive ids here because each location file on Drive
        // stores extra information attached to the photo, like notes and sort_num. We will
        // fetch this data separately later in the algorithm.
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT *, GROUP_CONCAT(tag_drive_id, ',') tag_drive_ids FROM (\n");
        sb.append("  SELECT l.*, t.drive_file_id tag_drive_id FROM locations l\n");
        sb.append("  LEFT OUTER JOIN location_tag_pairs lt ON l._id = lt.location_id\n");
        sb.append("  LEFT OUTER JOIN tags t ON lt.tag_id = t._id\n");
        if(alreadySynced) {
            sb.append("  WHERE l.drive_file_id IS NOT NULL\n");
        }
        else {
            sb.append("  WHERE l.drive_file_id IS NULL\n");
        }
        sb.append("  ORDER BY lt.location_tag_sort_num\n");
        sb.append(")\n");
        sb.append("GROUP BY _id");
        return query(getReadableDatabase(), sb.toString(), null, true);
    }

    public Cursor fetchHiddenTagsForSync(boolean alreadySynced) {
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT *, GROUP_CONCAT(location_drive_id, ',') location_drive_ids FROM (\n");
        sb.append("  SELECT t.*, l.drive_file_id location_drive_id FROM tags t\n");
        sb.append("  LEFT OUTER JOIN location_tag_pairs lt ON t._id = lt.tag_id\n");
        sb.append("  LEFT OUTER JOIN locations l ON lt.location_id = l._id\n");
        sb.append("  WHERE is_hidden = 1\n");
        if(alreadySynced) {
            sb.append("AND t.drive_file_id IS NOT NULL\n");
        }
        else {
            sb.append("AND t.drive_file_id IS NULL\n");
        }
        sb.append("  ORDER BY lt.location_tag_sort_num\n");
        sb.append(")\n");
        sb.append("GROUP BY _id");
        return query(getReadableDatabase(), sb.toString(), null, true);
    }

    public void savePhotoInfos(List<PhotoInfo> photoInfos, Long locationId, DatabaseQueryListener listener) {
        new SavePhotoInfosTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, photoInfos, locationId);
    }

    public List<Long> savePhotoInfos(List<PhotoInfo> photoInfos, Long locationId) {
        SQLiteDatabase db = getWritableDatabase();
        List<Long> photoIds = new ArrayList<>();
        try {
            db.beginTransaction();

            // Write the PhotoInfo instances to the DB.
            for(PhotoInfo photoInfo : photoInfos) {
                ContentValues values = ModelUtils.createContentValuesForPhoto(photoInfo, locationId);
                if(photoInfo.id != null) {
                    db.update(PHOTO_TABLE, values, getIdWhereClause(), new String[] { photoInfo.id.toString() });
                    photoIds.add(photoInfo.id);
                }
                else {
                    photoIds.add(db.insertOrThrow(PHOTO_TABLE, null, values));
                }
            }

            // Mark the location as updated.
            LocationModel locationModel = new LocationModel(locationId);
            ContentValues values = locationModel.createContentValues();
            db.update(LOCATION_TABLE, values, getIdWhereClause(), new String[]{locationId.toString()});

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }

        return photoIds;
    }

    public void deletePhotoInfo(PhotoInfo photoInfoToDelete, PhotoInfoList photoInfoList, Long locationId, DatabaseQueryListener listener) {
        new DeletePhotoInfoTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, photoInfoToDelete, photoInfoList, locationId);
    }

    public void deletePhotoInfo(PhotoInfo photoInfoToDelete, PhotoInfoList photoInfoList, Long locationId) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();

            String[] args = new String[] { photoInfoToDelete.id.toString() };

            // Fetch the photo's path to see if it only exists locally.
            String unsyncedWhereClause = "_id = ? AND drive_file_id IS NULL";
            Cursor cursor = db.rawQuery("SELECT path FROM photos WHERE " + unsyncedWhereClause, args);
            if(cursor.getCount() > 0) {
                cursor.moveToFirst();
                String path = MiscUtils.getString(cursor, PHOTO_PATH_COLUMN);

                // Delete the file and delete the record.
                deleteFile(path);
                db.delete(PHOTO_TABLE, unsyncedWhereClause, args);
            }
            else {
                // The photo is synced, so mark it as deleted in the DB.
                db.update(PHOTO_TABLE, getDeletedContentValues(), "_id = ? AND drive_file_id IS NOT NULL", args);
            }
            cursor.close();

            // Update the sortNums on the rest of the photos for this location.
            ContentValues values = new ContentValues();
            for(PhotoInfo photoInfo : photoInfoList.getList()) {
                values.put(PHOTO_SORT_NUM_COLUMN, photoInfo.sortNum);
                db.update(PHOTO_TABLE, values, getIdWhereClause(), new String[] { photoInfo.id.toString() });
            }

            // Mark the location as updated.
            LocationModel locationModel = new LocationModel(locationId);
            values = locationModel.createContentValues();
            db.update(LOCATION_TABLE, values, getIdWhereClause(), new String[] { locationId.toString() });

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void createPhoto(PhotoInfo photo) throws IOException {
        // Create the row in the photos table.
        ContentValues values = ModelUtils.createContentValuesForPhoto(photo, null);
        getWritableDatabase().insertOrThrow(PHOTO_TABLE, null, values);
    }

    public void updatePhoto(PhotoInfo photo) throws IOException {
        // Create the row in the photos table.
        ContentValues values = ModelUtils.createContentValuesForPhoto(photo, null);
        getWritableDatabase().update(PHOTO_TABLE, values, getIdWhereClause(), new String[]{photo.getLocalId().toString()});
    }

    public void updatePhotoLocation(String srcPath, String destPath) {
        ContentValues values = new ContentValues();
        values.put(PHOTO_PATH_COLUMN, destPath);
        String whereClause = "path = ?";
        getWritableDatabase().update(PHOTO_TABLE, values, whereClause, new String[] { srcPath });
    }

    // Permanent delete functions are only called from the sync algorithm.
    public void permanentlyDeletePhoto(PhotoInfo photoInfo, boolean deletePhotoRecord) {
        deleteFile(photoInfo.path);

        if(deletePhotoRecord) {
            SQLiteDatabase db = getWritableDatabase();

            try {
                db.beginTransaction();

                // See REFERENTIAL INTEGRITY comment for explanation.
                ContentValues values = new ContentValues();
                values.put(UPDATE_AFTER_SYNC_COLUMN, true);
                String whereClause = "_id = (SELECT location_id FROM photos WHERE _id = ?)";
                db.update(LOCATION_TABLE, values, whereClause, new String[]{photoInfo.getLocalId().toString()});

                deletePhotoRecord(db, photoInfo.getLocalId());

                db.setTransactionSuccessful();
            }
            finally {
                db.endTransaction();
            }
        }
    }

    public void deletePhotoRecord(SQLiteDatabase db, Long photoId) {
        db.delete(PHOTO_TABLE, getIdWhereClause(), new String[]{photoId.toString()});
    }

    // Deletes the photo IFF it's under the ScoutLog folder.
    protected boolean deleteFile(String path) {
        if(path == null) {
            return false;
        }

        java.io.File file = new java.io.File(path);
        if(!file.exists()) {
            return false;
        }

        boolean isFileUnderAppFolder = false;
        java.io.File parentFile = file;
        while(parentFile != null) {
            if(parentFile.getName().equals(MiscUtils.APP_FOLDER)) {
                isFileUnderAppFolder = true;
                break;
            }
            parentFile = parentFile.getParentFile();
        }

        if(isFileUnderAppFolder) {
            try {
                if(file.getCanonicalFile().delete()) {
                    FileUtils.scanGalleryPhoto(context, file.getCanonicalFile());
                    return true;
                }
                else {
                    Crashlytics.getInstance().core.logException(new RuntimeException("File could not be deleted. Path: " + file.getAbsolutePath()));
                }
            }
            catch(IOException e) {
                Crashlytics.getInstance().core.logException(new RuntimeException("File could not be deleted. Path: " + file.getAbsolutePath(), e));
            }
        }

        return false;
    }

    public void nullifyPathForPhoto(Long photoId) {
        ContentValues values = new ContentValues();
        values.putNull(PHOTO_PATH_COLUMN);
        getWritableDatabase().update(PHOTO_TABLE, values, getIdWhereClause(), new String[]{photoId.toString()});
    }

    public void populatePhotoInfoForLocation(LocationModel location) {
        String query = "SELECT drive_file_id, notes, sort_num FROM photos WHERE location_id = $LOCATION_ID$";
        SQLParams sqlParams = new SQLParams();
        sqlParams.put("LOCATION_ID", location.getLocalId());

        List<PhotoInfo> photos = new ArrayList<>();
        Cursor cursor = query(getWritableDatabase(), query, sqlParams);
        while(!cursor.isAfterLast()) {
            PhotoInfo photo = ModelUtils.createPhotoFromCursor(cursor);
            photos.add(photo);
            cursor.moveToNext();
        }
        cursor.close();

        location.setPhotoInfoList(photos);
    }

    public void createLocation(LocationModel location) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();

            // Insert the location record.
            ContentValues values = ModelUtils.createContentValuesForLocationCreate(location);
            long locationId = getWritableDatabase().insert(LOCATION_TABLE, null, values);
            if(locationId < 0) {
                throw new RuntimeException("SQLiteDatabase.insert() returned -1; could not create location");
            }
            location.setLocalId(locationId);

            // Add the location-tag pair records.
            List<Long> tagIds = fetchTagIdsByRemoteIds(db, location.getRemoteTagIds());
            location.setTagIds(tagIds);
            updateTagsForLocation(db, location);

            // Update the photo records with the extra data (notes, sortNum, etc).
            updatePhotosForLocation(db, location);

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void updateLocation(LocationModel location) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();

            // Update the location record.
            ContentValues values = ModelUtils.createContentValuesForLocation(location);
            db.update(LOCATION_TABLE, values, getIdWhereClause(), new String[]{location.getLocalId().toString()});

            // Update the location-tag pair records.
            if(location.getRemoteTagIds() != null) {
                List<Long> tagIds = fetchTagIdsByRemoteIds(db, location.getRemoteTagIds());
                location.setTagIds(tagIds);
                updateTagsForLocation(db, location);
            }

            // Update the photo records with the extra data (notes, sortNum, etc).
            updatePhotosForLocation(db, location);

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    // Permanent delete functions are only called from the sync algorithm.
    public void permanentlyDeleteLocation(LocationModel location) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();

            // File has been deleted on Drive, so remove the row from the DB.
            String[] args = new String[] { location.getLocalId().toString() };
            db.delete(LOCATION_TABLE, getIdWhereClause(), args);

            // Delete all rows in location_tag_pairs that point to that location.
            db.delete(LOCATION_TAG_PAIR_TABLE, getIdWhereClause(FOREIGN_LOCATION_ID_COLUMN), args);

            // Delete the actual photos within the location (if applicable).
            SQLParams params = new SQLParams();
            params.put("LOCATION_ID", location.getLocalId());
            Cursor cursor = query(db, "SELECT * FROM photos WHERE location_id = $LOCATION_ID$", params, true);
            while(!cursor.isAfterLast()) {
                PhotoInfo photo = ModelUtils.createPhotoFromCursor(cursor);
                permanentlyDeletePhoto(photo, false);
                cursor.moveToNext();
            }
            cursor.close();

            // Mark the photo records within the location as deleted. In a normal usecase, the
            // photos for a location would already be deleted because syncPhotos() executes before
            // syncLocations(). This covers the case where a user directly deletes a location
            // remotely.
            //
            // We mark the photos as deleted so that on next sync, they get deleted remotely too.
            db.update(PHOTO_TABLE, getDeletedContentValues(), getIdWhereClause(FOREIGN_LOCATION_ID_COLUMN), args);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private List<Long> fetchTagIdsByRemoteIds(SQLiteDatabase db, List<String> remoteTagIds) {
        if(remoteTagIds == null || remoteTagIds.isEmpty()) {
            return new ArrayList<>();
        }

        String query = "SELECT _id, drive_file_id FROM tags\n"
                     + "WHERE (is_hidden IS NULL OR is_hidden = 0)\n"
                     + "AND drive_file_id IN $REMOTE_TAG_IDS$\n";
        SQLParams params = new SQLParams();
        params.put("REMOTE_TAG_IDS", remoteTagIds);
        Cursor cursor = query(db, query, params);

        Long[] tagIds = new Long[remoteTagIds.size()];
        while(!cursor.isAfterLast()) {
            int idx = remoteTagIds.indexOf(MiscUtils.getString(cursor, DRIVE_FILE_ID_COLUMN));
            tagIds[idx] = MiscUtils.getLong(cursor, ID_COLUMN);
            cursor.moveToNext();
        }
        cursor.close();

        List<Long> list = new ArrayList<>(tagIds.length);
        for(Long tagId : tagIds) {
            if(tagId != null) {
                list.add(tagId);
            }
        }
        return list;
    }

    private void updateTagsForLocation(SQLiteDatabase db, LocationModel location) {
        List<Long> tagIds = location.getTagIds();
        if(tagIds == null) {
            // If tagIds is not set on the LocationModel, then the caller doesn't want to modify them.
            return;
        }
        if(location.getLocalId() == null) {
            throw new NullPointerException("location.getLocalId() should not be null");
        }

        SQLParams sqlParams = new SQLParams();
        sqlParams.put("LOCATION_ID", location.getLocalId());

        // Fetch any hidden tag for this location.
        List<Long> tagIdsToNotDelete = new ArrayList<>(tagIds);
        String query = "SELECT t._id FROM tags t\n"
                     + "INNER JOIN location_tag_pairs lt ON t._id = lt.tag_id\n"
                     + "WHERE lt.location_id = $LOCATION_ID$\n"
                     + "AND t.is_hidden = 1\n";
        Cursor hiddenTagCursor = query(db, query, sqlParams);
        if(hiddenTagCursor.getCount() > 0) {
            Long hiddenTagId = hiddenTagCursor.getLong(hiddenTagCursor.getColumnIndex(ID_COLUMN));
            tagIdsToNotDelete.add(hiddenTagId);
        }
        hiddenTagCursor.close();

        // Delete non-hidden tags that were removed.
        String whereClause;
        if(!tagIdsToNotDelete.isEmpty()) {
            sqlParams.put("TAG_IDS", tagIdsToNotDelete);
            whereClause = "location_id = $LOCATION_ID$ AND tag_id NOT IN $TAG_IDS$";
        }
        else {
            // The location model doesn't have any non-hidden tags set, so delete any
            // that exist in the DB.
            whereClause = "location_id = $LOCATION_ID$";
        }
        delete(db, LOCATION_TAG_PAIR_TABLE, whereClause, sqlParams);

        // Insert all the tags for this location.
        ContentValues values = new ContentValues();
        values.put(FOREIGN_LOCATION_ID_COLUMN, location.getLocalId());
        for(int i = 0; i < tagIds.size(); ++i) {
            Long tagId = tagIds.get(i);
            values.put(FOREIGN_TAG_ID_COLUMN, tagId);
            values.put(LOCATION_TAG_SORT_NUM, i);
            db.insertWithOnConflict(LOCATION_TAG_PAIR_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private void updatePhotosForLocation(SQLiteDatabase db, LocationModel location) {
        List<PhotoInfo> photos = location.getPhotoInfoList();
        if(photos == null) {
            // If photos is not set on the LocationModel, then the caller doesn't want to modify them.
            return;
        }

        try {
            db.beginTransaction();

            for(PhotoInfo photo : photos) {
                ContentValues values = ModelUtils.createContentValuesForPhoto(photo, location.getLocalId());
                db.update(PHOTO_TABLE, values, getIdWhereClause(DRIVE_FILE_ID_COLUMN), new String[] { photo.getRemoteId() });
            }

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void setDistancesForLocations(LatLng latLng, DatabaseQueryListener listener) {
        new SetDistancesForLocationsTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, latLng);
    }

    public void createTag(TagModel remoteTag) {
        ContentValues values = ModelUtils.createContentValuesForTagCreate(remoteTag);
        getWritableDatabase().insert(TAG_TABLE, null, values);
    }

    public void updateTag(TagModel tag) {
        ContentValues values = ModelUtils.createContentValuesForTagUpdate(tag);
        getWritableDatabase().update(TAG_TABLE, values, getIdWhereClause(), new String[]{tag.getLocalId().toString()});
    }

    // Permanent delete functions are only called from the sync algorithm.
    public void permanentlyDeleteTag(Long tagId) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = new String[] { tagId.toString() };
        try {
            db.beginTransaction();

            // REFERENTIAL INTEGRITY
            //
            // This code ensures the corresponding location records are set to be touched after the sync
            // is finished. This is done in the case that the remote tag file is deleted by the user directly
            // rather than via ScoutLog. If that's done, then the referential integrity for the remote locations
            // would be compromised, because the location files would have references to tag files that no longer
            // exist. But because we are marking the locations as updated, the location files will be fixed
            // on the next sync.
            //
            // The UPDATE_AFTER_SYNC_COLUMN flag is used here instead of directly updating the last modified
            // date because if we touched them all now, they would overwrite all changes made in their
            // remote counterparts (this function is called before syncLocations()).
            ContentValues values = new ContentValues();
            values.put(UPDATE_AFTER_SYNC_COLUMN, true);
            String whereClause = "_id IN (SELECT location_id FROM location_tag_pairs WHERE tag_id IN (?))";
            db.update(LOCATION_TABLE, values, whereClause, args);

            // Delete all rows in location_tag_pairs that point to the tag. Even though this is also done
            // in deleteLocation(), it needs to be done here in case the user decides to go crazy
            // and delete a tag file directly from Google Drive.
            db.delete(LOCATION_TAG_PAIR_TABLE, getIdWhereClause(FOREIGN_TAG_ID_COLUMN), args);

            // Delete the tag record.
            db.delete(TAG_TABLE, getIdWhereClause(), args);

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public enum TagsQuerySortType {
        SORT_ALPHABETICAL,
        SORT_REVERSE_ALPHABETICAL,
        SORT_MOST_LOCATIONS_TAGGED,
        SORT_FEWEST_LOCATIONS_TAGGED
    }

    public Cursor fetchTagsByStr(String matchStr) {
        String query = "SELECT t.* FROM tags t\n"
                     + "WHERE (t.is_hidden IS NULL OR t.is_hidden = 0)\n"
                     + "AND UPPER(t.name) LIKE UPPER($TAG_STR$) || '%'";

        SQLParams sqlParams = new SQLParams();
        sqlParams.put("TAG_STR", matchStr);

        return query(getReadableDatabase(), query, sqlParams);
    }

    public void fetchTagsForLocation(Long locationId, DatabaseQueryListener listener) {
        new FetchTagsForLocationTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, locationId);
    }

    public void saveTag(TagModel tag, DatabaseQueryListener listener) {
        new SaveTagTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, tag);
    }

    public Long saveTag(TagModel tag) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = ModelUtils.createContentValuesForTagUpdate(tag);
        values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());

        if(tag.id != null) {
            db.update(TAG_TABLE, values, getIdWhereClause(), new String[] { tag.id.toString() });
            return null;
        }

        values.put(CREATED_DATE_COLUMN, values.getAsLong(LAST_MODIFIED_DATE_COLUMN));

        // Try to fetch an existing tag by this name, because tags have unique names.
        String query = "SELECT _id FROM tags WHERE name = ? AND (is_hidden IS NULL OR is_hidden = 0)";
        Cursor cursor = db.rawQuery(query, new String[] { tag.name });
        if(cursor.getCount() > 0) {
            cursor.moveToFirst();
            Long tagId = cursor.getLong(cursor.getColumnIndex(ID_COLUMN));
            cursor.close();
            return tagId;
        }
        cursor.close();

        // Create a new tag and return the id.
        values.put(CREATED_DATE_COLUMN, values.getAsLong(LAST_MODIFIED_DATE_COLUMN));
        db.insert(TAG_TABLE, null, values);
        return getLastInsertedTagId();
    }

    public boolean duplicateTagExistsLocally(String tagName) {
        if(tagName == null) {
            // [tagName] can be null for hidden tags, which do not need names.
            return false;
        }

        String query = "SELECT * FROM tags\n"
                + "WHERE drive_file_id IS NULL\n"
                + "AND (is_hidden IS NULL OR is_hidden = 0)\n"
                + "AND name = $TAG_NAME$\n";

        SQLParams sqlParams = new SQLParams();
        sqlParams.put("TAG_NAME", tagName);

        Cursor cursor = query(getWritableDatabase(), query, sqlParams, false);
        boolean duplicateTagExistsLocally = cursor.getCount() > 0;
        cursor.close();
        return duplicateTagExistsLocally;
    }

    public void mergeDuplicateLocalTag(TagModel remoteTag) {
        ContentValues values = ModelUtils.createContentValuesForTagCreate(remoteTag);
        getWritableDatabase().update(TAG_TABLE, values, getIdWhereClause(TAG_NAME_COLUMN), new String[]{remoteTag.name});
    }

    public boolean tagAlreadyExistsLocally(String remoteTagId) {
        return modelAlreadyExistsLocally(TAG_TABLE, remoteTagId);
    }

    public boolean photoAlreadyExistsLocally(String remoteTagId) {
        return modelAlreadyExistsLocally(PHOTO_TABLE, remoteTagId);
    }

    public boolean locationAlreadyExistsLocally(String remoteTagId) {
        return modelAlreadyExistsLocally(LOCATION_TABLE, remoteTagId);
    }

    public boolean hiddenTagAlreadyExistsLocally(String remoteTagId) {
        return modelAlreadyExistsLocally(TAG_TABLE, remoteTagId);
    }

    public boolean modelAlreadyExistsLocally(String tableName, String remoteId) {
        String query = "SELECT COUNT(*) model_count FROM " + tableName + "\n"
                     + "WHERE drive_file_id = $DRIVE_FILE_ID$\n";

        SQLParams sqlParams = new SQLParams();
        sqlParams.put("DRIVE_FILE_ID", remoteId);

        Cursor cursor = query(getReadableDatabase(), query, sqlParams);
        boolean modelAlreadyExistsLocally = cursor.getInt(cursor.getColumnIndex("model_count")) > 0;
        cursor.close();
        return modelAlreadyExistsLocally;
    }

    public void createHiddenTag(TagModel remoteTag) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = ModelUtils.createContentValuesForTagCreate(remoteTag);
        long tagId = db.insert(TAG_TABLE, null, values);

        List<Long> locationIds = fetchLocationIdsByRemoteIds(db, remoteTag.getRemoteLocationIds());
        saveLinkedLocations(db, tagId, locationIds);
    }

    public void updateHiddenTag(TagModel remoteTag) {
        SQLiteDatabase db = getWritableDatabase();

        try {
            db.beginTransaction();

            ContentValues values = ModelUtils.createContentValuesForTagUpdate(remoteTag);
            db.update(TAG_TABLE, values, getIdWhereClause(), new String[]{ remoteTag.getLocalId().toString() });

            List<Long> locationIds = fetchLocationIdsByRemoteIds(db, remoteTag.getRemoteLocationIds());
            saveLinkedLocations(db, remoteTag.getLocalId(), locationIds);

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    private List<Long> fetchLocationIdsByRemoteIds(SQLiteDatabase db, List<String> remoteLocationIds) {
        if(remoteLocationIds == null || remoteLocationIds.isEmpty()) {
            return new ArrayList<>();
        }

        String query = "SELECT _id, drive_file_id FROM locations\n"
                     + "WHERE drive_file_id IN $REMOTE_LOCATION_IDS$\n";
        SQLParams params = new SQLParams();
        params.put("REMOTE_LOCATION_IDS", remoteLocationIds);
        Cursor cursor = query(db, query, params);

        Long[] locationIds = new Long[remoteLocationIds.size()];
        while(!cursor.isAfterLast()) {
            int idx = remoteLocationIds.indexOf(MiscUtils.getString(cursor, DRIVE_FILE_ID_COLUMN));
            locationIds[idx] = MiscUtils.getLong(cursor, ID_COLUMN);
            cursor.moveToNext();
        }
        cursor.close();

        List<Long> list = new ArrayList<>(locationIds.length);
        for(Long locationId : locationIds) {
            if(locationId != null) {
                list.add(locationId);
            }
        }
        return list;
    }

    // Permanent delete functions are only called from the sync algorithm.
    public void permanentlyDeleteHiddenTag(Long tagId) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = new String[] { tagId.toString() };
        try {
            db.beginTransaction();

            // Delete all rows in location_tag_pairs that point to the tag.
            db.delete(LOCATION_TAG_PAIR_TABLE, getIdWhereClause(FOREIGN_TAG_ID_COLUMN), args);

            // Delete the tag record.
            db.delete(TAG_TABLE, getIdWhereClause(), args);

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void deleteTag(Long tagId, DatabaseQueryListener listener) {
        new DeleteTagTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, tagId);
    }

    public void deleteTag(Long tagId) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();

            removeTagFromLocations(db, tagId, fetchLocationIds(db, tagId));
            deleteTagRecord(db, tagId);

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void tentativelyDeleteTag(Long tagId, DatabaseQueryListener listener) {
        new TentativelyDeleteTagTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, tagId);
    }

    public void undeleteTag(Long tagId, DatabaseQueryListener listener) {
        new UndeleteTagTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, tagId);
    }

    public void addTagsToLocations(List<Long> locationIds, List<Long> tagIdsToAddBefore, List<Long> tagIdsToAddAfter, DatabaseQueryListener listener) {
        new BulkAddTagsTask(locationIds, tagIdsToAddBefore, tagIdsToAddAfter, listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void removeTagsFromLocations(List<Long> locationIds, List<Long> tagIdsToRemove, DatabaseQueryListener listener) {
        new BulkRemoveTagsTask(locationIds, tagIdsToRemove, listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void movePhotoFiles(String oldFolder, String newFolder) {
        new MovePhotoFilesTask(oldFolder, newFolder).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public int fetchLocationsCount() {
        Cursor cursor = getLocationsCount();
        int locationsCount = cursor.getInt(cursor.getColumnIndex(DatabaseHelper.TRANSIENT_COUNT_COLUMN));
        cursor.close();
        return locationsCount;
    }

    public void fetchLocationsCount(DatabaseQueryListener listener) {
        new FetchLocationsCountTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private Cursor getLocationsCount() {
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT COUNT(*) count FROM locations\n");
        sb.append("WHERE (is_tentatively_deleted IS NULL OR is_tentatively_deleted = 0)\n");
        sb.append("AND (is_deleted IS NULL OR is_deleted = 0)\n");
        return query(getReadableDatabase(), sb.toString(), null);
    }

    // Determines if the user has enough places set with photos.
    public void hasPhotosForPlaces(DatabaseQueryListener listener) {
        new HasPhotosForPlacesTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void addTagToLocation(Long locationId, Long tagId) {
        SQLiteDatabase db = getWritableDatabase();

        try {
            db.beginTransaction();

            StringBuffer sb = new StringBuffer();
            sb.append("SELECT lt.location_tag_sort_num\n");
            sb.append("FROM location_tag_pairs lt\n");
            sb.append("WHERE lt.location_id = $LOCATION_ID$\n");
            sb.append("AND lt.tag_id = $TAG_ID$\n");
            sb.append("ORDER BY lt.location_tag_sort_num DESC\n");
            sb.append("LIMIT 1\n");

            SQLParams params = new SQLParams();
            params.put("LOCATION_ID", locationId);
            params.put("TAG_ID", tagId);

            Cursor cursor = query(db, sb.toString(), params);

            int newSortNum = 0;
            if(cursor.getCount() > 0) {
                newSortNum = cursor.getInt(cursor.getColumnIndex(LOCATION_TAG_SORT_NUM)) + 1;
            }
            cursor.close();

            ContentValues values = new ContentValues();
            values.put(FOREIGN_LOCATION_ID_COLUMN, locationId);
            values.put(FOREIGN_TAG_ID_COLUMN, tagId);
            values.put(LOCATION_TAG_SORT_NUM, newSortNum);
            db.insertWithOnConflict(LOCATION_TAG_PAIR_TABLE, null, values, SQLiteDatabase.CONFLICT_ABORT);

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    // See documentation for UPDATE_AFTER_SYNC_COLUMN.
    public void updateRecordsAfterSync() {
        ContentValues values = new ContentValues();
        values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
        values.putNull(UPDATE_AFTER_SYNC_COLUMN);
        String whereClause = "update_after_sync_column = 1";

        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();
            db.update(LOCATION_TABLE, values, whereClause, null);
            db.update(TAG_TABLE, values, whereClause, null);
            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    // This method is called when a user attaches a new account for syncing. Any ties to an old account
    // are severed, so this method nullifies the DRIVE_FILE_ID_COLUMN for every record in every table.
    public void nullifyAllDriveIds() {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.putNull(DRIVE_FILE_ID_COLUMN);

        db.update(LOCATION_TABLE, values, null, null);
        db.update(PHOTO_TABLE, values, null, null);
        db.update(TAG_TABLE, values, null, null);
    }

    private ContentValues getDeletedContentValues() {
        ContentValues values = new ContentValues();
        values.put(IS_DELETED_COLUMN, true);
        values.putNull(IS_TENTATIVELY_DELETED_COLUMN);
        return values;
    }

    private ContentValues getTentativelyDeletedContentValues() {
        ContentValues values = new ContentValues();
        values.put(IS_TENTATIVELY_DELETED_COLUMN, true);
        return values;
    }

    private ContentValues getUndeletedContentValues() {
        ContentValues values = new ContentValues();
        values.putNull(IS_TENTATIVELY_DELETED_COLUMN);
        return values;
    }

    private String getIdWhereClause() {
        return getIdWhereClause(ID_COLUMN);
    }

    private String getIdWhereClause(String columnName) {
        return columnName + " = ?";
    }

    private Cursor fetchLocations(String filter, String filterType, LocationsQuerySortType sortType, int start, int count) {
        String filteredQuery = null;
        String filteredTagName = null;
        boolean fetchUntaggedLocations = false;
        if(Strings.SUGGESTION_TYPE_RAW.equals(filterType)) {
            filteredQuery = filter;
        }
        else if(Strings.SUGGESTION_TYPE_TAG.equals(filterType)) {
            if(filter != null) {
                filteredTagName = filter;
            }
            else {
                fetchUntaggedLocations = true;
            }
        }

        String selectFirstTag = "\n"
                + "  SELECT * FROM tags t\n"
                + "  INNER JOIN location_tag_pairs lt ON lt.tag_id = t._id\n"
                + "  WHERE t.is_deleted IS NULL\n"
                + "  AND t.is_tentatively_deleted IS NULL\n"
                + "  AND (t.is_hidden IS NULL OR t.is_hidden = 0)\n"
                + "  AND lt.location_id = l._id\n"
                + "  ORDER BY lt.location_tag_sort_num\n"
                + "  LIMIT 1\n";

        StringBuffer sb = new StringBuffer();
        sb.append("SELECT l.*, p.path, p.drive_file_id photo_drive_id, (\n");
        sb.append("  SELECT GROUP_CONCAT(t2.name, ', ') FROM (\n");
        sb.append("    SELECT t3.name FROM tags t3\n");
        sb.append("    INNER JOIN location_tag_pairs lt2 ON lt2.tag_id = t3._id\n");
        sb.append("    WHERE t3.is_deleted IS NULL\n");
        sb.append("    AND t3.is_tentatively_deleted IS NULL\n");
        sb.append("    AND (t3.is_hidden IS NULL OR t3.is_hidden = 0)\n");
        sb.append("    AND lt2.location_id = l._id\n");
        sb.append("    ORDER BY lt2.location_tag_sort_num\n");
        sb.append("  ) t2\n");
        sb.append(") tags_str, (\n");
        sb.append("  SELECT color FROM (").append(selectFirstTag).append(")\n");
        sb.append(") first_tag_color, (\n");
        sb.append("  SELECT icon_path FROM (").append(selectFirstTag).append(")\n");
        sb.append(") first_tag_icon_path, (\n");
        sb.append("  CASE WHEN (l.title IS NOT NULL AND l.title != '')\n");
        sb.append("  THEN l.title\n");
        sb.append("  ELSE l.address_str\n");
        sb.append("  END\n");
        sb.append(") display_title\n");
        sb.append("FROM locations l\n");
        sb.append("LEFT OUTER JOIN photos p ON (l._id = p.location_id AND p.sort_num = 0 AND p.is_deleted IS NULL AND p.is_tentatively_deleted IS NULL)\n");
        if(filteredTagName != null) {
            sb.append("LEFT OUTER JOIN location_tag_pairs lt ON l._id = lt.location_id\n");
            sb.append("LEFT OUTER JOIN tags t ON lt.tag_id = t._id\n");
        }
        sb.append("WHERE l.is_deleted IS NULL AND l.is_tentatively_deleted IS NULL\n");

        SQLParams sqlParams = new SQLParams();

        if(filteredTagName != null) {
            sb.append("AND t.name = $FILTERED_TAG_NAME$\n");
            sqlParams.put("FILTERED_TAG_NAME", filteredTagName);
        }
        else if(filteredQuery != null) {
            sb.append("AND (\n");
            sb.append("  (UPPER(l.title) LIKE '%' || UPPER($FILTERED_QUERY$) || '%')\n");
            sb.append("  OR (l.address_str IS NOT NULL AND (UPPER(l.address_str) LIKE '%' || UPPER($FILTERED_QUERY$) || '%'))\n");
            sb.append("  OR (l.notes IS NOT NULL AND (UPPER(l.notes) LIKE '%' || UPPER($FILTERED_QUERY$) || '%'))\n");
            sb.append(")\n");
            sqlParams.put("FILTERED_QUERY", filteredQuery);
        }
        else if(fetchUntaggedLocations) {
            sb.append("AND " + getUntaggedLocationsWhereClause(""));
        }

        if(sortType == null) {
            sortType = LocationsQuerySortType.SORT_ALPHABETICAL;
        }
        switch(sortType) {
            case SORT_ALPHABETICAL:
                sb.append("ORDER BY display_title ASC\n");
                break;
            case SORT_OLDEST:
                sb.append("ORDER BY l.date ASC\n");
                break;
            case SORT_REVERSE_ALPHABETICAL:
                sb.append("ORDER BY display_title DESC\n");
                break;
            case SORT_NEWEST:
                sb.append("ORDER BY l.date DESC\n");
                break;
            case SORT_CLOSEST:
                sb.append("ORDER BY l.distance ASC\n");
                break;
        }

        if(count > 0) {
            sb.append("LIMIT $LIMIT$ OFFSET $OFFSET$\n");
            sqlParams.put("LIMIT", count);
            sqlParams.put("OFFSET", start);
        }

        return query(getReadableDatabase(), sb.toString(), sqlParams);
    }

    private List<Long> fetchLocationIds(SQLiteDatabase db, Long tagId) {
        // Fetch all locations with the tag.
        StringBuffer sb = new StringBuffer();
        sb.append("SELECT l._id FROM locations l\n");
        sb.append("INNER JOIN location_tag_pairs lt ON l._id = lt.location_id\n");
        sb.append("WHERE lt.tag_id = $TAG_ID$");

        SQLParams sqlParams = new SQLParams();
        sqlParams.put("TAG_ID", tagId);

        Cursor cursor = query(db, sb.toString(), sqlParams);

        List<Long> locationIds = new ArrayList<Long>();
        while(!cursor.isAfterLast()) {
            locationIds.add(cursor.getLong(cursor.getColumnIndex(ID_COLUMN)));
            cursor.moveToNext();
        }
        cursor.close();

        return locationIds;
    }

    private void removeTagFromLocations(SQLiteDatabase db, Long tagId, List<Long> locationIds) {
        // We don't need to update the sort numbers for all other location-tag pairs
        // for every affected location because they're still ordered correctly. The
        // sort numbers will be reset when saving the location again.
        db.delete(LOCATION_TAG_PAIR_TABLE, getIdWhereClause(FOREIGN_TAG_ID_COLUMN), new String[]{tagId.toString()});

        // Update the LAST_MODIFIED_DATE column for all locations.
        String locationsWhereClause = "_id IN $LOCATION_IDS$";

        SQLParams sqlParams = new SQLParams();
        sqlParams.put("LOCATION_IDS", locationIds);

        ContentValues values = new ContentValues();
        values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
        update(db, LOCATION_TABLE, values, locationsWhereClause, sqlParams);
    }

    private void deleteTagRecord(SQLiteDatabase db, Long tagId) {
        // Delete the tag if it hasn't been synced yet.
        String whereClause = getIdWhereClause();
        String whereClauseSuffix = " AND drive_file_id IS NULL";
        String[] args = new String[] { tagId.toString() };
        int numRowsAffected = db.delete(TAG_TABLE, whereClause + whereClauseSuffix, args);

        // Mark the tag as deleted if it's already been synced.
        if(numRowsAffected == 0) {
            whereClauseSuffix = " AND drive_file_id IS NOT NULL";
            ContentValues values = getDeletedContentValues();
            values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
            db.update(TAG_TABLE, values, whereClause + whereClauseSuffix, args);
        }
    }

    public List<Long> fetchHiddenTagIds(Long... locationIds) {
        String query = "SELECT DISTINCT(t._id) FROM tags t\n"
                     + "INNER JOIN location_tag_pairs lt ON t._id = lt.tag_id\n"
                     + "WHERE lt.location_id IN $LOCATION_IDS$\n"
                     + "AND t.is_hidden = 1\n"
                     + "AND (t.is_deleted IS NULL OR t.is_deleted = 0)\n";
        SQLParams params = new SQLParams();
        params.put("LOCATION_IDS", locationIds);

        Cursor cursor = query(getReadableDatabase(), query, params);
        List<Long> hiddenTagIds = new ArrayList<>(cursor.getCount());
        while(!cursor.isAfterLast()) {
            hiddenTagIds.add(cursor.getLong(cursor.getColumnIndex(ID_COLUMN)));
            cursor.moveToNext();
        }
        cursor.close();

        return hiddenTagIds;
    }

    // Delete any hidden tag records if they're going to become orphans
    // (i.e. if there are no other locations linked to a tag).
    public void deleteHiddenTagsIfNecessary(List<Long> hiddenTagIds, boolean syncing) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();

            for(Long tagId : hiddenTagIds) {
                String query = "SELECT t.drive_file_id, (\n"
                             + "  SELECT COUNT(*) FROM location_tag_pairs lt\n"
                             + "  WHERE lt.tag_id = t._id\n"
                             + ") locations_count\n"
                             + "FROM tags t\n"
                             + "WHERE t._id = $TAG_ID$\n";

                SQLParams params = new SQLParams();
                params.put("TAG_ID", tagId);
                String[] args = new String[] { tagId.toString() };

                Cursor cursor = query(db, query, params);
                if(cursor.getInt(cursor.getColumnIndex("locations_count")) < 2) {
                    if(cursor.isNull(cursor.getColumnIndex(DRIVE_FILE_ID_COLUMN))) {
                        // The hidden tag hasn't been synced yet, so just delete it.
                        db.delete(TAG_TABLE, getIdWhereClause(), args);
                    }
                    else {
                        // Mark the tag as deleted since it's already been synced.
                        ContentValues values = getDeletedContentValues();
                        values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
                        db.update(TAG_TABLE, values, getIdWhereClause(), args);
                    }
                }
                else if(syncing) {
                    // See REFERENTIAL INTEGRITY comment for explanation.
                    ContentValues values = new ContentValues();
                    values.put(UPDATE_AFTER_SYNC_COLUMN, true);
                    db.update(TAG_TABLE, values, getIdWhereClause(), args);
                }

                cursor.close();
            }

            db.setTransactionSuccessful();
        }
        finally {
            db.endTransaction();
        }
    }

    public void ensureModelsAreDeleted(DatabaseQueryListener listener) {
        new EnsureModelsAreDeletedTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // This function ensures that any locations, tags and photos that are marked
    // tentatively deleted are properly deleted. This is done because sometimes
    public void ensureModelsAreDeleted() {
        SQLiteDatabase db = getWritableDatabase();

        // Delete tags that have been tentatively deleted.
        Cursor cursor = query(db, "SELECT _id FROM tags WHERE is_tentatively_deleted = 1", null);
        if(cursor.getCount() > 0) {
            // Log when the database needs to be cleaned up, to keep track of issues with the snackbar logic.
            CrashlyticsCore.getInstance().logException(new RuntimeException(cursor.getCount() + " tag(s) were marked tentatively deleted and needed to be cleaned up"));
        }
        while(!cursor.isAfterLast()) {
            deleteTag(cursor.getLong(cursor.getColumnIndex(ID_COLUMN)));
            cursor.moveToNext();
        }
        cursor.close();

        // Photos are not tentatively deleted through the database so we don't need
        // to worry about cleaning those up.

        // Delete locations that have been tentatively deleted.
        cursor = query(db, "SELECT _id FROM locations WHERE is_tentatively_deleted = 1", null);
        if(cursor.getCount() > 0) {
            // Log when the database needs to be cleaned up, to keep track of issues with the snackbar logic.
            CrashlyticsCore.getInstance().logException(new RuntimeException(cursor.getCount() + " location(s) were marked tentatively deleted and needed to be cleaned up"));

            List<Long> locationIds = new ArrayList<>(cursor.getCount());
            while(!cursor.isAfterLast()) {
                locationIds.add(cursor.getLong(cursor.getColumnIndex(ID_COLUMN)));
                cursor.moveToNext();
            }
            deleteLocations(locationIds);
        }
        cursor.close();
    }

    // Asynchronous task classes.
    class EnsureModelsAreDeletedTask extends AsyncTask<Void, Void, Void> {

        private DatabaseQueryListener listener;

        public EnsureModelsAreDeletedTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Void... params) {
            ensureModelsAreDeleted();
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }

    }

    class FetchLocationsTask extends AsyncTask<Void, Void, Cursor> {

        private String filter;
        private String filterType;
        private LocationsQuerySortType sortType;
        private int start;
        private int count;
        private DatabaseQueryListener listener;

        public FetchLocationsTask(String filter, String filterType, LocationsQuerySortType sortType, int start, int count, DatabaseQueryListener listener) {
            this.filter = filter;
            this.filterType = filterType;
            this.sortType = sortType;
            this.start = start;
            this.count = count;
            this.listener = listener;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            return fetchLocations(filter, filterType, sortType, start, count);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(cursor));
                cursor.close();
            }
        }

    }

    class FetchLocationTask extends AsyncTask<Void, Void, Cursor> {

        private Long locationId;
        private DatabaseQueryListener listener;

        public FetchLocationTask(Long locationId, DatabaseQueryListener listener) {
            this.locationId = locationId;
            this.listener = listener;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            String selectFirstTag = "\n"
                    + "  SELECT * FROM tags t\n"
                    + "  INNER JOIN location_tag_pairs lt ON lt.tag_id = t._id\n"
                    + "  WHERE t.is_deleted IS NULL\n"
                    + "  AND t.is_tentatively_deleted IS NULL\n"
                    + "  AND (t.is_hidden IS NULL OR t.is_hidden = 0)\n"
                    + "  AND lt.location_id = l._id\n"
                    + "  ORDER BY lt.location_tag_sort_num\n"
                    + "  LIMIT 1\n";

            StringBuffer sb = new StringBuffer();
            sb.append("SELECT l.*, (\n");
            sb.append("  SELECT color FROM (").append(selectFirstTag).append(")\n");
            sb.append(") first_tag_color, (\n");
            sb.append("  SELECT icon_path FROM (").append(selectFirstTag).append(")\n");
            sb.append(") first_tag_icon_path\n");
            sb.append("FROM locations l\n");
            sb.append("WHERE l._id = $LOCATION_ID$\n");

            SQLParams sqlParams = new SQLParams();
            sqlParams.put("LOCATION_ID", locationId);
            return query(getReadableDatabase(), sb.toString(), sqlParams);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(cursor));
                cursor.close();
            }
        }
    }

    class FetchPhotosForLocationTask extends AsyncTask<Long, Void, Cursor> {

        private DatabaseQueryListener listener;

        public FetchPhotosForLocationTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Cursor doInBackground(Long... params) {
            return fetchPhotosForLocation(params[0]);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(cursor));
                cursor.close();
            }
        }
    }

    class FetchLinkedLocationsTask extends AsyncTask<Long, Void, Cursor> {
        private DatabaseQueryListener listener;

        public FetchLinkedLocationsTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Cursor doInBackground(Long... params) {
            return fetchLinkedLocations(params[0]);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(cursor));
                cursor.close();
            }
        }
    }

    class FetchLinkableLocationsTask extends AsyncTask<Object, Void, Cursor> {
        private DatabaseQueryListener listener;

        public FetchLinkableLocationsTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Cursor doInBackground(Object... params) {
            return fetchLinkableLocations((Long) params[0], (String) params[1], (Integer) params[2], (Integer) params[3]);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(cursor));
                cursor.close();
            }
        }
    }

    // Used to create/save a location for the detail screen.
    class SaveLocationTask extends AsyncTask<Object, Void, Long> {
        private DatabaseQueryListener listener;

        public SaveLocationTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Long doInBackground(Object... params) {
            return saveLocation((LocationModel) params[0]);
        }

        @Override
        protected void onPostExecute(Long locationId) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(locationId));
            }
        }

    }

    class DeleteLocationTask extends AsyncTask<Long, Void, Void> {

        private DatabaseQueryListener listener;

        public DeleteLocationTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Long... params) {
            deleteLocation(params[0]);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class TentativelyDeleteLocationTask extends AsyncTask<Long, Void, Void> {

        private DatabaseQueryListener listener;

        public TentativelyDeleteLocationTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Long... params) {
            String[] args = new String[] { params[0].toString() };

            // Mark the location as tentatively deleted.
            getWritableDatabase().update(LOCATION_TABLE, getTentativelyDeletedContentValues(), getIdWhereClause(), args);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class UndeleteLocationTask extends AsyncTask<Long, Void, Void> {

        private DatabaseQueryListener listener;

        public UndeleteLocationTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Long... params) {
            String[] args = new String[] { params[0].toString() };

            // Mark the location as not deleted.
            getWritableDatabase().update(LOCATION_TABLE, getUndeletedContentValues(), getIdWhereClause(), args);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class DeleteLocationsTask extends AsyncTask<Object, Void, Void> {

        private DatabaseQueryListener listener;

        public DeleteLocationsTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Object... params) {
            deleteLocations((List<Long>) params[0]);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class TentativelyDeleteLocationsTask extends AsyncTask<Void, Void, Void> {

        private List<Long> locationIds;
        private DatabaseQueryListener listener;

        public TentativelyDeleteLocationsTask(List<Long> locationIds, DatabaseQueryListener listener) {
            this.locationIds = locationIds;
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Void... params) {
            SQLiteDatabase db = getWritableDatabase();

            ContentValues values = getTentativelyDeletedContentValues();

            try {
                db.beginTransaction();

                String whereClause = "_id IN $LOCATION_IDS$";

                SQLParams sqlParams = new SQLParams();
                sqlParams.put("LOCATION_IDS", locationIds);

                // Mark locations as tentatively deleted.
                update(db, LOCATION_TABLE, values, whereClause, sqlParams);

                // Mark photos as tentatively deleted.
                whereClause = "location_id IN $LOCATION_IDS$";
                update(db, PHOTO_TABLE, values, whereClause, sqlParams);

                db.setTransactionSuccessful();
            }
            finally {
                db.endTransaction();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class UndeleteLocationsTask extends AsyncTask<Void, Void, Void> {

        private List<Long> locationIds;
        private DatabaseQueryListener listener;

        public UndeleteLocationsTask(List<Long> locationIds, DatabaseQueryListener listener) {
            this.locationIds = locationIds;
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Void... params) {
            SQLiteDatabase db = getWritableDatabase();

            ContentValues values = getUndeletedContentValues();

            try {
                db.beginTransaction();

                String whereClause = "_id IN $LOCATION_IDS$";

                SQLParams sqlParams = new SQLParams();
                sqlParams.put("LOCATION_IDS", locationIds);

                // Mark locations as not deleted.
                update(db, LOCATION_TABLE, values, whereClause, sqlParams);

                // Mark photos as not deleted.
                whereClause = "location_id IN $LOCATION_IDS$";
                update(db, PHOTO_TABLE, values, whereClause, sqlParams);

                db.setTransactionSuccessful();
            }
            finally {
                db.endTransaction();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class FetchTagTask extends AsyncTask<Object, Void, Cursor> {

        private DatabaseQueryListener listener;

        public FetchTagTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Cursor doInBackground(Object... params) {
            StringBuffer sb = new StringBuffer();
            sb.append("SELECT * FROM tags\n");
            sb.append("WHERE _id = $ID$\n");

            SQLParams sqlParams = new SQLParams();
            sqlParams.put("ID", params[0]);

            return query(getReadableDatabase(), sb.toString(), sqlParams);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(cursor));
                cursor.close();
            }
        }
    }

    class FetchTagsTask extends AsyncTask<Void, Void, Cursor> {

        private TagsQuerySortType sortType;
        private DatabaseQueryListener listener;
        private boolean includeUntaggedCount;

        public FetchTagsTask(TagsQuerySortType sortType, boolean includeUntaggedCount, DatabaseQueryListener listener) {
            this.sortType = sortType;
            this.listener = listener;
            this.includeUntaggedCount = includeUntaggedCount;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            StringBuffer sb = new StringBuffer();
            sb.append("SELECT t._id AS _id, t.name AS name, UPPER(t.name) AS ordered_name, t.color AS color, t.icon_path AS icon_path, (\n");
            sb.append("  SELECT COUNT(*) FROM location_tag_pairs lt\n");
            sb.append("  WHERE lt.tag_id = t._id\n");
            sb.append(") count\n");
            sb.append("FROM tags t\n");
            sb.append("WHERE (t.is_deleted IS NULL OR t.is_deleted = 0)\n");
            sb.append("AND (t.is_tentatively_deleted IS NULL OR t.is_tentatively_deleted = 0)\n");
            sb.append("AND (t.is_hidden IS NULL OR t.is_hidden = 0)\n");

            if(includeUntaggedCount) {
                sb.append("UNION ALL\n");
                sb.append("SELECT NULL _id, NULL name, NULL ordered_name, NULL color, NULL icon_path, (\n");
                sb.append("  SELECT COUNT(*) FROM locations l\n");
                sb.append("  WHERE " + getUntaggedLocationsWhereClause("  "));
                sb.append("  AND (l.is_deleted IS NULL OR l.is_deleted = 0)\n");
                sb.append("  AND (l.is_tentatively_deleted IS NULL OR l.is_tentatively_deleted = 0)\n");
                sb.append(") count\n");
            }

            if(sortType == null) {
                sortType = TagsQuerySortType.SORT_ALPHABETICAL;
            }
            switch(sortType) {
                case SORT_ALPHABETICAL:
                    sb.append("ORDER BY ordered_name ASC\n");
                    break;
                case SORT_FEWEST_LOCATIONS_TAGGED:
                    sb.append("ORDER BY count ASC\n");
                    break;
                case SORT_REVERSE_ALPHABETICAL:
                    sb.append("ORDER BY ordered_name DESC\n");
                    break;
                case SORT_MOST_LOCATIONS_TAGGED:
                    sb.append("ORDER BY count DESC\n");
                    break;
            }

            return query(getReadableDatabase(), sb.toString(), null);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(cursor));
                cursor.close();
            }
        }
    }

    private String getUntaggedLocationsWhereClause(String indent) {
        StringBuffer sb = new StringBuffer();
        sb.append("NOT EXISTS (\n").append(indent);
        sb.append("  SELECT lt.* FROM location_tag_pairs lt\n").append(indent);
        sb.append("  INNER JOIN tags t ON lt.tag_id = t._id\n").append(indent);
        sb.append("  WHERE lt.location_id = l._id\n").append(indent);
        sb.append("  AND (t.is_hidden IS NULL OR t.is_hidden = 0)\n").append(indent);
        sb.append(")\n");
        return sb.toString();
    }

    class SavePhotoInfosTask extends AsyncTask<Object, Void, List<Long>> {

        private DatabaseQueryListener listener;

        public SavePhotoInfosTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected List<Long> doInBackground(Object... params) {
            return savePhotoInfos((List<PhotoInfo>) params[0], (Long) params[1]);
        }

        @Override
        protected void onPostExecute(List<Long> photoIds) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(photoIds));
            }
        }
    }

    class DeletePhotoInfoTask extends AsyncTask<Object, Void, Void> {

        private DatabaseQueryListener listener;

        public DeletePhotoInfoTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Object... params) {
            deletePhotoInfo((PhotoInfo) params[0], (PhotoInfoList) params[1], (Long) params[2]);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class SetDistancesForLocationsTask extends AsyncTask<Object, Void, Void> {

        private DatabaseQueryListener listener;

        public SetDistancesForLocationsTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Object... params) {
            LatLng currentLatLng = (LatLng) params[0];
            SQLiteDatabase db = getWritableDatabase();

            try {
                db.beginTransaction();

                SQLiteStatement updateStmt = db.compileStatement("UPDATE locations SET distance = ? WHERE _id = ?\n");

                StringBuffer sb = new StringBuffer();
                sb.append("SELECT _id, latitude, longitude FROM locations\n");
                sb.append("WHERE (is_deleted IS NULL OR is_deleted = 0)\n");
                sb.append("AND (is_tentatively_deleted IS NULL OR is_tentatively_deleted = 0)\n");

                Cursor cursor = query(db, sb.toString(), null);
                while(!cursor.isAfterLast()) {
                    Long id = cursor.getLong(cursor.getColumnIndex(ID_COLUMN));
                    double latitude = cursor.getDouble(cursor.getColumnIndex(LOCATION_LATITUDE_COLUMN));
                    double longitude = cursor.getDouble(cursor.getColumnIndex(LOCATION_LONGITUDE_COLUMN));

                    float[] distanceResults = new float[1];
                    Location.distanceBetween(currentLatLng.latitude, currentLatLng.longitude, latitude, longitude, distanceResults);

                    ContentValues values = new ContentValues();
                    values.put(LOCATION_DISTANCE_COLUMN, distanceResults[0]);

                    updateStmt.bindDouble(1, distanceResults[0]);
                    updateStmt.bindLong(2, id);
                    updateStmt.execute();

                    cursor.moveToNext();
                }
                cursor.close();

                db.setTransactionSuccessful();
            }
            finally {
                db.endTransaction();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class FetchTagsForLocationTask extends AsyncTask<Object, Void, Cursor> {

        private DatabaseQueryListener listener;

        public FetchTagsForLocationTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Cursor doInBackground(Object... params) {
            StringBuffer sb = new StringBuffer();
            sb.append("SELECT t.* FROM tags t\n");
            sb.append("INNER JOIN location_tag_pairs lt ON t._id = lt.tag_id\n");
            sb.append("WHERE lt.location_id = $LOCATION_ID$\n");
            sb.append("AND (t.is_deleted IS NULL OR t.is_deleted = 0)\n");
            sb.append("AND (t.is_tentatively_deleted IS NULL OR t.is_tentatively_deleted = 0)\n");
            sb.append("AND (t.is_hidden IS NULL OR t.is_hidden = 0)\n");
            sb.append("ORDER BY lt.location_tag_sort_num\n");

            SQLParams sqlParams = new SQLParams();
            sqlParams.put("LOCATION_ID", params[0]);

            return query(getReadableDatabase(), sb.toString(), sqlParams);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(cursor));
                cursor.close();
            }
        }
    }

    class SaveTagTask extends AsyncTask<Object, Void, Long> {

        private DatabaseQueryListener listener;

        public SaveTagTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Long doInBackground(Object... params) {
            return saveTag((TagModel) params[0]);
        }

        @Override
        protected void onPostExecute(Long tagId) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(tagId));
            }
        }
    }

    class DeleteTagTask extends AsyncTask<Object, Void, Void> {

        private DatabaseQueryListener listener;

        public DeleteTagTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Object... params) {
            deleteTag((Long) params[0]);
            return null;
        }

        @Override
        protected void onPostExecute(Void arg0) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class TentativelyDeleteTagTask extends AsyncTask<Object, Void, Void> {

        private DatabaseQueryListener listener;

        public TentativelyDeleteTagTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Object... params) {
            // Mark the tag as tentatively deleted.
            String whereClause = getIdWhereClause();
            Long tagId = (Long) params[0];
            getWritableDatabase().update(TAG_TABLE, getTentativelyDeletedContentValues(), whereClause, new String[]{ tagId.toString() });

            return null;
        }

        @Override
        protected void onPostExecute(Void arg0) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class UndeleteTagTask extends AsyncTask<Object, Void, Void> {

        private DatabaseQueryListener listener;

        public UndeleteTagTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Object... params) {
            // Mark the tag as no longer deleted.
            String whereClause = getIdWhereClause();
            Long tagId = (Long) params[0];
            getWritableDatabase().update(TAG_TABLE, getUndeletedContentValues(), whereClause, new String[] { tagId.toString() });

            return null;
        }

        @Override
        protected void onPostExecute(Void arg0) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class BulkAddTagsTask extends AsyncTask<Void, Void, Void> {

        private List<Long> locationIds;
        private List<Long> tagIdsToAddBefore;
        private List<Long> tagIdsToAddAfter;
        private DatabaseQueryListener listener;

        public BulkAddTagsTask(List<Long> locationIds, List<Long> tagIdsToAddBefore, List<Long> tagIdsToAddAfter, DatabaseQueryListener listener) {
            this.locationIds = locationIds;
            this.tagIdsToAddBefore = tagIdsToAddBefore;
            this.tagIdsToAddAfter = tagIdsToAddAfter;
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Void... params) {
            SQLiteDatabase db = getWritableDatabase();

            try {
                db.beginTransaction();

                // Fetch the current location-tag pairs in order.
                StringBuffer sb = new StringBuffer();
                sb.append("SELECT l._id, lt.*\n");
                sb.append("FROM locations l\n");
                sb.append("LEFT OUTER JOIN location_tag_pairs lt ON l._id = lt.location_id\n");
                sb.append("LEFT OUTER JOIN tags t ON lt.tag_id = t._id\n");
                sb.append("WHERE l._id IN $LOCATION_IDS$\n");
                sb.append("AND (t.is_hidden IS NULL OR t.is_hidden = 0)\n");
                sb.append("ORDER BY l._id ASC, lt.location_tag_sort_num ASC\n");

                SQLParams sqlParams = new SQLParams();
                sqlParams.put("LOCATION_IDS", locationIds);
                Cursor cursor = query(db, sb.toString(), sqlParams);

                sb = new StringBuffer();
                sb.append("INSERT OR REPLACE INTO location_tag_pairs\n");
                sb.append("(location_id, tag_id, location_tag_sort_num)\n");
                sb.append("VALUES (?, ?, ?)");
                SQLiteStatement insertStmt = db.compileStatement(sb.toString());

                // Iterate all the tags for every location, adding them to the INSERT OR REPLACE query.
                Long currentLocationId = null;
                List<Long> existingTagIds = null;
                while(!cursor.isAfterLast()) {
                    Long locationId = cursor.getLong(cursor.getColumnIndex(ID_COLUMN));
                    if(currentLocationId == null || !currentLocationId.equals(locationId)) {
                        if(currentLocationId != null) {
                            // We have a set of location-tag pairs, so add to the query.
                            addToInsertStmt(insertStmt, currentLocationId, existingTagIds);
                        }

                        currentLocationId = locationId;
                        existingTagIds = new ArrayList<>();
                    }

                    if(!cursor.isNull(cursor.getColumnIndex(FOREIGN_TAG_ID_COLUMN))) {
                        existingTagIds.add(cursor.getLong(cursor.getColumnIndex(FOREIGN_TAG_ID_COLUMN)));
                    }
                    cursor.moveToNext();
                }
                cursor.close();

                addToInsertStmt(insertStmt, currentLocationId, existingTagIds);

                // Update the LAST_MODIFIED_DATE column for all locations.
                ContentValues values = new ContentValues();
                values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
                update(db, LOCATION_TABLE, values, "_id IN $LOCATION_IDS$", sqlParams);

                db.setTransactionSuccessful();
            }
            finally {
                db.endTransaction();
            }

            return null;
        }

        protected void addToInsertStmt(SQLiteStatement insertStmt, Long locationId, List<Long> existingTagIds) {
            // If there are any duplicate tag ids between [existingTagIds] and the other lists,
            // then we will remove them from [existingTagIds], so the effective result will be
            // that the tag has changed its ordering.
            for(Long tagId : tagIdsToAddBefore) {
                if (existingTagIds.contains(tagId)) {
                    existingTagIds.remove(tagId);
                }
            }
            for(Long tagId : tagIdsToAddAfter) {
                if (existingTagIds.contains(tagId)) {
                    existingTagIds.remove(tagId);
                }
            }

            int tagSortNum = 0;
            for(Long tagId : tagIdsToAddBefore) {
                insertStmt.bindLong(1, locationId);
                insertStmt.bindLong(2, tagId);
                insertStmt.bindLong(3, tagSortNum++);
                insertStmt.execute();
            }
            for(Long tagId : existingTagIds) {
                insertStmt.bindLong(1, locationId);
                insertStmt.bindLong(2, tagId);
                insertStmt.bindLong(3, tagSortNum++);
                insertStmt.execute();
            }
            for(Long tagId : tagIdsToAddAfter) {
                if(existingTagIds.contains(tagId)) {
                    existingTagIds.remove(tagId);
                }

                insertStmt.bindLong(1, locationId);
                insertStmt.bindLong(2, tagId);
                insertStmt.bindLong(3, tagSortNum++);
                insertStmt.execute();
            }
        }

        @Override
        protected void onPostExecute(Void arg0) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }

    }

    class BulkRemoveTagsTask extends AsyncTask<Void, Void, Void> {

        private List<Long> locationIds;
        private List<Long> tagIdsToRemove;
        private DatabaseQueryListener listener;

        public BulkRemoveTagsTask(List<Long> locationIds, List<Long> tagIdsToRemove, DatabaseQueryListener listener) {
            this.locationIds = locationIds;
            this.tagIdsToRemove = tagIdsToRemove;
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(Void... params) {
            SQLiteDatabase db = getWritableDatabase();

            try {
                db.beginTransaction();

                // Remove all location-tag pairs.
                String whereClause = "location_id IN $LOCATION_IDS$ AND tag_id IN $TAG_IDS$";

                SQLParams sqlParams = new SQLParams();
                sqlParams.put("LOCATION_IDS", locationIds);
                sqlParams.put("TAG_IDS", tagIdsToRemove);

                delete(db, LOCATION_TAG_PAIR_TABLE, whereClause, sqlParams);

                // Update the LAST_MODIFIED_DATE column for all locations.
                ContentValues values = new ContentValues();
                values.put(LAST_MODIFIED_DATE_COLUMN, new Date().getTime());
                update(db, LOCATION_TABLE, values, "_id IN $LOCATION_IDS$", sqlParams);

                db.setTransactionSuccessful();
            }
            finally {
                db.endTransaction();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult());
            }
        }
    }

    class SaveLinkedLocationsTask extends AsyncTask<List<Long>, Void, Void> {

        private DatabaseQueryListener listener;

        public SaveLinkedLocationsTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Void doInBackground(List<Long>... params) {
            saveLinkedLocations(params[0]);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if(listener != null) {
                listener.onQueryExecuted(null);
            }
        }
    }

    class MovePhotoFilesTask extends AsyncTask<Void, Void, Void> {

        private String oldFolder;
        private String newFolder;

        public MovePhotoFilesTask(String oldFolder, String newFolder) {
            this.oldFolder = oldFolder;
            this.newFolder = newFolder;
        }

        @Override
        protected Void doInBackground(Void... params) {
            SQLiteDatabase db = getWritableDatabase();
            try {
                db.beginTransaction();

                ContentValues values = new ContentValues();
                String query = "SELECT _id, path FROM photos";
                Cursor cursor = query(db, query, null);
                while(!cursor.isAfterLast()) {
                    Long id = cursor.getLong(cursor.getColumnIndex(ID_COLUMN));
                    String path = cursor.getString(cursor.getColumnIndex(PHOTO_PATH_COLUMN));
                    path = path.replace("/" + oldFolder + "/", "/" + newFolder + "/");

                    values.put(PHOTO_PATH_COLUMN, path);
                    db.update(PHOTO_TABLE, values, getIdWhereClause(), new String[] { id.toString() });

                    cursor.moveToNext();
                }
                cursor.close();

                db.setTransactionSuccessful();
            }
            finally {
                db.endTransaction();
            }

            return null;
        }
    }

    class FetchLocationsCountTask extends AsyncTask<Void, Void, Cursor> {

        private DatabaseQueryListener listener;

        public FetchLocationsCountTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            return getLocationsCount();
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(cursor));
                cursor.close();
            }
        }
    }

    class HasPhotosForPlacesTask extends AsyncTask<Void, Void, Boolean> {

        private DatabaseQueryListener listener;

        public HasPhotosForPlacesTask(DatabaseQueryListener listener) {
            this.listener = listener;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            Cursor cursor;
            StringBuffer sb;

            sb = new StringBuffer();
            sb.append("SELECT COUNT(*) count\n");
            sb.append("FROM locations\n");
            sb.append("WHERE (is_deleted IS NULL OR is_deleted = 0)\n");
            sb.append("AND (is_tentatively_deleted IS NULL OR is_tentatively_deleted = 0)\n");
            cursor = query(getReadableDatabase(), sb.toString(), null);
            int numPlaces = cursor.getInt(cursor.getColumnIndex("count"));
            cursor.close();

            sb = new StringBuffer();
            sb.append("SELECT count(distinct(p.location_id)) count\n");
            sb.append("FROM photos p\n");
            sb.append("WHERE (p.is_deleted IS NULL OR p.is_deleted = 0)\n");
            sb.append("AND (p.is_tentatively_deleted IS NULL OR p.is_tentatively_deleted = 0)\n");

            cursor = query(getReadableDatabase(), sb.toString(), null);
            int numPlacesWithPhotos = cursor.getInt(cursor.getColumnIndex("count"));
            cursor.close();

            return (numPlaces >= 5 && ((double) numPlacesWithPhotos / numPlaces) < 0.6);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if(listener != null) {
                listener.onQueryExecuted(new DatabaseQueryResult(result));
            }
        }
    }

    protected Long getLastInsertedTagId() {
        Cursor cursor = query(getReadableDatabase(), "SELECT _id FROM tags ORDER BY _id DESC LIMIT 1", null);
        Long lastInsertedTagId = MiscUtils.getLong(cursor, ID_COLUMN);
        cursor.close();
        return lastInsertedTagId;
    }

    protected int update(SQLiteDatabase db, String table, ContentValues values, String whereClause, SQLParams params) {
        String[] whereArgs = null;
        if(params != null) {
            whereArgs = params.getParamsOrderedByQuery(whereClause);
            whereClause = params.massageQueryForExecution(whereClause);
        }

        return db.update(table, values, whereClause, whereArgs);
    }

    protected int delete(SQLiteDatabase db, String table, String whereClause, SQLParams params) {
        String[] whereArgs = null;
        if(params != null) {
            whereArgs = params.getParamsOrderedByQuery(whereClause);
            whereClause = params.massageQueryForExecution(whereClause);
        }

        return db.delete(table, whereClause, whereArgs);
    }

    protected Cursor query(SQLiteDatabase db, String query, SQLParams params) {
        return query(db, query, params, true);
    }

    protected Cursor query(SQLiteDatabase db, String query, SQLParams params, boolean initCursor) {
        String[] selectionArgs = null;
        if(params != null) {
            selectionArgs = params.getParamsOrderedByQuery(query);
            query = params.massageQueryForExecution(query);
        }

        Cursor cursor = db.rawQuery(query, selectionArgs);
        if(initCursor) {
            if(cursor.getCount() > 0) {
                cursor.moveToFirst();
            }
        }
        return cursor;
    }

    protected void execSQL(SQLiteDatabase db, String query, SQLParams params) {
        String[] selectionArgs = null;
        if(params != null) {
            selectionArgs = params.getParamsOrderedByQuery(query);
            query = params.massageQueryForExecution(query);
        }

        db.execSQL(query, selectionArgs);
    }

}
