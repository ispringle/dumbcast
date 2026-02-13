package com.ispringle.dumbcast;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EpisodeStateTest {

    private Context context;
    private DatabaseHelper dbHelper;
    private EpisodeRepository repository;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getTargetContext();
        context.deleteDatabase("dumbcast.db");
        dbHelper = new DatabaseHelper(context);
        repository = new EpisodeRepository(dbHelper);
    }

    @After
    public void tearDown() {
        dbHelper.close();
        context.deleteDatabase("dumbcast.db");
    }

    @Test
    public void testDecayNewToAvailableAfter7Days() {
        // Create an episode fetched 8 days ago
        long eightDaysAgo = System.currentTimeMillis() - (8L * 24 * 60 * 60 * 1000);
        Episode episode = new Episode(1, "guid-1", "Test Episode", "http://example.com/ep1.mp3", eightDaysAgo);
        episode.setFetchedAt(eightDaysAgo);
        episode.setState(EpisodeState.NEW);
        episode.setSessionGrace(false);

        long episodeId = repository.insertEpisode(episode);

        // Run decay logic
        repository.decayNewEpisodes();

        // Verify episode decayed to AVAILABLE
        Episode updated = repository.getEpisodeById(episodeId);
        assertNotNull("Episode should exist", updated);
        assertEquals("Episode should decay to AVAILABLE after 7 days", EpisodeState.AVAILABLE, updated.getState());
    }

    @Test
    public void testNoDecayIfLessThan7Days() {
        // Create an episode fetched 5 days ago
        long fiveDaysAgo = System.currentTimeMillis() - (5L * 24 * 60 * 60 * 1000);
        Episode episode = new Episode(1, "guid-2", "Recent Episode", "http://example.com/ep2.mp3", fiveDaysAgo);
        episode.setFetchedAt(fiveDaysAgo);
        episode.setState(EpisodeState.NEW);
        episode.setSessionGrace(false);

        long episodeId = repository.insertEpisode(episode);

        // Run decay logic
        repository.decayNewEpisodes();

        // Verify episode stays NEW
        Episode updated = repository.getEpisodeById(episodeId);
        assertNotNull("Episode should exist", updated);
        assertEquals("Episode should stay NEW if less than 7 days old", EpisodeState.NEW, updated.getState());
    }

    @Test
    public void testSessionGraceDecay() {
        // Create an episode with sessionGrace=true, fetched any time
        long now = System.currentTimeMillis();
        Episode episode = new Episode(1, "guid-3", "Session Grace Episode", "http://example.com/ep3.mp3", now);
        episode.setFetchedAt(now);
        episode.setState(EpisodeState.NEW);
        episode.setSessionGrace(true);

        long episodeId = repository.insertEpisode(episode);

        // Run decay logic
        repository.decayNewEpisodes();

        // Verify episode decayed to AVAILABLE and sessionGrace cleared
        Episode updated = repository.getEpisodeById(episodeId);
        assertNotNull("Episode should exist", updated);
        assertEquals("Episode with sessionGrace should decay to AVAILABLE", EpisodeState.AVAILABLE, updated.getState());
        assertFalse("sessionGrace flag should be cleared after decay", updated.isSessionGrace());
    }
}
