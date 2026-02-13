package com.ispringle.dumbcast;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.view.KeyEvent;
import android.widget.TextView;

import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;
import com.ispringle.dumbcast.fragments.DiscoveryFragment;
import com.ispringle.dumbcast.fragments.EpisodeListFragment;
import com.ispringle.dumbcast.fragments.NewFragment;
import com.ispringle.dumbcast.fragments.PlayerFragment;
import com.ispringle.dumbcast.fragments.SubscriptionsFragment;

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

    // Current tab (default to Subscriptions)
    private int currentTab = TAB_SUBSCRIPTIONS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize database and repository using singleton
        DatabaseHelper dbHelper = DatabaseManager.getInstance(this);
        episodeRepository = new EpisodeRepository(dbHelper);

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
        if (savedInstanceState == null) {
            loadFragmentForTab(currentTab);
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
