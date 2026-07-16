package com.ispringle.dumbcast.fragments;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
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
import com.ispringle.dumbcast.utils.LibriVoxApi;
import com.ispringle.dumbcast.utils.PodcastIndexApi;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fragment for discovering and searching podcasts using Podcast Index API.
 * Allows users to search for podcasts and subscribe to them.
 */
public class DiscoveryFragment extends Fragment {

    private static final String TAG = "DiscoveryFragment";
    private static final int TAB_SUBSCRIPTIONS = 2;

    // Regex pattern to detect HTTP/HTTPS URLs
    private static final Pattern URL_PATTERN = Pattern.compile(
        "^https?://.*",
        Pattern.CASE_INSENSITIVE
    );

    // Search mode enum
    private enum SearchMode {
        PODCASTS,
        AUDIOBOOKS
    }

    private EditText searchInput;
    private Button searchButton;
    private Button modeToggleButton;
    private Button pasteButton;
    private TextView statusMessage;
    private ListView resultsList;
    private SearchResultAdapter adapter;
    private PodcastRepository podcastRepository;
    private SearchMode currentSearchMode = SearchMode.PODCASTS;
    private List<LibriVoxApi.SearchResult> currentAudiobookResults = new ArrayList<>();

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
        modeToggleButton = view.findViewById(R.id.mode_toggle_button);
        pasteButton = view.findViewById(R.id.paste_button);
        statusMessage = view.findViewById(R.id.status_message);
        resultsList = view.findViewById(R.id.results_list);

        // Initialize adapter with empty list
        adapter = new SearchResultAdapter(getContext(), new ArrayList<PodcastIndexApi.SearchResult>(), this);
        resultsList.setAdapter(adapter);

