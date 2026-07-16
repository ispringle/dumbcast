package com.ispringle.dumbcast.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.sqlite.SQLiteDatabase;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.ispringle.dumbcast.MainActivity;
import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background service for audio playback with MediaPlayer integration.
 *
 * Features:
 * - MediaSession for Bluetooth / headset transport controls
 * - Audio focus for phone calls and other media
 * - Foreground service with media-style notification
 * - Play/pause/skip forward/backward controls
 * - Periodic position tracking
 * - Wakelock for screen-off playback
 */
public class PlaybackService extends Service {

    private static final String TAG = "PlaybackService";
    private static final String CHANNEL_ID = "playback_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int SKIP_FORWARD_MS = 30000; // 30 seconds
    private static final int SKIP_BACKWARD_MS = 30000; // 30 seconds
    private static final int POSITION_UPDATE_INTERVAL_MS = 10000; // 10 seconds

    // Actions for notification buttons and service control
    public static final String ACTION_PLAY = "com.ispringle.dumbcast.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.ispringle.dumbcast.ACTION_PAUSE";
    public static final String ACTION_SKIP_FORWARD = "com.ispringle.dumbcast.ACTION_SKIP_FORWARD";
    public static final String ACTION_SKIP_BACKWARD = "com.ispringle.dumbcast.ACTION_SKIP_BACKWARD";
    public static final String ACTION_STOP = "com.ispringle.dumbcast.ACTION_STOP";
    public static final String ACTION_LOAD_EPISODE = "LOAD_EPISODE";
    public static final String EXTRA_EPISODE_ID = "episode_id";

    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PowerManager.WakeLock wakeLock;
    private DatabaseHelper dbHelper;
    private EpisodeRepository episodeRepo;
    private Episode currentEpisode;
    private PlaybackListener listener;
    private final IBinder binder = new PlaybackBinder();

    // Position tracking
    private Handler positionHandler;
    private Runnable positionRunnable;
    private long lastPositionSave = 0;

    // Playback state
    private boolean isPlaying = false;
    private boolean resumeOnFocusGain = false;
    private boolean noisyReceiverRegistered = false;

