package com.ispringle.dumbcast;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
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
import com.ispringle.dumbcast.services.DownloadService;
import com.ispringle.dumbcast.services.PlaybackService;

import java.util.ArrayList;
import java.util.List;

/**
 * Main activity providing tabbed navigation for the podcast app.
 * Tabs: New, Backlog, Subscriptions, Discover, Now Playing
 * Implements keypad event handling for KaiOS devices.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private TextView tabIndicator;
    private EpisodeRepository episodeRepository;

    // Tab identifiers (constants for tab types, not indices)
    private static final int TAB_NOW_PLAYING = 0;
    private static final int TAB_NEW = 1;
    private static final int TAB_BACKLOG = 2;
    private static final int TAB_SUBSCRIPTIONS = 3;
    private static final int TAB_DISCOVER = 4;

    // Dynamic tab list - order: Now Playing, NEW (conditional), BACKLOG (conditional), Subscriptions, Discovery
    private List<Integer> visibleTabs = new ArrayList<>();

    // Intent extras
    public static final String EXTRA_NAVIGATE_TO_TAB = "navigate_to_tab";

    // Current tab index in visibleTabs list (default to 0 - will be set properly during setup)
    private int currentTabIndex = 0;

    // Service binding for checking playback state
    private PlaybackService playbackService;
    private boolean serviceBound = false;
    private boolean isCheckingPlayback = false;

    // Broadcast receiver for episode state changes
    private BroadcastReceiver episodeStateChangeReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize database and repository using singleton
        DatabaseHelper dbHelper = DatabaseManager.getInstance(this);
        episodeRepository = new EpisodeRepository(dbHelper);
        final PodcastRepository podcastRepository = new PodcastRepository(dbHelper);

        // Run episode maintenance on background thread to avoid blocking app startup
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Decay old NEW episodes to AVAILABLE
                episodeRepository.decayNewEpisodes();

                // Fix state for downloaded episodes (migration fix)
                // Downloaded episodes should be in BACKLOG state, not NEW
                episodeRepository.fixDownloadedEpisodesState();

                // After maintenance, update tab visibility on main thread
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateTabVisibility();
                    }
                });
            }
        }).start();

        // Recover orphaned downloads (files that exist but database doesn't know about)
        // This handles cases where download completes but broadcast is missed
        DownloadService.recoverOrphanedDownloads(this);

        // Setup tab indicator
        tabIndicator = findViewById(R.id.tab_indicator);

        // Build initial tab list (synchronously for first setup)
        buildVisibleTabs();

        // Determine initial tab
        int requestedTab = TAB_SUBSCRIPTIONS;
        if (getIntent() != null && getIntent().hasExtra(EXTRA_NAVIGATE_TO_TAB)) {
            requestedTab = getIntent().getIntExtra(EXTRA_NAVIGATE_TO_TAB, TAB_SUBSCRIPTIONS);
        } else if (savedInstanceState == null) {
            // On first launch, check if there are any subscriptions
            // If none, start on Discovery tab instead of Subscriptions
            if (podcastRepository.getAllPodcasts().isEmpty()) {
                requestedTab = TAB_DISCOVER;
            } else {
                // Check if there's an active playback session
                // If yes, default to Now Playing tab
                isCheckingPlayback = true;
                checkPlaybackAndSetTab();
            }
        }

        // Find the index of the requested tab in visibleTabs
        if (!isCheckingPlayback) {
            currentTabIndex = findTabIndex(requestedTab);
            updateTabIndicator();
        }

        // Listen for back stack changes to update tab indicator
        // This ensures header updates when user navigates back from EpisodeListFragment
        getSupportFragmentManager().addOnBackStackChangedListener(new android.support.v4.app.FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
                Log.d(TAG, "onBackStackChanged: back stack count=" +
                    getSupportFragmentManager().getBackStackEntryCount());
                updateTabIndicator();
            }
        });

        // Register broadcast receiver for episode state changes
        episodeStateChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Received episode state change broadcast");
                updateTabVisibility();
            }
        };
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_EPISODE_STATE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(episodeStateChangeReceiver, filter);

        // Set initial fragment if this is first creation
        // Only load if we're not checking playback state
        if (savedInstanceState == null && !isCheckingPlayback) {
            loadFragmentForTab(getCurrentTab());
        }
    }

    /**
     * Build the list of visible tabs based on episode counts.
     * Always shows: Now Playing, Subscriptions, Discovery
     * Conditionally shows: NEW (if NEW episodes exist), BACKLOG (if BACKLOG episodes exist)
     * Order: Now Playing, NEW, BACKLOG, Subscriptions, Discovery
     */
    private void buildVisibleTabs() {
        visibleTabs.clear();

        // Always add Now Playing
        visibleTabs.add(TAB_NOW_PLAYING);

        // Add NEW tab if there are NEW episodes
        int newCount = episodeRepository.getEpisodeCountByState(EpisodeState.NEW);
        if (newCount > 0) {
            visibleTabs.add(TAB_NEW);
            Log.d(TAG, "buildVisibleTabs: Added NEW tab (count=" + newCount + ")");
        }

        // Add BACKLOG tab if there are BACKLOG episodes
        int backlogCount = episodeRepository.getEpisodeCountByState(EpisodeState.BACKLOG);
        if (backlogCount > 0) {
            visibleTabs.add(TAB_BACKLOG);
            Log.d(TAG, "buildVisibleTabs: Added BACKLOG tab (count=" + backlogCount + ")");
        }

        // Always add Subscriptions and Discovery
        visibleTabs.add(TAB_SUBSCRIPTIONS);
        visibleTabs.add(TAB_DISCOVER);

        Log.d(TAG, "buildVisibleTabs: Built " + visibleTabs.size() + " visible tabs");
    }

    /**
     * Update tab visibility dynamically based on current episode counts.
     * This should be called after episode state changes (download, delete, state changes).
     * Attempts to preserve the current fragment if possible.
     */
    public void updateTabVisibility() {
        Log.d(TAG, "updateTabVisibility: Updating tab visibility");

        // Remember current tab type (not index)
        int currentTabType = getCurrentTab();

        // Get current fragment to check if we're on a tab-level view
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);

        // Rebuild visible tabs list
        buildVisibleTabs();

        // Try to find the current tab in the new visible tabs list
        int newTabIndex = findTabIndex(currentTabType);

        if (newTabIndex != -1) {
            // Current tab is still visible, update index
            currentTabIndex = newTabIndex;
            Log.d(TAG, "updateTabVisibility: Current tab still visible at index " + currentTabIndex);
        } else {
            // Current tab is no longer visible, navigate to a sensible default
            Log.d(TAG, "updateTabVisibility: Current tab no longer visible, navigating to default");

            // If we were on NEW or BACKLOG and they're gone, navigate to Subscriptions (or Now Playing if we have active playback)
            if (currentTabType == TAB_NEW || currentTabType == TAB_BACKLOG) {
                // Navigate to Subscriptions (it's always visible)
                currentTabIndex = findTabIndex(TAB_SUBSCRIPTIONS);
                if (currentTabIndex != -1) {
                    loadFragmentForTab(TAB_SUBSCRIPTIONS);
                }
            }
        }

        // Update tab indicator
        updateTabIndicator();

        Log.d(TAG, "updateTabVisibility: Complete. Current tab index=" + currentTabIndex);
    }

    /**
     * Find the index of a tab type in the visibleTabs list.
     * @param tabType The tab type constant (TAB_NEW, TAB_BACKLOG, etc.)
     * @return The index in visibleTabs, or -1 if not found
     */
    private int findTabIndex(int tabType) {
        for (int i = 0; i < visibleTabs.size(); i++) {
            if (visibleTabs.get(i) == tabType) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get the current tab type (TAB_NEW, TAB_BACKLOG, etc.) based on currentTabIndex.
     * @return The current tab type constant
     */
    private int getCurrentTab() {
        if (currentTabIndex >= 0 && currentTabIndex < visibleTabs.size()) {
            return visibleTabs.get(currentTabIndex);
        }
        return TAB_SUBSCRIPTIONS; // Default fallback
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
            loadFragmentForTab(getCurrentTab());
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
                currentTabIndex = findTabIndex(TAB_NOW_PLAYING);
                updateTabIndicator();
            }

            // Load the appropriate fragment
            loadFragmentForTab(getCurrentTab());
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
                loadFragmentForTab(getCurrentTab());
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        // Update tab visibility when activity resumes
        // This ensures tabs are correct after returning from background (episodes may have changed)
        updateTabVisibility();
    }

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

        // Unregister broadcast receiver
        if (episodeStateChangeReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(episodeStateChangeReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver was not registered, ignore
            }
            episodeStateChangeReceiver = null;
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
     * Also checks the actual current fragment to handle cases where fragments
     * navigate to sub-fragments (like EpisodeListFragment from SubscriptionsFragment).
     */
    private void updateTabIndicator() {
        // Check what fragment is actually showing
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);

        Log.d(TAG, "updateTabIndicator: currentTabIndex=" + currentTabIndex + ", currentFragment=" +
            (currentFragment != null ? currentFragment.getClass().getSimpleName() : "null"));

        String tabName;
        int currentTabType = getCurrentTab();

        if (currentFragment instanceof EpisodeListFragment) {
            // If we're showing an episode list, check if it's a tab-level view or a drill-down
            // Tab-level views: BACKLOG tab shows EpisodeListFragment for BACKLOG state
            // Drill-down views: Clicking a podcast in Subscriptions/New shows EpisodeListFragment
            if (currentTabType == TAB_BACKLOG) {
                // This is the BACKLOG tab view
                tabName = getTabName(TAB_BACKLOG);
                Log.d(TAG, "updateTabIndicator: showing BACKLOG tab (EpisodeListFragment)");
            } else {
                // This is a drill-down from another tab (Subscriptions or New)
                // Show "Episodes" instead of the parent tab name
                tabName = getString(R.string.tab_episodes);
                Log.d(TAG, "updateTabIndicator: showing drill-down Episodes view");
            }
        } else if (currentFragment instanceof NewFragment) {
            tabName = getTabName(TAB_NEW);
            Log.d(TAG, "updateTabIndicator: showing New tab");
        } else if (currentFragment instanceof SubscriptionsFragment) {
            tabName = getTabName(TAB_SUBSCRIPTIONS);
            Log.d(TAG, "updateTabIndicator: showing Subscriptions tab");
        } else if (currentFragment instanceof DiscoveryFragment) {
            tabName = getTabName(TAB_DISCOVER);
            Log.d(TAG, "updateTabIndicator: showing Discovery tab");
        } else if (currentFragment instanceof PlayerFragment) {
            tabName = getTabName(TAB_NOW_PLAYING);
            Log.d(TAG, "updateTabIndicator: showing Now Playing tab");
        } else {
            // Fallback to the stored current tab value
            tabName = getTabName(currentTabType);
            Log.d(TAG, "updateTabIndicator: fallback to stored currentTab");
        }

        String newText = "← " + tabName + " →";
        tabIndicator.setText(newText);
        Log.d(TAG, "updateTabIndicator: set header to: " + newText);
    }

    /**
     * Navigate to a different tab with wrapping.
     * @param direction -1 for previous, 1 for next
     */
    private void navigateTab(int direction) {
        if (visibleTabs.isEmpty()) {
            Log.w(TAG, "navigateTab: No visible tabs!");
            return;
        }

        currentTabIndex = currentTabIndex + direction;

        // Wrap around at boundaries
        if (currentTabIndex < 0) {
            currentTabIndex = visibleTabs.size() - 1;
        } else if (currentTabIndex >= visibleTabs.size()) {
            currentTabIndex = 0;
        }

        int newTabType = getCurrentTab();
        Log.d(TAG, "navigateTab: direction=" + direction + ", newTabIndex=" + currentTabIndex + ", newTabType=" + newTabType);
        updateTabIndicator();
        loadFragmentForTab(newTabType);
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

        // Update tab indicator after fragment is committed
        // Post to handler to ensure fragment transaction completes first
        tabIndicator.post(new Runnable() {
            @Override
            public void run() {
                updateTabIndicator();
            }
        });
    }

    /**
     * Public method for fragments to notify MainActivity that they've navigated.
     * This ensures the tab indicator updates correctly.
     * Call this after any FragmentTransaction that changes the displayed fragment.
     */
    public void onFragmentNavigated() {
        Log.d(TAG, "onFragmentNavigated: called by fragment");
        // Post to handler to ensure fragment transaction completes first
        tabIndicator.post(new Runnable() {
            @Override
            public void run() {
                updateTabIndicator();
            }
        });
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
                // Sync currentTab with actual displayed fragment before navigating
                syncCurrentTabWithFragment();
                navigateTab(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Navigate to next tab
                // Sync currentTab with actual displayed fragment before navigating
                syncCurrentTabWithFragment();
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

    /**
     * Sync currentTabIndex with the actual displayed fragment.
     * This ensures D-pad navigation works correctly when drilling into sub-fragments
     * (e.g., navigating from Backlog to episode to Now Playing).
     */
    private void syncCurrentTabWithFragment() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);

        int detectedTabType = -1;

        if (currentFragment instanceof NewFragment) {
            detectedTabType = TAB_NEW;
        } else if (currentFragment instanceof SubscriptionsFragment) {
            detectedTabType = TAB_SUBSCRIPTIONS;
        } else if (currentFragment instanceof DiscoveryFragment) {
            detectedTabType = TAB_DISCOVER;
        } else if (currentFragment instanceof PlayerFragment) {
            detectedTabType = TAB_NOW_PLAYING;
        } else if (currentFragment instanceof EpisodeListFragment) {
            // EpisodeListFragment can be either BACKLOG tab or a drill-down from another tab
            // Check if this is the BACKLOG tab by examining the back stack
            // If back stack is empty, we're at a top-level tab (BACKLOG)
            // If back stack has entries, we're in a drill-down (keep currentTabIndex as is)
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                // This is the BACKLOG tab view (top-level)
                detectedTabType = TAB_BACKLOG;
            }
            // Otherwise keep currentTabIndex unchanged (drill-down from Subscriptions/New)
        }

        // Update currentTabIndex to match the detected tab type
        if (detectedTabType != -1) {
            int newIndex = findTabIndex(detectedTabType);
            if (newIndex != -1) {
                currentTabIndex = newIndex;
            }
        }

        Log.d(TAG, "syncCurrentTabWithFragment: currentTabIndex=" + currentTabIndex +
            ", currentTabType=" + getCurrentTab() +
            ", currentFragment=" + (currentFragment != null ? currentFragment.getClass().getSimpleName() : "null") +
            ", backStackCount=" + getSupportFragmentManager().getBackStackEntryCount());
    }
}
