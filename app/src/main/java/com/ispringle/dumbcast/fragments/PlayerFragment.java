package com.ispringle.dumbcast.fragments;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;
import com.ispringle.dumbcast.services.PlaybackService;
import com.ispringle.dumbcast.utils.ImageLoader;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * Fragment for audio playback control.
 *
 * Features:
 * - Binds to PlaybackService for playback control
 * - Displays currently playing episode metadata
 * - Shows real-time playback progress
 * - Play/pause/skip controls via keypad
 * - Chapter support (UI ready)
 * - Simple text-based UI for performance
 *
 * Keypad Controls:
 * - 5 or Enter: Play/Pause
 * - *: Skip backward 30s
 * - #: Skip forward 30s
 */
public class PlayerFragment extends Fragment implements PlaybackService.PlaybackListener {

    private static final String TAG = "PlayerFragment";

    // UI Components
    private ImageView artworkImage;
    private TextView episodeTitleText;
    private TextView chapterNameText;
    private TextView progressText;
    private TextView elapsedTimeText;
    private TextView totalTimeText;
    private TextView playPauseButton;
    private TextView statusMessage;
    private ProgressBar progressBar;

    // Service binding
    private PlaybackService playbackService;
    private boolean serviceBound = false;

    // Data
    private PodcastRepository podcastRepository;
    private EpisodeRepository episodeRepository;

    public PlayerFragment() {
        // Required empty public constructor
    }

    public static PlayerFragment newInstance() {
        return new PlayerFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize repositories using singleton DatabaseHelper
        // DatabaseManager is a singleton that handles the database lifecycle
        // No explicit cleanup needed in onDestroy() as the singleton persists across app lifecycle
        DatabaseHelper dbHelper = DatabaseManager.getInstance(getContext());
        podcastRepository = new PodcastRepository(dbHelper);
        episodeRepository = new EpisodeRepository(dbHelper);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_player, container, false);

        // Initialize UI components
        artworkImage = view.findViewById(R.id.player_artwork);
        episodeTitleText = view.findViewById(R.id.player_episode_title);
        chapterNameText = view.findViewById(R.id.player_chapter_name);
        progressText = view.findViewById(R.id.player_progress_text);
        playPauseButton = view.findViewById(R.id.player_play_pause);
        statusMessage = view.findViewById(R.id.player_status_message);
        progressBar = view.findViewById(R.id.player_progress_bar);

        // Initialize new separate time displays
        elapsedTimeText = view.findViewById(R.id.player_elapsed_time);
        totalTimeText = view.findViewById(R.id.player_total_time);

        // Enable marquee effect for episode title
        episodeTitleText.setSelected(true);

