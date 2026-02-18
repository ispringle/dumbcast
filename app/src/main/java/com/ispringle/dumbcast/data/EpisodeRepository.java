package com.ispringle.dumbcast.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import android.util.Log;

public class EpisodeRepository {

    private static final String TAG = "EpisodeRepository";
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
     * Fix state for downloaded episodes.
     * Episodes that are downloaded but still in NEW state should be moved to BACKLOG.
     * This is a migration fix for episodes downloaded before auto-state-change was implemented.
     */
    public void fixDownloadedEpisodesState() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_EPISODE_STATE, EpisodeState.BACKLOG.name());
        values.put(DatabaseHelper.COL_EPISODE_SAVED_AT, System.currentTimeMillis());

        // Update episodes that are downloaded but not in BACKLOG or LISTENED state
        String whereClause = DatabaseHelper.COL_EPISODE_DOWNLOAD_PATH + " IS NOT NULL AND " +
            DatabaseHelper.COL_EPISODE_STATE + " != ? AND " +
            DatabaseHelper.COL_EPISODE_STATE + " != ?";

        String[] whereArgs = {EpisodeState.BACKLOG.name(), EpisodeState.LISTENED.name()};

        db.beginTransaction();
        try {
            int updated = db.update(DatabaseHelper.TABLE_EPISODES, values, whereClause, whereArgs);
            db.setTransactionSuccessful();
            Log.d(TAG, "Fixed state for " + updated + " downloaded episodes");
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
     * Delete an episode's download and update its state appropriately.
     * If the episode is in BACKLOG state, it will be moved to AVAILABLE.
     * This ensures deleted episodes are removed from the backlog view.
     * @param id The episode ID
     * @param downloadPath The download path to delete (for verification)
     * @return The number of rows affected
     */
    public int deleteEpisodeDownloadAndUpdateState(long id, String downloadPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // First get the current episode to check its state
        Episode episode = getEpisodeById(id);
        if (episode == null) {
            Log.w(TAG, "Cannot delete download: Episode not found (ID: " + id + ")");
            return 0;
        }

        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_EPISODE_DOWNLOAD_PATH, (String) null);
        values.put(DatabaseHelper.COL_EPISODE_DOWNLOADED_AT, 0);

        // If episode is in BACKLOG state, move it to AVAILABLE
        // This removes it from the backlog view when deleted
        if (episode.getState() == EpisodeState.BACKLOG) {
            values.put(DatabaseHelper.COL_EPISODE_STATE, EpisodeState.AVAILABLE.name());
            values.put(DatabaseHelper.COL_EPISODE_VIEWED_AT, System.currentTimeMillis());
            Log.d(TAG, "Moving episode from BACKLOG to AVAILABLE after deletion: " + id);
        }

        return db.update(
            DatabaseHelper.TABLE_EPISODES,
            values,
            DatabaseHelper.COL_EPISODE_ID + " = ?",
            new String[]{String.valueOf(id)}
        );
    }

    /**
     * Update the playback position for an episode.
     * This is called frequently during playback, so it only updates the position column.
     * @param id The episode ID
     * @param positionSeconds The playback position in seconds
     * @return The number of rows affected
     */
    public int updateEpisodePlaybackPosition(long id, int positionSeconds) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_EPISODE_PLAYBACK_POS, positionSeconds);

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
     * Get all episodes for a specific podcast.
     * @param podcastId The podcast ID to filter by
     * @return List of episodes for the podcast, ordered by published date (newest first)
     */
    public List<Episode> getEpisodesByPodcast(long podcastId) {
        return getEpisodesByPodcast(podcastId, false);
    }

    /**
     * Get all episodes for a specific podcast with optional reverse order.
     * @param podcastId The podcast ID to filter by
     * @param reverseOrder If true, order oldest first (for episodic podcasts)
     * @return List of episodes for the podcast, ordered by published date
     */
    public List<Episode> getEpisodesByPodcast(long podcastId, boolean reverseOrder) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Episode> episodes = new ArrayList<>();

        String orderBy = reverseOrder ?
            DatabaseHelper.COL_EPISODE_PUBLISHED_AT + " ASC" :
            DatabaseHelper.COL_EPISODE_PUBLISHED_AT + " DESC";

        Cursor cursor = db.query(
            DatabaseHelper.TABLE_EPISODES,
            null,
            DatabaseHelper.COL_EPISODE_PODCAST_ID + " = ?",
            new String[]{String.valueOf(podcastId)},
            null,
            null,
            orderBy
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
     * Get episodes for a specific podcast AND state.
     * @param podcastId The podcast ID to filter by
     * @param state The episode state to filter by
     * @return List of episodes matching both filters, ordered by published date (newest first)
     */
    public List<Episode> getEpisodesByPodcastAndState(long podcastId, EpisodeState state) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Episode> episodes = new ArrayList<>();

        Cursor cursor = db.query(
            DatabaseHelper.TABLE_EPISODES,
            null,
            DatabaseHelper.COL_EPISODE_PODCAST_ID + " = ? AND " + DatabaseHelper.COL_EPISODE_STATE + " = ?",
            new String[]{String.valueOf(podcastId), state.name()},
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
     * Get the count of episodes for a specific podcast AND state.
     * @param podcastId The podcast ID
     * @param state The episode state to filter by
     * @return The number of episodes matching both filters
     */
    public int getEpisodeCountByPodcastAndState(long podcastId, EpisodeState state) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
            "SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_EPISODES +
            " WHERE " + DatabaseHelper.COL_EPISODE_PODCAST_ID + " = ? AND " +
            DatabaseHelper.COL_EPISODE_STATE + " = ?",
            new String[]{String.valueOf(podcastId), state.name()}
        );

        int count = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
        }

        return count;
    }

    /**
     * Get the count of episodes for a specific podcast.
     * @param podcastId The podcast ID
     * @return The number of episodes for the podcast
     */
    public int getEpisodeCountByPodcast(long podcastId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        int count = 0;

        Cursor cursor = db.query(
            DatabaseHelper.TABLE_EPISODES,
            new String[]{"COUNT(*) as count"},
            DatabaseHelper.COL_EPISODE_PODCAST_ID + " = ?",
            new String[]{String.valueOf(podcastId)},
            null,
            null,
            null
        );

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
        }

        return count;
    }

    /**
     * Check if an episode with the given GUID already exists for a podcast.
     * @param podcastId The podcast ID
     * @param guid The episode GUID
     * @return true if the episode exists, false otherwise
     */
    public boolean episodeExists(long podcastId, String guid) {
        if (guid == null || guid.isEmpty()) {
            return false;
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
            DatabaseHelper.TABLE_EPISODES,
            new String[]{DatabaseHelper.COL_EPISODE_ID},
            DatabaseHelper.COL_EPISODE_PODCAST_ID + " = ? AND " + DatabaseHelper.COL_EPISODE_GUID + " = ?",
            new String[]{String.valueOf(podcastId), guid},
            null,
            null,
            null
        );

        boolean exists = false;
        if (cursor != null) {
            exists = cursor.getCount() > 0;
            cursor.close();
        }

        return exists;
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
        if (episode.getArtworkUrl() != null) {
            values.put(DatabaseHelper.COL_EPISODE_ARTWORK_URL, episode.getArtworkUrl());
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

        int artworkUrlIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_EPISODE_ARTWORK_URL);
        if (!cursor.isNull(artworkUrlIndex)) {
            episode.setArtworkUrl(cursor.getString(artworkUrlIndex));
        }

        return episode;
    }
}