    // Background thread for database operations
    private ExecutorService dbExecutor;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
        new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_LOSS:
                        // Permanent loss (e.g. another app took over) — pause and don't auto-resume
                        resumeOnFocusGain = false;
                        pause();
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        // Call / notification — pause, resume when focus returns
                        resumeOnFocusGain = isPlaying;
                        pause();
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        // Duck not useful for spoken word; pause instead
                        resumeOnFocusGain = isPlaying;
                        pause();
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN:
                        if (resumeOnFocusGain) {
                            resumeOnFocusGain = false;
                            play();
                        }
                        break;
                    default:
                        break;
                }
            }
        };

    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    /**
     * Interface for playback state callbacks
     */
    public interface PlaybackListener {
        void onPlaybackStarted(Episode episode);
        void onPlaybackPaused(Episode episode);
        void onPlaybackStopped();
        void onPlaybackCompleted(Episode episode);
        void onPositionChanged(int position, int duration);
        void onError(String error);
    }

    /**
     * Binder for local service binding
     */
    public class PlaybackBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        dbHelper = DatabaseManager.getInstance(this);
        episodeRepo = new EpisodeRepository(dbHelper);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        dbExecutor = Executors.newSingleThreadExecutor();

        mediaPlayer = new MediaPlayer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        } else {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
        mediaPlayer.setOnCompletionListener(mp -> onPlaybackCompleted());
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
            if (what == -38) {
                Log.d(TAG, "Ignoring spurious error -38");
                return true;
            }
            String errorMsg = "Playback error: " + getErrorDescription(what, extra);
            notifyError(errorMsg);
            updatePlaybackState(PlaybackStateCompat.STATE_ERROR);
            return true;
        });
        mediaPlayer.setOnPreparedListener(mp -> {
            Log.d(TAG, "MediaPlayer prepared");
            startPlayback();
        });

        initMediaSession();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dumbcast::PlaybackWakeLock");
        wakeLock.setReferenceCounted(false);

        positionHandler = new Handler();
        positionRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && mediaPlayer != null) {
                    try {
                        int position = mediaPlayer.getCurrentPosition() / 1000;
                        int duration = mediaPlayer.getDuration() / 1000;

                        long now = System.currentTimeMillis();
                        if (now - lastPositionSave >= POSITION_UPDATE_INTERVAL_MS) {
                            savePlaybackPosition(position);
                            lastPositionSave = now;
                        }

                        if (listener != null) {
                            listener.onPositionChanged(position, duration);
                        }
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);

                        positionHandler.postDelayed(this, 1000);
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error getting playback position", e);
                    }
                }
            }
        };

        createNotificationChannel();
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stop();
                stopSelf();
            }

            @Override
            public void onSkipToNext() {
                skipForward();
            }

            @Override
            public void onSkipToPrevious() {
                skipBackward();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) (pos / 1000L));
            }

            @Override
            public void onFastForward() {
                skipForward();
            }

            @Override
            public void onRewind() {
                skipBackward();
            }
        });
        mediaSession.setActive(true);
        updatePlaybackState(PlaybackStateCompat.STATE_NONE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle media button intents from Bluetooth / headset
        MediaButtonReceiver.handleIntent(mediaSession, intent);

        if (intent != null && intent.getAction() != null) {
            handleAction(intent.getAction(), intent);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");

        if (positionHandler != null) {
            positionHandler.removeCallbacks(positionRunnable);
        }

        if (currentEpisode != null && mediaPlayer != null) {
            try {
                int position = mediaPlayer.getCurrentPosition() / 1000;
                savePlaybackPosition(position);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error saving final position", e);
            }
        }

        abandonAudioFocus();
        unregisterNoisyReceiver();

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }

        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        if (dbExecutor != null) {
            dbExecutor.shutdown();
        }

        super.onDestroy();
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            return true;
        }
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .setAcceptsDelayedFocusGain(true)
                    .build();
            }
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            );
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
    }

    private void registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            registerReceiver(becomingNoisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyReceiverRegistered = true;
        }
    }

    private void unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            noisyReceiverRegistered = false;
        }
    }

    private void updateMediaMetadata() {
        if (mediaSession == null || currentEpisode == null) {
            return;
        }
        long durationMs = 0;
        if (mediaPlayer != null) {
            try {
                durationMs = mediaPlayer.getDuration();
                if (durationMs < 0) {
                    durationMs = 0;
                }
            } catch (IllegalStateException ignored) {
            }
        }
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentEpisode.getTitle())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentEpisode.getTitle())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        mediaSession.setMetadata(builder.build());
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) {
            return;
        }
        long positionMs = 0;
        if (mediaPlayer != null) {
            try {
                positionMs = mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException ignored) {
            }
        }
        long actions = PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_PAUSE
            | PlaybackStateCompat.ACTION_STOP
            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            | PlaybackStateCompat.ACTION_SEEK_TO
            | PlaybackStateCompat.ACTION_FAST_FORWARD
            | PlaybackStateCompat.ACTION_REWIND;

        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, positionMs, state == PlaybackStateCompat.STATE_PLAYING ? 1.0f : 0f)
            .build();
        mediaSession.setPlaybackState(playbackState);
    }

    /**
     * Set the playback listener for callbacks
     */
    public void setPlaybackListener(PlaybackListener listener) {
        this.listener = listener;
    }

    /**
     * Load and prepare an episode for playback
     */
    public void loadEpisode(Episode episode) {
        if (episode == null) {
            Log.e(TAG, "Cannot load null episode");
            notifyError("Invalid episode");
            return;
        }

        if (mediaPlayer == null) {
            Log.e(TAG, "MediaPlayer is null, cannot load episode");
            notifyError("Playback not available");
            return;
        }

        // Stop current playback if any
        if (isPlaying) {
            pause();
        }

        currentEpisode = episode;

        // Get audio URL (prefer downloaded file)
        String audioUrl;
        if (episode.isDownloaded()) {
            audioUrl = episode.getDownloadPath();
        } else {
            audioUrl = episode.getEnclosureUrl();
        }

        if (audioUrl == null || audioUrl.isEmpty()) {
            Log.e(TAG, "No valid audio URL for episode");
            notifyError("Episode has no audio file");
            return;
        }

        try {
            mediaPlayer.reset();

            // DIAGNOSTIC: Log audio URL being used
            Log.d(TAG, "Setting data source - Downloaded: " + episode.isDownloaded() +
                  ", URL: " + audioUrl);

            // DIAGNOSTIC: If it's a local file path, check if file exists
            if (episode.isDownloaded() && audioUrl != null && !audioUrl.startsWith("http")) {
                java.io.File file = new java.io.File(audioUrl);
                Log.d(TAG, "Local file check - Exists: " + file.exists() +
                      ", CanRead: " + file.canRead() +
                      ", Path: " + file.getAbsolutePath());
            }

            mediaPlayer.setDataSource(audioUrl);

            // Restore playback position
            int savedPosition = episode.getPlaybackPosition();
            if (savedPosition > 0) {
                mediaPlayer.setOnSeekCompleteListener(mp -> {
                    Log.d(TAG, "Seek to saved position complete: " + savedPosition);
                    mediaPlayer.setOnSeekCompleteListener(null);
                });
            }

            mediaPlayer.prepareAsync();
            Log.d(TAG, "Loading episode: " + episode.getTitle());

        } catch (IOException e) {
            Log.e(TAG, "Error loading episode", e);
            // Clear any listeners that may have been set
            mediaPlayer.setOnSeekCompleteListener(null);
            // Reset currentEpisode since load failed
            currentEpisode = null;
            notifyError("Failed to load episode: " + e.getMessage());
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaPlayer in invalid state", e);
            mediaPlayer.setOnSeekCompleteListener(null);
            currentEpisode = null;
            notifyError("Playback error - please try again");
        }
    }

    /**
     * Start playback
     */
    public void play() {
        if (currentEpisode == null) {
            Log.w(TAG, "No episode loaded");
            notifyError("No episode to play");
            return;
        }

        if (mediaPlayer == null) {
            Log.e(TAG, "MediaPlayer is null");
            notifyError("Playback not available");
            return;
        }

        try {
            if (!isPlaying) {
                startPlayback();
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error starting playback", e);
            notifyError("Failed to start playback");
        }
    }

    /**
     * Internal method to start playback
     */
    private void startPlayback() {
        if (mediaPlayer == null || currentEpisode == null) {
            return;
        }

        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio focus not granted");
            notifyError("Cannot play — audio focus denied");
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            return;
        }

        // Seek to saved position if needed
        int savedPosition = currentEpisode.getPlaybackPosition();
        if (savedPosition > 0 && mediaPlayer.getCurrentPosition() == 0) {
            mediaPlayer.seekTo(savedPosition * 1000);
        }

        mediaPlayer.start();
        isPlaying = true;
        resumeOnFocusGain = false;

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        registerNoisyReceiver();
        lastPositionSave = System.currentTimeMillis();
        positionHandler.post(positionRunnable);

        updateMediaMetadata();
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        startForeground(NOTIFICATION_ID, buildNotification());

        if (listener != null) {
            listener.onPlaybackStarted(currentEpisode);
        }

        Log.d(TAG, "Playback started: " + currentEpisode.getTitle());
    }

    /**
     * Pause playback
     */
    public void pause() {
        if (mediaPlayer != null && isPlaying) {
            mediaPlayer.pause();
            isPlaying = false;

            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }

            positionHandler.removeCallbacks(positionRunnable);
            unregisterNoisyReceiver();

            if (currentEpisode != null) {
                try {
                    int position = mediaPlayer.getCurrentPosition() / 1000;
                    savePlaybackPosition(position);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error saving position on pause", e);
                }
            }

            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(NOTIFICATION_ID, buildNotification());

            if (listener != null && currentEpisode != null) {
                listener.onPlaybackPaused(currentEpisode);
            }

            Log.d(TAG, "Playback paused");
        }
    }

    /**
     * Stop playback and clear current episode
     */
    public void stop() {
        if (mediaPlayer != null) {
            if (isPlaying) {
                try {
                    mediaPlayer.stop();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error stopping MediaPlayer", e);
                }
                isPlaying = false;
            }

            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }

            positionHandler.removeCallbacks(positionRunnable);

            if (currentEpisode != null) {
                try {
                    int position = mediaPlayer.getCurrentPosition() / 1000;
                    savePlaybackPosition(position);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error saving position on stop", e);
                }
            }
        }

        abandonAudioFocus();
        unregisterNoisyReceiver();
        currentEpisode = null;

        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        stopForeground(true);

        if (listener != null) {
            listener.onPlaybackStopped();
        }

        Log.d(TAG, "Playback stopped");
    }

    /**
     * Skip forward by 30 seconds
     */
    public void skipForward() {
        if (mediaPlayer != null && currentEpisode != null) {
            try {
                int currentPosition = mediaPlayer.getCurrentPosition();
                int duration = mediaPlayer.getDuration();
                int newPosition = Math.min(currentPosition + SKIP_FORWARD_MS, duration);

                mediaPlayer.seekTo(newPosition);
                savePlaybackPosition(newPosition / 1000);
                updatePlaybackState(isPlaying
                    ? PlaybackStateCompat.STATE_PLAYING
                    : PlaybackStateCompat.STATE_PAUSED);

                Log.d(TAG, "Skipped forward to: " + (newPosition / 1000) + "s");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error skipping forward", e);
            }
        }
    }

    /**
     * Skip backward by 30 seconds
     */
    public void skipBackward() {
        if (mediaPlayer != null && currentEpisode != null) {
            try {
                int currentPosition = mediaPlayer.getCurrentPosition();
                int newPosition = Math.max(currentPosition - SKIP_BACKWARD_MS, 0);

                mediaPlayer.seekTo(newPosition);
                savePlaybackPosition(newPosition / 1000);
                updatePlaybackState(isPlaying
                    ? PlaybackStateCompat.STATE_PLAYING
                    : PlaybackStateCompat.STATE_PAUSED);

                Log.d(TAG, "Skipped backward to: " + (newPosition / 1000) + "s");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error skipping backward", e);
            }
        }
    }

    /**
     * Seek to specific position in seconds
     */
    public void seekTo(int positionSeconds) {
        if (mediaPlayer != null && currentEpisode != null) {
            try {
                int duration = mediaPlayer.getDuration() / 1000;
                int clampedPosition = Math.max(0, Math.min(positionSeconds, duration));

                mediaPlayer.seekTo(clampedPosition * 1000);
                savePlaybackPosition(clampedPosition);
                updatePlaybackState(isPlaying
                    ? PlaybackStateCompat.STATE_PLAYING
                    : PlaybackStateCompat.STATE_PAUSED);

                Log.d(TAG, "Seeked to: " + clampedPosition + "s");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error seeking", e);
            }
        }
    }

    /**
     * Get current playback position in seconds
     */
    public int getCurrentPosition() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition() / 1000;
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error getting position", e);
            }
        }
        return 0;
    }

    /**
     * Get duration in seconds
     */
    public int getDuration() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getDuration() / 1000;
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error getting duration", e);
            }
        }
        return 0;
    }

    /**
     * Check if currently playing
     */
    public boolean isPlaying() {
        return isPlaying;
    }

    /**
     * Get current episode
     */
    public Episode getCurrentEpisode() {
        return currentEpisode;
    }

    /**
     * Handle playback completion: mark LISTENED, auto-delete local download, free the file handle.
     */
    private void onPlaybackCompleted() {
        Log.d(TAG, "Playback completed");

        isPlaying = false;
        resumeOnFocusGain = false;

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        positionHandler.removeCallbacks(positionRunnable);
        unregisterNoisyReceiver();
        abandonAudioFocus();

        final Episode finished = currentEpisode;
        if (finished != null) {
            int duration = getDuration();
            if (duration > 0) {
                finished.setPlaybackPosition(duration);
            }
            finished.setState(EpisodeState.LISTENED);
            finished.setPlayedAt(System.currentTimeMillis());

            final long episodeId = finished.getId();
            final String downloadPath = finished.isDownloaded() ? finished.getDownloadPath() : null;

            // Release MediaPlayer's hold on the file before deleting it
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.reset();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error resetting MediaPlayer after completion", e);
                }
            }

            // Clear download from in-memory episode immediately
            if (downloadPath != null) {
                finished.setDownloadPath(null);
                finished.setDownloadedAt(null);
            }

            if (dbExecutor != null && !dbExecutor.isShutdown()) {
                dbExecutor.execute(() -> {
                    try {
                        // Persist position at end
                        if (duration > 0) {
                            episodeRepo.updateEpisodePlaybackPosition(episodeId, duration);
                        }

                        // Single DB update: LISTENED + clear download columns
                        // (avoids race where removeFromBacklog overwrote LISTENED with AVAILABLE)
                        int rows = episodeRepo.markListenedAndClearDownload(episodeId);
                        Log.d(TAG, "Completion DB update rows=" + rows + " episode=" + episodeId);

                        if (downloadPath != null && !downloadPath.isEmpty()) {
                            File file = new File(downloadPath);
                            if (file.exists()) {
                                if (file.delete()) {
                                    Log.d(TAG, "Auto-deleted finished download: " + downloadPath);
                                } else {
                                    Log.w(TAG, "Failed to delete finished download: " + downloadPath);
                                }
                            } else {
                                Log.d(TAG, "Download already gone: " + downloadPath);
                            }
                        }

                        // Refresh NEW/BACKLOG tabs
                        Intent broadcast = new Intent(DownloadService.ACTION_EPISODE_STATE_CHANGED);
                        LocalBroadcastManager.getInstance(PlaybackService.this).sendBroadcast(broadcast);
                    } catch (Exception e) {
                        Log.e(TAG, "Error finishing episode after completion", e);
                    }
                });
            }

            if (listener != null) {
                listener.onPlaybackCompleted(finished);
            }
        }

        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    /**
     * Handle intent actions from notification buttons and episode loading
     */
    private void handleAction(String action, Intent intent) {
        switch (action) {
            case ACTION_LOAD_EPISODE:
                // Load episode by ID
                if (intent != null && intent.hasExtra(EXTRA_EPISODE_ID)) {
                    long episodeId = intent.getLongExtra(EXTRA_EPISODE_ID, -1);
                    if (episodeId != -1) {
                        loadEpisodeById(episodeId);
                    }
                }
                break;
            case ACTION_PLAY:
                play();
                break;
            case ACTION_PAUSE:
                pause();
                break;
            case ACTION_SKIP_FORWARD:
                skipForward();
                break;
            case ACTION_SKIP_BACKWARD:
                skipBackward();
                break;
            case ACTION_STOP:
                stop();
                stopSelf();
                break;
        }
    }

    /**
     * Load an episode by ID from the database
     */
    private void loadEpisodeById(long episodeId) {
        // Load episode on background thread
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.execute(() -> {
                try {
                    Episode episode = episodeRepo.getEpisodeById(episodeId);
                    if (episode != null) {
                        // Load episode on main thread
                        new android.os.Handler(getMainLooper()).post(() -> {
                            loadEpisode(episode);
                        });
                    } else {
                        Log.e(TAG, "Episode not found: " + episodeId);
                        notifyError("Episode not found");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error loading episode by ID", e);
                    notifyError("Failed to load episode");
                }
            });
        }
    }

    /**
     * Save playback position to database
     * Uses background thread to avoid blocking playback
     */
    private void savePlaybackPosition(int positionSeconds) {
        if (currentEpisode == null) {
            return;
        }

        final long episodeId = currentEpisode.getId();
        final int position = positionSeconds;

        // Update in-memory episode immediately
        currentEpisode.setPlaybackPosition(positionSeconds);

        // Save to database on background thread
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.execute(() -> {
                try {
                    int rowsAffected = episodeRepo.updateEpisodePlaybackPosition(episodeId, position);
                    if (rowsAffected > 0) {
                        Log.d(TAG, "Saved position: " + position + "s for episode ID: " + episodeId);
                    } else {
                        Log.w(TAG, "Failed to save position - episode may have been deleted: " + episodeId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error saving playback position to database", e);
                }
            });
        }
    }

    /**
     * Mark episode as LISTENED using the repository
     * This sets the state to LISTENED and updates played_at timestamp
     */
    private void markAsListened() {
        if (currentEpisode == null) {
            return;
        }

        final long episodeId = currentEpisode.getId();

        // Update in-memory episode
        currentEpisode.setState(EpisodeState.LISTENED);
        currentEpisode.setPlayedAt(System.currentTimeMillis());

        // Update database on background thread
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.execute(() -> {
                try {
                    int rowsAffected = episodeRepo.updateEpisodeState(episodeId, EpisodeState.LISTENED);
                    if (rowsAffected > 0) {
                        Log.d(TAG, "Episode marked as LISTENED in database: " + episodeId);
                    } else {
                        Log.w(TAG, "Failed to mark as LISTENED - episode may have been deleted: " + episodeId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error marking episode as LISTENED in database", e);
                }
            });
        }
    }

    /**
     * Remove episode from BACKLOG by setting state to AVAILABLE
     * This happens automatically when a BACKLOG episode finishes playing
     */
    private void removeFromBacklog() {
        if (currentEpisode == null) {
            return;
        }

        final long episodeId = currentEpisode.getId();

        // Update in-memory episode
        currentEpisode.setState(EpisodeState.AVAILABLE);

        // Update database on background thread
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.execute(() -> {
                try {
                    int rowsAffected = episodeRepo.updateEpisodeState(episodeId, EpisodeState.AVAILABLE);
                    if (rowsAffected > 0) {
                        Log.d(TAG, "Episode removed from BACKLOG in database: " + episodeId);
                    } else {
                        Log.w(TAG, "Failed to remove from BACKLOG - episode may have been deleted: " + episodeId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error removing episode from BACKLOG in database", e);
                }
            });
        }
    }

    /**
     * Update played_at timestamp (for partial playback)
     */
    private void updatePlayedAt() {
        if (currentEpisode == null) {
            return;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_EPISODE_PLAYED_AT, System.currentTimeMillis());

        db.update(
            DatabaseHelper.TABLE_EPISODES,
            values,
            DatabaseHelper.COL_EPISODE_ID + " = ?",
            new String[]{String.valueOf(currentEpisode.getId())}
        );

        currentEpisode.setPlayedAt(System.currentTimeMillis());
    }

    /**
     * Create notification channel for Android O and above
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Podcast playback controls");
            channel.setShowBadge(false);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Build notification with playback controls and MediaSession token for BT/lockscreen.
     */
    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_NAVIGATE_TO_TAB, 0); // TAB_NOW_PLAYING
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT
        );

        PendingIntent skipBackwardIntent = PendingIntent.getService(
            this, 101,
            new Intent(this, PlaybackService.class).setAction(ACTION_SKIP_BACKWARD),
            PendingIntent.FLAG_UPDATE_CURRENT
        );
        PendingIntent playPauseIntent = PendingIntent.getService(
            this, 102,
            new Intent(this, PlaybackService.class).setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT
        );
        PendingIntent skipForwardIntent = PendingIntent.getService(
            this, 103,
            new Intent(this, PlaybackService.class).setAction(ACTION_SKIP_FORWARD),
            PendingIntent.FLAG_UPDATE_CURRENT
        );
        PendingIntent stopIntent = PendingIntent.getService(
            this, 104,
            new Intent(this, PlaybackService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        String title = currentEpisode != null ? currentEpisode.getTitle() : "No episode";
        String text = isPlaying ? "Playing" : "Paused";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_rew, "<<30s", skipBackwardIntent)
            .addAction(
                isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying ? "Pause" : "Play",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_ff, "30s>>", skipForwardIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent);

        if (mediaSession != null) {
            builder.setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        }

        return builder.build();
    }

    /**
     * Notify listener of error
     */
    private void notifyError(String error) {
        if (listener != null) {
            listener.onError(error);
        }
    }

    /**
     * Get human-readable description of MediaPlayer error codes
     */
    private String getErrorDescription(int what, int extra) {
        String whatStr;
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                whatStr = "MEDIA_ERROR_UNKNOWN";
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                whatStr = "MEDIA_ERROR_SERVER_DIED";
                break;
            case -38:
                whatStr = "MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK";
                break;
            default:
                whatStr = "Error " + what;
        }

        String extraStr;
        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO:
                extraStr = "MEDIA_ERROR_IO";
                break;
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                extraStr = "MEDIA_ERROR_MALFORMED";
                break;
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                extraStr = "MEDIA_ERROR_UNSUPPORTED";
                break;
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                extraStr = "MEDIA_ERROR_TIMED_OUT";
                break;
            default:
                extraStr = "Extra " + extra;
        }

        return whatStr + " (" + extraStr + ")";
    }
}
