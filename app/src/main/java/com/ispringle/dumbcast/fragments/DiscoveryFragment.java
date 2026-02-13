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
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;
import com.ispringle.dumbcast.utils.PodcastIndexApi;
import com.ispringle.dumbcast.utils.RssFeed;
import com.ispringle.dumbcast.utils.RssFeedUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Fragment for discovering and searching podcasts using Podcast Index API.
 * Allows users to search for podcasts and subscribe to them.
 */
public class DiscoveryFragment extends Fragment {

    private static final String TAG = "DiscoveryFragment";
    private static final int TAB_SUBSCRIPTIONS = 2;

    private EditText searchInput;
    private Button searchButton;
    private TextView statusMessage;
    private ListView resultsList;
    private SearchResultAdapter adapter;
    private PodcastRepository podcastRepository;

    public DiscoveryFragment() {
        // Required empty public constructor
    }

    public static DiscoveryFragment newInstance() {
        return new DiscoveryFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialize repository using singleton DatabaseHelper
        DatabaseHelper dbHelper = DatabaseManager.getInstance(getContext());
        podcastRepository = new PodcastRepository(dbHelper);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_discovery, container, false);

        searchInput = view.findViewById(R.id.search_input);
        searchButton = view.findViewById(R.id.search_button);
        statusMessage = view.findViewById(R.id.status_message);
        resultsList = view.findViewById(R.id.results_list);

        // Initialize adapter with empty list
        adapter = new SearchResultAdapter(getContext(), new ArrayList<PodcastIndexApi.SearchResult>());
        resultsList.setAdapter(adapter);

