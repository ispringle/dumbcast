package com.ispringle.dumbcast.fragments;

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
import android.widget.ListView;
import android.widget.TextView;

import com.ispringle.dumbcast.MainActivity;
import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.adapters.PodcastAdapter;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;
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
 * Fragment displaying podcasts that have NEW episodes.
 * Shows only podcasts with episodes in NEW state, with count of NEW episodes.
 * Click on a podcast to navigate to its NEW episodes list.
 */
public class NewFragment extends Fragment {

    private static final String TAG = "NewFragment";

    private ListView listView;
    private TextView emptyText;
    private PodcastAdapter adapter;
    private PodcastRepository podcastRepository;
    private EpisodeRepository episodeRepository;

    public NewFragment() {
        // Required empty public constructor
    }

    public static NewFragment newInstance() {
        return new NewFragment();
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

        // Add empty state text
        emptyText = view.findViewById(R.id.subscriptions_empty);

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

        // Add key listener for D-pad
        listView.setOnKeyListener(new View.OnKeyListener() {
            private long keyPressStartTime = 0;
            private static final long LONG_PRESS_DURATION = 1000; // 1 second

            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                Log.d(TAG, "Key event on ListView: keyCode=" + keyCode + ", action=" + event.getAction());

                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    int position = listView.getSelectedItemPosition();
                    Podcast podcast = position >= 0 ? adapter.getItem(position) : null;

                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        keyPressStartTime = System.currentTimeMillis();
                        return true;
                    } else if (event.getAction() == KeyEvent.ACTION_UP) {
                        long pressDuration = System.currentTimeMillis() - keyPressStartTime;

                        if (podcast != null) {
                            if (pressDuration >= LONG_PRESS_DURATION) {
                                // Long press - refresh podcast
                                Log.d(TAG, "Long press detected - refreshing podcast: " + podcast.getTitle());
                                refreshPodcast(podcast);
                            } else {
                                // Short press - navigate to episodes
                                Log.d(TAG, "Short press - navigating to episodes");
                                navigateToEpisodeList(podcast);
                            }
                            return true;
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

        // Show/hide empty message (will say "No new episodes")
        if (data.podcasts.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText("No new episodes");
        } else {
            emptyText.setVisibility(View.GONE);
        }

        Log.d(TAG, "Loaded " + data.podcasts.size() + " podcasts");
    }

    /**
     * Navigate to the NEW episode list for a specific podcast.
     * Shows only NEW episodes from this podcast.
     * @param podcast The podcast to view NEW episodes for
     */
    private void navigateToEpisodeList(Podcast podcast) {
        EpisodeListFragment fragment = EpisodeListFragment.newInstanceForPodcastAndState(
            podcast.getId(),
            EpisodeState.NEW
        );

        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.addToBackStack(null);
        transaction.commit();

        // Notify MainActivity to update tab indicator
        if (getActivity() instanceof com.ispringle.dumbcast.MainActivity) {
            ((com.ispringle.dumbcast.MainActivity) getActivity()).onFragmentNavigated();
        }

        Log.d(TAG, "Navigating to NEW episodes for podcast: " + podcast.getTitle());
    }

    /**
     * Refresh a podcast to fetch new episodes.
     * @param podcast The podcast to refresh
     */
    private void refreshPodcast(Podcast podcast) {
        android.widget.Toast.makeText(getContext(),
            "Refreshing " + podcast.getTitle() + "...",
            android.widget.Toast.LENGTH_SHORT).show();
        new RefreshPodcastTask(this, podcastRepository, podcast).execute();
    }

    /**
     * AsyncTask to load podcasts with NEW episodes from database on a background thread.
     */
    private static class LoadPodcastsTask extends AsyncTask<Void, Void, PodcastData> {
        private final WeakReference<NewFragment> fragmentRef;
        private final PodcastRepository podcastRepository;
        private final EpisodeRepository episodeRepository;

        LoadPodcastsTask(NewFragment fragment, PodcastRepository podcastRepository, EpisodeRepository episodeRepository) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.podcastRepository = podcastRepository;
            this.episodeRepository = episodeRepository;
        }

        @Override
        protected PodcastData doInBackground(Void... voids) {
            List<Podcast> allPodcasts = podcastRepository.getAllPodcasts();
            List<Podcast> podcastsWithNew = new ArrayList<>();
            Map<Long, Integer> newEpisodeCounts = new HashMap<>();

            // Only include podcasts that have NEW episodes
            for (Podcast podcast : allPodcasts) {
                int newCount = episodeRepository.getEpisodeCountByPodcastAndState(
                    podcast.getId(),
                    EpisodeState.NEW
                );
                if (newCount > 0) {
                    podcastsWithNew.add(podcast);
                    newEpisodeCounts.put(podcast.getId(), newCount);
                }
            }

            return new PodcastData(podcastsWithNew, newEpisodeCounts);
        }

        @Override
        protected void onPostExecute(PodcastData data) {
            NewFragment fragment = fragmentRef.get();
            if (fragment != null && data != null) {
                fragment.updatePodcastList(data);
            }
        }
    }

    /**
     * AsyncTask to refresh a podcast on a background thread.
     */
    private static class RefreshPodcastTask extends AsyncTask<Void, Void, String> {
        private final WeakReference<NewFragment> fragmentRef;
        private final PodcastRepository podcastRepository;
        private final Podcast podcast;

        RefreshPodcastTask(NewFragment fragment, PodcastRepository podcastRepository, Podcast podcast) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.podcastRepository = podcastRepository;
            this.podcast = podcast;
        }

        @Override
        protected String doInBackground(Void... voids) {
            try {
                Log.d(TAG, "Refreshing podcast: " + podcast.getTitle());
                podcastRepository.refreshPodcast(podcast.getId());
                return "SUCCESS";
            } catch (IOException e) {
                Log.e(TAG, "Network error while refreshing podcast", e);
                return "NETWORK_ERROR: " + e.getMessage();
            } catch (XmlPullParserException e) {
                Log.e(TAG, "XML parsing error while refreshing podcast", e);
                return "PARSE_ERROR: " + e.getMessage();
            } catch (Exception e) {
                Log.e(TAG, "Error refreshing podcast", e);
                return "ERROR: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            NewFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null) return;

            if ("SUCCESS".equals(result)) {
                android.widget.Toast.makeText(fragment.getContext(),
                    "Refreshed " + podcast.getTitle(),
                    android.widget.Toast.LENGTH_SHORT).show();
                // Reload podcasts to show updated episode counts
                fragment.loadPodcasts();
                // Update tab visibility in MainActivity (new episodes may have been added)
                if (fragment.getActivity() instanceof MainActivity) {
                    ((MainActivity) fragment.getActivity()).updateTabVisibility();
                }
            } else {
                String errorMsg = "Failed to refresh";
                if (result != null && result.startsWith("NETWORK_ERROR")) {
                    errorMsg = "Network error. Check connection.";
                } else if (result != null && result.startsWith("PARSE_ERROR")) {
                    errorMsg = "Error parsing feed.";
                }
                android.widget.Toast.makeText(fragment.getContext(),
                    errorMsg,
                    android.widget.Toast.LENGTH_LONG).show();
                Log.e(TAG, "Refresh failed: " + result);
            }
        }
    }
}
