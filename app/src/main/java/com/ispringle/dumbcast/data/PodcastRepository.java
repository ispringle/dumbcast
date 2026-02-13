package com.ispringle.dumbcast.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.ispringle.dumbcast.utils.RssFeed;
import com.ispringle.dumbcast.utils.RssParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository for managing podcast subscriptions and RSS feed operations.
 * Handles CRUD operations for podcasts, RSS feed fetching/parsing,
 * and episode insertion with refresh frequency limiting.
 */
public class PodcastRepository {

    private static final String TAG = "PodcastRepository";
    private static final long ONE_HOUR_MS = 60 * 60 * 1000L;
    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000L;

    private final DatabaseHelper dbHelper;
    private final EpisodeRepository episodeRepository;
    private final RssParser rssParser;

    public PodcastRepository(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
        this.episodeRepository = new EpisodeRepository(dbHelper);
        this.rssParser = new RssParser();
    }

    /**
     * Insert a new podcast into the database.
     * @param podcast The podcast to insert
     * @return The row ID of the newly inserted podcast, or -1 if an error occurred
     */
    public long insertPodcast(Podcast podcast) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = podcastToContentValues(podcast);
        long id = db.insert(DatabaseHelper.TABLE_PODCASTS, null, values);
        if (id != -1) {
            podcast.setId(id);
            Log.d(TAG, "Inserted podcast: " + podcast.getTitle() + " (ID: " + id + ")");
        } else {
            Log.e(TAG, "Failed to insert podcast: " + podcast.getTitle());
        }
        return id;
    }

    /**
     * Get a podcast by its ID.
     * @param id The podcast ID
     * @return The podcast, or null if not found
     */
    public Podcast getPodcastById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
            DatabaseHelper.TABLE_PODCASTS,
            null,
            DatabaseHelper.COL_PODCAST_ID + " = ?",
            new String[]{String.valueOf(id)},
            null,
            null,
            null
        );

        Podcast podcast = null;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                podcast = cursorToPodcast(cursor);
            }
            cursor.close();
        }
        return podcast;
    }

    /**
     * Get all podcasts from the database.
     * @return List of all podcasts, ordered by creation date (newest first)
     */
    public List<Podcast> getAllPodcasts() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Podcast> podcasts = new ArrayList<>();

        Cursor cursor = db.query(
            DatabaseHelper.TABLE_PODCASTS,
            null,
            null,
            null,
            null,
            null,
            DatabaseHelper.COL_PODCAST_CREATED + " DESC"
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                podcasts.add(cursorToPodcast(cursor));
            }
            cursor.close();
        }

        Log.d(TAG, "Retrieved " + podcasts.size() + " podcasts");
        return podcasts;
    }

    /**
     * Get a podcast by its feed URL.
     * @param feedUrl The RSS feed URL
     * @return The podcast if found, null otherwise
     */
    public Podcast getPodcastByFeedUrl(String feedUrl) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
            DatabaseHelper.TABLE_PODCASTS,
            null,
            DatabaseHelper.COL_PODCAST_FEED_URL + " = ?",
            new String[]{feedUrl},
            null,
            null,
            null
        );

        Podcast podcast = null;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                podcast = cursorToPodcast(cursor);
            }
            cursor.close();
        }
        return podcast;
    }

    /**
     * Delete a podcast and all its episodes from the database.
     * Foreign key constraints will cascade the delete to episodes.
     * @param podcastId The ID of the podcast to delete
     */
    public void deletePodcast(long podcastId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsDeleted = db.delete(
            DatabaseHelper.TABLE_PODCASTS,
            DatabaseHelper.COL_PODCAST_ID + " = ?",
            new String[]{String.valueOf(podcastId)}
        );
        if (rowsDeleted > 0) {
            Log.d(TAG, "Deleted podcast ID: " + podcastId);
        } else {
            Log.w(TAG, "No podcast found with ID: " + podcastId);
        }
    }

    /**
     * Refresh a podcast's feed if it hasn't been refreshed in the last hour.
     * Fetches RSS feed, parses it, and inserts new episodes.
     * Updates podcast metadata from feed.
     * @param podcastId The ID of the podcast to refresh
     * @throws IOException If network or I/O error occurs
     * @throws XmlPullParserException If XML parsing error occurs
     */
    public void refreshPodcast(long podcastId) throws IOException, XmlPullParserException {
        Podcast podcast = getPodcastById(podcastId);
        if (podcast == null) {
            Log.e(TAG, "Cannot refresh: Podcast not found (ID: " + podcastId + ")");
            return;
        }

        if (!shouldRefresh(podcast)) {
            Log.d(TAG, "Skipping refresh for " + podcast.getTitle() +
                  " (last refresh was less than 1 hour ago)");
            return;
        }

        Log.d(TAG, "Refreshing podcast: " + podcast.getTitle());

        // Fetch and parse RSS feed
        RssFeed feed = fetchFeed(podcast.getFeedUrl());

        // Update podcast metadata from feed
        updatePodcastFromFeed(podcast, feed);

        // Insert new episodes from feed
        insertEpisodesFromFeed(podcastId, feed);

        // Update last refresh timestamp
        updateLastRefresh(podcastId);

        Log.d(TAG, "Successfully refreshed podcast: " + podcast.getTitle());
    }

    /**
     * Refresh all podcasts that are due for refresh (last refresh > 1 hour ago).
     * @throws IOException If network or I/O error occurs
     * @throws XmlPullParserException If XML parsing error occurs
     */
    public void refreshAllPodcasts() throws IOException, XmlPullParserException {
        List<Podcast> podcasts = getAllPodcasts();
        Log.d(TAG, "Refreshing all podcasts (" + podcasts.size() + " total)");

        int refreshedCount = 0;
        for (Podcast podcast : podcasts) {
            try {
                if (shouldRefresh(podcast)) {
                    refreshPodcast(podcast.getId());
                    refreshedCount++;
                }
            } catch (IOException | XmlPullParserException e) {
                Log.e(TAG, "Failed to refresh podcast: " + podcast.getTitle(), e);
                // Continue with other podcasts
            }
        }

        Log.d(TAG, "Refreshed " + refreshedCount + " out of " + podcasts.size() + " podcasts");
    }

    /**
     * Check if a podcast should be refreshed based on last refresh time.
     * Refreshes are limited to once per hour.
     * @param podcast The podcast to check
     * @return true if podcast should be refreshed, false otherwise
     */
    private boolean shouldRefresh(Podcast podcast) {
        long lastRefresh = podcast.getLastRefreshAt();
        if (lastRefresh == 0) {
            // Never refreshed before
            return true;
        }

        long now = System.currentTimeMillis();
        long timeSinceRefresh = now - lastRefresh;
        return timeSinceRefresh > ONE_HOUR_MS;
    }

    /**
     * Fetch RSS feed from the given URL using HttpURLConnection.
     * @param feedUrl The URL of the RSS feed
     * @return Parsed RssFeed object
     * @throws IOException If network or I/O error occurs
     * @throws XmlPullParserException If XML parsing error occurs
     */
    private RssFeed fetchFeed(String feedUrl) throws IOException, XmlPullParserException {
        URL url = new URL(feedUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Dumbcast/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error code: " + responseCode);
            }

            InputStream inputStream = connection.getInputStream();
            return rssParser.parse(inputStream);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Update podcast metadata from RSS feed data.
     * @param podcast The podcast to update
     * @param feed The RSS feed data
     */
    private void updatePodcastFromFeed(Podcast podcast, RssFeed feed) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();

        // Update title and description if available
        if (feed.getTitle() != null && !feed.getTitle().isEmpty()) {
            values.put(DatabaseHelper.COL_PODCAST_TITLE, feed.getTitle());
            podcast.setTitle(feed.getTitle());
        }

        if (feed.getDescription() != null && !feed.getDescription().isEmpty()) {
            values.put(DatabaseHelper.COL_PODCAST_DESCRIPTION, feed.getDescription());
            podcast.setDescription(feed.getDescription());
        }

        if (feed.getImageUrl() != null && !feed.getImageUrl().isEmpty()) {
            values.put(DatabaseHelper.COL_PODCAST_ARTWORK_URL, feed.getImageUrl());
            podcast.setArtworkUrl(feed.getImageUrl());
        }

        if (values.size() > 0) {
            db.update(
                DatabaseHelper.TABLE_PODCASTS,
                values,
                DatabaseHelper.COL_PODCAST_ID + " = ?",
                new String[]{String.valueOf(podcast.getId())}
            );
        }
    }

    /**
     * Insert new episodes from RSS feed into the database.
     * Checks for existing episodes by podcast_id + guid to avoid duplicates.
     * Sets session grace for episodes published more than 7 days ago on first fetch.
     * @param podcastId The ID of the podcast
     * @param feed The RSS feed containing episodes
     */
    private void insertEpisodesFromFeed(long podcastId, RssFeed feed) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long now = System.currentTimeMillis();
        int newEpisodeCount = 0;

        db.beginTransaction();
        try {
            for (RssFeed.RssItem item : feed.getItems()) {
                // Skip items without required fields
                if (item.getGuid() == null || item.getTitle() == null ||
                    item.getEnclosureUrl() == null) {
                    Log.w(TAG, "Skipping item with missing required fields");
                    continue;
                }

                // Check if episode already exists
                if (episodeExists(podcastId, item.getGuid())) {
                    continue;
                }

                // Create new episode
                Episode episode = new Episode(
                    podcastId,
                    item.getGuid(),
                    item.getTitle(),
                    item.getEnclosureUrl(),
                    item.getPublishedAt()
                );

                // Set optional fields
                episode.setDescription(item.getDescription());
                episode.setEnclosureType(item.getEnclosureType());
                episode.setEnclosureLength(item.getEnclosureLength());
                episode.setDuration(item.getDuration());
                episode.setChaptersUrl(item.getChaptersUrl());
                episode.setFetchedAt(now);

                // Set session grace for old episodes (published > 7 days ago)
                long publishedAt = item.getPublishedAt();
                if (publishedAt > 0 && (now - publishedAt) > SEVEN_DAYS_MS) {
                    episode.setSessionGrace(true);
                }

                // Insert episode
                long episodeId = episodeRepository.insertEpisode(episode);
                if (episodeId != -1) {
                    newEpisodeCount++;
                }
            }

            db.setTransactionSuccessful();
            Log.d(TAG, "Inserted " + newEpisodeCount + " new episodes for podcast ID: " + podcastId);
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Check if an episode already exists in the database.
     * @param podcastId The podcast ID
     * @param guid The episode GUID
     * @return true if episode exists, false otherwise
     */
    private boolean episodeExists(long podcastId, String guid) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
            DatabaseHelper.TABLE_EPISODES,
            new String[]{DatabaseHelper.COL_EPISODE_ID},
            DatabaseHelper.COL_EPISODE_PODCAST_ID + " = ? AND " +
            DatabaseHelper.COL_EPISODE_GUID + " = ?",
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
     * Update the last refresh timestamp for a podcast.
     * @param podcastId The ID of the podcast
     */
    private void updateLastRefresh(long podcastId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_PODCAST_LAST_REFRESH, System.currentTimeMillis());

        db.update(
            DatabaseHelper.TABLE_PODCASTS,
            values,
            DatabaseHelper.COL_PODCAST_ID + " = ?",
            new String[]{String.valueOf(podcastId)}
        );
    }

    /**
     * Convert a Podcast object to ContentValues for database insertion/update.
     * @param podcast The podcast to convert
     * @return ContentValues containing podcast data
     */
    private ContentValues podcastToContentValues(Podcast podcast) {
        ContentValues values = new ContentValues();

        values.put(DatabaseHelper.COL_PODCAST_FEED_URL, podcast.getFeedUrl());
        values.put(DatabaseHelper.COL_PODCAST_TITLE, podcast.getTitle());
        values.put(DatabaseHelper.COL_PODCAST_CREATED, podcast.getCreatedAt());

        // Nullable fields
        if (podcast.getDescription() != null) {
            values.put(DatabaseHelper.COL_PODCAST_DESCRIPTION, podcast.getDescription());
        }
        if (podcast.getArtworkUrl() != null) {
            values.put(DatabaseHelper.COL_PODCAST_ARTWORK_URL, podcast.getArtworkUrl());
        }
        if (podcast.getPodcastIndexId() != null) {
            values.put(DatabaseHelper.COL_PODCAST_INDEX_ID, podcast.getPodcastIndexId());
        }
        if (podcast.getLastRefreshAt() > 0) {
            values.put(DatabaseHelper.COL_PODCAST_LAST_REFRESH, podcast.getLastRefreshAt());
        }

        return values;
    }

    /**
     * Convert a database cursor to a Podcast object.
     * @param cursor The cursor pointing to podcast data
     * @return Podcast object populated from cursor data
     */
    private Podcast cursorToPodcast(Cursor cursor) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PODCAST_ID));
        String feedUrl = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PODCAST_FEED_URL));
        String title = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PODCAST_TITLE));

        Podcast podcast = new Podcast(id, feedUrl, title);

        // Set created timestamp
        podcast.setCreatedAt(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PODCAST_CREATED)));

        // Set optional fields
        int descIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PODCAST_DESCRIPTION);
        if (!cursor.isNull(descIndex)) {
            podcast.setDescription(cursor.getString(descIndex));
        }

        int artworkIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PODCAST_ARTWORK_URL);
        if (!cursor.isNull(artworkIndex)) {
            podcast.setArtworkUrl(cursor.getString(artworkIndex));
        }

        int indexIdIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PODCAST_INDEX_ID);
        if (!cursor.isNull(indexIdIndex)) {
            podcast.setPodcastIndexId(cursor.getLong(indexIdIndex));
        }

        int lastRefreshIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PODCAST_LAST_REFRESH);
        if (!cursor.isNull(lastRefreshIndex)) {
            podcast.setLastRefreshAt(cursor.getLong(lastRefreshIndex));
        }

        return podcast;
    }
}
