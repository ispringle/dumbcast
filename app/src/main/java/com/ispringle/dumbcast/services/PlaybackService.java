package com.ispringle.dumbcast.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.ispringle.dumbcast.MainActivity;
import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background service for audio playback with MediaPlayer integration.
 *
 * Features:
 * - Foreground service with persistent notification
 * - Play/pause/skip forward/backward controls
 * - Periodic position tracking (every 10 seconds)
 * - Wakelock for screen-off playback
 * - MediaPlayer lifecycle management
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

    // Background thread for database operations
    private ExecutorService dbExecutor;

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

        // Initialize background executor for database operations
        dbExecutor = Executors.newSingleThreadExecutor();

        // Initialize MediaPlayer
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(mp -> onPlaybackCompleted());
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
            notifyError("Playback error occurred");
            return true;
        });
        mediaPlayer.setOnPreparedListener(mp -> {
            Log.d(TAG, "MediaPlayer prepared");
            startPlayback();
        });

        // Acquire wakelock for screen-off playback
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dumbcast::PlaybackWakeLock");
        wakeLock.setReferenceCounted(false);

        // Initialize position tracking
        positionHandler = new Handler();
        positionRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && mediaPlayer != null) {
                    try {
                        int position = mediaPlayer.getCurrentPosition() / 1000; // Convert to seconds
                        int duration = mediaPlayer.getDuration() / 1000;

                        // Save position to database every 10 seconds
                        long now = System.currentTimeMillis();
                        if (now - lastPositionSave >= POSITION_UPDATE_INTERVAL_MS) {
                            savePlaybackPosition(position);
                            lastPositionSave = now;
                        }

                        // Notify listener of position change
                        if (listener != null) {
                            listener.onPositionChanged(position, duration);
                        }

                        positionHandler.postDelayed(this, 1000); // Update every second
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error getting playback position", e);
                    }
                }
            }
        };

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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

        // Stop position tracking
        if (positionHandler != null) {
            positionHandler.removeCallbacks(positionRunnable);
        }

        // Save final position
        if (currentEpisode != null && mediaPlayer != null) {
            try {
                int position = mediaPlayer.getCurrentPosition() / 1000;
                savePlaybackPosition(position);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error saving final position", e);
            }
        }

        // Release MediaPlayer
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }

        // Release wakelock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // Shutdown background executor
        if (dbExecutor != null) {
            dbExecutor.shutdown();
        }

        super.onDestroy();
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

        // Seek to saved position if needed
        int savedPosition = currentEpisode.getPlaybackPosition();
        if (savedPosition > 0 && mediaPlayer.getCurrentPosition() == 0) {
            mediaPlayer.seekTo(savedPosition * 1000);
        }

        mediaPlayer.start();
        isPlaying = true;

        // Acquire wakelock
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        // Start position tracking
        lastPositionSave = System.currentTimeMillis();
        positionHandler.post(positionRunnable);

        // Update notification
        startForeground(NOTIFICATION_ID, buildNotification());

        // Notify listener
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

            // Release wakelock
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }

            // Stop position tracking
            positionHandler.removeCallbacks(positionRunnable);

            // Save current position
            if (currentEpisode != null) {
                int position = mediaPlayer.getCurrentPosition() / 1000;
                savePlaybackPosition(position);
            }

            // Update notification
            NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(NOTIFICATION_ID, buildNotification());

            // Notify listener
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
                mediaPlayer.stop();
                isPlaying = false;
            }

            // Release wakelock
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }

            // Stop position tracking
            positionHandler.removeCallbacks(positionRunnable);

            // Save final position
            if (currentEpisode != null) {
                int position = mediaPlayer.getCurrentPosition() / 1000;
                savePlaybackPosition(position);
            }
        }

        currentEpisode = null;

        // Stop foreground service
        stopForeground(true);

        // Notify listener
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

                Log.d(TAG, "Skipped forward to: " + (newPosition / 1000) + "s");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error skipping forward", e);
            }
        }
    }

    /**
     * Skip backward by 15 seconds
     */
    public void skipBackward() {
        if (mediaPlayer != null && currentEpisode != null) {
            try {
                int currentPosition = mediaPlayer.getCurrentPosition();
                int newPosition = Math.max(currentPosition - SKIP_BACKWARD_MS, 0);

                mediaPlayer.seekTo(newPosition);
                savePlaybackPosition(newPosition / 1000);

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
     * Handle playback completion
     */
    private void onPlaybackCompleted() {
        Log.d(TAG, "Playback completed");

        isPlaying = false;

        // Release wakelock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // Stop position tracking
        positionHandler.removeCallbacks(positionRunnable);

        // Save final position and update state
        if (currentEpisode != null) {
            int duration = getDuration();
            int currentPosition = getCurrentPosition();
            savePlaybackPosition(duration);

            // Check if episode was played >90% - mark as LISTENED
            if (duration > 0) {
                double percentPlayed = (double) currentPosition / duration;
                if (percentPlayed >= 0.9) {
                    markAsListened();
                    Log.d(TAG, "Episode marked as LISTENED (played " +
                        String.format("%.1f%%", percentPlayed * 100) + ")");
                } else {
                    // Still update played_at timestamp even if not fully listened
                    updatePlayedAt();
                }
            }

            // Notify listener
            if (listener != null) {
                listener.onPlaybackCompleted(currentEpisode);
            }
        }

        // Update notification
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
     * Build notification with playback controls
     */
    private Notification buildNotification() {
        // Intent to open app when notification is clicked
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Action buttons
        PendingIntent skipBackwardIntent = PendingIntent.getService(
            this,
            0,
            new Intent(this, PlaybackService.class).setAction(ACTION_SKIP_BACKWARD),
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        PendingIntent playPauseIntent = PendingIntent.getService(
            this,
            0,
            new Intent(this, PlaybackService.class).setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        PendingIntent skipForwardIntent = PendingIntent.getService(
            this,
            0,
            new Intent(this, PlaybackService.class).setAction(ACTION_SKIP_FORWARD),
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        PendingIntent stopIntent = PendingIntent.getService(
            this,
            0,
            new Intent(this, PlaybackService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Build notification
        String title = currentEpisode != null ? currentEpisode.getTitle() : "No episode";
        String text = isPlaying ? "Playing" : "Paused";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // Add action buttons
        builder.addAction(0, "<<15s", skipBackwardIntent);
        builder.addAction(0, isPlaying ? "Pause" : "Play", playPauseIntent);
        builder.addAction(0, "30s>>", skipForwardIntent);

        // Add stop button if playing
        if (isPlaying) {
            builder.addAction(0, "Stop", stopIntent);
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
}
