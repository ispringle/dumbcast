package com.ispringle.dumbcast.fragments;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;
import com.ispringle.dumbcast.services.PlaybackService;

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
    private TextView episodeTitleText;
    private TextView podcastNameText;
    private TextView chapterNameText;
    private TextView progressText;
    private TextView playPauseButton;
    private TextView skipBackwardButton;
    private TextView skipForwardButton;
    private TextView statusMessage;
    private ProgressBar progressBar;

    // Service binding
    private PlaybackService playbackService;
    private boolean serviceBound = false;

    // Data
    private PodcastRepository podcastRepository;

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
        DatabaseHelper dbHelper = DatabaseManager.getInstance(getContext());
        podcastRepository = new PodcastRepository(dbHelper);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_player, container, false);

        // Initialize UI components
        episodeTitleText = view.findViewById(R.id.player_episode_title);
        podcastNameText = view.findViewById(R.id.player_podcast_name);
        chapterNameText = view.findViewById(R.id.player_chapter_name);
        progressText = view.findViewById(R.id.player_progress_text);
        playPauseButton = view.findViewById(R.id.player_play_pause);
        skipBackwardButton = view.findViewById(R.id.player_skip_backward);
        skipForwardButton = view.findViewById(R.id.player_skip_forward);
        statusMessage = view.findViewById(R.id.player_status_message);
        progressBar = view.findViewById(R.id.player_progress_bar);

        // Set up click listeners
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayPause();
            }
        });

        skipBackwardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                skipBackward();
            }
        });

        skipForwardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                skipForward();
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

        // Load podcast name
        Podcast podcast = podcastRepository.getPodcastById(currentEpisode.getPodcastId());
        if (podcast != null) {
            podcastNameText.setText(podcast.getTitle());
            podcastNameText.setVisibility(View.VISIBLE);
        } else {
            podcastNameText.setVisibility(View.GONE);
        }

        // Update play/pause button
        if (playbackService.isPlaying()) {
            playPauseButton.setText("⏸ Pause");
        } else {
            playPauseButton.setText("▶ Play");
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
        episodeTitleText.setText("No episode loaded");
        podcastNameText.setVisibility(View.GONE);
        chapterNameText.setVisibility(View.GONE);
        progressText.setText("00:00 / 00:00");
        progressBar.setProgress(0);
        playPauseButton.setText("▶ Play");
        statusMessage.setText("Load an episode to start playback");
        statusMessage.setVisibility(View.VISIBLE);
    }

    /**
     * Update progress bar and text
     */
    private void updateProgress(int positionSeconds, int durationSeconds) {
        // Update progress text
        String positionStr = formatTime(positionSeconds);
        String durationStr = formatTime(durationSeconds);
        progressText.setText(positionStr + " / " + durationStr);

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
                    playPauseButton.setText("⏸ Pause");
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
                    playPauseButton.setText("▶ Play");
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
                    playPauseButton.setText("▶ Play");
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
}
