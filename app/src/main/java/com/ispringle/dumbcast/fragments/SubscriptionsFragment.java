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
import com.ispringle.dumbcast.data.Episode;
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

        // Show/hide empty message
        if (data.podcasts.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
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
     * Refresh a single podcast by fetching its RSS feed and adding new episodes.
     * @param podcast The podcast to refresh
     */
    private void refreshPodcast(Podcast podcast) {
        if (getContext() == null) {
            return;
        }

        // Show refreshing toast
        String message = getString(R.string.toast_refreshing_podcast, podcast.getTitle());
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

        // Start async task to refresh via PodcastRepository
        new RefreshPodcastTask(this, podcast, podcastRepository, episodeRepository).execute();
    }

    /**
     * Refresh all podcasts by fetching RSS feeds and adding new episodes.
     * Shows progress for each podcast and a final summary.
     */
    private void refreshAllPodcasts() {
        if (getContext() == null) {
            return;
        }

        // Show starting toast
        Toast.makeText(getContext(), getString(R.string.toast_refreshing_all), Toast.LENGTH_SHORT).show();

        // Start async task to refresh all podcasts
        new RefreshAllPodcastsTask(this, podcastRepository, episodeRepository).execute();
    }

    /**
     * Unsubscribe from a podcast.
     * Shows confirmation dialog, then deletes podcast and all episodes.
     * @param podcast The podcast to unsubscribe from
     */
    private void unsubscribePodcast(final Podcast podcast) {
        if (getContext() == null) {
            return;
        }

        // Show confirmation dialog
        String message = getString(R.string.dialog_unsubscribe_message, podcast.getTitle());
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.dialog_unsubscribe_title);
        builder.setMessage(message);
        builder.setPositiveButton(R.string.dialog_unsubscribe, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                new UnsubscribePodcastTask(SubscriptionsFragment.this, podcastRepository, episodeRepository, podcast).execute();
            }
        });
        builder.setNegativeButton(R.string.dialog_cancel, null);
        builder.show();
    }

    /**
     * Remove NEW flag from all episodes of a podcast.
     * Updates all NEW episodes to AVAILABLE state.
     * @param podcast The podcast to update
     */
    private void removeNewFromAllEpisodes(Podcast podcast) {
        new RemoveNewFromAllTask(this, episodeRepository, podcast).execute();
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

    /**
     * AsyncTask to remove NEW state from all episodes of a podcast on a background thread.
     */
    private static class RemoveNewFromAllTask extends AsyncTask<Void, Void, Integer> {
        private final WeakReference<SubscriptionsFragment> fragmentRef;
        private final EpisodeRepository episodeRepository;
        private final Podcast podcast;

        RemoveNewFromAllTask(SubscriptionsFragment fragment, EpisodeRepository episodeRepository, Podcast podcast) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.episodeRepository = episodeRepository;
            this.podcast = podcast;
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            // Get all NEW episodes for this podcast
            List<Episode> newEpisodes = episodeRepository.getEpisodesByPodcastAndState(podcast.getId(), EpisodeState.NEW);

            int updatedCount = 0;
            for (Episode episode : newEpisodes) {
                int result = episodeRepository.updateEpisodeState(episode.getId(), EpisodeState.AVAILABLE);
                if (result > 0) {
                    updatedCount++;
                }
            }

            return updatedCount;
        }

        @Override
        protected void onPostExecute(Integer updatedCount) {
            SubscriptionsFragment fragment = fragmentRef.get();
            if (fragment != null && fragment.getContext() != null) {
                if (updatedCount > 0) {
                    String message = fragment.getString(R.string.toast_marked_episodes_viewed, updatedCount);
                    Toast.makeText(fragment.getContext(), message, Toast.LENGTH_SHORT).show();
                    // Refresh the podcast list to update episode counts
                    fragment.loadPodcasts();
                } else {
                    Toast.makeText(fragment.getContext(), R.string.toast_no_new_episodes, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * Result class for podcast refresh operation.
     */
    private static class RefreshResult {
        enum Status { SUCCESS, NETWORK_ERROR, PARSE_ERROR, UNKNOWN_ERROR }

        Status status;
        int newEpisodeCount;
        String errorMessage;

        RefreshResult(Status status, int newEpisodeCount) {
            this.status = status;
            this.newEpisodeCount = newEpisodeCount;
            this.errorMessage = null;
        }

        RefreshResult(Status status, String errorMessage) {
            this.status = status;
            this.newEpisodeCount = 0;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * AsyncTask to refresh a podcast using PodcastRepository.
     * This ensures we use the fixed timestamp-based filtering logic.
     */
    private static class RefreshPodcastTask extends AsyncTask<Void, Void, RefreshResult> {
        private final WeakReference<SubscriptionsFragment> fragmentRef;
        private final Podcast podcast;
        private final PodcastRepository podcastRepository;
        private final EpisodeRepository episodeRepository;

        RefreshPodcastTask(SubscriptionsFragment fragment, Podcast podcast, PodcastRepository podcastRepository, EpisodeRepository episodeRepository) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.podcast = podcast;
            this.podcastRepository = podcastRepository;
            this.episodeRepository = episodeRepository;
        }

        @Override
        protected RefreshResult doInBackground(Void... voids) {
            try {
                // Get episode count before refresh
                int episodeCountBefore = episodeRepository.getEpisodeCountByPodcast(podcast.getId());

                // Use PodcastRepository.refreshPodcast() which has timestamp filtering
                podcastRepository.refreshPodcast(podcast.getId());

                // Get episode count after refresh to calculate new episodes
                int episodeCountAfter = episodeRepository.getEpisodeCountByPodcast(podcast.getId());
                int newEpisodeCount = episodeCountAfter - episodeCountBefore;

                return new RefreshResult(RefreshResult.Status.SUCCESS, newEpisodeCount);

            } catch (IOException e) {
                Log.e(TAG, "Network error while refreshing podcast: " + podcast.getTitle(), e);
                return new RefreshResult(RefreshResult.Status.NETWORK_ERROR, e.getMessage());
            } catch (XmlPullParserException e) {
                Log.e(TAG, "Parse error while refreshing podcast: " + podcast.getTitle(), e);
                return new RefreshResult(RefreshResult.Status.PARSE_ERROR, e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unknown error while refreshing podcast: " + podcast.getTitle(), e);
                return new RefreshResult(RefreshResult.Status.UNKNOWN_ERROR, e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(RefreshResult result) {
            SubscriptionsFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null) {
                return;
            }

            // Show result toast
            switch (result.status) {
                case SUCCESS:
                    if (result.newEpisodeCount > 0) {
                        String message = fragment.getString(R.string.toast_refresh_success, result.newEpisodeCount);
                        Toast.makeText(fragment.getContext(), message, Toast.LENGTH_SHORT).show();
                        // Refresh the podcast list to update episode counts
                        fragment.loadPodcasts();
                    } else {
                        Toast.makeText(fragment.getContext(), R.string.toast_refresh_no_new, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case NETWORK_ERROR:
                    Toast.makeText(fragment.getContext(), R.string.toast_refresh_network_error, Toast.LENGTH_LONG).show();
                    break;
                case PARSE_ERROR:
                    Toast.makeText(fragment.getContext(), R.string.toast_refresh_parse_error, Toast.LENGTH_LONG).show();
                    break;
                case UNKNOWN_ERROR:
                    String errorMessage = fragment.getString(R.string.toast_refresh_error, result.errorMessage);
                    Toast.makeText(fragment.getContext(), errorMessage, Toast.LENGTH_LONG).show();
                    break;
            }
        }
    }

    /**
     * Result class for refresh all podcasts operation.
     */
    private static class RefreshAllResult {
        int totalNewEpisodes;
        int successCount;
        int failCount;

        RefreshAllResult(int totalNewEpisodes, int successCount, int failCount) {
            this.totalNewEpisodes = totalNewEpisodes;
            this.successCount = successCount;
            this.failCount = failCount;
        }
    }

    /**
     * AsyncTask to refresh all podcasts using PodcastRepository.
     * This ensures we use the fixed timestamp-based filtering logic.
     */
    private static class RefreshAllPodcastsTask extends AsyncTask<Void, String, RefreshAllResult> {
        private final WeakReference<SubscriptionsFragment> fragmentRef;
        private final PodcastRepository podcastRepository;
        private final EpisodeRepository episodeRepository;

        RefreshAllPodcastsTask(SubscriptionsFragment fragment, PodcastRepository podcastRepository, EpisodeRepository episodeRepository) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.podcastRepository = podcastRepository;
            this.episodeRepository = episodeRepository;
        }

        @Override
        protected RefreshAllResult doInBackground(Void... voids) {
            // Get all podcasts
            List<Podcast> podcasts = podcastRepository.getAllPodcasts();

            int totalNewEpisodes = 0;
            int successCount = 0;
            int failCount = 0;

            // Iterate through each podcast
            for (Podcast podcast : podcasts) {
                try {
                    // Get episode count before refresh
                    int episodeCountBefore = episodeRepository.getEpisodeCountByPodcast(podcast.getId());

                    // Use PodcastRepository.refreshPodcast() which has timestamp filtering
                    podcastRepository.refreshPodcast(podcast.getId());

                    // Get episode count after refresh to calculate new episodes
                    int episodeCountAfter = episodeRepository.getEpisodeCountByPodcast(podcast.getId());
                    int newEpisodeCount = episodeCountAfter - episodeCountBefore;

                    // Track success
                    totalNewEpisodes += newEpisodeCount;
                    successCount++;

                    // Publish progress update
                    publishProgress(podcast.getTitle() + ":" + newEpisodeCount);

                } catch (IOException e) {
                    Log.e(TAG, "Network error while refreshing podcast: " + podcast.getTitle(), e);
                    failCount++;
                    publishProgress(podcast.getTitle() + ":error");
                } catch (XmlPullParserException e) {
                    Log.e(TAG, "Parse error while refreshing podcast: " + podcast.getTitle(), e);
                    failCount++;
                    publishProgress(podcast.getTitle() + ":error");
                } catch (Exception e) {
                    Log.e(TAG, "Unknown error while refreshing podcast: " + podcast.getTitle(), e);
                    failCount++;
                    publishProgress(podcast.getTitle() + ":error");
                }
            }

            return new RefreshAllResult(totalNewEpisodes, successCount, failCount);
        }

        @Override
        protected void onProgressUpdate(String... values) {
            SubscriptionsFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null || values.length == 0) {
                return;
            }

            // Parse progress update (format: "PodcastTitle:count" or "PodcastTitle:error")
            String update = values[0];
            String[] parts = update.split(":", 2);
            if (parts.length == 2) {
                String podcastTitle = parts[0];
                String countStr = parts[1];

                if ("error".equals(countStr)) {
                    // Don't show error toasts during refresh - they'll be in the summary
                    return;
                } else {
                    try {
                        int count = Integer.parseInt(countStr);
                        if (count > 0) {
                            String message = fragment.getString(R.string.toast_refresh_all_progress, podcastTitle, count);
                            Toast.makeText(fragment.getContext(), message, Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Failed to parse episode count: " + countStr, e);
                    }
                }
            }
        }

        @Override
        protected void onPostExecute(RefreshAllResult result) {
            SubscriptionsFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null) {
                return;
            }

            // Show final summary toast
            int totalPodcasts = result.successCount + result.failCount;
            String message = fragment.getString(
                R.string.toast_refresh_all_complete,
                result.totalNewEpisodes,
                result.successCount,
                totalPodcasts
            );
            Toast.makeText(fragment.getContext(), message, Toast.LENGTH_LONG).show();

            // Refresh the podcast list to update episode counts
            if (result.totalNewEpisodes > 0) {
                fragment.loadPodcasts();
            }
        }
    }

    /**
     * AsyncTask to unsubscribe from a podcast on a background thread.
     * Deletes all downloaded episode files and removes podcast from database.
     */
    private static class UnsubscribePodcastTask extends AsyncTask<Void, Void, Boolean> {
        private final WeakReference<SubscriptionsFragment> fragmentRef;
        private final PodcastRepository podcastRepository;
        private final EpisodeRepository episodeRepository;
        private final Podcast podcast;

        UnsubscribePodcastTask(SubscriptionsFragment fragment, PodcastRepository podcastRepository,
                             EpisodeRepository episodeRepository, Podcast podcast) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.podcastRepository = podcastRepository;
            this.episodeRepository = episodeRepository;
            this.podcast = podcast;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                // Get all episodes for this podcast
                List<Episode> episodes = episodeRepository.getEpisodesByPodcast(podcast.getId());

                // Delete downloaded files for all episodes
                for (Episode episode : episodes) {
                    if (episode.isDownloaded() && episode.getDownloadPath() != null) {
                        java.io.File file = new java.io.File(episode.getDownloadPath());
                        if (file.exists()) {
                            if (!file.delete()) {
                                Log.w(TAG, "Failed to delete file: " + episode.getDownloadPath());
                            } else {
                                Log.d(TAG, "Deleted file: " + episode.getDownloadPath());
                            }
                        }
                    }
                }

                // Delete podcast (episodes will be deleted automatically via CASCADE)
                podcastRepository.deletePodcast(podcast.getId());

                return true;
            } catch (Exception e) {
                Log.e(TAG, "Error unsubscribing from podcast: " + podcast.getTitle(), e);
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            SubscriptionsFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null) {
                return;
            }

            if (success) {
                String message = fragment.getString(R.string.toast_unsubscribe_success, podcast.getTitle());
                Toast.makeText(fragment.getContext(), message, Toast.LENGTH_SHORT).show();
                // Refresh the podcast list
                fragment.loadPodcasts();
            } else {
                Toast.makeText(fragment.getContext(), R.string.toast_unsubscribe_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
