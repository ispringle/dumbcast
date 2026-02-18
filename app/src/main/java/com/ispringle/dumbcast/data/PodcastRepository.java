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
     * Fetch episodes for a newly subscribed podcast.
     * Sets all episodes to BACKLOG (no NEW pressure) and skips description storage.
     * @param podcastId The ID of the podcast
     * @throws IOException If network or I/O error occurs
     * @throws XmlPullParserException If XML parsing error occurs
     */
    public void fetchInitialEpisodes(long podcastId) throws IOException, XmlPullParserException {
        Podcast podcast = getPodcastById(podcastId);
        if (podcast == null) {
            Log.e(TAG, "Cannot fetch episodes: Podcast not found (ID: " + podcastId + ")");
            return;
        }

        Log.d(TAG, "Fetching initial episodes for new subscription: " + podcast.getTitle());

        // Fetch and parse RSS feed
        RssFeed feed = fetchFeed(podcast.getFeedUrl());

        // Update podcast metadata from feed
        updatePodcastFromFeed(podcast, feed);

        // Insert episodes with initial subscription flag (all BACKLOG, no descriptions)
        insertEpisodesFromFeed(podcastId, feed, 10, true);

        // Update last refresh timestamp
        updateLastRefresh(podcastId);

        Log.d(TAG, "Successfully fetched initial episodes: " + podcast.getTitle());
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

        // Insert new episodes from feed (limit to 10 most recent, not initial subscription)
        insertEpisodesFromFeed(podcastId, feed, 10, false);

        // Update last refresh timestamp
        updateLastRefresh(podcastId);

        Log.d(TAG, "Successfully refreshed podcast: " + podcast.getTitle());
    }

    /**
     * Refresh a podcast's feed with a custom episode limit.
     * Use this for "Load More Episodes" functionality.
     * @param podcastId The ID of the podcast to refresh
     * @param maxNewEpisodes Maximum number of new episodes to fetch (0 = unlimited)
     * @throws IOException If network or I/O error occurs
     * @throws XmlPullParserException If XML parsing error occurs
     */
    public void refreshPodcastWithLimit(long podcastId, int maxNewEpisodes) throws IOException, XmlPullParserException {
        Podcast podcast = getPodcastById(podcastId);
        if (podcast == null) {
            Log.e(TAG, "Cannot refresh: Podcast not found (ID: " + podcastId + ")");
            return;
        }

        Log.d(TAG, "Refreshing podcast with limit " + maxNewEpisodes + ": " + podcast.getTitle());

        // Fetch and parse RSS feed
        RssFeed feed = fetchFeed(podcast.getFeedUrl());

        // Update podcast metadata from feed
        updatePodcastFromFeed(podcast, feed);

        // Insert new episodes from feed with custom limit (not initial subscription)
        insertEpisodesFromFeed(podcastId, feed, maxNewEpisodes, false);

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
        return fetchFeedWithRedirects(feedUrl, 5); // Allow up to 5 redirects
    }

    /**
     * Fetch RSS feed with manual redirect following.
     * HttpURLConnection doesn't follow HTTP -> HTTPS redirects by default.
     */
    private RssFeed fetchFeedWithRedirects(String feedUrl, int maxRedirects) throws IOException, XmlPullParserException {
        if (maxRedirects <= 0) {
            throw new IOException("Too many redirects");
        }

        URL url = new URL(feedUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Dumbcast/1.0");
            connection.setInstanceFollowRedirects(false); // Handle redirects manually

            int responseCode = connection.getResponseCode();

            // Handle redirects (301, 302, 303, 307, 308)
            if (responseCode >= 300 && responseCode < 400) {
                String newUrl = connection.getHeaderField("Location");
                if (newUrl == null) {
                    throw new IOException("Redirect with no Location header");
                }

                Log.d(TAG, "Following redirect: " + feedUrl + " -> " + newUrl);
                connection.disconnect();
                return fetchFeedWithRedirects(newUrl, maxRedirects - 1);
            }

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
     * @param maxNewEpisodes Maximum number of NEW episodes to insert (0 = unlimited)
     * @param isInitialSubscription If true, all episodes go to BACKLOG (no NEW pressure), no descriptions stored
     */
    private void insertEpisodesFromFeed(long podcastId, RssFeed feed, int maxNewEpisodes, boolean isInitialSubscription) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long now = System.currentTimeMillis();
        int newEpisodeCount = 0;
        int skippedCount = 0;
        int consecutiveDuplicates = 0;
        final int DUPLICATE_THRESHOLD = 10; // Stop after 10 consecutive duplicates

        // Get last refresh timestamp for timestamp-based filtering on refreshes
        long lastRefreshAt = 0;
        if (!isInitialSubscription) {
            Podcast podcast = getPodcastById(podcastId);
            if (podcast != null) {
                lastRefreshAt = podcast.getLastRefreshAt();
            }
        }

        Log.d(TAG, "Processing " + feed.getItems().size() + " items from feed for podcast ID: " + podcastId + " (max new: " + maxNewEpisodes + ", lastRefresh: " + lastRefreshAt + ")");

        db.beginTransaction();
        try {
            for (RssFeed.RssItem item : feed.getItems()) {
                // Stop processing if we've hit too many consecutive duplicates
                // This prevents re-processing thousands of old episodes on refresh
                if (consecutiveDuplicates >= DUPLICATE_THRESHOLD) {
                    Log.d(TAG, "Stopping early: encountered " + DUPLICATE_THRESHOLD + " consecutive duplicates (total processed: " + (newEpisodeCount + skippedCount) + ")");
                    break;
                }

                // Stop processing if we've reached the maximum number of new episodes
                if (maxNewEpisodes > 0 && newEpisodeCount >= maxNewEpisodes) {
                    Log.d(TAG, "Stopping: reached maximum new episodes limit of " + maxNewEpisodes);
                    break;
                }

                // For refreshes (not initial subscription), skip episodes published before last refresh
                // This prevents processing hundreds of old episodes when only checking for new ones
                if (!isInitialSubscription && lastRefreshAt > 0 && item.getPublishedAt() > 0 && item.getPublishedAt() < lastRefreshAt) {
                    Log.d(TAG, "Skipping old episode (published before last refresh): " + item.getTitle());
                    skippedCount++;
                    continue;
                }
                // Only require title - GUID and enclosureUrl are now optional
                if (item.getTitle() == null || item.getTitle().trim().isEmpty()) {
                    Log.w(TAG, "Skipping item without title");
                    skippedCount++;
                    continue;
                }

                // Generate GUID if missing
                String guid = item.getGuid();
                if (guid == null || guid.trim().isEmpty()) {
                    if (item.getEnclosureUrl() != null && !item.getEnclosureUrl().trim().isEmpty()) {
                        // Use enclosure URL as GUID
                        guid = "url:" + item.getEnclosureUrl();
                        Log.d(TAG, "Generated GUID from enclosure URL for: " + item.getTitle());
                    } else if (item.getPublishedAt() > 0) {
                        // Use title + publishedAt as GUID
                        guid = "title-date:" + item.getTitle() + ":" + item.getPublishedAt();
                        Log.d(TAG, "Generated GUID from title+date for: " + item.getTitle());
                    } else {
                        // Last resort: just use title
                        guid = "title:" + item.getTitle();
                        Log.d(TAG, "Generated GUID from title for: " + item.getTitle());
                    }
                }

                // Check if episode already exists
                if (episodeExists(podcastId, guid)) {
                    Log.d(TAG, "Episode already exists, skipping: " + item.getTitle());
                    skippedCount++;
                    consecutiveDuplicates++;
                    continue;
                }

                // Reset consecutive duplicate counter when we find a new episode
                consecutiveDuplicates = 0;

                // Create new episode with potentially generated GUID
                Episode episode = new Episode(
                    podcastId,
                    guid,
                    item.getTitle(),
                    item.getEnclosureUrl(),  // Can be null now
                    item.getPublishedAt()
                );

                // Set optional fields
                episode.setEnclosureType(item.getEnclosureType());
                episode.setEnclosureLength(item.getEnclosureLength());
                episode.setDuration(item.getDuration());
                episode.setChaptersUrl(item.getChaptersUrl());
                episode.setFetchedAt(now);

                long publishedAt = item.getPublishedAt();
                boolean isOldEpisode = publishedAt > 0 && (now - publishedAt) > SEVEN_DAYS_MS;

                if (isInitialSubscription) {
                    // Initial subscription: Give session grace (old episodes won't bug you)
                    // No description stored (saves ~5-10KB per episode)
                    // Set to AVAILABLE instead of NEW to avoid overwhelming user
                    episode.setSessionGrace(true);
                    episode.setState(EpisodeState.AVAILABLE);
                } else {
                    // Subsequent refresh: Recent episodes are NEW with description
                    if (isOldEpisode) {
                        episode.setSessionGrace(true);
                    } else {
                        // Store description for NEW episodes (likely to be read soon)
                        episode.setDescription(item.getDescription());
                    }
                }

                // Insert episode
                long episodeId = episodeRepository.insertEpisode(episode);
                if (episodeId != -1) {
                    newEpisodeCount++;
                    Log.d(TAG, "Inserted episode: " + episode.getTitle() + " (GUID: " + guid + ")");
                } else {
                    Log.w(TAG, "Failed to insert episode: " + episode.getTitle());
                    skippedCount++;
                }
            }

            db.setTransactionSuccessful();
            Log.d(TAG, "Inserted " + newEpisodeCount + " new episodes for podcast " + podcastId +
                  " (skipped " + skippedCount + " existing/invalid items)");
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
     * Toggle the reverse order setting for a podcast.
     * @param podcastId The ID of the podcast
     * @return true if the new state is reverse order, false otherwise
     */
    public boolean toggleReverseOrder(long podcastId) {
        Podcast podcast = getPodcastById(podcastId);
        if (podcast == null) {
            return false;
        }

        // Toggle the setting
        boolean newReverseOrder = !podcast.isReverseOrder();

        // Update database
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_PODCAST_REVERSE_ORDER, newReverseOrder ? 1 : 0);

        db.update(
            DatabaseHelper.TABLE_PODCASTS,
            values,
            DatabaseHelper.COL_PODCAST_ID + " = ?",
            new String[]{String.valueOf(podcastId)}
        );

        return newReverseOrder;
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
        values.put(DatabaseHelper.COL_PODCAST_REVERSE_ORDER, podcast.isReverseOrder() ? 1 : 0);

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

        // Set reverse order flag
        int reverseOrderIndex = cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PODCAST_REVERSE_ORDER);
        podcast.setReverseOrder(cursor.getInt(reverseOrderIndex) == 1);

        return podcast;
    }
}
