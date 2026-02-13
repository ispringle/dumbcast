package com.ispringle.dumbcast.fragments;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.adapters.EpisodeAdapter;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;
import com.ispringle.dumbcast.services.DownloadService;
import com.ispringle.dumbcast.services.PlaybackService;
import com.ispringle.dumbcast.utils.RssFeed;
import com.ispringle.dumbcast.utils.RssParser;

import org.xmlpull.v1.XmlPullParserException;

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
    private static final String ARG_PREVIEW_FEED_URL = "preview_feed_url";
    private static final String ARG_PREVIEW_TITLE = "preview_title";

    private ListView listView;
    private TextView titleText;
    private TextView emptyText;
    private EpisodeAdapter adapter;
    private EpisodeRepository episodeRepository;
    private PodcastRepository podcastRepository;

    private long podcastId = -1;
    private EpisodeState episodeState = null;
    private String previewFeedUrl = null;
    private String previewTitle = null;
    private boolean isPreviewMode = false;

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

    /**
     * Create a new instance to show episodes for a specific podcast AND state.
     * @param podcastId The podcast ID
     * @param state The episode state
     * @return Fragment instance
     */
    public static EpisodeListFragment newInstanceForPodcastAndState(long podcastId, EpisodeState state) {
        EpisodeListFragment fragment = new EpisodeListFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_PODCAST_ID, podcastId);
        args.putString(ARG_EPISODE_STATE, state.name());
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Create a new instance to preview episodes from a feed URL without subscribing.
     * This is a read-only preview mode - episodes are not saved to database.
     * @param feedUrl The RSS feed URL
     * @param podcastTitle The podcast title
     * @return Fragment instance
     */
    public static EpisodeListFragment newInstanceForPreview(String feedUrl, String podcastTitle) {
        EpisodeListFragment fragment = new EpisodeListFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PREVIEW_FEED_URL, feedUrl);
        args.putString(ARG_PREVIEW_TITLE, podcastTitle);
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
            previewFeedUrl = getArguments().getString(ARG_PREVIEW_FEED_URL);
            previewTitle = getArguments().getString(ARG_PREVIEW_TITLE);
            isPreviewMode = (previewFeedUrl != null);
        }

        Log.d(TAG, "onCreate - podcastId: " + podcastId + ", episodeState: " + episodeState + ", previewMode: " + isPreviewMode);

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

        // Add key listener for D-pad navigation
        listView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                Log.d(TAG, "Key event: keyCode=" + keyCode + ", action=" + event.getAction());
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int position = listView.getSelectedItemPosition();
                        Log.d(TAG, "Selected position: " + position);
                        if (position >= 0) {
                            Episode episode = adapter.getItem(position);
                            if (episode != null) {
                                handleEpisodeClick(episode);
                                return true;
                            }
                        }
                    }
                } else if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int position = listView.getSelectedItemPosition();
                        if (position >= 0) {
                            Episode episode = adapter.getItem(position);
                            if (episode != null) {
                                showContextMenu(episode);
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
        // Refresh data when fragment becomes visible
        loadEpisodes();
    }

    /**
     * Update the fragment title based on the filter type.
     */
    private void updateTitle() {
        if (isPreviewMode) {
            titleText.setText(previewTitle + " (Preview)");
        } else if (podcastId != -1) {
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
        if (isPreviewMode) {
            new LoadPreviewEpisodesTask(this, previewFeedUrl, previewTitle).execute();
        } else {
            new LoadEpisodesTask(this, episodeRepository, podcastRepository).execute();
        }
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
        // In preview mode, disable playback/download actions
        if (isPreviewMode) {
            Toast.makeText(getContext(), "Subscribe to podcast to play or download episodes", Toast.LENGTH_SHORT).show();
            return;
        }

        if (episode.isDownloaded()) {
            // Start PlaybackService and load the episode
            Intent serviceIntent = new Intent(getContext(), PlaybackService.class);
            getContext().startService(serviceIntent);

            // Load episode for playback (will be handled via service binding in PlayerFragment)
            // For now, we'll just navigate and let the PlayerFragment handle the binding
            // We need to pass the episode to the service before navigating
            startPlaybackAndNavigate(episode);
        } else {
            // Start download
            DownloadService.startDownload(getContext(), episode.getId());
            Toast.makeText(getContext(), R.string.toast_download_started, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Start playback for an episode and navigate to PlayerFragment.
     * This starts the service, loads the episode, and switches to the player view.
     */
    private void startPlaybackAndNavigate(final Episode episode) {
        // Load the episode into the playback service
        Intent loadIntent = new Intent(getContext(), PlaybackService.class);
        loadIntent.setAction(PlaybackService.ACTION_LOAD_EPISODE);
        loadIntent.putExtra(PlaybackService.EXTRA_EPISODE_ID, episode.getId());
        getContext().startService(loadIntent);

        // Navigate to PlayerFragment immediately
        PlayerFragment playerFragment = PlayerFragment.newInstance();
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, playerFragment);
        transaction.addToBackStack(null);
        transaction.commit();

        Log.d(TAG, "Navigating to player for episode: " + episode.getTitle());
    }

    /**
     * Handle long-click on an episode.
     * Save to BACKLOG if not already in BACKLOG or LISTENED state.
     */
    private void handleEpisodeLongClick(Episode episode) {
        // In preview mode, disable database actions
        if (isPreviewMode) {
            Toast.makeText(getContext(), "Subscribe to podcast to save to backlog", Toast.LENGTH_SHORT).show();
            return;
        }

        EpisodeState currentState = episode.getState();

        if (currentState == EpisodeState.BACKLOG) {
            Toast.makeText(getContext(), R.string.toast_already_in_backlog, Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentState == EpisodeState.LISTENED) {
            Toast.makeText(getContext(), R.string.toast_cannot_save_listened, Toast.LENGTH_SHORT).show();
            return;
        }

        // Save to backlog on background thread
        new SaveToBacklogTask(this, episodeRepository, episode).execute();
    }

    /**
     * Show context menu for an episode with conditional options based on episode state.
     * @param episode The episode to show options for
     */
    private void showContextMenu(final Episode episode) {
        if (episode == null) {
            return;
        }

        if (getContext() == null) {
            return;
        }

        // In preview mode, don't show context menu (no database actions available)
        if (isPreviewMode) {
            Toast.makeText(getContext(), "Subscribe to podcast to interact with episodes", Toast.LENGTH_SHORT).show();
            return;
        }

        // Build menu items conditionally
        final List<String> menuItems = new ArrayList<>();

        // If downloaded: show Delete Download and Play
        if (episode.isDownloaded()) {
            menuItems.add(getString(R.string.menu_delete_download));
            menuItems.add(getString(R.string.menu_play));
        } else {
            // If not downloaded: show Download
            menuItems.add(getString(R.string.menu_download));
        }

        // Always show Add to Backlog
        menuItems.add(getString(R.string.menu_add_to_backlog));

        // If NEW state: show Remove NEW
        if (episode.getState() == EpisodeState.NEW) {
            menuItems.add(getString(R.string.menu_remove_new_episode));
        }

        // Convert to array
        final String[] menuArray = menuItems.toArray(new String[0]);

        // Show AlertDialog
        new AlertDialog.Builder(getContext())
                .setTitle(episode.getTitle())
                .setItems(menuArray, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        handleContextMenuAction(episode, which);
                    }
                })
                .show();
    }

    /**
     * Handle context menu action selection.
     * Menu indices depend on episode state:
     * - If downloaded: 0=Delete Download, 1=Play, 2=Add to Backlog, 3=Remove NEW (if NEW)
     * - If not downloaded: 0=Download, 1=Add to Backlog, 2=Remove NEW (if NEW)
     *
     * @param episode The episode to act on
     * @param actionIndex The selected menu item index
     */
    private void handleContextMenuAction(Episode episode, int actionIndex) {
        if (episode.isDownloaded()) {
            // Downloaded menu: Delete Download, Play, Add to Backlog, [Remove NEW]
            switch (actionIndex) {
                case 0: // Delete Download
                    deleteDownload(episode);
                    break;
                case 1: // Play
                    handleEpisodeClick(episode);
                    break;
                case 2: // Add to Backlog
                    addToBacklog(episode);
                    break;
                case 3: // Remove NEW (only if NEW state)
                    removeNew(episode);
                    break;
            }
        } else {
            // Not downloaded menu: Download, Add to Backlog, [Remove NEW]
            switch (actionIndex) {
                case 0: // Download
                    downloadEpisode(episode);
                    break;
                case 1: // Add to Backlog
                    addToBacklog(episode);
                    break;
                case 2: // Remove NEW (only if NEW state)
                    removeNew(episode);
                    break;
            }
        }
    }

    /**
     * Start download for an episode.
     * @param episode The episode to download
     */
    private void downloadEpisode(Episode episode) {
        DownloadService.startDownload(getContext(), episode.getId());
        Toast.makeText(getContext(), R.string.toast_download_started, Toast.LENGTH_SHORT).show();
    }

    /**
     * Delete downloaded file for an episode.
     * Shows confirmation dialog before deleting.
     * @param episode The episode to delete download for
     */
    private void deleteDownload(final Episode episode) {
        String message = getString(R.string.dialog_delete_download_message, episode.getTitle());
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.dialog_delete_download_title)
                .setMessage(message)
                .setPositiveButton(R.string.dialog_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new DeleteDownloadTask(EpisodeListFragment.this, episodeRepository, episode).execute();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * Add episode to backlog.
     * @param episode The episode to add to backlog
     */
    private void addToBacklog(Episode episode) {
        EpisodeState currentState = episode.getState();

        if (currentState == EpisodeState.BACKLOG) {
            Toast.makeText(getContext(), R.string.toast_already_in_backlog, Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentState == EpisodeState.LISTENED) {
            Toast.makeText(getContext(), R.string.toast_cannot_save_listened, Toast.LENGTH_SHORT).show();
            return;
        }

        new SaveToBacklogTask(this, episodeRepository, episode).execute();
    }

    /**
     * Remove NEW state from episode.
     * @param episode The episode to mark as viewed
     */
    private void removeNew(Episode episode) {
        new RemoveNewTask(this, episodeRepository, episode).execute();
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
                Log.w(TAG, "LoadEpisodesTask - fragment is null");
                return new EpisodeData(new ArrayList<Episode>(), new HashMap<Long, Podcast>());
            }

            Log.d(TAG, "LoadEpisodesTask - loading episodes for podcastId: " + fragment.podcastId + ", state: " + fragment.episodeState);

            List<Episode> episodes;
            if (fragment.podcastId != -1 && fragment.episodeState != null) {
                // Load episodes by both podcast ID AND state
                Log.d(TAG, "LoadEpisodesTask - calling getEpisodesByPodcastAndState(" + fragment.podcastId + ", " + fragment.episodeState + ")");
                episodes = episodeRepository.getEpisodesByPodcastAndState(fragment.podcastId, fragment.episodeState);
                Log.d(TAG, "LoadEpisodesTask - loaded " + episodes.size() + " episodes");
            } else if (fragment.podcastId != -1) {
                // Load episodes by podcast ID only
                Log.d(TAG, "LoadEpisodesTask - calling getEpisodesByPodcast(" + fragment.podcastId + ")");
                episodes = episodeRepository.getEpisodesByPodcast(fragment.podcastId);
                Log.d(TAG, "LoadEpisodesTask - loaded " + episodes.size() + " episodes");
            } else if (fragment.episodeState != null) {
                // Load episodes by state only
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
                    Toast.makeText(fragment.getContext(), R.string.toast_saved_to_backlog, Toast.LENGTH_SHORT).show();
                    // Refresh the list
                    fragment.loadEpisodes();
                } else {
                    Toast.makeText(fragment.getContext(), R.string.toast_failed_save_backlog, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * AsyncTask to delete downloaded episode file on a background thread.
     */
    private static class DeleteDownloadTask extends AsyncTask<Void, Void, Boolean> {
        private final WeakReference<EpisodeListFragment> fragmentRef;
        private final EpisodeRepository repository;
        private final Episode episode;

        DeleteDownloadTask(EpisodeListFragment fragment, EpisodeRepository repository, Episode episode) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
            this.episode = episode;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            // Delete the file
            String downloadPath = episode.getDownloadPath();
            if (downloadPath != null) {
                File file = new File(downloadPath);
                if (file.exists()) {
                    if (!file.delete()) {
                        Log.e(TAG, "Failed to delete file: " + downloadPath);
                        return false;
                    }
                }
            }

            // Update database to clear download info
            int rowsUpdated = repository.updateEpisodeDownload(episode.getId(), null, 0);
            return rowsUpdated > 0;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            EpisodeListFragment fragment = fragmentRef.get();
            if (fragment != null && fragment.getContext() != null) {
                if (success) {
                    Toast.makeText(fragment.getContext(), R.string.toast_download_deleted, Toast.LENGTH_SHORT).show();
                    // Refresh the list
                    fragment.loadEpisodes();
                } else {
                    Toast.makeText(fragment.getContext(), R.string.toast_failed_delete_download, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * AsyncTask to remove NEW state from episode on a background thread.
     */
    private static class RemoveNewTask extends AsyncTask<Void, Void, Integer> {
        private final WeakReference<EpisodeListFragment> fragmentRef;
        private final EpisodeRepository repository;
        private final Episode episode;

        RemoveNewTask(EpisodeListFragment fragment, EpisodeRepository repository, Episode episode) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
            this.episode = episode;
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            return repository.updateEpisodeState(episode.getId(), EpisodeState.AVAILABLE);
        }

        @Override
        protected void onPostExecute(Integer rowsUpdated) {
            EpisodeListFragment fragment = fragmentRef.get();
            if (fragment != null && fragment.getContext() != null) {
                if (rowsUpdated > 0) {
                    Toast.makeText(fragment.getContext(), R.string.toast_marked_viewed, Toast.LENGTH_SHORT).show();
                    // Refresh the list
                    fragment.loadEpisodes();
                } else {
                    Toast.makeText(fragment.getContext(), R.string.toast_failed_update_episode, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * AsyncTask to load episodes from RSS feed for preview mode (without saving to database).
     */
    private static class LoadPreviewEpisodesTask extends AsyncTask<Void, Void, EpisodeData> {
        private final WeakReference<EpisodeListFragment> fragmentRef;
        private final String feedUrl;
        private final String podcastTitle;

        LoadPreviewEpisodesTask(EpisodeListFragment fragment, String feedUrl, String podcastTitle) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.feedUrl = feedUrl;
            this.podcastTitle = podcastTitle;
        }

        @Override
        protected EpisodeData doInBackground(Void... voids) {
            try {
                Log.d(TAG, "Loading preview episodes from: " + feedUrl);

                // Fetch RSS feed
                RssFeed feed = fetchFeedWithRedirects(feedUrl, 5);

                // Create temporary Episode objects (not saved to database)
                List<Episode> episodes = new ArrayList<>();
                if (feed != null && feed.getItems() != null) {
                    for (RssFeed.RssItem rssItem : feed.getItems()) {
                        Episode episode = new Episode(
                            0, // Temporary podcast ID (not in database)
                            rssItem.getGuid() != null ? rssItem.getGuid() : rssItem.getEnclosureUrl(),
                            rssItem.getTitle(),
                            rssItem.getEnclosureUrl(),
                            rssItem.getPublishedAt()
                        );
                        episode.setDescription(rssItem.getDescription());
                        episode.setDuration(rssItem.getDuration());
                        episode.setState(EpisodeState.AVAILABLE);
                        episodes.add(episode);
                    }
                }

                // Create temporary Podcast for the cache
                Map<Long, Podcast> podcastCache = new HashMap<>();
                Podcast tempPodcast = new Podcast(0, feedUrl, podcastTitle);
                podcastCache.put(0L, tempPodcast);

                Log.d(TAG, "Loaded " + episodes.size() + " preview episodes");
                return new EpisodeData(episodes, podcastCache);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load preview episodes", e);
                return new EpisodeData(new ArrayList<Episode>(), new HashMap<Long, Podcast>());
            }
        }

        @Override
        protected void onPostExecute(EpisodeData data) {
            EpisodeListFragment fragment = fragmentRef.get();
            if (fragment != null) {
                fragment.updateEpisodeList(data);
            }
        }

        /**
         * Fetch RSS feed with manual redirect following.
         */
        private RssFeed fetchFeedWithRedirects(String feedUrl, int maxRedirects) throws IOException, XmlPullParserException {
            if (maxRedirects <= 0) {
                throw new IOException("Too many redirects");
            }

            URL url = new URL(feedUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "Dumbcast/1.0");
                connection.setInstanceFollowRedirects(false);

                int responseCode = connection.getResponseCode();

                // Handle redirects
                if (responseCode >= 300 && responseCode < 400) {
                    String newUrl = connection.getHeaderField("Location");
                    if (newUrl == null) {
                        throw new IOException("Redirect with no Location header");
                    }

                    Log.d(TAG, "Following redirect: " + feedUrl + " -> " + newUrl);
                    connection.disconnect();
                    return fetchFeedWithRedirects(newUrl, maxRedirects - 1);
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP error code: " + responseCode);
                }

                InputStream inputStream = connection.getInputStream();
                RssParser parser = new RssParser();
                return parser.parse(inputStream);
            } finally {
                connection.disconnect();
            }
        }
    }
}