        // Set up mode toggle button click listener
        modeToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSearchMode();
            }
        });

        // Set up paste button click listener
        pasteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });

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
                if (currentSearchMode == SearchMode.AUDIOBOOKS && position < currentAudiobookResults.size()) {
                    // Handle audiobook selection
                    LibriVoxApi.SearchResult audiobook = currentAudiobookResults.get(position);
                    showAudiobookSubscribeDialog(audiobook);
                } else {
                    // Handle podcast selection
                    PodcastIndexApi.SearchResult result = adapter.getItem(position);
                    if (result != null) {
                        showSubscribeDialog(result);
                    }
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
                            if (currentSearchMode == SearchMode.AUDIOBOOKS && position < currentAudiobookResults.size()) {
                                LibriVoxApi.SearchResult audiobook = currentAudiobookResults.get(position);
                                showAudiobookSubscribeDialog(audiobook);
                                return true;
                            } else {
                                PodcastIndexApi.SearchResult result = adapter.getItem(position);
                                if (result != null) {
                                    showSubscribeDialog(result);
                                    return true;
                                }
                            }
                        }
                    }
                } else if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int position = resultsList.getSelectedItemPosition();
                        if (position >= 0) {
                            if (currentSearchMode == SearchMode.AUDIOBOOKS && position < currentAudiobookResults.size()) {
                                LibriVoxApi.SearchResult audiobook = currentAudiobookResults.get(position);
                                showAudiobookContextMenu(audiobook);
                                return true;
                            } else {
                                PodcastIndexApi.SearchResult result = adapter.getItem(position);
                                if (result != null) {
                                    showContextMenu(result);
                                    return true;
                                }
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
     * If the input is a URL (HTTP/HTTPS), subscribe directly instead of searching.
     */
    private void performSearch() {
        String query = searchInput.getText().toString().trim();

        if (query.isEmpty()) {
            showStatus(getString(R.string.error_empty_search));
            return;
        }

        // Check if input is a URL
        if (isUrl(query)) {
            // Subscribe directly from URL
            subscribeFromUrl(query);
            return;
        }

        // Clear previous results
        adapter.clear();
        adapter.notifyDataSetChanged();

        // Show searching status based on mode
        String searchingMessage = (currentSearchMode == SearchMode.PODCASTS) ?
            getString(R.string.searching) : getString(R.string.searching_audiobooks);
        showStatus(searchingMessage);

        // Perform search in background with current search mode
        new SearchTask(this, currentSearchMode).execute(query);
    }

    /**
     * Check if a string is an HTTP/HTTPS URL.
     * @param input The input string to check
     * @return true if the input is a URL, false otherwise
     */
    private boolean isUrl(String input) {
        return URL_PATTERN.matcher(input).matches();
    }

    /**
     * Toggle between podcast and audiobook search modes.
     */
    private void toggleSearchMode() {
        // Toggle mode
        currentSearchMode = (currentSearchMode == SearchMode.PODCASTS) ?
            SearchMode.AUDIOBOOKS : SearchMode.PODCASTS;

        // Update search hint
        String hint = (currentSearchMode == SearchMode.PODCASTS) ?
            getString(R.string.search_hint) : getString(R.string.search_hint_audiobooks);
        searchInput.setHint(hint);

        // Update button text
        String buttonText = (currentSearchMode == SearchMode.PODCASTS) ?
            getString(R.string.search_mode_podcasts) : getString(R.string.search_mode_audiobooks);
        modeToggleButton.setText(buttonText);

        // Show mode change message
        String modeName = (currentSearchMode == SearchMode.PODCASTS) ?
            getString(R.string.search_mode_podcasts) : getString(R.string.search_mode_audiobooks);
        Toast.makeText(getContext(), "Mode: " + modeName, Toast.LENGTH_SHORT).show();

        // Clear results when switching modes
        adapter.clear();
        adapter.notifyDataSetChanged();
        hideStatus();
    }

    /**
     * Paste text from clipboard into the search input.
     */
    private void pasteFromClipboard() {
        if (getContext() == null) {
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(getContext(), "Clipboard not available", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(getContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            Toast.makeText(getContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipData.Item item = clipData.getItemAt(0);
        CharSequence pasteData = item.getText();
        if (pasteData != null) {
            searchInput.setText(pasteData);
            searchInput.setSelection(pasteData.length()); // Move cursor to end
            Toast.makeText(getContext(), "Pasted", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "No text in clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Subscribe directly from a feed URL.
     * @param feedUrl The RSS feed URL to subscribe to
     */
    private void subscribeFromUrl(String feedUrl) {
        // Clear previous results
        adapter.clear();
        adapter.notifyDataSetChanged();

        // Show subscribing status
        showStatus(getString(R.string.subscribing));

        // Subscribe in background
        new SubscribeFromUrlTask(this, podcastRepository, feedUrl).execute();
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
     * Update UI with audiobook search results from LibriVox.
     */
    private void updateAudiobookResults(List<LibriVoxApi.SearchResult> audiobookResults) {
        // Store the actual audiobook results for later subscription
        currentAudiobookResults.clear();
        if (audiobookResults != null) {
            currentAudiobookResults.addAll(audiobookResults);
        }

        // Create a simple adapter for audiobooks
        adapter.clear();
        if (audiobookResults != null && !audiobookResults.isEmpty()) {
            // Create pseudo search results for display
            // The adapter will use position to look up the real audiobook data
            for (int i = 0; i < audiobookResults.size(); i++) {
                LibriVoxApi.SearchResult audiobook = audiobookResults.get(i);
                // We'll handle display in the adapter using position
                adapter.add(null); // Placeholder - adapter will check for audiobook mode
            }
            hideStatus();
        } else {
            showStatus(getString(R.string.no_results));
        }
        adapter.notifyDataSetChanged();

        Log.d(TAG, "Loaded " + audiobookResults.size() + " audiobook results");
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
     * Show subscribe confirmation for an audiobook.
     * @param audiobook The audiobook to subscribe to
     */
    private void showAudiobookSubscribeDialog(final LibriVoxApi.SearchResult audiobook) {
        String message = getString(R.string.dialog_subscribe_confirm, audiobook.getTitle());
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

        // Subscribe with reverse order enabled
        subscribeToAudiobook(audiobook);
    }

    /**
     * Show context menu for an audiobook result.
     * @param audiobook The audiobook to show options for
     */
    private void showAudiobookContextMenu(final LibriVoxApi.SearchResult audiobook) {
        if (audiobook == null || getContext() == null) {
            return;
        }

        final String[] menuItems = new String[] {
            getString(R.string.subscribe),
            getString(R.string.menu_view_podcast_info)
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(audiobook.getTitle());
        builder.setItems(menuItems, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    subscribeToAudiobook(audiobook);
                } else if (which == 1) {
                    viewAudiobookInfo(audiobook);
                }
            }
        });
        builder.show();
    }

    /**
     * Subscribe to an audiobook.
     * @param audiobook The audiobook to subscribe to
     */
    private void subscribeToAudiobook(LibriVoxApi.SearchResult audiobook) {
        // Construct RSS feed URL from audiobook ID
        String feedUrl = "https://librivox.org/rss/" + audiobook.getId();

        showStatus(getString(R.string.subscribing));
        new SubscribeAudiobookTask(this, podcastRepository, audiobook, feedUrl).execute();
    }

    /**
     * Show detailed information about an audiobook.
     * @param audiobook The audiobook to show info for
     */
    private void viewAudiobookInfo(LibriVoxApi.SearchResult audiobook) {
        if (getContext() == null) {
            return;
        }

        StringBuilder info = new StringBuilder();

        if (audiobook.getAuthor() != null && !audiobook.getAuthor().isEmpty()) {
            info.append("Author: ").append(audiobook.getAuthor()).append("\n\n");
        }

        if (audiobook.getCopyrightYear() != null && !audiobook.getCopyrightYear().isEmpty()) {
            info.append("Year: ").append(audiobook.getCopyrightYear()).append("\n\n");
        }

        String duration = audiobook.getFormattedDuration();
        if (duration != null && !duration.isEmpty()) {
            info.append("Duration: ").append(duration).append("\n\n");
        }

        if (audiobook.getLanguage() != null && !audiobook.getLanguage().isEmpty()) {
            info.append("Language: ").append(audiobook.getLanguage()).append("\n\n");
        }

        if (audiobook.getDescription() != null && !audiobook.getDescription().isEmpty()) {
            info.append("Description:\n").append(audiobook.getDescription());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(audiobook.getTitle());
        builder.setMessage(info.toString().trim());
        builder.setPositiveButton(getString(R.string.dialog_close), null);
        builder.show();
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

        // Notify MainActivity to update tab indicator
        if (getActivity() instanceof com.ispringle.dumbcast.MainActivity) {
            ((com.ispringle.dumbcast.MainActivity) getActivity()).onFragmentNavigated();
        }

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
        private final SearchMode searchMode;

        SearchTask(DiscoveryFragment fragment, SearchMode mode) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.searchMode = mode;
        }

        @Override
        protected SearchTaskResult doInBackground(String... params) {
            String query = params[0];
            try {
                if (searchMode == SearchMode.AUDIOBOOKS) {
                    // Search LibriVox for audiobooks
                    List<LibriVoxApi.SearchResult> audiobookResults = LibriVoxApi.search(query);
                    // Convert to podcast results for display
                    List<PodcastIndexApi.SearchResult> convertedResults = new ArrayList<>();
                    for (LibriVoxApi.SearchResult audiobook : audiobookResults) {
                        // Create pseudo-PodcastIndexApi.SearchResult from audiobook
                        // We'll need to handle this in the adapter or create a unified result type
                        // For now, pass the audiobook results directly
                    }
                    return new SearchTaskResult(null, audiobookResults, null);
                } else {
                    // Search Podcast Index for podcasts
                    List<PodcastIndexApi.SearchResult> results = PodcastIndexApi.search(query);
                    return new SearchTaskResult(results, null, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Search failed", e);
                return new SearchTaskResult(null, null, e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(SearchTaskResult result) {
            DiscoveryFragment fragment = fragmentRef.get();
            if (fragment == null) return;

            if (result.error != null) {
                fragment.showError(fragment.getString(R.string.error_network));
            } else {
                if (result.podcastResults != null) {
                    fragment.updateSearchResults(result.podcastResults);
                } else if (result.audiobookResults != null) {
                    fragment.updateAudiobookResults(result.audiobookResults);
                }
            }
        }
    }

    /**
     * Result container for SearchTask.
     */
    private static class SearchTaskResult {
        final List<PodcastIndexApi.SearchResult> podcastResults;
        final List<LibriVoxApi.SearchResult> audiobookResults;
        final String error;

        SearchTaskResult(List<PodcastIndexApi.SearchResult> podcastResults,
                        List<LibriVoxApi.SearchResult> audiobookResults,
                        String error) {
            this.podcastResults = podcastResults;
            this.audiobookResults = audiobookResults;
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
                long podcastId = repository.subscribe(
                    result.getFeedUrl(),
                    result.getTitle(),
                    result.getDescription(),
                    result.getArtworkUrl(),
                    result.getId(),
                    false
                );
                if (podcastId == -1) {
                    return SubscribeResult.unknownError("Database insertion failed");
                }
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
     * AsyncTask to subscribe to an audiobook from LibriVox.
     */
    private static class SubscribeAudiobookTask extends AsyncTask<Void, Void, SubscribeResult> {
        private final WeakReference<DiscoveryFragment> fragmentRef;
        private final PodcastRepository repository;
        private final LibriVoxApi.SearchResult audiobook;
        private final String feedUrl;

        SubscribeAudiobookTask(DiscoveryFragment fragment, PodcastRepository repository,
                              LibriVoxApi.SearchResult audiobook, String feedUrl) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
            this.audiobook = audiobook;
            this.feedUrl = feedUrl;
        }

        @Override
        protected SubscribeResult doInBackground(Void... voids) {
            try {
                Log.d(TAG, "Subscribing to audiobook: " + audiobook.getTitle());
                Podcast existing = repository.getPodcastByFeedUrl(feedUrl);
                if (existing != null) {
                    return SubscribeResult.alreadySubscribed();
                }

                // reverseOrder=true so initial batch is chapter 1 first
                long podcastId = repository.subscribe(
                    feedUrl,
                    audiobook.getTitle(),
                    audiobook.getDescription(),
                    audiobook.getArtworkUrl(),
                    null,
                    true
                );
                if (podcastId == -1) {
                    return SubscribeResult.unknownError("Database insertion failed");
                }
                return SubscribeResult.success();
            } catch (IOException e) {
                Log.e(TAG, "Network error while subscribing to audiobook", e);
                return SubscribeResult.networkError(e.getMessage());
            } catch (XmlPullParserException e) {
                Log.e(TAG, "Parse error while subscribing to audiobook", e);
                return SubscribeResult.parseError(e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Subscribe to audiobook failed", e);
                return SubscribeResult.unknownError(e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(SubscribeResult result) {
            DiscoveryFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null) return;

            if (result.isSuccess()) {
                fragment.onSubscribeSuccess();
            } else if (result.getStatus() == SubscribeResult.Status.ALREADY_SUBSCRIBED) {
                Toast.makeText(fragment.getContext(),
                    fragment.getString(R.string.toast_already_subscribed),
                    Toast.LENGTH_LONG).show();
                fragment.hideStatus();
            } else {
                // Show error message
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
                Log.e(TAG, "Subscribe to audiobook failed: " + result.getStatus());
            }
        }
    }

    /**
     * AsyncTask to subscribe to a podcast directly from a feed URL.
     * Fetches the feed, extracts metadata, and creates the subscription.
     */
    private static class SubscribeFromUrlTask extends AsyncTask<Void, Void, SubscribeResult> {
        private final WeakReference<DiscoveryFragment> fragmentRef;
        private final PodcastRepository repository;
        private final String feedUrl;

        SubscribeFromUrlTask(DiscoveryFragment fragment, PodcastRepository repository, String feedUrl) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
            this.feedUrl = feedUrl;
        }

        @Override
        protected SubscribeResult doInBackground(Void... voids) {
            try {
                Log.d(TAG, "Subscribing from URL: " + feedUrl);
                Podcast existing = repository.getPodcastByFeedUrl(feedUrl);
                if (existing != null) {
                    return SubscribeResult.alreadySubscribed();
                }

                long podcastId = repository.subscribe(feedUrl, null, null, null, null, false);
                if (podcastId == -1) {
                    return SubscribeResult.unknownError("Database insertion failed");
                }
                return SubscribeResult.success();
            } catch (IOException e) {
                Log.e(TAG, "Network error while subscribing from URL", e);
                return SubscribeResult.networkError(e.getMessage());
            } catch (XmlPullParserException e) {
                Log.e(TAG, "Parse error while subscribing from URL", e);
                return SubscribeResult.parseError(e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Subscribe from URL failed", e);
                return SubscribeResult.unknownError(e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(SubscribeResult result) {
            DiscoveryFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null) return;

            if (result.isSuccess()) {
                fragment.onSubscribeSuccess();
            } else if (result.getStatus() == SubscribeResult.Status.ALREADY_SUBSCRIBED) {
                Toast.makeText(fragment.getContext(),
                    fragment.getString(R.string.toast_already_subscribed),
                    Toast.LENGTH_LONG).show();
                fragment.hideStatus();
            } else {
                // Show error message based on result type
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
                Log.e(TAG, "Subscribe from URL failed: " + result.getStatus());
            }
        }
    }

    /**
     * Custom adapter for displaying search results.
     */
    private static class SearchResultAdapter extends ArrayAdapter<PodcastIndexApi.SearchResult> {
        private final WeakReference<DiscoveryFragment> fragmentRef;

        SearchResultAdapter(android.content.Context context,
                           List<PodcastIndexApi.SearchResult> results,
                           DiscoveryFragment fragment) {
            super(context, 0, results);
            this.fragmentRef = new WeakReference<>(fragment);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.search_result_item, parent, false);
            }

            DiscoveryFragment fragment = fragmentRef.get();
            TextView titleView = convertView.findViewById(R.id.result_title);
            TextView authorView = convertView.findViewById(R.id.result_author);
            TextView descriptionView = convertView.findViewById(R.id.result_description);

            // Check if we're displaying audiobooks
            if (fragment != null && fragment.currentSearchMode == SearchMode.AUDIOBOOKS &&
                position < fragment.currentAudiobookResults.size()) {
                // Display audiobook result
                LibriVoxApi.SearchResult audiobook = fragment.currentAudiobookResults.get(position);
                titleView.setText(audiobook.getTitle());

                // Author and Year
                StringBuilder authorInfo = new StringBuilder();
                if (audiobook.getAuthor() != null && !audiobook.getAuthor().isEmpty()) {
                    authorInfo.append(audiobook.getAuthor());
                }
                if (audiobook.getCopyrightYear() != null && !audiobook.getCopyrightYear().isEmpty()) {
                    if (authorInfo.length() > 0) {
                        authorInfo.append(" • ");
                    }
                    authorInfo.append(audiobook.getCopyrightYear());
                }
                authorView.setText(authorInfo.toString());

                // Duration
                String duration = audiobook.getFormattedDuration();
                if (duration != null && !duration.isEmpty()) {
                    descriptionView.setText(duration);
                } else {
                    descriptionView.setText("");
                }
            } else {
                // Display podcast result
                PodcastIndexApi.SearchResult result = getItem(position);
                if (result != null) {
                    titleView.setText(result.getTitle());
                    authorView.setText(result.getAuthor());

                    // Parse HTML and truncate description to a reasonable length
                    String description = result.getDescription();
                    if (description != null) {
                        // Parse HTML for proper formatting
                        CharSequence formattedDescription;
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            formattedDescription = android.text.Html.fromHtml(description, android.text.Html.FROM_HTML_MODE_COMPACT);
                        } else {
                            formattedDescription = android.text.Html.fromHtml(description);
                        }

                        // Truncate if needed
                        if (formattedDescription.length() > 100) {
                            descriptionView.setText(formattedDescription.subSequence(0, 100) + "...");
                        } else {
                            descriptionView.setText(formattedDescription);
                        }
                    } else {
                        descriptionView.setText("");
                    }
                }
            }

            return convertView;
        }
    }
}
