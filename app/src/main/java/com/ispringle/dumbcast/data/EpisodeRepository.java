package com.ispringle.dumbcast.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public class EpisodeRepository {

    private final DatabaseHelper dbHelper;
    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000;

    public EpisodeRepository(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Insert a new episode into the database.
     * @param episode The episode to insert
     * @return The row ID of the newly inserted episode, or -1 if an error occurred
     */
    public long insertEpisode(Episode episode) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = episodeToContentValues(episode);
        long id = db.insert(DatabaseHelper.TABLE_EPISODES, null, values);
        if (id != -1) {
            episode.setId(id);
        }
        return id;
    }

    /**
     * Get an episode by its ID.
     * @param id The episode ID
     * @return The episode, or null if not found
     */
    public Episode getEpisodeById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
            DatabaseHelper.TABLE_EPISODES,
            null,
            DatabaseHelper.COL_EPISODE_ID + " = ?",
            new String[]{String.valueOf(id)},
            null,
            null,
            null
        );

        Episode episode = null;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                episode = cursorToEpisode(cursor);
            }
            cursor.close();
        }
        return episode;
    }

    /**
     * Core decay logic for episode state management.
     * This method implements the anxiety-free episode state transitions:
     * - NEW episodes fetched more than 7 days ago decay to AVAILABLE
     * - Episodes with sessionGrace=true decay to AVAILABLE and clear the flag
     * - BACKLOG and LISTENED episodes are not affected
     */
    public void decayNewEpisodes() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long now = System.currentTimeMillis();
        long sevenDaysAgo = now - SEVEN_DAYS_MS;

        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_EPISODE_STATE, EpisodeState.AVAILABLE.name());
        values.put(DatabaseHelper.COL_EPISODE_SESSION_GRACE, 0);

        String whereClause = DatabaseHelper.COL_EPISODE_STATE + " = ? AND (" +
            DatabaseHelper.COL_EPISODE_SESSION_GRACE + " = 1 OR " +
            DatabaseHelper.COL_EPISODE_FETCHED_AT + " <= ?)";

        String[] whereArgs = {EpisodeState.NEW.name(), String.valueOf(sevenDaysAgo)};

        db.beginTransaction();
        try {
            db.update(DatabaseHelper.TABLE_EPISODES, values, whereClause, whereArgs);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Update the state of an episode.
     * @param id The episode ID
     * @param state The new state
     * @return The number of rows affected
     */
    public int updateEpisodeState(long id, EpisodeState state) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_EPISODE_STATE, state.name());

        // Update timestamp based on state
        long now = System.currentTimeMillis();
        if (state == EpisodeState.BACKLOG) {
            values.put(DatabaseHelper.COL_EPISODE_SAVED_AT, now);
        } else if (state == EpisodeState.LISTENED) {
            values.put(DatabaseHelper.COL_EPISODE_PLAYED_AT, now);
        } else if (state == EpisodeState.AVAILABLE) {
            values.put(DatabaseHelper.COL_EPISODE_VIEWED_AT, now);
        }

        return db.update(
            DatabaseHelper.TABLE_EPISODES,
            values,
            DatabaseHelper.COL_EPISODE_ID + " = ?",
            new String[]{String.valueOf(id)}
        );
    }

    /**
     * Update the download information for an episode.
     * @param id The episode ID
     * @param downloadPath The local file path to the downloaded episode
     * @param downloadedAt The timestamp when the download completed
     * @return The number of rows affected
     */
    public int updateEpisodeDownload(long id, String downloadPath, long downloadedAt) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_EPISODE_DOWNLOAD_PATH, downloadPath);
        values.put(DatabaseHelper.COL_EPISODE_DOWNLOADED_AT, downloadedAt);

        return db.update(
            DatabaseHelper.TABLE_EPISODES,
            values,
            DatabaseHelper.COL_EPISODE_ID + " = ?",
            new String[]{String.valueOf(id)}
        );
    }

    /**
     * Get all episodes with a specific state.
     * @param state The episode state to filter by
     * @return List of episodes in the specified state
     */
    public List<Episode> getEpisodesByState(EpisodeState state) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Episode> episodes = new ArrayList<>();

        Cursor cursor = db.query(
            DatabaseHelper.TABLE_EPISODES,
            null,
            DatabaseHelper.COL_EPISODE_STATE + " = ?",
            new String[]{state.name()},
            null,
            null,
            DatabaseHelper.COL_EPISODE_PUBLISHED_AT + " DESC"
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                episodes.add(cursorToEpisode(cursor));
            }
            cursor.close();
        }

        return episodes;
    }

    /**
     * Convert an Episode object to ContentValues for database insertion/update.
     * @param episode The episode to convert
     * @return ContentValues containing episode data
     */
    private ContentValues episodeToContentValues(Episode episode) {
        ContentValues values = new ContentValues();

        values.put(DatabaseHelper.COL_EPISODE_PODCAST_ID, episode.getPodcastId());
        values.put(DatabaseHelper.COL_EPISODE_GUID, episode.getGuid());
        values.put(DatabaseHelper.COL_EPISODE_TITLE, episode.getTitle());
        values.put(DatabaseHelper.COL_EPISODE_DESCRIPTION, episode.getDescription());
        values.put(DatabaseHelper.COL_EPISODE_ENCLOSURE_URL, episode.getEnclosureUrl());
        values.put(DatabaseHelper.COL_EPISODE_ENCLOSURE_TYPE, episode.getEnclosureType());
        values.put(DatabaseHelper.COL_EPISODE_ENCLOSURE_LENGTH, episode.getEnclosureLength());
        values.put(DatabaseHelper.COL_EPISODE_PUBLISHED_AT, episode.getPublishedAt());
        values.put(DatabaseHelper.COL_EPISODE_FETCHED_AT, episode.getFetchedAt());
        values.put(DatabaseHelper.COL_EPISODE_DURATION, episode.getDuration());
        values.put(DatabaseHelper.COL_EPISODE_STATE, episode.getState().name());
        values.put(DatabaseHelper.COL_EPISODE_PLAYBACK_POS, episode.getPlaybackPosition());
        values.put(DatabaseHelper.COL_EPISODE_SESSION_GRACE, episode.isSessionGrace() ? 1 : 0);

        // Nullable fields
        if (episode.getViewedAt() != null) {
            values.put(DatabaseHelper.COL_EPISODE_VIEWED_AT, episode.getViewedAt());
        }
        if (episode.getSavedAt() != null) {
            values.put(DatabaseHelper.COL_EPISODE_SAVED_AT, episode.getSavedAt());
        }
        if (episode.getPlayedAt() != null) {
            values.put(DatabaseHelper.COL_EPISODE_PLAYED_AT, episode.getPlayedAt());
        }
        if (episode.getDownloadPath() != null) {
            values.put(DatabaseHelper.COL_EPISODE_DOWNLOAD_PATH, episode.getDownloadPath());
        }
        if (episode.getDownloadedAt() != null) {
            values.put(DatabaseHelper.COL_EPISODE_DOWNLOADED_AT, episode.getDownloadedAt());
        }
        if (episode.getChaptersUrl() != null) {
            values.put(DatabaseHelper.COL_EPISODE_CHAPTERS_URL, episode.getChaptersUrl());
        }

        return values;
    }

    /**
     * Convert a database cursor to an Episode object.
     * @param cursor The cursor pointing to episode data
     * @return Episode object populated from cursor data
     */
    private Episode cursorToEpisode(Cursor cursor) {
        long podcastId = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_PODCAST_ID));
        String guid = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_GUID));
        String title = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_TITLE));
        String enclosureUrl = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_ENCLOSURE_URL));
        long publishedAt = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_PUBLISHED_AT));

        Episode episode = new Episode(podcastId, guid, title, enclosureUrl, publishedAt);

        // Set ID
        episode.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_ID)));

        // Set other fields
        episode.setDescription(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_DESCRIPTION)));
        episode.setEnclosureType(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_ENCLOSURE_TYPE)));
        episode.setEnclosureLength(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_ENCLOSURE_LENGTH)));
        episode.setFetchedAt(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_FETCHED_AT)));
        episode.setDuration(cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_DURATION)));

        String stateStr = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_STATE));
        episode.setState(EpisodeState.fromString(stateStr));

        episode.setPlaybackPosition(cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_PLAYBACK_POS)));
        episode.setSessionGrace(cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_SESSION_GRACE)) == 1);

        // Nullable fields
        int viewedAtIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_VIEWED_AT);
        if (!cursor.isNull(viewedAtIndex)) {
            episode.setViewedAt(cursor.getLong(viewedAtIndex));
        }

        int savedAtIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_SAVED_AT);
        if (!cursor.isNull(savedAtIndex)) {
            episode.setSavedAt(cursor.getLong(savedAtIndex));
        }

        int playedAtIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_PLAYED_AT);
        if (!cursor.isNull(playedAtIndex)) {
            episode.setPlayedAt(cursor.getLong(playedAtIndex));
        }

        int downloadPathIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_DOWNLOAD_PATH);
        if (!cursor.isNull(downloadPathIndex)) {
            episode.setDownloadPath(cursor.getString(downloadPathIndex));
        }

        int downloadedAtIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_DOWNLOADED_AT);
        if (!cursor.isNull(downloadedAtIndex)) {
            episode.setDownloadedAt(cursor.getLong(downloadedAtIndex));
        }

        int chaptersUrlIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_CHAPTERS_URL);
        if (!cursor.isNull(chaptersUrlIndex)) {
            episode.setChaptersUrl(cursor.getString(chaptersUrlIndex));
        }

        return episode;
    }
}
