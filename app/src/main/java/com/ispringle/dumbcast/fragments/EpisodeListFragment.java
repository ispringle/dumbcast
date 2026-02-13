package com.ispringle.dumbcast.fragments;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.adapters.EpisodeAdapter;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;
import com.ispringle.dumbcast.services.PlaybackService;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fragment displaying a list of episodes for a specific podcast or episode state.
 * Supports filtering by:
 * - Podcast ID (shows all episodes for that podcast)
 * - Episode state (shows all episodes with that state)
 *
 * Features:
 * - Background loading of episodes from database
 * - Click to play (if downloaded) or download (if not downloaded)
 * - Long-press to save to BACKLOG
 * - Visual indicators for download status and episode state
 */
public class EpisodeListFragment extends Fragment {

    private static final String TAG = "EpisodeListFragment";
    private static final String ARG_PODCAST_ID = "podcast_id";
    private static final String ARG_EPISODE_STATE = "episode_state";

    private ListView listView;
    private TextView titleText;
    private TextView emptyText;
    private EpisodeAdapter adapter;
    private EpisodeRepository episodeRepository;
    private PodcastRepository podcastRepository;

    private long podcastId = -1;
    private EpisodeState episodeState = null;

    public EpisodeListFragment() {
        // Required empty public constructor
    }

