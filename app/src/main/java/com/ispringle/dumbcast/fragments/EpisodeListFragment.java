package com.ispringle.dumbcast.fragments;

import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
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
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

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

        // Initialize repositories
        DatabaseHelper dbHelper = new DatabaseHelper(getContext());
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

        // Initialize adapter with empty list
        adapter = new EpisodeAdapter(getContext(), new ArrayList<Episode>(), podcastRepository);
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
     * Load episodes from database on a background thread.
     */
    private void loadEpisodes() {
        new LoadEpisodesTask(this, episodeRepository).execute();
    }

    /**
     * Update the UI with loaded episodes (called on main thread).
     * @param episodes List of episodes to display
     */
    private void updateEpisodeList(List<Episode> episodes) {
        adapter.clear();
        adapter.addAll(episodes);
        adapter.notifyDataSetChanged();

        // Show/hide empty state
        if (episodes.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            listView.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
        }

        Log.d(TAG, "Loaded " + episodes.size() + " episodes");
    }

    /**
     * Handle click on an episode.
     * If downloaded, play it. If not downloaded, download it.
     */
    private void handleEpisodeClick(Episode episode) {
        if (episode.isDownloaded()) {
            // TODO: Implement playback
            Toast.makeText(getContext(), "Play: " + episode.getTitle(), Toast.LENGTH_SHORT).show();
        } else {
            // TODO: Implement download
            Toast.makeText(getContext(), "Download: " + episode.getTitle(), Toast.LENGTH_SHORT).show();
        }
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
     * AsyncTask to load episodes from database on a background thread.
     */
    private static class LoadEpisodesTask extends AsyncTask<Void, Void, List<Episode>> {
        private final WeakReference<EpisodeListFragment> fragmentRef;
        private final EpisodeRepository repository;

        LoadEpisodesTask(EpisodeListFragment fragment, EpisodeRepository repository) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
        }

        @Override
        protected List<Episode> doInBackground(Void... voids) {
            EpisodeListFragment fragment = fragmentRef.get();
            if (fragment == null) {
                return new ArrayList<>();
            }

            if (fragment.podcastId != -1) {
                // Load episodes by podcast ID
                return repository.getEpisodesByPodcast(fragment.podcastId);
            } else if (fragment.episodeState != null) {
                // Load episodes by state
                return repository.getEpisodesByState(fragment.episodeState);
            } else {
                // No filter specified
                return new ArrayList<>();
            }
        }

        @Override
        protected void onPostExecute(List<Episode> episodes) {
            EpisodeListFragment fragment = fragmentRef.get();
            if (fragment != null && episodes != null) {
                fragment.updateEpisodeList(episodes);
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
