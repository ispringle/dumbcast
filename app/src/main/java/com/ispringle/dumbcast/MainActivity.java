package com.ispringle.dumbcast;

import android.os.Bundle;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.view.KeyEvent;

import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.fragments.SubscriptionsFragment;

/**
 * Main activity providing tabbed navigation for the podcast app.
 * Tabs: New, Backlog, Subscriptions, Discovery
 * Implements keypad event handling for KaiOS devices.
 */
public class MainActivity extends AppCompatActivity {

    private TabLayout tabLayout;
    private EpisodeRepository episodeRepository;

    // Tab indices
    private static final int TAB_NEW = 0;
    private static final int TAB_BACKLOG = 1;
    private static final int TAB_SUBSCRIPTIONS = 2;
    private static final int TAB_DISCOVERY = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize database and repository
        DatabaseHelper dbHelper = new DatabaseHelper(this);
        episodeRepository = new EpisodeRepository(dbHelper);

        // Run episode decay on app startup
        episodeRepository.decayNewEpisodes();

        // Setup TabLayout
        tabLayout = findViewById(R.id.tab_layout);
        setupTabs();

        // Set initial fragment if this is first creation
        if (savedInstanceState == null) {
            loadFragment(SubscriptionsFragment.newInstance());
        }
    }

    /**
     * Configure the TabLayout with all four tabs and handle selection.
     */
    private void setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_new));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_backlog));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_subscriptions));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_discovery));

        // Set Subscriptions tab as initially selected
        tabLayout.getTabAt(TAB_SUBSCRIPTIONS).select();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                handleTabSelection(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                // No action needed
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // No action needed
            }
        });
    }

    /**
     * Handle tab selection and load appropriate fragment.
     * Currently only Subscriptions is implemented; others are stubs.
     */
    private void handleTabSelection(int position) {
        Fragment fragment = null;

        switch (position) {
            case TAB_NEW:
                // TODO: Implement NewFragment
                fragment = createStubFragment("New");
                break;
            case TAB_BACKLOG:
                // TODO: Implement BacklogFragment
                fragment = createStubFragment("Backlog");
                break;
            case TAB_SUBSCRIPTIONS:
                fragment = SubscriptionsFragment.newInstance();
                break;
            case TAB_DISCOVERY:
                // TODO: Implement DiscoveryFragment
                fragment = createStubFragment("Discovery");
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
     * Create a placeholder fragment for unimplemented tabs.
     * This will be removed once all fragments are implemented.
     */
    private Fragment createStubFragment(String tabName) {
        // For now, return the subscriptions fragment as a placeholder
        // In the future, this will be removed when actual fragments are implemented
        return SubscriptionsFragment.newInstance();
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

    /**
     * Navigate between tabs using left/right keypad buttons.
     * @param direction -1 for previous tab, 1 for next tab
     */
    private void navigateTab(int direction) {
        int currentTab = tabLayout.getSelectedTabPosition();
        int newTab = currentTab + direction;

        // Wrap around at boundaries
        if (newTab < 0) {
            newTab = tabLayout.getTabCount() - 1;
        } else if (newTab >= tabLayout.getTabCount()) {
            newTab = 0;
        }

        TabLayout.Tab tab = tabLayout.getTabAt(newTab);
        if (tab != null) {
            tab.select();
        }
    }
}
