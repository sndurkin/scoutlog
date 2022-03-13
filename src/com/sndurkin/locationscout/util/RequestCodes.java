package com.sndurkin.locationscout.util;

public final class RequestCodes {

    public static final int EXPORT_NOTIFICATION_ID = 100;
    public static final int IMPORT_NOTIFICATION_ID = 101;

    public static final int SELECT_GOOGLE_ACCOUNT = 200;
    public static final int REQUEST_AUTHORIZATION = 201;
    public static final int CONNECTION_FAILURE_RESOLUTION = 202;

    public static final int SYNC_INVALID_ACCOUNT_ERROR = 515;

    // Used in DetailFragment
    public static final int DETAIL_MAP_ACTIVITY = 300;
    public static final int SELECT_TAGS_ACTIVITY = 301;
    public static final int EDIT_NOTE_FOR_LOCATION_ACTIVITY = 302;
    public static final int EDIT_NOTE_FOR_PHOTO_ACTIVITY = 303;
    public static final int SELECT_LINKED_LOCATIONS_ACTIVITY = 304;
    public static final int BULK_EDIT_TAGS_ACTIVITY = 305;
    public static final int DETAIL_ACTIVITY = 306;
    public static final int SET_MAP_MARKER_ACTIVITY = 307;

    public static final int SELECT_IMAGE = 400;
    public static final int CAPTURE_IMAGE = 401;
    public static final int SELECT_FOLDER = 402;

    public static final int SYNC_STARTED = 500;
    public static final int SYNC_FINISHED = 501;
    public static final int SYNC_RECOVERABLE_AUTH_EXCEPTION = 502;
    public static final int SYNC_AUTH_EXCEPTION = 503;
    public static final int SYNC_IO_EXCEPTION = 504;
    public static final int SYNC_PHOTO_FILE_CREATE_EXCEPTION = 505;
    public static final int SYNC_CANNOT_REACH_SERVER_ERROR = 510;
    public static final int SYNC_STORAGE_LIMIT_REACHED_ERROR = 511;

    public static final int EXPORT_STARTED = 600;
    public static final int EXPORT_FINISHED = 601;
    public static final int EXPORT_FAILED = 602;

    public static final int EXPORT_TYPE_GPX = 630;
    public static final int EXPORT_TYPE_KML = 631;
    public static final int EXPORT_TYPE_CSV = 632;

    public static final int PERMISSION_REQUEST_LOCATION = 700;
    public static final int PERMISSION_REQUEST_STORAGE = 701;
    public static final int PERMISSION_REQUEST_CONTACTS = 702;
    public static final int PERMISSION_REQUEST_ALL = 703;

}