        // Set up search button click listener
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performSearch();
            }
        });

        // Set up Enter key listener on search input
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                     event.getAction() == KeyEvent.ACTION_DOWN)) {
                    performSearch();
                    return true;
                }
                return false;
            }
        });

        // Set up result item click listener
        resultsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PodcastIndexApi.SearchResult result = adapter.getItem(position);
                if (result != null) {
                    showSubscribeDialog(result);
                }
            }
        });

        // Add key listener for D-pad navigation
        resultsList.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int position = resultsList.getSelectedItemPosition();
                        if (position >= 0) {
                            PodcastIndexApi.SearchResult result = adapter.getItem(position);
                            if (result != null) {
                                showSubscribeDialog(result);
                                return true;
                            }
                        }
                    }
                } else if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int position = resultsList.getSelectedItemPosition();
                        if (position >= 0) {
                            PodcastIndexApi.SearchResult result = adapter.getItem(position);
                            if (result != null) {
                                showContextMenu(result);
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

    /**
     * Perform search with the current input value.
     */
    private void performSearch() {
        String query = searchInput.getText().toString().trim();

        if (query.isEmpty()) {
            showStatus(getString(R.string.error_empty_search));
            return;
        }

        // Clear previous results
        adapter.clear();
        adapter.notifyDataSetChanged();

        // Show searching status
        showStatus(getString(R.string.searching));

        // Perform search in background
        new SearchTask(this).execute(query);
    }

    /**
     * Update the UI with search results.
     * @param results List of search results
     */
    private void updateSearchResults(List<PodcastIndexApi.SearchResult> results) {
        adapter.clear();
        if (results != null && !results.isEmpty()) {
            adapter.addAll(results);
            hideStatus();
        } else {
            showStatus(getString(R.string.no_results));
        }
        adapter.notifyDataSetChanged();

        Log.d(TAG, "Loaded " + (results != null ? results.size() : 0) + " search results");
    }

    /**
     * Show an error message to the user.
     * @param error Error message
     */
    private void showError(String error) {
        showStatus(error);
        Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
    }

    /**
     * Show a status message.
     * @param message Status message
     */
    private void showStatus(String message) {
        statusMessage.setText(message);
        statusMessage.setVisibility(View.VISIBLE);
    }

    /**
     * Hide the status message.
     */
    private void hideStatus() {
        statusMessage.setVisibility(View.GONE);
    }

    /**
     * Show a simple subscribe confirmation dialog (using Toast for KaiOS simplicity).
     * @param result The search result to subscribe to
     */
    private void showSubscribeDialog(final PodcastIndexApi.SearchResult result) {
        // For KaiOS, we'll show a simple confirmation with a toast
        // and subscribe directly
        String message = getString(R.string.dialog_subscribe_confirm, result.getTitle());
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

        // Subscribe immediately
        subscribeToResult(result);
    }

    /**
     * Show context menu for a search result.
     * @param result The search result to show options for
     */
    private void showContextMenu(final PodcastIndexApi.SearchResult result) {
        if (result == null) {
            return;
        }

        if (getContext() == null) {
            return;
        }

        final String[] menuItems = new String[] {
            getString(R.string.subscribe),
            getString(R.string.menu_view_episodes),
            getString(R.string.menu_view_podcast_info)
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(result.getTitle());
        builder.setItems(menuItems, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                handleContextMenuAction(result, which);
            }
        });
        builder.show();
    }

    /**
     * Handle context menu action selection.
     * @param result The search result to perform action on
     * @param actionIndex The selected menu item index
     */
    private void handleContextMenuAction(PodcastIndexApi.SearchResult result, int actionIndex) {
        switch (actionIndex) {
            case 0:
                subscribeToPodcast(result);
                break;
            case 1:
                viewEpisodes(result);
                break;
            case 2:
                viewPodcastInfo(result);
                break;
            default:
                Log.w(TAG, "Unknown menu action index: " + actionIndex);
                break;
        }
    }

    /**
     * Subscribe to a podcast with duplicate check.
     * @param result The search result to subscribe to
     */
    private void subscribeToPodcast(PodcastIndexApi.SearchResult result) {
        // Check if already subscribed
        new CheckSubscriptionTask(this, podcastRepository, result).execute();
    }

    /**
     * View episodes for a podcast without subscribing (preview mode).
     * @param result The search result to preview episodes for
     */
    private void viewEpisodes(PodcastIndexApi.SearchResult result) {
        // Check if fragment is in valid state for transaction (I3)
        if (!isAdded() || getActivity() == null) {
            Log.w(TAG, "Fragment not attached, cannot navigate to preview");
            return;
        }

        // Navigate to episode list in preview mode
        EpisodeListFragment fragment = EpisodeListFragment.newInstanceForPreview(
            result.getFeedUrl(),
            result.getTitle()
        );

        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.addToBackStack(null);
        transaction.commit();

        Log.d(TAG, "Navigating to episode preview for: " + result.getTitle());
    }

    /**
     * Show podcast information dialog.
     * @param result The search result to show info for
     */
    private void viewPodcastInfo(PodcastIndexApi.SearchResult result) {
        if (getContext() == null) {
            return;
        }

        // Build info message
        StringBuilder info = new StringBuilder();

        if (result.getAuthor() != null && !result.getAuthor().isEmpty()) {
            info.append(getString(R.string.podcast_info_author, result.getAuthor())).append("\n\n");
        }

        if (result.getEpisodeCount() > 0) {
            info.append(getString(R.string.podcast_info_episodes, result.getEpisodeCount())).append("\n\n");
        }

        if (result.getDescription() != null && !result.getDescription().isEmpty()) {
            info.append(getString(R.string.podcast_info_description, result.getDescription()));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(result.getTitle());
        builder.setMessage(info.toString());
        builder.setPositiveButton(getString(R.string.dialog_cancel), null);
        builder.show();
    }

    /**
     * Subscribe to a search result by fetching its RSS feed and creating a podcast.
     * @param result The search result to subscribe to
     */
    private void subscribeToResult(PodcastIndexApi.SearchResult result) {
        showStatus(getString(R.string.subscribing));
        new SubscribeTask(this, podcastRepository, result).execute();
    }

    /**
     * Handle successful subscription.
     */
    private void onSubscribeSuccess() {
        Toast.makeText(getContext(), getString(R.string.subscribed), Toast.LENGTH_LONG).show();
        hideStatus();

        // Navigate to subscriptions tab
        navigateToSubscriptions();
    }

    /**
     * Handle subscription failure.
     */
    private void onSubscribeFailure() {
        showError(getString(R.string.error_subscribe));
    }

    /**
     * Navigate to the Subscriptions fragment.
     */
    private void navigateToSubscriptions() {
        // Navigate to subscriptions fragment
        SubscriptionsFragment fragment = SubscriptionsFragment.newInstance();

        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();

        Log.d(TAG, "Navigating to subscriptions");
    }

    /**
     * AsyncTask to search for podcasts on a background thread.
     */
    private static class SearchTask extends AsyncTask<String, Void, SearchTaskResult> {
        private final WeakReference<DiscoveryFragment> fragmentRef;

        SearchTask(DiscoveryFragment fragment) {
            this.fragmentRef = new WeakReference<>(fragment);
        }

        @Override
        protected SearchTaskResult doInBackground(String... params) {
            String query = params[0];
            try {
                List<PodcastIndexApi.SearchResult> results = PodcastIndexApi.search(query);
                return new SearchTaskResult(results, null);
            } catch (Exception e) {
                Log.e(TAG, "Search failed", e);
                return new SearchTaskResult(null, e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(SearchTaskResult result) {
            DiscoveryFragment fragment = fragmentRef.get();
            if (fragment == null) return;

            if (result.error != null) {
                fragment.showError(fragment.getString(R.string.error_network));
            } else {
                fragment.updateSearchResults(result.results);
            }
        }
    }

    /**
     * Result container for SearchTask.
     */
    private static class SearchTaskResult {
        final List<PodcastIndexApi.SearchResult> results;
        final String error;

        SearchTaskResult(List<PodcastIndexApi.SearchResult> results, String error) {
            this.results = results;
            this.error = error;
        }
    }

    /**
     * AsyncTask to check if a podcast is already subscribed.
     */
    private static class CheckSubscriptionTask extends AsyncTask<Void, Void, Boolean> {
        private final WeakReference<DiscoveryFragment> fragmentRef;
        private final PodcastRepository repository;
        private final PodcastIndexApi.SearchResult result;

        CheckSubscriptionTask(DiscoveryFragment fragment, PodcastRepository repository,
                            PodcastIndexApi.SearchResult result) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
            this.result = result;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                Podcast existing = repository.getPodcastByFeedUrl(result.getFeedUrl());
                return existing != null;
            } catch (Exception e) {
                Log.e(TAG, "Error checking subscription", e);
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean isSubscribed) {
            DiscoveryFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null) return;

            if (isSubscribed) {
                Toast.makeText(fragment.getContext(),
                    fragment.getString(R.string.toast_already_subscribed),
                    Toast.LENGTH_LONG).show();
            } else {
                fragment.subscribeToResult(result);
            }
        }
    }

    /**
     * AsyncTask to subscribe to a podcast on a background thread.
     * Uses SubscribeResult for type-safe error handling (M1).
     * Subscription check removed - already done by CheckSubscriptionTask (I1).
     */
    private static class SubscribeTask extends AsyncTask<Void, Void, SubscribeResult> {
        private final WeakReference<DiscoveryFragment> fragmentRef;
        private final PodcastRepository repository;
        private final PodcastIndexApi.SearchResult result;

        SubscribeTask(DiscoveryFragment fragment, PodcastRepository repository,
                     PodcastIndexApi.SearchResult result) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
            this.result = result;
        }

        @Override
        protected SubscribeResult doInBackground(Void... voids) {
            try {
                Log.d(TAG, "Subscribing to: " + result.getTitle());
                Log.d(TAG, "Feed URL: " + result.getFeedUrl());

                // Fetch RSS feed using shared utility (C1)
                Log.d(TAG, "Fetching RSS feed...");
                RssFeed feed = RssFeedUtils.fetchFeed(result.getFeedUrl());
                Log.d(TAG, "RSS feed fetched successfully");

                // Create podcast object
                Podcast podcast = new Podcast(0, result.getFeedUrl(), result.getTitle());
                podcast.setDescription(result.getDescription());
                podcast.setArtworkUrl(result.getArtworkUrl());
                podcast.setPodcastIndexId(result.getId());

                // Insert podcast into database
                Log.d(TAG, "Inserting podcast into database...");
                long podcastId = repository.insertPodcast(podcast);
                if (podcastId == -1) {
                    Log.e(TAG, "Failed to insert podcast into database");
                    return SubscribeResult.unknownError("Database insertion failed");
                }
                Log.d(TAG, "Podcast inserted with ID: " + podcastId);

                // Refresh podcast to fetch episodes
                Log.d(TAG, "Fetching episodes...");
                repository.refreshPodcast(podcastId);
                Log.d(TAG, "Episodes fetched successfully");

                return SubscribeResult.success();
            } catch (IOException e) {
                Log.e(TAG, "Network error while subscribing", e);
                return SubscribeResult.networkError(e.getMessage());
            } catch (XmlPullParserException e) {
                Log.e(TAG, "Parse error while subscribing", e);
                return SubscribeResult.parseError(e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Subscribe failed", e);
                return SubscribeResult.unknownError(e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(SubscribeResult result) {
            DiscoveryFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null) return;

            if (result.isSuccess()) {
                fragment.onSubscribeSuccess();
            } else {
                // Show error message based on result type (M1)
                String errorMsg;
                switch (result.getStatus()) {
                    case NETWORK_ERROR:
                        errorMsg = fragment.getString(R.string.error_network);
                        break;
                    case PARSE_ERROR:
                    case UNKNOWN_ERROR:
                        if (result.getErrorDetails() != null) {
                            errorMsg = fragment.getString(R.string.error_subscribe_with_details, result.getErrorDetails());
                        } else {
                            errorMsg = fragment.getString(R.string.error_subscribe);
                        }
                        break;
                    default:
                        errorMsg = fragment.getString(R.string.error_subscribe);
                        break;
                }
                Toast.makeText(fragment.getContext(), errorMsg, Toast.LENGTH_LONG).show();
                fragment.hideStatus();
                Log.e(TAG, "Subscribe failed: " + result.getStatus());
            }
        }
    }

    /**
     * Custom adapter for displaying search results.
     */
    private static class SearchResultAdapter extends ArrayAdapter<PodcastIndexApi.SearchResult> {

        SearchResultAdapter(android.content.Context context,
                           List<PodcastIndexApi.SearchResult> results) {
            super(context, 0, results);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.search_result_item, parent, false);
            }

            PodcastIndexApi.SearchResult result = getItem(position);
            if (result != null) {
                TextView titleView = convertView.findViewById(R.id.result_title);
                TextView authorView = convertView.findViewById(R.id.result_author);
                TextView descriptionView = convertView.findViewById(R.id.result_description);

                titleView.setText(result.getTitle());
                authorView.setText(result.getAuthor());

                // Truncate description to a reasonable length
                String description = result.getDescription();
                if (description != null && description.length() > 100) {
                    description = description.substring(0, 100) + "...";
                }
                descriptionView.setText(description);
            }

            return convertView;
        }
    }
}
