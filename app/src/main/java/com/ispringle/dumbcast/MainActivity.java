package com.ispringle.dumbcast;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.view.KeyEvent;
import android.widget.TextView;

import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;
import com.ispringle.dumbcast.data.PodcastRepository;
import com.ispringle.dumbcast.fragments.DiscoveryFragment;
import com.ispringle.dumbcast.fragments.EpisodeListFragment;
import com.ispringle.dumbcast.fragments.NewFragment;
import com.ispringle.dumbcast.fragments.PlayerFragment;
import com.ispringle.dumbcast.fragments.SubscriptionsFragment;
import com.ispringle.dumbcast.services.PlaybackService;

/**
 * Main activity providing tabbed navigation for the podcast app.
 * Tabs: New, Backlog, Subscriptions, Discover, Now Playing
 * Implements keypad event handling for KaiOS devices.
 */
public class MainActivity extends AppCompatActivity {

    private TextView tabIndicator;
    private EpisodeRepository episodeRepository;

    // Tab indices
    private static final int TAB_NEW = 0;
    private static final int TAB_BACKLOG = 1;
    private static final int TAB_SUBSCRIPTIONS = 2;
    private static final int TAB_DISCOVER = 3;
    private static final int TAB_NOW_PLAYING = 4;
    private static final int TAB_COUNT = 5;

    // Intent extras
    public static final String EXTRA_NAVIGATE_TO_TAB = "navigate_to_tab";

    // Current tab (default to Subscriptions)
    private int currentTab = TAB_SUBSCRIPTIONS;

    // Service binding for checking playback state
    private PlaybackService playbackService;
    private boolean serviceBound = false;
    private boolean isCheckingPlayback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize database and repository using singleton
        DatabaseHelper dbHelper = DatabaseManager.getInstance(this);
        episodeRepository = new EpisodeRepository(dbHelper);
        PodcastRepository podcastRepository = new PodcastRepository(dbHelper);

        // Check if we should navigate to a specific tab (e.g., from notification)
        if (getIntent() != null && getIntent().hasExtra(EXTRA_NAVIGATE_TO_TAB)) {
            currentTab = getIntent().getIntExtra(EXTRA_NAVIGATE_TO_TAB, TAB_SUBSCRIPTIONS);
        } else if (savedInstanceState == null) {
            // On first launch, check if there are any subscriptions
            // If none, start on Discovery tab instead of Subscriptions
            if (podcastRepository.getAllPodcasts().isEmpty()) {
                currentTab = TAB_DISCOVER;
            } else {
                // Check if there's an active playback session
                // If yes, default to Now Playing tab
                isCheckingPlayback = true;
                checkPlaybackAndSetTab();
            }
        }

        // Run episode maintenance on background thread to avoid blocking app startup
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Decay old NEW episodes to AVAILABLE
                episodeRepository.decayNewEpisodes();

                // Fix state for downloaded episodes (migration fix)
                // Downloaded episodes should be in BACKLOG state, not NEW
                episodeRepository.fixDownloadedEpisodesState();
            }
        }).start();

        // Setup tab indicator
        tabIndicator = findViewById(R.id.tab_indicator);
        updateTabIndicator();

        // Set initial fragment if this is first creation
        // Only load if we're not checking playback state
        if (savedInstanceState == null && !isCheckingPlayback) {
            loadFragmentForTab(currentTab);
        }
    }

    /**
     * Check if PlaybackService is running with an active episode
     * and set the initial tab to Now Playing if so.
     */
    private void checkPlaybackAndSetTab() {
        Intent intent = new Intent(this, PlaybackService.class);
        boolean bound = bindService(intent, playbackCheckConnection, Context.BIND_AUTO_CREATE);

        // If binding fails, just load the default tab
        if (!bound) {
            isCheckingPlayback = false;
            loadFragmentForTab(currentTab);
        }
    }

    /**
     * ServiceConnection for checking playback state at startup
     */
    private final ServiceConnection playbackCheckConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PlaybackService.PlaybackBinder binder = (PlaybackService.PlaybackBinder) service;
            playbackService = binder.getService();
            serviceBound = true;

            // Check if there's an active episode
            if (playbackService.getCurrentEpisode() != null) {
                // Set tab to Now Playing
                currentTab = TAB_NOW_PLAYING;
                updateTabIndicator();
            }

            // Load the appropriate fragment
            loadFragmentForTab(currentTab);
            isCheckingPlayback = false;

            // Unbind immediately - we only needed to check the state
            unbindService(playbackCheckConnection);
            serviceBound = false;
            playbackService = null;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            playbackService = null;

            // If we're still checking playback, load the default tab
            if (isCheckingPlayback) {
                isCheckingPlayback = false;
                loadFragmentForTab(currentTab);
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Clean up service connection if still bound
        if (serviceBound) {
            try {
                unbindService(playbackCheckConnection);
            } catch (IllegalArgumentException e) {
                // Service was not registered, ignore
            }
            serviceBound = false;
            playbackService = null;
        }
    }

    /**
     * Get the localized tab name for the given tab index.
     * @param tabIndex The index of the tab
     * @return The localized string resource for the tab name
     */
    private String getTabName(int tabIndex) {
        switch (tabIndex) {
            case TAB_NEW:
                return getString(R.string.tab_new);
            case TAB_BACKLOG:
                return getString(R.string.tab_backlog);
            case TAB_SUBSCRIPTIONS:
                return getString(R.string.tab_subscriptions);
            case TAB_DISCOVER:
                return getString(R.string.tab_discovery);
            case TAB_NOW_PLAYING:
                return getString(R.string.tab_now_playing);
            default:
                return "";
        }
    }

    /**
     * Update the tab indicator text to show current tab with arrows.
     */
    private void updateTabIndicator() {
        String tabName = getTabName(currentTab);
        tabIndicator.setText("← " + tabName + " →");
    }

    /**
     * Navigate to a different tab with wrapping.
     * @param direction -1 for previous, 1 for next
     */
    private void navigateTab(int direction) {
        currentTab = currentTab + direction;

        // Wrap around at boundaries
        if (currentTab < 0) {
            currentTab = TAB_COUNT - 1;
        } else if (currentTab >= TAB_COUNT) {
            currentTab = 0;
        }

        updateTabIndicator();
        loadFragmentForTab(currentTab);
    }

    /**
     * Load the appropriate fragment for the given tab index.
     */
    private void loadFragmentForTab(int tabIndex) {
        Fragment fragment = null;

        switch (tabIndex) {
            case TAB_NEW:
                fragment = NewFragment.newInstance();
                break;
            case TAB_BACKLOG:
                fragment = EpisodeListFragment.newInstanceForState(EpisodeState.BACKLOG);
                break;
            case TAB_SUBSCRIPTIONS:
                fragment = SubscriptionsFragment.newInstance();
                break;
            case TAB_DISCOVER:
                fragment = DiscoveryFragment.newInstance();
                break;
            case TAB_NOW_PLAYING:
                fragment = PlayerFragment.newInstance();
                break;
        }

        if (fragment != null) {
            loadFragment(fragment);
        }
    }

    /**
     * Load a fragment into the container.
     */
    private void loadFragment(Fragment fragment) {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();
    }

    /**
     * Handle keypad events for KaiOS navigation.
     * This provides support for physical keypad navigation on KaiOS devices.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // Navigate to previous tab
                navigateTab(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Navigate to next tab
                navigateTab(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Handle selection (currently handled by fragment)
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_BACK:
                // Handle back button
                return super.onKeyDown(keyCode, event);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