        // Set up click listeners
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayPause();
            }
        });

        // Enable keypad event handling
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        view.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    return handleKeyDown(keyCode);
                }
                return false;
            }
        });

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Bind to PlaybackService
        Intent intent = new Intent(getContext(), PlaybackService.class);
        getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        // Unbind from service
        if (serviceBound) {
            playbackService.setPlaybackListener(null);
            getContext().unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    /**
     * ServiceConnection for binding to PlaybackService
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PlaybackService.PlaybackBinder binder = (PlaybackService.PlaybackBinder) service;
            playbackService = binder.getService();
            serviceBound = true;

            // Register as playback listener
            playbackService.setPlaybackListener(PlayerFragment.this);

            // Update UI with current state
            updateUI();

            Log.d(TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            playbackService = null;
            Log.d(TAG, "Service disconnected");
        }
    };

    /**
     * Handle keypad events for KaiOS navigation
     */
    private boolean handleKeyDown(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_STAR:
                // * key: Skip backward
                skipBackward();
                return true;
            case KeyEvent.KEYCODE_POUND:
                // # key: Skip forward
                skipForward();
                return true;
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                // 5 or Enter: Play/Pause
                togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_MENU:
                // Menu key: Show context menu
                showContextMenu();
                return true;
            default:
                return false;
        }
    }

    /**
     * Toggle play/pause
     */
    private void togglePlayPause() {
        if (!serviceBound || playbackService == null) {
            showError("Playback service not available");
            return;
        }

        if (playbackService.isPlaying()) {
            playbackService.pause();
        } else {
            playbackService.play();
        }
    }

    /**
     * Skip backward 30 seconds
     */
    private void skipBackward() {
        if (!serviceBound || playbackService == null) {
            showError("Playback service not available");
            return;
        }

        playbackService.skipBackward();
        Toast.makeText(getContext(), "Skip backward", Toast.LENGTH_SHORT).show();
    }

    /**
     * Skip forward 30 seconds
     */
    private void skipForward() {
        if (!serviceBound || playbackService == null) {
            showError("Playback service not available");
            return;
        }

        playbackService.skipForward();
        Toast.makeText(getContext(), "Skip forward", Toast.LENGTH_SHORT).show();
    }

    /**
     * Update UI with current playback state
     */
    private void updateUI() {
        if (!serviceBound || playbackService == null) {
            showNoEpisodeState();
            return;
        }

        Episode currentEpisode = playbackService.getCurrentEpisode();
        if (currentEpisode == null) {
            showNoEpisodeState();
            return;
        }

        // Update episode info
        episodeTitleText.setText(currentEpisode.getTitle());

        // Load artwork with fallback: try episode artwork first, fall back to podcast artwork
        Podcast podcast = podcastRepository.getPodcastById(currentEpisode.getPodcastId());
        if (podcast != null) {
            String episodeArtworkUrl = currentEpisode.getArtworkUrl();
            String podcastArtworkUrl = podcast.getArtworkUrl();
            ImageLoader.getInstance(getContext()).loadImageWithFallback(
                getContext(), episodeArtworkUrl, podcastArtworkUrl, artworkImage);
        } else {
            // No podcast found, show placeholder
            artworkImage.setBackgroundColor(0);
            artworkImage.setImageResource(R.drawable.ic_podcast_brain);
        }

        // Update play/pause button
        if (playbackService.isPlaying()) {
            playPauseButton.setText("");
        } else {
            playPauseButton.setText("");
        }

        // Update progress
        int position = playbackService.getCurrentPosition();
        int duration = playbackService.getDuration();
        updateProgress(position, duration);

        // Hide status message
        statusMessage.setVisibility(View.GONE);
    }

    /**
     * Show "no episode loaded" state
     */
    private void showNoEpisodeState() {
        artworkImage.setBackgroundColor(0);
        artworkImage.setImageResource(R.drawable.ic_podcast_brain);
        episodeTitleText.setText(R.string.no_episode_loaded);
        chapterNameText.setVisibility(View.GONE);
        elapsedTimeText.setText("0:00");
        totalTimeText.setText("0:00");
        progressBar.setProgress(0);
        playPauseButton.setText("");
        statusMessage.setText(R.string.load_episode_message);
        statusMessage.setVisibility(View.VISIBLE);
    }

    /**
     * Update progress bar and text
     */
    private void updateProgress(int positionSeconds, int durationSeconds) {
        // Update separate time displays
        elapsedTimeText.setText(formatTime(positionSeconds));
        totalTimeText.setText(formatTime(durationSeconds));

        // Update progress bar
        if (durationSeconds > 0) {
            int percentage = (int) ((positionSeconds * 100.0) / durationSeconds);
            progressBar.setProgress(percentage);
        } else {
            progressBar.setProgress(0);
        }
    }

    /**
     * Format time in seconds to MM:SS or HH:MM:SS
     */
    private String formatTime(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }

    /**
     * Show error message
     */
    private void showError(String message) {
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    // PlaybackListener interface implementation

    @Override
    public void onPlaybackStarted(Episode episode) {
        Log.d(TAG, "Playback started: " + episode.getTitle());
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    playPauseButton.setText("");
                }
            });
        }
    }

    @Override
    public void onPlaybackPaused(Episode episode) {
        Log.d(TAG, "Playback paused: " + episode.getTitle());
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    playPauseButton.setText("");
                }
            });
        }
    }

    @Override
    public void onPlaybackStopped() {
        Log.d(TAG, "Playback stopped");
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showNoEpisodeState();
                }
            });
        }
    }

    @Override
    public void onPlaybackCompleted(Episode episode) {
        Log.d(TAG, "Playback completed: " + episode.getTitle());
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    playPauseButton.setText("");
                    statusMessage.setText("Finished");
                    statusMessage.setVisibility(View.VISIBLE);
                    Toast.makeText(getContext(), "Episode finished", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    public void onPositionChanged(final int position, final int duration) {
        // Update UI on main thread
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateProgress(position, duration);
                }
            });
        }
    }

    @Override
    public void onError(final String error) {
        Log.e(TAG, "Playback error: " + error);
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showError(error);
                    statusMessage.setText("Error: " + error);
                    statusMessage.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    /**
     * Show context menu for the currently playing episode.
     */
    private void showContextMenu() {
        if (!serviceBound || playbackService == null) {
            showError(getString(R.string.player_menu_no_service));
            return;
        }

        Episode currentEpisode = playbackService.getCurrentEpisode();
        if (currentEpisode == null) {
            showError(getString(R.string.player_menu_no_episode));
            return;
        }

        if (getContext() == null) {
            return;
        }

        // Build menu items in fixed order
        final String[] menuArray = new String[] {
            getString(R.string.player_menu_delete_episode),
            getString(R.string.player_menu_view_chapters),
            getString(R.string.player_menu_skip_to_timestamp),
            getString(R.string.player_menu_view_show_notes)
        };

        // Show AlertDialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(currentEpisode.getTitle());
        builder.setItems(menuArray, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                handleContextMenuAction(currentEpisode, which);
            }
        });
        builder.show();
    }

    /**
     * Handle context menu action selection.
     * Fixed menu indices:
     * - 0: Delete Episode
     * - 1: View Chapters
     * - 2: Skip to Timestamp
     * - 3: View Show Notes
     *
     * @param episode The episode to act on
     * @param actionIndex The selected menu item index
     */
    private void handleContextMenuAction(Episode episode, int actionIndex) {
        switch (actionIndex) {
            case 0: // Delete Episode
                deleteEpisode(episode);
                break;
            case 1: // View Chapters
                viewChapters(episode);
                break;
            case 2: // Skip to Timestamp
                skipToTimestamp();
                break;
            case 3: // View Show Notes
                viewShowNotes(episode);
                break;
            default:
                Log.w(TAG, "Unknown menu action index: " + actionIndex);
                break;
        }
    }

    /**
     * Delete downloaded file for the currently playing episode.
     * Shows confirmation dialog before deleting.
     * @param episode The episode to delete download for
     */
    private void deleteEpisode(final Episode episode) {
        if (getContext() == null) {
            return;
        }

        // Defensive check: episode should be downloaded if we're in Now Playing
        if (!episode.isDownloaded()) {
            Toast.makeText(getContext(), R.string.player_episode_not_downloaded, Toast.LENGTH_SHORT).show();
            return;
        }

        String message = getString(R.string.player_delete_confirm_message, episode.getTitle());
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.player_delete_confirm_title);
        builder.setMessage(message);
        builder.setPositiveButton(R.string.dialog_delete, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                new DeleteEpisodeTask(PlayerFragment.this, episodeRepository, episode).execute();
            }
        });
        builder.setNegativeButton(R.string.dialog_cancel, null);
        builder.show();
    }

    /**
     * View chapters for an episode.
     * Shows chapter list if available, otherwise shows a message.
     * @param episode The episode to view chapters for
     */
    private void viewChapters(Episode episode) {
        if (getContext() == null) {
            return;
        }

        // Check if episode has chapters
        String chaptersUrl = episode.getChaptersUrl();
        if (chaptersUrl == null || chaptersUrl.isEmpty()) {
            Toast.makeText(getContext(), R.string.player_no_chapters, Toast.LENGTH_SHORT).show();
            return;
        }

        // TODO: Implement chapter parsing and display
        // For now, show placeholder message
        Toast.makeText(getContext(), R.string.player_chapters_not_implemented, Toast.LENGTH_SHORT).show();
    }

    /**
     * Show input dialog to skip to a specific timestamp.
     * Supports MM:SS and HH:MM:SS formats.
     */
    private void skipToTimestamp() {
        if (getContext() == null) {
            return;
        }

        if (!serviceBound || playbackService == null) {
            showError(getString(R.string.player_menu_no_service));
            return;
        }

        // Create input dialog
        final EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(getString(R.string.player_timestamp_hint));

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.player_skip_to_timestamp_title);
        builder.setMessage(R.string.player_skip_to_timestamp_message);
        builder.setView(input);
        builder.setPositiveButton(getString(R.string.player_skip_button), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String timestamp = input.getText().toString().trim();
                handleTimestampInput(timestamp);
            }
        });
        builder.setNegativeButton(R.string.dialog_cancel, null);
        builder.show();
    }

    /**
     * Parse timestamp string and seek to the specified position.
     * @param timestamp Timestamp string in MM:SS or HH:MM:SS format
     */
    private void handleTimestampInput(String timestamp) {
        if (timestamp.isEmpty()) {
            Toast.makeText(getContext(), R.string.player_invalid_timestamp, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check service is still available
        if (!serviceBound || playbackService == null) {
            showError(getString(R.string.player_menu_no_service));
            return;
        }

        int seconds = parseTimestamp(timestamp);
        if (seconds < 0) {
            Toast.makeText(getContext(), R.string.player_invalid_timestamp, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if timestamp is within episode duration
        int duration = playbackService.getDuration();
        if (seconds > duration) {
            String message = getString(R.string.player_timestamp_exceeds_duration, formatTime(duration));
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            return;
        }

        // Seek to timestamp
        playbackService.seekTo(seconds);
        String message = getString(R.string.player_skipped_to, formatTime(seconds));
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Parse timestamp string to seconds.
     * Supports MM:SS and HH:MM:SS formats.
     * @param timestamp Timestamp string
     * @return Seconds, or -1 if invalid
     */
    private int parseTimestamp(String timestamp) {
        try {
            String[] parts = timestamp.split(":");
            if (parts.length == 2) {
                // MM:SS format
                int minutes = Integer.parseInt(parts[0]);
                int seconds = Integer.parseInt(parts[1]);
                if (minutes < 0 || seconds < 0 || seconds >= 60) {
                    return -1;
                }
                return minutes * 60 + seconds;
            } else if (parts.length == 3) {
                // HH:MM:SS format
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                int seconds = Integer.parseInt(parts[2]);
                if (hours < 0 || minutes < 0 || minutes >= 60 || seconds < 0 || seconds >= 60) {
                    return -1;
                }
                return hours * 3600 + minutes * 60 + seconds;
            }
            return -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * View episode show notes (description).
     * @param episode The episode to view show notes for
     */
    private void viewShowNotes(Episode episode) {
        if (getContext() == null) {
            return;
        }

        String description = episode.getDescription();
        if (description == null || description.isEmpty()) {
            Toast.makeText(getContext(), R.string.player_no_show_notes, Toast.LENGTH_SHORT).show();
            return;
        }

        // Parse HTML in description for proper formatting
        CharSequence formattedDescription;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            formattedDescription = android.text.Html.fromHtml(description, android.text.Html.FROM_HTML_MODE_COMPACT);
        } else {
            formattedDescription = android.text.Html.fromHtml(description);
        }

        // Show description in scrollable dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.player_show_notes_title);
        builder.setMessage(formattedDescription);
        builder.setPositiveButton(R.string.dialog_close, null);
        builder.show();
    }

    /**
     * AsyncTask to delete episode download on a background thread.
     */
    private static class DeleteEpisodeTask extends AsyncTask<Void, Void, Boolean> {
        private final WeakReference<PlayerFragment> fragmentRef;
        private final EpisodeRepository repository;
        private final Episode episode;

        DeleteEpisodeTask(PlayerFragment fragment, EpisodeRepository repository, Episode episode) {
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

            // Update database to clear download info and update state if needed
            int rowsUpdated = repository.deleteEpisodeDownloadAndUpdateState(episode.getId(), downloadPath);
            return rowsUpdated > 0;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            PlayerFragment fragment = fragmentRef.get();
            if (fragment == null || fragment.getContext() == null) return;

            if (success) {
                Toast.makeText(fragment.getContext(), R.string.player_delete_success, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(fragment.getContext(), R.string.player_delete_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
