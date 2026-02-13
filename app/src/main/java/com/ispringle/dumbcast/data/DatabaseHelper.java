package com.ispringle.dumbcast.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "dumbcast.db";
    private static final int DATABASE_VERSION = 2;

    // Table names
    public static final String TABLE_PODCASTS = "podcasts";
    public static final String TABLE_EPISODES = "episodes";

    // Podcasts columns
    public static final String COL_PODCAST_ID = "id";
    public static final String COL_PODCAST_FEED_URL = "feed_url";
    public static final String COL_PODCAST_TITLE = "title";
    public static final String COL_PODCAST_DESCRIPTION = "description";
    public static final String COL_PODCAST_ARTWORK_URL = "artwork_url";
    public static final String COL_PODCAST_INDEX_ID = "podcast_index_id";
    public static final String COL_PODCAST_LAST_REFRESH = "last_refresh_at";
    public static final String COL_PODCAST_CREATED = "created_at";

    // Episodes columns
    public static final String COL_EPISODE_ID = "id";
    public static final String COL_EPISODE_PODCAST_ID = "podcast_id";
    public static final String COL_EPISODE_GUID = "guid";
    public static final String COL_EPISODE_TITLE = "title";
    public static final String COL_EPISODE_DESCRIPTION = "description";
    public static final String COL_EPISODE_ENCLOSURE_URL = "enclosure_url";
    public static final String COL_EPISODE_ENCLOSURE_TYPE = "enclosure_type";
    public static final String COL_EPISODE_ENCLOSURE_LENGTH = "enclosure_length";
    public static final String COL_EPISODE_PUBLISHED_AT = "published_at";
    public static final String COL_EPISODE_FETCHED_AT = "fetched_at";
    public static final String COL_EPISODE_DURATION = "duration";
    public static final String COL_EPISODE_STATE = "state";
    public static final String COL_EPISODE_VIEWED_AT = "viewed_at";
    public static final String COL_EPISODE_SAVED_AT = "saved_at";
    public static final String COL_EPISODE_PLAYED_AT = "played_at";
    public static final String COL_EPISODE_PLAYBACK_POS = "playback_position";
    public static final String COL_EPISODE_DOWNLOAD_PATH = "download_path";
    public static final String COL_EPISODE_DOWNLOADED_AT = "downloaded_at";
    public static final String COL_EPISODE_SESSION_GRACE = "session_grace";
    public static final String COL_EPISODE_CHAPTERS_URL = "chapters_url";

    private static final String CREATE_PODCASTS_TABLE =
        "CREATE TABLE " + TABLE_PODCASTS + " (" +
        COL_PODCAST_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        COL_PODCAST_FEED_URL + " TEXT UNIQUE NOT NULL, " +
        COL_PODCAST_TITLE + " TEXT NOT NULL, " +
        COL_PODCAST_DESCRIPTION + " TEXT, " +
        COL_PODCAST_ARTWORK_URL + " TEXT, " +
        COL_PODCAST_INDEX_ID + " INTEGER, " +
        COL_PODCAST_LAST_REFRESH + " INTEGER, " +
        COL_PODCAST_CREATED + " INTEGER NOT NULL)";

    private static final String CREATE_EPISODES_TABLE =
        "CREATE TABLE " + TABLE_EPISODES + " (" +
        COL_EPISODE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        COL_EPISODE_PODCAST_ID + " INTEGER NOT NULL, " +
        COL_EPISODE_GUID + " TEXT NOT NULL, " +
        COL_EPISODE_TITLE + " TEXT NOT NULL, " +
        COL_EPISODE_DESCRIPTION + " TEXT, " +
        COL_EPISODE_ENCLOSURE_URL + " TEXT, " +
        COL_EPISODE_ENCLOSURE_TYPE + " TEXT, " +
        COL_EPISODE_ENCLOSURE_LENGTH + " INTEGER, " +
        COL_EPISODE_PUBLISHED_AT + " INTEGER NOT NULL, " +
        COL_EPISODE_FETCHED_AT + " INTEGER NOT NULL, " +
        COL_EPISODE_DURATION + " INTEGER, " +
        COL_EPISODE_STATE + " TEXT NOT NULL DEFAULT 'NEW', " +
        COL_EPISODE_VIEWED_AT + " INTEGER, " +
        COL_EPISODE_SAVED_AT + " INTEGER, " +
        COL_EPISODE_PLAYED_AT + " INTEGER, " +
        COL_EPISODE_PLAYBACK_POS + " INTEGER DEFAULT 0, " +
        COL_EPISODE_DOWNLOAD_PATH + " TEXT, " +
        COL_EPISODE_DOWNLOADED_AT + " INTEGER, " +
        COL_EPISODE_SESSION_GRACE + " INTEGER DEFAULT 0, " +
        COL_EPISODE_CHAPTERS_URL + " TEXT, " +
        "FOREIGN KEY(" + COL_EPISODE_PODCAST_ID + ") REFERENCES " +
        TABLE_PODCASTS + "(" + COL_PODCAST_ID + ") ON DELETE CASCADE, " +
        "UNIQUE(" + COL_EPISODE_PODCAST_ID + ", " + COL_EPISODE_GUID + "))";

    private static final String CREATE_EPISODE_STATE_INDEX =
        "CREATE INDEX idx_episodes_state ON " + TABLE_EPISODES + "(" + COL_EPISODE_STATE + ")";

    private static final String CREATE_EPISODE_PODCAST_STATE_INDEX =
        "CREATE INDEX idx_episodes_podcast_state ON " + TABLE_EPISODES +
        "(" + COL_EPISODE_PODCAST_ID + ", " + COL_EPISODE_STATE + ")";

    private static final String CREATE_EPISODE_FETCHED_INDEX =
        "CREATE INDEX idx_episodes_fetched_at ON " + TABLE_EPISODES + "(" + COL_EPISODE_FETCHED_AT + ")";

    private static final String CREATE_EPISODE_PUBLISHED_INDEX =
        "CREATE INDEX idx_episodes_published_at ON " + TABLE_EPISODES + "(" + COL_EPISODE_PUBLISHED_AT + " DESC)";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_PODCASTS_TABLE);
        db.execSQL(CREATE_EPISODES_TABLE);
        db.execSQL(CREATE_EPISODE_STATE_INDEX);
        db.execSQL(CREATE_EPISODE_PODCAST_STATE_INDEX);
        db.execSQL(CREATE_EPISODE_FETCHED_INDEX);
        db.execSQL(CREATE_EPISODE_PUBLISHED_INDEX);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Migration from version 1 to 2: Make enclosure_url nullable
            // Create new episodes table with nullable enclosure_url
            db.execSQL("CREATE TABLE episodes_new (" +
                COL_EPISODE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_EPISODE_PODCAST_ID + " INTEGER NOT NULL, " +
                COL_EPISODE_GUID + " TEXT NOT NULL, " +
                COL_EPISODE_TITLE + " TEXT NOT NULL, " +
                COL_EPISODE_DESCRIPTION + " TEXT, " +
                COL_EPISODE_ENCLOSURE_URL + " TEXT, " +
                COL_EPISODE_ENCLOSURE_TYPE + " TEXT, " +
                COL_EPISODE_ENCLOSURE_LENGTH + " INTEGER, " +
                COL_EPISODE_PUBLISHED_AT + " INTEGER NOT NULL, " +
                COL_EPISODE_FETCHED_AT + " INTEGER NOT NULL, " +
                COL_EPISODE_DURATION + " INTEGER, " +
                COL_EPISODE_STATE + " TEXT NOT NULL DEFAULT 'NEW', " +
                COL_EPISODE_VIEWED_AT + " INTEGER, " +
                COL_EPISODE_SAVED_AT + " INTEGER, " +
                COL_EPISODE_PLAYED_AT + " INTEGER, " +
                COL_EPISODE_PLAYBACK_POS + " INTEGER DEFAULT 0, " +
                COL_EPISODE_DOWNLOAD_PATH + " TEXT, " +
                COL_EPISODE_DOWNLOADED_AT + " INTEGER, " +
                COL_EPISODE_SESSION_GRACE + " INTEGER DEFAULT 0, " +
                COL_EPISODE_CHAPTERS_URL + " TEXT, " +
                "FOREIGN KEY(" + COL_EPISODE_PODCAST_ID + ") REFERENCES " +
                TABLE_PODCASTS + "(" + COL_PODCAST_ID + ") ON DELETE CASCADE, " +
                "UNIQUE(" + COL_EPISODE_PODCAST_ID + ", " + COL_EPISODE_GUID + "))");

            // Copy data from old table to new table
            db.execSQL("INSERT INTO episodes_new SELECT * FROM " + TABLE_EPISODES);

            // Drop old table
            db.execSQL("DROP TABLE " + TABLE_EPISODES);

            // Rename new table to original name
            db.execSQL("ALTER TABLE episodes_new RENAME TO " + TABLE_EPISODES);

            // Recreate indexes
            db.execSQL(CREATE_EPISODE_STATE_INDEX);
            db.execSQL(CREATE_EPISODE_PODCAST_STATE_INDEX);
            db.execSQL(CREATE_EPISODE_FETCHED_INDEX);
            db.execSQL(CREATE_EPISODE_PUBLISHED_INDEX);
        }
    }
}