    /**
     * Create a new instance to show episodes for a specific podcast.
     * @param podcastId The podcast ID
     * @return Fragment instance
     */
    public static EpisodeListFragment newInstanceForPodcast(long podcastId) {
        EpisodeListFragment fragment = new EpisodeListFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_PODCAST_ID, podcastId);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Create a new instance to show episodes with a specific state.
     * @param state The episode state
     * @return Fragment instance
     */
    public static EpisodeListFragment newInstanceForState(EpisodeState state) {
        EpisodeListFragment fragment = new EpisodeListFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EPISODE_STATE, state.name());
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            podcastId = getArguments().getLong(ARG_PODCAST_ID, -1);
            String stateStr = getArguments().getString(ARG_EPISODE_STATE);
            if (stateStr != null) {
                episodeState = EpisodeState.fromString(stateStr);
            }
        }

        // Initialize repositories using singleton DatabaseHelper
        DatabaseHelper dbHelper = DatabaseManager.getInstance(getContext());
        episodeRepository = new EpisodeRepository(dbHelper);
        podcastRepository = new PodcastRepository(dbHelper);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_episode_list, container, false);

        listView = view.findViewById(R.id.episode_list);
        titleText = view.findViewById(R.id.episode_list_title);
        emptyText = view.findViewById(R.id.episode_list_empty);

        // Set title based on filter type
        updateTitle();

        // Initialize adapter with empty list and empty podcast cache
        adapter = new EpisodeAdapter(getContext(), new ArrayList<Episode>(), new HashMap<Long, Podcast>());
        listView.setAdapter(adapter);

        // Set up click listeners
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Episode episode = adapter.getItem(position);
                if (episode != null) {
                    handleEpisodeClick(episode);
                }
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Episode episode = adapter.getItem(position);
                if (episode != null) {
                    handleEpisodeLongClick(episode);
                }
                return true;
            }
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh data when fragment becomes visible
        loadEpisodes();
    }

    /**
     * Update the fragment title based on the filter type.
     */
    private void updateTitle() {
        if (podcastId != -1) {
            // Load podcast name asynchronously
            new LoadPodcastTitleTask(this, podcastRepository, titleText).execute(podcastId);
        } else if (episodeState != null) {
            titleText.setText(episodeState.name() + " Episodes");
        } else {
            titleText.setText("Episodes");
        }
    }

    /**
     * Load episodes and related podcasts from database on a background thread.
     */
    private void loadEpisodes() {
        new LoadEpisodesTask(this, episodeRepository, podcastRepository).execute();
    }

    /**
     * Data class to hold episodes and their related podcast data.
     */
    private static class EpisodeData {
        List<Episode> episodes;
        Map<Long, Podcast> podcastCache;

        EpisodeData(List<Episode> episodes, Map<Long, Podcast> podcastCache) {
            this.episodes = episodes;
            this.podcastCache = podcastCache;
        }
    }

    /**
     * Update the UI with loaded episodes (called on main thread).
     * @param data Episodes and their related podcast data
     */
    private void updateEpisodeList(EpisodeData data) {
        adapter = new EpisodeAdapter(getContext(), data.episodes, data.podcastCache);
        listView.setAdapter(adapter);

        // Show/hide empty state
        if (data.episodes.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            listView.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
        }

        Log.d(TAG, "Loaded " + data.episodes.size() + " episodes");
    }

    /**
     * Handle click on an episode.
     * If downloaded, play it and navigate to PlayerFragment. If not downloaded, show message.
     */
    private void handleEpisodeClick(Episode episode) {
        if (episode.isDownloaded()) {
            // Start PlaybackService and load the episode
            Intent serviceIntent = new Intent(getContext(), PlaybackService.class);
            getContext().startService(serviceIntent);

            // Load episode for playback (will be handled via service binding in PlayerFragment)
            // For now, we'll just navigate and let the PlayerFragment handle the binding
            // We need to pass the episode to the service before navigating
            startPlaybackAndNavigate(episode);
        } else {
            // TODO: Implement download
            Toast.makeText(getContext(), "Episode not downloaded. Download functionality coming soon.", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Start playback for an episode and navigate to PlayerFragment.
     * This starts the service, loads the episode, and switches to the player view.
     */
    private void startPlaybackAndNavigate(final Episode episode) {
        // Start the playback service
        Intent serviceIntent = new Intent(getContext(), PlaybackService.class);
        getContext().startService(serviceIntent);

        // Give the service a moment to start, then navigate
        // In a real implementation, we'd use a callback or binding, but this works for now
        new android.os.Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                // Navigate to PlayerFragment
                PlayerFragment playerFragment = PlayerFragment.newInstance();

                // Load the episode into the playback service
                Intent loadIntent = new Intent(getContext(), PlaybackService.class);
                loadIntent.setAction(PlaybackService.ACTION_LOAD_EPISODE);
                loadIntent.putExtra(PlaybackService.EXTRA_EPISODE_ID, episode.getId());
                getContext().startService(loadIntent);

                // Navigate to player
                FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container, playerFragment);
                transaction.addToBackStack(null);
                transaction.commit();
            }
        }, 100);
    }

    /**
     * Handle long-click on an episode.
     * Save to BACKLOG if not already in BACKLOG or LISTENED state.
     */
    private void handleEpisodeLongClick(Episode episode) {
        EpisodeState currentState = episode.getState();

        if (currentState == EpisodeState.BACKLOG) {
            Toast.makeText(getContext(), "Already in backlog", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentState == EpisodeState.LISTENED) {
            Toast.makeText(getContext(), "Cannot save listened episodes to backlog", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save to backlog on background thread
        new SaveToBacklogTask(this, episodeRepository, episode).execute();
    }

    /**
     * AsyncTask to load episodes and related podcasts from database on a background thread.
     */
    private static class LoadEpisodesTask extends AsyncTask<Void, Void, EpisodeData> {
        private final WeakReference<EpisodeListFragment> fragmentRef;
        private final EpisodeRepository episodeRepository;
        private final PodcastRepository podcastRepository;

        LoadEpisodesTask(EpisodeListFragment fragment, EpisodeRepository episodeRepository, PodcastRepository podcastRepository) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.episodeRepository = episodeRepository;
            this.podcastRepository = podcastRepository;
        }

        @Override
        protected EpisodeData doInBackground(Void... voids) {
            EpisodeListFragment fragment = fragmentRef.get();
            if (fragment == null) {
                return new EpisodeData(new ArrayList<Episode>(), new HashMap<Long, Podcast>());
            }

            List<Episode> episodes;
            if (fragment.podcastId != -1) {
                // Load episodes by podcast ID
                episodes = episodeRepository.getEpisodesByPodcast(fragment.podcastId);
            } else if (fragment.episodeState != null) {
                // Load episodes by state
                episodes = episodeRepository.getEpisodesByState(fragment.episodeState);
            } else {
                // No filter specified
                episodes = new ArrayList<>();
            }

            // Build podcast cache for all unique podcast IDs in the episode list
            Map<Long, Podcast> podcastCache = new HashMap<>();
            for (Episode episode : episodes) {
                long pid = episode.getPodcastId();
                if (!podcastCache.containsKey(pid)) {
                    Podcast podcast = podcastRepository.getPodcastById(pid);
                    if (podcast != null) {
                        podcastCache.put(pid, podcast);
                    }
                }
            }

            return new EpisodeData(episodes, podcastCache);
        }

        @Override
        protected void onPostExecute(EpisodeData data) {
            EpisodeListFragment fragment = fragmentRef.get();
            if (fragment != null && data != null) {
                fragment.updateEpisodeList(data);
            }
        }
    }

    /**
     * AsyncTask to load podcast title on a background thread.
     */
    private static class LoadPodcastTitleTask extends AsyncTask<Long, Void, String> {
        private final WeakReference<EpisodeListFragment> fragmentRef;
        private final PodcastRepository repository;
        private final TextView titleView;

        LoadPodcastTitleTask(EpisodeListFragment fragment, PodcastRepository repository, TextView titleView) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
            this.titleView = titleView;
        }

        @Override
        protected String doInBackground(Long... params) {
            long podcastId = params[0];
            Podcast podcast = repository.getPodcastById(podcastId);
            return podcast != null ? podcast.getTitle() : "Episodes";
        }

        @Override
        protected void onPostExecute(String title) {
            if (titleView != null) {
                titleView.setText(title);
            }
        }
    }

    /**
     * AsyncTask to save episode to backlog on a background thread.
     */
    private static class SaveToBacklogTask extends AsyncTask<Void, Void, Integer> {
        private final WeakReference<EpisodeListFragment> fragmentRef;
        private final EpisodeRepository repository;
        private final Episode episode;

        SaveToBacklogTask(EpisodeListFragment fragment, EpisodeRepository repository, Episode episode) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
            this.episode = episode;
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            return repository.updateEpisodeState(episode.getId(), EpisodeState.BACKLOG);
        }

        @Override
        protected void onPostExecute(Integer rowsUpdated) {
            EpisodeListFragment fragment = fragmentRef.get();
            if (fragment != null && fragment.getContext() != null) {
                if (rowsUpdated > 0) {
                    Toast.makeText(fragment.getContext(), "Saved to backlog", Toast.LENGTH_SHORT).show();
                    // Refresh the list
                    fragment.loadEpisodes();
                } else {
                    Toast.makeText(fragment.getContext(), "Failed to save to backlog", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
