package com.ispringle.dumbcast.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.ispringle.dumbcast.utils.RssFeed;
import com.ispringle.dumbcast.utils.RssFeedUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int DUPLICATE_THRESHOLD = 10;

    /** How to select episodes from a feed. */
    private enum FetchMode {
        /** First subscribe: take a batch from newest or oldest end based on reverseOrder. */
        INITIAL,
        /** Hourly refresh: always scan newest-first for episodes newer than last refresh. */
        REFRESH,
        /** Load more history: insert missing episodes not already in the DB. */
        BACKFILL
    }

    private final DatabaseHelper dbHelper;
    private final EpisodeRepository episodeRepository;

    public PodcastRepository(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
        this.episodeRepository = new EpisodeRepository(dbHelper);
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
     * Subscribe to a podcast feed and fetch the initial episode batch.
     * Single entry point used by discovery (search, URL, audiobook).
     *
     * @param feedUrl Feed URL (required)
     * @param title Preferred title (may be overridden by feed)
     * @param description Optional description
     * @param artworkUrl Optional artwork
     * @param podcastIndexId Optional Podcast Index id
     * @param reverseOrder If true, initial batch is oldest-first (audiobooks/serials)
     * @return podcast row id, or -1 on insert failure
     */
    public long subscribe(String feedUrl, String title, String description, String artworkUrl,
                          Long podcastIndexId, boolean reverseOrder)
            throws IOException, XmlPullParserException {
        if (feedUrl == null || feedUrl.trim().isEmpty()) {
            throw new IOException("Feed URL is required");
        }

        Podcast existing = getPodcastByFeedUrl(feedUrl);
        if (existing != null) {
            Log.d(TAG, "Already subscribed to: " + feedUrl);
            return existing.getId();
        }

        RssFeed feed = RssFeedUtils.fetchFeed(feedUrl);

        String resolvedTitle = title;
        if (resolvedTitle == null || resolvedTitle.trim().isEmpty()) {
            resolvedTitle = feed.getTitle();
        }
        if (resolvedTitle == null || resolvedTitle.trim().isEmpty()) {
            throw new IOException("Feed has no title");
        }

        Podcast podcast = new Podcast(0, feedUrl, resolvedTitle);
        if (description != null && !description.isEmpty()) {
            podcast.setDescription(description);
        } else if (feed.getDescription() != null) {
            podcast.setDescription(feed.getDescription());
        }
        if (artworkUrl != null && !artworkUrl.isEmpty()) {
            podcast.setArtworkUrl(artworkUrl);
        } else if (feed.getImageUrl() != null) {
            podcast.setArtworkUrl(feed.getImageUrl());
        }
        if (podcastIndexId != null) {
            podcast.setPodcastIndexId(podcastIndexId);
        }
        podcast.setReverseOrder(reverseOrder);

        long podcastId = insertPodcast(podcast);
        if (podcastId == -1) {
            return -1;
        }

        // Prefer feed metadata when present
        updatePodcastFromFeed(podcast, feed);
        insertEpisodesFromFeed(podcastId, feed, DEFAULT_BATCH_SIZE, FetchMode.INITIAL, reverseOrder);
        updateLastRefresh(podcastId);

        Log.d(TAG, "Subscribed to " + resolvedTitle + " (reverseOrder=" + reverseOrder + ")");
        return podcastId;
    }

    /**
     * Fetch episodes for a newly subscribed podcast.
     * If reverse order is enabled, fetches oldest episodes first (for audiobooks/series).
     */
    public void fetchInitialEpisodes(long podcastId) throws IOException, XmlPullParserException {
        Podcast podcast = getPodcastById(podcastId);
        if (podcast == null) {
            Log.e(TAG, "Cannot fetch episodes: Podcast not found (ID: " + podcastId + ")");
            return;
        }

        Log.d(TAG, "Fetching initial episodes for new subscription: " + podcast.getTitle()
            + " reverseOrder=" + podcast.isReverseOrder());

        RssFeed feed = RssFeedUtils.fetchFeed(podcast.getFeedUrl());
        updatePodcastFromFeed(podcast, feed);
        insertEpisodesFromFeed(podcastId, feed, DEFAULT_BATCH_SIZE, FetchMode.INITIAL, podcast.isReverseOrder());
        updateLastRefresh(podcastId);

        Log.d(TAG, "Successfully fetched initial episodes: " + podcast.getTitle());
    }

    /**
     * Refresh a podcast's feed if it hasn't been refreshed in the last hour.
     * Always scans newest-first so reverse-order shows still get new episodes.
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

        RssFeed feed = RssFeedUtils.fetchFeed(podcast.getFeedUrl());
        updatePodcastFromFeed(podcast, feed);
        // reverseOrder does not affect refresh scanning — always newest-first for new eps
        insertEpisodesFromFeed(podcastId, feed, DEFAULT_BATCH_SIZE, FetchMode.REFRESH, false);
        updateLastRefresh(podcastId);

        Log.d(TAG, "Successfully refreshed podcast: " + podcast.getTitle());
    }

    /**
     * Load additional episodes from the feed that are not already in the database.
     * Ignores lastRefreshAt so historic episodes can be backfilled.
     *
     * @param podcastId Podcast to backfill
     * @param maxEpisodes Max new rows to insert (typically 10)
     * @return number of episodes actually inserted
     */
    public int loadOlderEpisodes(long podcastId, int maxEpisodes) throws IOException, XmlPullParserException {
        Podcast podcast = getPodcastById(podcastId);
        if (podcast == null) {
            Log.e(TAG, "Cannot load more: Podcast not found (ID: " + podcastId + ")");
            return 0;
        }

        int limit = maxEpisodes > 0 ? maxEpisodes : DEFAULT_BATCH_SIZE;
        Log.d(TAG, "Backfilling up to " + limit + " episodes for: " + podcast.getTitle());

        RssFeed feed = RssFeedUtils.fetchFeed(podcast.getFeedUrl());
        updatePodcastFromFeed(podcast, feed);
        int added = insertEpisodesFromFeed(podcastId, feed, limit, FetchMode.BACKFILL, podcast.isReverseOrder());
        // Do not bump lastRefreshAt on backfill — that would hide new-episode detection semantics

        Log.d(TAG, "Backfill added " + added + " episodes for: " + podcast.getTitle());
        return added;
    }

    /**
     * @deprecated Use {@link #loadOlderEpisodes(long, int)} for history.
     * Kept for callers that meant "refresh with a higher limit".
     */
    public void refreshPodcastWithLimit(long podcastId, int maxNewEpisodes) throws IOException, XmlPullParserException {
        loadOlderEpisodes(podcastId, maxNewEpisodes);
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
     * Insert episodes from an RSS feed.
     *
     * @param podcastId Podcast id
     * @param feed Parsed feed
     * @param maxNewEpisodes Cap on inserts (0 = unlimited)
     * @param mode INITIAL / REFRESH / BACKFILL
     * @param reverseOrder For INITIAL/BACKFILL: prefer oldest-first when true
     * @return number of episodes inserted
     */
    private int insertEpisodesFromFeed(long podcastId, RssFeed feed, int maxNewEpisodes,
                                       FetchMode mode, boolean reverseOrder) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long now = System.currentTimeMillis();
        int newEpisodeCount = 0;
        int consecutiveDuplicates = 0;

        long lastRefreshAt = 0;
        if (mode == FetchMode.REFRESH) {
            Podcast podcast = getPodcastById(podcastId);
            if (podcast != null) {
                lastRefreshAt = podcast.getLastRefreshAt();
            }
        }

        List<RssFeed.RssItem> items = prepareItemsForMode(feed, mode, reverseOrder);

        Log.d(TAG, "insertEpisodes mode=" + mode + " reverseOrder=" + reverseOrder
            + " items=" + items.size() + " max=" + maxNewEpisodes
            + " lastRefreshAt=" + lastRefreshAt);

        db.beginTransaction();
        try {
            for (RssFeed.RssItem item : items) {
                if (mode == FetchMode.REFRESH && consecutiveDuplicates >= DUPLICATE_THRESHOLD) {
                    Log.d(TAG, "Stopping refresh: " + DUPLICATE_THRESHOLD + " consecutive duplicates");
                    break;
                }
                if (maxNewEpisodes > 0 && newEpisodeCount >= maxNewEpisodes) {
                    break;
                }

                if (item.getTitle() == null || item.getTitle().trim().isEmpty()) {
                    continue;
                }

                // Refresh: only episodes published at/after last refresh (when date known)
                if (mode == FetchMode.REFRESH && lastRefreshAt > 0) {
                    long publishedAt = item.getPublishedAt();
                    if (publishedAt == 0) {
                        // Undated items cannot be filtered safely — skip on refresh
                        continue;
                    }
                    if (publishedAt < lastRefreshAt) {
                        continue;
                    }
                }

                String guid = resolveGuid(item);
                if (episodeExists(podcastId, guid)) {
                    consecutiveDuplicates++;
                    continue;
                }
                consecutiveDuplicates = 0;

                Episode episode = new Episode(
                    podcastId,
                    guid,
                    item.getTitle(),
                    item.getEnclosureUrl(),
                    item.getPublishedAt()
                );
                episode.setEnclosureType(item.getEnclosureType());
                episode.setEnclosureLength(item.getEnclosureLength());
                episode.setDuration(item.getDuration());
                episode.setChaptersUrl(item.getChaptersUrl());
                episode.setArtworkUrl(item.getImageUrl());
                episode.setFetchedAt(now);

                long publishedAt = item.getPublishedAt();
                boolean isOldEpisode = publishedAt > 0 && (now - publishedAt) > SEVEN_DAYS_MS;

                if (mode == FetchMode.INITIAL || mode == FetchMode.BACKFILL) {
                    // No NEW spam for catalog fills
                    episode.setSessionGrace(true);
                    episode.setState(EpisodeState.AVAILABLE);
                } else if (isOldEpisode) {
                    episode.setSessionGrace(true);
                    // REFRESH of truly new eps stays default NEW
                }

                long episodeId = episodeRepository.insertEpisode(episode);
                if (episodeId != -1) {
                    newEpisodeCount++;
                    Log.d(TAG, "Inserted episode: " + episode.getTitle());
                }
            }

            db.setTransactionSuccessful();
            Log.d(TAG, "insertEpisodes done mode=" + mode + " added=" + newEpisodeCount);
        } finally {
            db.endTransaction();
        }
        return newEpisodeCount;
    }

    /**
     * Order/filter feed items for the given fetch mode.
     * REFRESH always newest-first. INITIAL/BACKFILL use reverseOrder for which end to prefer.
     */
    private List<RssFeed.RssItem> prepareItemsForMode(RssFeed feed, FetchMode mode, boolean reverseOrder) {
        List<RssFeed.RssItem> items = new ArrayList<>(feed.getItems());

        // Sort by published date when available so order is predictable
        Collections.sort(items, new Comparator<RssFeed.RssItem>() {
            @Override
            public int compare(RssFeed.RssItem a, RssFeed.RssItem b) {
                long dateA = a.getPublishedAt();
                long dateB = b.getPublishedAt();
                if (dateA == dateB) {
                    return 0;
                }
                // Default sort: newest first
                return dateA > dateB ? -1 : 1;
            }
        });

        if (mode == FetchMode.REFRESH) {
            // Newest first (already sorted)
            return items;
        }

        // INITIAL and BACKFILL: reverseOrder means oldest-first catalog
        if (reverseOrder) {
            Collections.reverse(items);
        }
        return items;
    }

    private String resolveGuid(RssFeed.RssItem item) {
        String guid = item.getGuid();
        if (guid != null && !guid.trim().isEmpty()) {
            return guid;
        }
        if (item.getEnclosureUrl() != null && !item.getEnclosureUrl().trim().isEmpty()) {
            return "url:" + item.getEnclosureUrl();
        }
        if (item.getPublishedAt() > 0) {
            return "title-date:" + item.getTitle() + ":" + item.getPublishedAt();
        }
        return "title:" + item.getTitle();
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
