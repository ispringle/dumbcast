package com.ispringle.dumbcast.services;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import android.support.v4.app.NotificationCompat;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for managing episode downloads using Android's DownloadManager.
 * Handles download queuing, completion tracking, and database updates.
 * Downloads are saved to external files directory organized by podcast/episode.
 */
public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final String PREFS_NAME = "download_prefs";
    private static final String CHANNEL_ID = "download_channel";

    private DownloadManager downloadManager;
    private EpisodeRepository episodeRepository;
    private PodcastRepository podcastRepository;
    private SharedPreferences prefs;
    private DownloadCompleteReceiver downloadCompleteReceiver;

    // Map to track download ID -> episode ID mapping
    private Map<Long, Long> downloadEpisodeMap;

    @Override
    public void onCreate() {
        super.onCreate();

        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DatabaseHelper dbHelper = DatabaseManager.getInstance(this);
        episodeRepository = new EpisodeRepository(dbHelper);
        podcastRepository = new PodcastRepository(dbHelper);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        downloadEpisodeMap = new HashMap<>();

        // Register broadcast receiver for download completion
        downloadCompleteReceiver = new DownloadCompleteReceiver();
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        registerReceiver(downloadCompleteReceiver, filter);

        // Create notification channel for download notifications
        createNotificationChannel();

        Log.d(TAG, "DownloadService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if ("ACTION_DOWNLOAD_EPISODE".equals(action)) {
                long episodeId = intent.getLongExtra("episode_id", -1);
                if (episodeId != -1) {
                    Episode episode = episodeRepository.getEpisodeById(episodeId);
                    if (episode != null) {
                        Podcast podcast = podcastRepository.getPodcastById(episode.getPodcastId());
                        if (podcast != null) {
                            downloadEpisode(episode, podcast.getTitle());
                        } else {
                            Log.e(TAG, "Podcast not found for episode ID: " + episodeId);
                        }
                    } else {
                        Log.e(TAG, "Episode not found with ID: " + episodeId);
                    }
                }
            } else if ("ACTION_CANCEL_DOWNLOAD".equals(action)) {
                long episodeId = intent.getLongExtra("episode_id", -1);
                if (episodeId != -1) {
                    cancelDownload(episodeId);
                }
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // This is an unbound service
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Unregister broadcast receiver
        if (downloadCompleteReceiver != null) {
            unregisterReceiver(downloadCompleteReceiver);
        }

        Log.d(TAG, "DownloadService destroyed");
    }

    /**
     * Queue an episode for download using DownloadManager.
     *
     * @param episode The episode to download
     * @param podcastName The name of the podcast (for organizing files)
     */
    public void downloadEpisode(Episode episode, String podcastName) {
        if (episode.isDownloaded()) {
            Log.d(TAG, "Episode already downloaded: " + episode.getTitle());
            return;
        }

        String enclosureUrl = episode.getEnclosureUrl();
        if (enclosureUrl == null || enclosureUrl.isEmpty()) {
            Log.e(TAG, "Cannot download episode without enclosure URL: " + episode.getTitle());
            showErrorNotification("Download failed", "No download URL available");
            return;
        }

        try {
            // Extract file extension from URL
            String fileExtension = getFileExtension(enclosureUrl);

            // Sanitize names for file system
            String sanitizedPodcastName = sanitizeFileName(podcastName);
            String sanitizedEpisodeName = sanitizeFileName(episode.getTitle());

            // Build destination path: Podcasts/{podcast_name}/{episode_name}.{ext}
            File podcastDir = new File(
                getExternalFilesDir(Environment.DIRECTORY_PODCASTS),
                sanitizedPodcastName
            );

            // Create podcast directory if it doesn't exist
            if (!podcastDir.exists() && !podcastDir.mkdirs()) {
                Log.e(TAG, "Failed to create podcast directory: " + podcastDir.getAbsolutePath());
                showErrorNotification("Download failed", "Could not create directory");
                return;
            }

            String fileName = sanitizedEpisodeName + "." + fileExtension;
            File destinationFile = new File(podcastDir, fileName);

            // Configure download request
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(enclosureUrl))
                .setTitle(episode.getTitle())
                .setDescription("Downloading from " + podcastName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(destinationFile))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false);

            // Queue the download
            long downloadId = downloadManager.enqueue(request);

            // Store mapping of download ID to episode ID
            downloadEpisodeMap.put(downloadId, episode.getId());
            saveDownloadMapping(downloadId, episode.getId());

            Log.d(TAG, "Queued download for episode: " + episode.getTitle() +
                  " (Download ID: " + downloadId + ", Path: " + destinationFile.getAbsolutePath() + ")");

        } catch (Exception e) {
            Log.e(TAG, "Error queuing download for episode: " + episode.getTitle(), e);
            showErrorNotification("Download failed", e.getMessage());
        }
    }

    /**
     * Cancel an in-progress download for an episode.
     *
     * @param episodeId The ID of the episode whose download should be cancelled
     */
    public void cancelDownload(long episodeId) {
        // Find download ID for this episode
        Long downloadId = null;
        for (Map.Entry<Long, Long> entry : downloadEpisodeMap.entrySet()) {
            if (entry.getValue() == episodeId) {
                downloadId = entry.getKey();
                break;
            }
        }

        if (downloadId != null) {
            int removed = downloadManager.remove(downloadId);
            if (removed > 0) {
                downloadEpisodeMap.remove(downloadId);
                removeDownloadMapping(downloadId);
                Log.d(TAG, "Cancelled download for episode ID: " + episodeId);
            } else {
                Log.w(TAG, "Failed to cancel download for episode ID: " + episodeId);
            }
        } else {
            Log.w(TAG, "No active download found for episode ID: " + episodeId);
        }
    }

    /**
     * Handle download completion.
     *
     * @param downloadId The ID of the completed download
     */
    private void handleDownloadComplete(long downloadId) {
        // Get episode ID for this download
        Long episodeId = downloadEpisodeMap.get(downloadId);
        if (episodeId == null) {
            episodeId = getDownloadMapping(downloadId);
        }

        if (episodeId == null) {
            Log.w(TAG, "No episode mapping found for download ID: " + downloadId);
            return;
        }

        // Query download status
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        Cursor cursor = downloadManager.query(query);

        if (cursor != null && cursor.moveToFirst()) {
            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int status = cursor.getInt(statusIndex);

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                // Get local URI
                int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                String localUri = cursor.getString(uriIndex);

                if (localUri != null) {
                    // Convert URI to file path
                    String filePath = Uri.parse(localUri).getPath();

                    // Update database
                    long downloadedAt = System.currentTimeMillis();
                    int updated = episodeRepository.updateEpisodeDownload(episodeId, filePath, downloadedAt);

                    if (updated > 0) {
                        Episode episode = episodeRepository.getEpisodeById(episodeId);
                        if (episode != null) {
                            showSuccessNotification(episode.getTitle());
                            Log.d(TAG, "Download completed successfully: " + episode.getTitle());
                        }
                    } else {
                        Log.e(TAG, "Failed to update database for episode ID: " + episodeId);
                        showErrorNotification("Download completed", "Failed to update database");
                    }
                } else {
                    Log.e(TAG, "Local URI is null for download ID: " + downloadId);
                    showErrorNotification("Download failed", "Could not get file path");
                }
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                int reason = cursor.getInt(reasonIndex);
                String reasonText = getFailureReason(reason);

                Log.e(TAG, "Download failed for download ID " + downloadId + ": " + reasonText);
                showErrorNotification("Download failed", reasonText);
            }

            cursor.close();
        }

        // Clean up mapping
        downloadEpisodeMap.remove(downloadId);
        removeDownloadMapping(downloadId);
    }

    /**
     * Extract file extension from URL.
     * Defaults to "mp3" if extension cannot be determined.
     *
     * @param url The URL to extract extension from
     * @return The file extension (without dot)
     */
    private String getFileExtension(String url) {
        if (url == null || url.isEmpty()) {
            return "mp3";
        }

        // Remove query parameters
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            url = url.substring(0, queryIndex);
        }

        // Get extension from last dot
        int dotIndex = url.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < url.length() - 1) {
            String ext = url.substring(dotIndex + 1).toLowerCase();
            // Validate extension
            if (ext.matches("[a-z0-9]{2,5}")) {
                return ext;
            }
        }

        return "mp3";
    }

    /**
     * Sanitize a string for use as a file name.
     * Removes or replaces characters that are invalid in file names.
     *
     * @param name The name to sanitize
     * @return Sanitized file name
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown";
        }

        // Replace invalid characters with underscores
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_");

        // Trim whitespace and dots (problematic on some systems)
        sanitized = sanitized.trim().replaceAll("^\\.*", "").replaceAll("\\.*$", "");

        // Limit length to 100 characters
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }

        // Ensure not empty
        if (sanitized.isEmpty()) {
            sanitized = "unknown";
        }

        return sanitized;
    }

    /**
     * Get human-readable failure reason from DownloadManager reason code.
     *
     * @param reason The reason code from DownloadManager
     * @return Human-readable reason string
     */
    private String getFailureReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "Cannot resume download";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Storage not found";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "File already exists";
            case DownloadManager.ERROR_FILE_ERROR:
                return "File error";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "HTTP data error";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Insufficient storage space";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "Too many redirects";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "Unhandled HTTP code";
            case DownloadManager.ERROR_UNKNOWN:
            default:
                return "Unknown error";
        }
    }

    /**
     * Save download ID to episode ID mapping in SharedPreferences.
     * Used for persistence across service restarts.
     *
     * @param downloadId The download ID
     * @param episodeId The episode ID
     */
    private void saveDownloadMapping(long downloadId, long episodeId) {
        prefs.edit().putLong("download_" + downloadId, episodeId).apply();
    }

    /**
     * Get episode ID for a download ID from SharedPreferences.
     *
     * @param downloadId The download ID
     * @return The episode ID, or null if not found
     */
    private Long getDownloadMapping(long downloadId) {
        long episodeId = prefs.getLong("download_" + downloadId, -1);
        return episodeId != -1 ? episodeId : null;
    }

    /**
     * Remove download ID mapping from SharedPreferences.
     *
     * @param downloadId The download ID to remove
     */
    private void removeDownloadMapping(long downloadId) {
        prefs.edit().remove("download_" + downloadId).apply();
    }

    /**
     * Create notification channel for download notifications (Android O+).
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Episode Downloads",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notifications for episode download completion");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Show success notification for completed download.
     *
     * @param episodeTitle The title of the downloaded episode
     */
    private void showSuccessNotification(String episodeTitle) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(episodeTitle)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true);

        NotificationManager notificationManager =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    /**
     * Show error notification for failed download.
     *
     * @param title Notification title
     * @param message Error message
     */
    private void showErrorNotification(String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true);

        NotificationManager notificationManager =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    /**
     * BroadcastReceiver for handling download completion events.
     */
    public class DownloadCompleteReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

                if (downloadId != -1) {
                    Log.d(TAG, "Download complete broadcast received for ID: " + downloadId);
                    handleDownloadComplete(downloadId);
                }
            }
        }
    }

    /**
     * Helper method to start download for an episode.
     *
     * @param context Application context
     * @param episodeId The ID of the episode to download
     */
    public static void startDownload(Context context, long episodeId) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction("ACTION_DOWNLOAD_EPISODE");
        intent.putExtra("episode_id", episodeId);
        context.startService(intent);
    }

    /**
     * Helper method to cancel download for an episode.
     *
     * @param context Application context
     * @param episodeId The ID of the episode whose download should be cancelled
     */
    public static void cancelDownload(Context context, long episodeId) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction("ACTION_CANCEL_DOWNLOAD");
        intent.putExtra("episode_id", episodeId);
        context.startService(intent);
    }
}
