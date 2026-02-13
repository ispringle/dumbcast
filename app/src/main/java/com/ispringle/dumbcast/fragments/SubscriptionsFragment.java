package com.ispringle.dumbcast.fragments;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.adapters.PodcastAdapter;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fragment displaying the user's podcast subscriptions.
 * Loads podcasts from the database and displays them in a list.
 * Click on a podcast to navigate to its episode list.
 */
public class SubscriptionsFragment extends Fragment {

    private static final String TAG = "SubscriptionsFragment";

    private ListView listView;
    private TextView emptyText;
    private PodcastAdapter adapter;
    private PodcastRepository podcastRepository;
    private EpisodeRepository episodeRepository;

    public SubscriptionsFragment() {
        // Required empty public constructor
    }

    public static SubscriptionsFragment newInstance() {
        return new SubscriptionsFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialize repositories using singleton DatabaseHelper
        DatabaseHelper dbHelper = DatabaseManager.getInstance(getContext());
        podcastRepository = new PodcastRepository(dbHelper);
        episodeRepository = new EpisodeRepository(dbHelper);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_subscriptions, container, false);

        listView = view.findViewById(R.id.subscriptions_list);

        // Add empty state text (reusing the title TextView for now)
        emptyText = view.findViewById(R.id.subscriptions_title);

        // Initialize adapter with empty list and empty count map
        adapter = new PodcastAdapter(getContext(), new ArrayList<Podcast>(), new HashMap<Long, Integer>());
        listView.setAdapter(adapter);

