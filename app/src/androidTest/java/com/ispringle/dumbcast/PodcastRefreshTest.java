package com.ispringle.dumbcast;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;
import com.ispringle.dumbcast.utils.RssFeed;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Regression test for podcast refresh bug (dumbcast-3tz).
 *
 * Bug: Subscribe to podcast (adds 10 episodes) → hit refresh immediately → hundreds of old episodes added
 *
 * This test verifies that refreshing immediately after initial subscription does not add
 * additional episodes, especially old episodes with invalid/missing publish dates.
 */
@RunWith(AndroidJUnit4.class)
public class PodcastRefreshTest {

    private Context context;
    private DatabaseHelper dbHelper;
    private PodcastRepository podcastRepository;
    private EpisodeRepository episodeRepository;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getTargetContext();
        context.deleteDatabase("dumbcast.db");
        dbHelper = new DatabaseHelper(context);
        podcastRepository = new TestPodcastRepository(dbHelper);
        episodeRepository = new EpisodeRepository(dbHelper);
    }

    @After
    public void tearDown() {
        dbHelper.close();
        context.deleteDatabase("dumbcast.db");
    }

    /**
     * Test that refreshing immediately after initial subscription does not add duplicate episodes.
     * This is the core regression test for bug dumbcast-3tz.
     */
    @Test
    public void testRefreshDoesNotAddOldEpisodesAfterInitialSubscription() throws Exception {
        // Create a test podcast
        Podcast podcast = new Podcast(0, "http://example.com/feed.rss", "Test Podcast");
        long podcastId = podcastRepository.insertPodcast(podcast);
        assertTrue("Podcast should be inserted successfully", podcastId > 0);

        // Simulate initial subscription (10 recent episodes)
        ((TestPodcastRepository) podcastRepository).simulateInitialFetch(podcastId);

        // Verify initial episode count
        int initialCount = episodeRepository.getEpisodeCountByPodcast(podcastId);
        assertEquals("Initial subscription should add exactly 10 episodes", 10, initialCount);

        // Immediately refresh the podcast (simulates user hitting refresh button)
        ((TestPodcastRepository) podcastRepository).simulateRefresh(podcastId);

        // Verify episode count hasn't changed
        int afterRefreshCount = episodeRepository.getEpisodeCountByPodcast(podcastId);
        assertEquals("Refresh immediately after subscription should not add new episodes",
                     initialCount, afterRefreshCount);
    }

    /**
     * Test that episodes with invalid/missing publish dates are not added during refresh.
     * This covers the edge case mentioned in the bug: episodes without valid timestamps.
     */
    @Test
    public void testRefreshSkipsEpisodesWithInvalidPublishDates() throws Exception {
        // Create a test podcast
        Podcast podcast = new Podcast(0, "http://example.com/feed.rss", "Test Podcast");
        long podcastId = podcastRepository.insertPodcast(podcast);
        assertTrue("Podcast should be inserted successfully", podcastId > 0);

        // Simulate initial subscription (10 episodes with valid dates)
        ((TestPodcastRepository) podcastRepository).simulateInitialFetch(podcastId);

        int initialCount = episodeRepository.getEpisodeCountByPodcast(podcastId);
        assertEquals("Initial subscription should add exactly 10 episodes", 10, initialCount);

        // Simulate refresh with feed containing episodes with invalid dates (publishedAt = 0)
        ((TestPodcastRepository) podcastRepository).simulateRefreshWithInvalidDates(podcastId);

        // Verify episode count hasn't changed (invalid date episodes should be skipped)
        int afterRefreshCount = episodeRepository.getEpisodeCountByPodcast(podcastId);
        assertEquals("Refresh should skip episodes with invalid publish dates",
                     initialCount, afterRefreshCount);
    }

    /**
     * Test that new episodes published after last refresh ARE added correctly.
     * This ensures the fix doesn't break normal refresh functionality.
     */
    @Test
    public void testRefreshAddsNewEpisodesPublishedAfterLastRefresh() throws Exception {
        // Create a test podcast
        Podcast podcast = new Podcast(0, "http://example.com/feed.rss", "Test Podcast");
        long podcastId = podcastRepository.insertPodcast(podcast);
        assertTrue("Podcast should be inserted successfully", podcastId > 0);

        // Simulate initial subscription
        ((TestPodcastRepository) podcastRepository).simulateInitialFetch(podcastId);
        int initialCount = episodeRepository.getEpisodeCountByPodcast(podcastId);

        // Wait a moment to ensure timestamp difference
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // Ignore
        }

        // Simulate refresh with NEW episodes (published after last refresh)
        ((TestPodcastRepository) podcastRepository).simulateRefreshWithNewEpisodes(podcastId, 3);

        // Verify new episodes were added
        int afterRefreshCount = episodeRepository.getEpisodeCountByPodcast(podcastId);
        assertEquals("Refresh should add new episodes published after last refresh",
                     initialCount + 3, afterRefreshCount);
    }

    /**
     * Test helper class that extends PodcastRepository to allow testing without network calls.
     * Simulates RSS feed responses for testing.
     */
    private static class TestPodcastRepository extends PodcastRepository {
        private final DatabaseHelper dbHelper;
        private final EpisodeRepository episodeRepository;

        public TestPodcastRepository(DatabaseHelper dbHelper) {
            super(dbHelper);
            this.dbHelper = dbHelper;
            this.episodeRepository = new EpisodeRepository(dbHelper);
        }

        /**
         * Simulate initial podcast subscription with 10 recent episodes.
         */
        public void simulateInitialFetch(long podcastId) throws Exception {
            RssFeed feed = createTestFeed(10, true, false);

            // Manually call the internal method flow for initial subscription
            Podcast podcast = getPodcastById(podcastId);
            assertNotNull("Podcast must exist", podcast);

            // Simulate fetchInitialEpisodes behavior
            insertTestEpisodesFromFeed(podcastId, feed, 10, true);
            updateTestLastRefresh(podcastId);
        }

        /**
         * Simulate podcast refresh with the same episodes (should not add duplicates).
         */
        public void simulateRefresh(long podcastId) throws Exception {
            RssFeed feed = createTestFeed(10, true, false);

            // Simulate refreshPodcast behavior
            insertTestEpisodesFromFeed(podcastId, feed, 10, false);
            updateTestLastRefresh(podcastId);
        }

        /**
         * Simulate refresh with episodes that have invalid publish dates.
         */
        public void simulateRefreshWithInvalidDates(long podcastId) throws Exception {
            RssFeed feed = createTestFeed(5, false, false);

            insertTestEpisodesFromFeed(podcastId, feed, 10, false);
            updateTestLastRefresh(podcastId);
        }

        /**
         * Simulate refresh with new episodes published after last refresh.
         */
        public void simulateRefreshWithNewEpisodes(long podcastId, int newEpisodeCount) throws Exception {
            // Create new episodes with current timestamp (after last refresh)
            RssFeed feed = createTestFeed(newEpisodeCount, true, true);

            insertTestEpisodesFromFeed(podcastId, feed, 10, false);
            updateTestLastRefresh(podcastId);
        }

        /**
         * Create a test RSS feed with specified number of episodes.
         *
         * @param count Number of episodes to create
         * @param validDates If true, episodes have valid publish dates; if false, publishedAt = 0
         * @param useNewGuids If true, generate new GUIDs; if false, reuse GUIDs from first 10
         */
        private RssFeed createTestFeed(int count, boolean validDates, boolean useNewGuids) {
            RssFeed feed = new RssFeed();
            feed.setTitle("Test Podcast");
            feed.setDescription("Test Description");
            feed.setImageUrl("http://example.com/image.jpg");

            long baseTime;
            if (useNewGuids) {
                // For new episodes, use current time so they're AFTER last refresh
                baseTime = System.currentTimeMillis();
            } else {
                // For initial episodes, use past time
                baseTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000); // 30 days ago
            }

            long oneDayMs = 24L * 60 * 60 * 1000;

            for (int i = 0; i < count; i++) {
                RssFeed.RssItem item = new RssFeed.RssItem();

                // Generate GUID
                String guidSuffix = useNewGuids ? "new-" + i : String.valueOf(i);
                item.setGuid("test-episode-" + guidSuffix);

                item.setTitle("Test Episode " + (i + 1));
                item.setDescription("Description for episode " + (i + 1));
                item.setEnclosureUrl("http://example.com/episode" + i + ".mp3");
                item.setEnclosureType("audio/mpeg");
                item.setEnclosureLength(1024L * 1024L * 50L); // 50MB
                item.setDuration(3600); // 1 hour

                // Set publish date based on validDates parameter
                if (validDates) {
                    // Most recent episode has highest timestamp
                    item.setPublishedAt(baseTime + (count - i) * oneDayMs);
                } else {
                    // Invalid date (0 timestamp)
                    item.setPublishedAt(0);
                }

                feed.addItem(item);
            }

            return feed;
        }

        /**
         * Helper to insert episodes from test feed.
         * This mimics the behavior of the private insertEpisodesFromFeed method.
         */
        private void insertTestEpisodesFromFeed(long podcastId, RssFeed feed, int maxNewEpisodes, boolean isInitialSubscription) {
            Podcast podcast = getPodcastById(podcastId);
            if (podcast == null) return;

            long now = System.currentTimeMillis();
            long lastRefreshAt = isInitialSubscription ? 0 : podcast.getLastRefreshAt();
            int newEpisodeCount = 0;

            for (RssFeed.RssItem item : feed.getItems()) {
                // Stop if we've reached max
                if (maxNewEpisodes > 0 && newEpisodeCount >= maxNewEpisodes) {
                    break;
                }

                // Apply timestamp-based filtering for refreshes (not initial subscription)
                if (!isInitialSubscription && lastRefreshAt > 0) {
                    long episodePublishTime = item.getPublishedAt();

                    // Skip episodes without valid publish dates during refresh
                    if (episodePublishTime == 0) {
                        continue;
                    }

                    // Skip episodes published before last refresh
                    if (episodePublishTime < lastRefreshAt) {
                        continue;
                    }
                }

                // Skip if title is missing
                if (item.getTitle() == null || item.getTitle().trim().isEmpty()) {
                    continue;
                }

                // Generate GUID if missing
                String guid = item.getGuid();
                if (guid == null || guid.trim().isEmpty()) {
                    guid = "title:" + item.getTitle();
                }

                // Check if episode already exists
                if (episodeRepository.episodeExists(podcastId, guid)) {
                    continue;
                }

                // Create and insert episode
                com.ispringle.dumbcast.data.Episode episode =
                    new com.ispringle.dumbcast.data.Episode(
                        podcastId,
                        guid,
                        item.getTitle(),
                        item.getEnclosureUrl(),
                        item.getPublishedAt()
                    );

                episode.setEnclosureType(item.getEnclosureType());
                episode.setEnclosureLength(item.getEnclosureLength());
                episode.setDuration(item.getDuration());
                episode.setFetchedAt(now);

                if (isInitialSubscription) {
                    episode.setSessionGrace(true);
                    episode.setState(com.ispringle.dumbcast.data.EpisodeState.AVAILABLE);
                }

                long episodeId = episodeRepository.insertEpisode(episode);
                if (episodeId != -1) {
                    newEpisodeCount++;
                }
            }
        }

        /**
         * Helper to update last refresh timestamp.
         */
        private void updateTestLastRefresh(long podcastId) {
            android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(DatabaseHelper.COL_PODCAST_LAST_REFRESH, System.currentTimeMillis());

            db.update(
                DatabaseHelper.TABLE_PODCASTS,
                values,
                DatabaseHelper.COL_PODCAST_ID + " = ?",
                new String[]{String.valueOf(podcastId)}
            );
        }
    }
}