        // Set up click listener to navigate to episode list
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Log.d(TAG, "Item clicked at position: " + position);
                Podcast podcast = adapter.getItem(position);
                Log.d(TAG, "Podcast: " + (podcast != null ? podcast.getTitle() : "null"));
                if (podcast != null) {
                    navigateToEpisodeList(podcast);
                }
            }
        });

        // Add touch listener to debug
        listView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                Log.d(TAG, "Touch event on ListView: " + event.getAction());
                return false; // Don't consume the event
            }
        });

        // Add key listener for D-pad and menu button
        listView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int position = listView.getSelectedItemPosition();
                        if (position >= 0) {
                            Podcast podcast = adapter.getItem(position);
                            if (podcast != null) {
                                navigateToEpisodeList(podcast);
                                return true;
                            }
                        }
                    }
                }
                // Menu button shows context menu for selected podcast
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int position = listView.getSelectedItemPosition();
                        if (position >= 0) {
                            Podcast podcast = adapter.getItem(position);
                            if (podcast != null) {
                                showContextMenu(podcast);
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume called");
        // Refresh data when fragment becomes visible
        loadPodcasts();
    }

    /**
     * Load podcasts and episode counts from database on a background thread.
     */
    private void loadPodcasts() {
        new LoadPodcastsTask(this, podcastRepository, episodeRepository).execute();
    }

    /**
     * Data class to hold podcasts and their episode counts.
     */
    private static class PodcastData {
        List<Podcast> podcasts;
        Map<Long, Integer> episodeCounts;

        PodcastData(List<Podcast> podcasts, Map<Long, Integer> episodeCounts) {
            this.podcasts = podcasts;
            this.episodeCounts = episodeCounts;
        }
    }

    /**
     * Update the UI with loaded podcasts (called on main thread).
     * @param data Podcasts and their episode counts
     */
    private void updatePodcastList(PodcastData data) {
        adapter = new PodcastAdapter(getContext(), data.podcasts, data.episodeCounts);
        listView.setAdapter(adapter);

        // Show message if no podcasts
        if (data.podcasts.isEmpty()) {
            emptyText.setText("No subscriptions yet");
        } else {
            emptyText.setText(R.string.subscriptions_title);
        }

        Log.d(TAG, "Loaded " + data.podcasts.size() + " podcasts");
    }

    /**
     * Navigate to the episode list for a specific podcast.
     * @param podcast The podcast to view episodes for
     */
    private void navigateToEpisodeList(Podcast podcast) {
        EpisodeListFragment fragment = EpisodeListFragment.newInstanceForPodcast(podcast.getId());

        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.addToBackStack(null);
        transaction.commit();

        Log.d(TAG, "Navigating to episode list for podcast: " + podcast.getTitle());
    }

    /**
     * Show context menu for a podcast.
     * @param podcast The podcast to show options for
     */
    private void showContextMenu(Podcast podcast) {
        if (podcast == null) {
            return;
        }

        if (getContext() == null) {
            return;
        }

        final String[] menuItems = new String[] {
            getString(R.string.menu_refresh),
            getString(R.string.menu_refresh_all),
            getString(R.string.menu_unsubscribe),
            getString(R.string.menu_remove_new)
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(podcast.getTitle());
        builder.setItems(menuItems, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                handleContextMenuAction(podcast, which);
            }
        });
        builder.show();
    }

    /**
     * Handle context menu action selection.
     * @param podcast The podcast to perform action on
     * @param actionIndex The selected menu item index
     */
    private void handleContextMenuAction(Podcast podcast, int actionIndex) {
        switch (actionIndex) {
            case 0:
                refreshPodcast(podcast);
                break;
            case 1:
                refreshAllPodcasts();
                break;
            case 2:
                unsubscribePodcast(podcast);
                break;
            case 3:
                removeNewFromAllEpisodes(podcast);
                break;
            default:
                Log.w(TAG, "Unknown menu action index: " + actionIndex);
                break;
        }
    }

    /**
     * Refresh a single podcast (stub implementation).
     * @param podcast The podcast to refresh
     */
    private void refreshPodcast(Podcast podcast) {
        Toast.makeText(getContext(), getString(R.string.toast_refresh, podcast.getTitle()), Toast.LENGTH_SHORT).show();
    }

    /**
     * Refresh all podcasts (stub implementation).
     */
    private void refreshAllPodcasts() {
        Toast.makeText(getContext(), getString(R.string.toast_refresh_all), Toast.LENGTH_SHORT).show();
    }

    /**
     * Unsubscribe from a podcast (stub implementation).
     * @param podcast The podcast to unsubscribe from
     */
    private void unsubscribePodcast(Podcast podcast) {
        Toast.makeText(getContext(), getString(R.string.toast_unsubscribe, podcast.getTitle()), Toast.LENGTH_SHORT).show();
    }

    /**
     * Remove NEW flag from all episodes of a podcast (stub implementation).
     * @param podcast The podcast to update
     */
    private void removeNewFromAllEpisodes(Podcast podcast) {
        Toast.makeText(getContext(), getString(R.string.toast_remove_new, podcast.getTitle()), Toast.LENGTH_SHORT).show();
    }

    /**
     * AsyncTask to load podcasts and episode counts from database on a background thread.
     */
    private static class LoadPodcastsTask extends AsyncTask<Void, Void, PodcastData> {
        private final WeakReference<SubscriptionsFragment> fragmentRef;
        private final PodcastRepository podcastRepository;
        private final EpisodeRepository episodeRepository;

        LoadPodcastsTask(SubscriptionsFragment fragment, PodcastRepository podcastRepository, EpisodeRepository episodeRepository) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.podcastRepository = podcastRepository;
            this.episodeRepository = episodeRepository;
        }

        @Override
        protected PodcastData doInBackground(Void... voids) {
            List<Podcast> podcasts = podcastRepository.getAllPodcasts();
            Map<Long, Integer> episodeCounts = new HashMap<>();

            // Preload episode counts for all podcasts
            for (Podcast podcast : podcasts) {
                int count = episodeRepository.getEpisodeCountByPodcast(podcast.getId());
                episodeCounts.put(podcast.getId(), count);
            }

            return new PodcastData(podcasts, episodeCounts);
        }

        @Override
        protected void onPostExecute(PodcastData data) {
            SubscriptionsFragment fragment = fragmentRef.get();
            if (fragment != null && data != null) {
                fragment.updatePodcastList(data);
            }
        }
    }
}
