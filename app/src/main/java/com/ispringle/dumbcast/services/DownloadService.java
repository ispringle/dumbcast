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
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import android.support.v4.app.NotificationCompat;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.DatabaseManager;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.EpisodeState;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;
import com.ispringle.dumbcast.utils.RssFeed;
import com.ispringle.dumbcast.utils.RssParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
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

        try {
            downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DatabaseHelper dbHelper = DatabaseManager.getInstance(this);
            episodeRepository = new EpisodeRepository(dbHelper);
            podcastRepository = new PodcastRepository(dbHelper);
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            downloadEpisodeMap = new HashMap<>();

            // Register broadcast receiver for download completion
            downloadCompleteReceiver = new DownloadCompleteReceiver(this);
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            registerReceiver(downloadCompleteReceiver, filter);

            // Create notification channel for download notifications
            createNotificationChannel();

            Log.d(TAG, "DownloadService created");
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate, cleaning up", e);
            // Ensure receiver is unregistered if registration succeeded before exception
            if (downloadCompleteReceiver != null) {
                try {
                    unregisterReceiver(downloadCompleteReceiver);
                } catch (IllegalArgumentException ignored) {
                    // Receiver was not registered, ignore
                }
                downloadCompleteReceiver = null;
            }
            throw e; // Re-throw to signal service creation failure
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if ("ACTION_DOWNLOAD_EPISODE".equals(action)) {
                final long episodeId = intent.getLongExtra("episode_id", -1);
                if (episodeId != -1) {
                    // Run download on background thread to avoid NetworkOnMainThreadException
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
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
                    }).start();
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

        // Unregister broadcast receiver safely
        if (downloadCompleteReceiver != null) {
            try {
                unregisterReceiver(downloadCompleteReceiver);
                downloadCompleteReceiver = null;
            } catch (IllegalArgumentException e) {
                // Receiver was not registered or already unregistered
                Log.w(TAG, "Receiver already unregistered", e);
            }
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
            // Resolve redirects manually because:
            // 1. DownloadManager doesn't handle HTTP->HTTPS redirects
            // 2. DownloadManager has a low redirect limit (~5) but podcast analytics chains can be longer
            Log.d(TAG, "Resolving URL: " + enclosureUrl);
            String finalUrl = resolveRedirects(enclosureUrl, 15); // Allow up to 15 redirects
            Log.d(TAG, "Final URL: " + finalUrl);

            // Extract file extension from URL
            String fileExtension = getFileExtension(finalUrl);

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
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(finalUrl))
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
     * Package-private so DownloadCompleteReceiver can access it.
     *
     * @param downloadId The ID of the completed download
     */
    void handleDownloadComplete(long downloadId) {
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
                    // DIAGNOSTIC: Log the URI conversion
                    Log.d(TAG, "Download complete URI conversion - LocalURI: " + localUri);

                    // Parse URI properly to get file path
                    Uri uri = Uri.parse(localUri);
                    String filePath;

                    // Handle file:// URIs correctly
                    if ("file".equals(uri.getScheme())) {
                        filePath = uri.getPath();
                    } else {
                        // For other schemes (content://), use path as-is
                        filePath = uri.toString();
                    }

                    Log.d(TAG, "Converted to FilePath: " + filePath);

                    // Verify file actually exists before saving to database
                    File file = new File(filePath);
                    if (!file.exists()) {
                        Log.e(TAG, "ERROR: Downloaded file does not exist at path: " + filePath);
                        showErrorNotification("Download failed", "File not found at: " + filePath);
                    } else {
                        // Update database - mark as downloaded
                        long downloadedAt = System.currentTimeMillis();
                        int updated = episodeRepository.updateEpisodeDownload(episodeId, filePath, downloadedAt);

                        if (updated > 0) {
                            // Move episode to BACKLOG state (downloaded episodes go to backlog)
                            episodeRepository.updateEpisodeState(episodeId, EpisodeState.BACKLOG);

                            Episode episode = episodeRepository.getEpisodeById(episodeId);
                            if (episode != null) {
                                // Fetch and save description from RSS feed
                                fetchAndSaveDescription(episode);

                                showSuccessNotification(episode.getTitle());
                                Log.d(TAG, "Download completed successfully: " + episode.getTitle());
                            }
                        } else {
                            Log.e(TAG, "Failed to update database for episode ID: " + episodeId);
                            showErrorNotification("Download completed", "Failed to update database");
                        }
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
     * Resolve redirects for a URL and return the final URL.
     * DownloadManager doesn't handle HTTP->HTTPS redirects well, so we pre-resolve them.
     *
     * @param url The URL to resolve
     * @param maxRedirects Maximum number of redirects to follow
     * @return The final URL after following redirects
     * @throws IOException If network error occurs or too many redirects
     */
    private String resolveRedirects(String url, int maxRedirects) throws IOException {
        if (maxRedirects <= 0) {
            throw new IOException("Too many redirects");
        }

        HttpURLConnection connection = null;
        try {
            URL urlObj = new URL(url);
            connection = (HttpURLConnection) urlObj.openConnection();
            connection.setRequestMethod("HEAD");  // Use HEAD to avoid downloading content
            connection.setInstanceFollowRedirects(false);  // Handle redirects manually
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Dumbcast/1.0");

            int responseCode = connection.getResponseCode();

            // Handle redirects (301, 302, 303, 307, 308)
            if (responseCode >= 300 && responseCode < 400) {
                String newUrl = connection.getHeaderField("Location");
                if (newUrl == null) {
                    throw new IOException("Redirect with no Location header");
                }

                Log.d(TAG, "Following redirect: " + url + " -> " + newUrl);
                return resolveRedirects(newUrl, maxRedirects - 1);
            }

            // Not a redirect, return the URL as-is
            return url;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Sanitize a string for use as a file name.
     * Removes or replaces characters that are invalid in file names.
     *
     * @param name The name to sanitize
     * @return Sanitized file name
     */
    private static String sanitizeFileName(String name) {
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
     * Notification auto-dismisses after 3 seconds to prevent notification spam.
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

        final NotificationManager notificationManager =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            final int notificationId = (int) System.currentTimeMillis();
            notificationManager.notify(notificationId, builder.build());

            // Auto-dismiss notification after 3 seconds to prevent spam
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    notificationManager.cancel(notificationId);
                }
            }, 3000);
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
     * Must be static because it's registered in AndroidManifest.
     */
    public static class DownloadCompleteReceiver extends BroadcastReceiver {
        private final WeakReference<DownloadService> serviceRef;

        public DownloadCompleteReceiver(DownloadService service) {
            this.serviceRef = new WeakReference<>(service);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

                if (downloadId != -1) {
                    Log.d(TAG, "Download complete broadcast received for ID: " + downloadId);
                    DownloadService service = serviceRef.get();
                    if (service != null) {
                        service.handleDownloadComplete(downloadId);
                    }
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

    /**
     * Fetch episode description from RSS feed and save to database.
     * This is called after an episode is successfully downloaded.
     * If RSS is unavailable or episode not found, description remains null (non-critical failure).
     *
     * @param episode The episode to fetch description for
     */
    private void fetchAndSaveDescription(Episode episode) {
        // Run on background thread to avoid blocking
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "Fetching description for downloaded episode: " + episode.getTitle());

                    // Get podcast to retrieve RSS feed URL
                    Podcast podcast = podcastRepository.getPodcastById(episode.getPodcastId());
                    if (podcast == null) {
                        Log.w(TAG, "Cannot fetch description: Podcast not found (ID: " + episode.getPodcastId() + ")");
                        return;
                    }

                    // Fetch and parse RSS feed
                    RssFeed feed = fetchRssFeed(podcast.getFeedUrl());
                    if (feed == null) {
                        Log.w(TAG, "Cannot fetch description: RSS feed unavailable for " + podcast.getTitle());
                        return;
                    }

                    // Find matching episode in feed by GUID
                    String description = null;
                    for (RssFeed.RssItem item : feed.getItems()) {
                        // Match by GUID (accounting for generated GUIDs)
                        String itemGuid = item.getGuid();
                        if (itemGuid == null || itemGuid.trim().isEmpty()) {
                            // Generate GUID same way as insertEpisodesFromFeed
                            if (item.getEnclosureUrl() != null && !item.getEnclosureUrl().trim().isEmpty()) {
                                itemGuid = "url:" + item.getEnclosureUrl();
                            } else if (item.getPublishedAt() > 0) {
                                itemGuid = "title-date:" + item.getTitle() + ":" + item.getPublishedAt();
                            } else {
                                itemGuid = "title:" + item.getTitle();
                            }
                        }

                        if (itemGuid.equals(episode.getGuid())) {
                            description = item.getBestDescription();
                            break;
                        }
                    }

                    if (description != null && !description.trim().isEmpty()) {
                        // Save description to database
                        int updated = episodeRepository.updateEpisodeDescription(episode.getId(), description);
                        if (updated > 0) {
                            Log.d(TAG, "Successfully saved description for episode: " + episode.getTitle());
                        } else {
                            Log.w(TAG, "Failed to save description for episode: " + episode.getTitle());
                        }
                    } else {
                        Log.w(TAG, "No description found in RSS feed for episode: " + episode.getTitle());
                    }

                } catch (Exception e) {
                    // Non-critical failure - episode is downloaded, just missing description
                    Log.w(TAG, "Failed to fetch description for episode: " + episode.getTitle(), e);
                }
            }
        }).start();
    }

    /**
     * Fetch RSS feed from URL with redirect handling.
     * Returns null if fetch fails (non-critical - description fetch is optional).
     *
     * @param feedUrl The RSS feed URL
     * @return Parsed RssFeed object, or null if fetch/parse fails
     */
    private RssFeed fetchRssFeed(String feedUrl) {
        return fetchRssFeedWithRedirects(feedUrl, 5);
    }

    /**
     * Fetch RSS feed with manual redirect following.
     * Returns null if fetch fails.
     *
     * @param feedUrl The RSS feed URL
     * @param maxRedirects Maximum number of redirects to follow
     * @return Parsed RssFeed object, or null if fetch/parse fails
     */
    private RssFeed fetchRssFeedWithRedirects(String feedUrl, int maxRedirects) {
        if (maxRedirects <= 0) {
            Log.w(TAG, "Too many redirects for RSS feed: " + feedUrl);
            return null;
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(feedUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Dumbcast/1.0");
            connection.setInstanceFollowRedirects(false);

            int responseCode = connection.getResponseCode();

            // Handle redirects (301, 302, 303, 307, 308)
            if (responseCode >= 300 && responseCode < 400) {
                String newUrl = connection.getHeaderField("Location");
                if (newUrl == null) {
                    Log.w(TAG, "Redirect with no Location header for: " + feedUrl);
                    return null;
                }

                Log.d(TAG, "Following redirect: " + feedUrl + " -> " + newUrl);
                connection.disconnect();
                return fetchRssFeedWithRedirects(newUrl, maxRedirects - 1);
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP error code " + responseCode + " for RSS feed: " + feedUrl);
                return null;
            }

            InputStream inputStream = connection.getInputStream();
            RssParser parser = new RssParser();
            return parser.parse(inputStream);

        } catch (IOException | XmlPullParserException e) {
            Log.w(TAG, "Failed to fetch RSS feed: " + feedUrl, e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Scan for orphaned downloaded files and update database.
     * This handles cases where download completes but the broadcast is missed.
     * Should be called on app startup to recover from interrupted downloads.
     *
     * @param context Application context
     */
    public static void recoverOrphanedDownloads(final Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "Starting orphaned download recovery scan");

                    DatabaseHelper dbHelper = DatabaseManager.getInstance(context);
                    EpisodeRepository episodeRepository = new EpisodeRepository(dbHelper);
                    PodcastRepository podcastRepository = new PodcastRepository(dbHelper);

                    File podcastsDir = context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS);
                    if (podcastsDir == null || !podcastsDir.exists()) {
                        Log.d(TAG, "No podcasts directory found, nothing to recover");
                        return;
                    }

                    int recoveredCount = 0;
                    int verifiedCount = 0;

                    // Iterate through podcast directories
                    File[] podcastDirs = podcastsDir.listFiles();
                    if (podcastDirs == null) {
                        Log.d(TAG, "Podcasts directory is empty");
                        return;
                    }

                    for (File podcastDir : podcastDirs) {
                        if (!podcastDir.isDirectory()) {
                            continue;
                        }

                        String sanitizedPodcastName = podcastDir.getName();

                        // Get all podcasts and find matching podcast by sanitized name
                        List<Podcast> allPodcasts = podcastRepository.getAllPodcasts();
                        Podcast matchingPodcast = null;

                        for (Podcast podcast : allPodcasts) {
                            if (sanitizeFileName(podcast.getTitle()).equals(sanitizedPodcastName)) {
                                matchingPodcast = podcast;
                                break;
                            }
                        }

                        if (matchingPodcast == null) {
                            Log.w(TAG, "Found orphaned podcast directory (no matching podcast in DB): " + sanitizedPodcastName);
                            continue;
                        }

                        // Get all episodes for this podcast
                        List<Episode> episodes = episodeRepository.getEpisodesByPodcast(matchingPodcast.getId());

                        // Scan episode files in this podcast directory
                        File[] episodeFiles = podcastDir.listFiles();
                        if (episodeFiles == null) {
                            continue;
                        }

                        for (File episodeFile : episodeFiles) {
                            if (!episodeFile.isFile()) {
                                continue;
                            }

                            String fileName = episodeFile.getName();
                            String filePath = episodeFile.getAbsolutePath();

                            // Try to match file to an episode
                            Episode matchedEpisode = null;

                            // First, check if any episode already has this exact download path
                            for (Episode episode : episodes) {
                                if (filePath.equals(episode.getDownloadPath())) {
                                    // Episode already knows about this file
                                    verifiedCount++;
                                    matchedEpisode = episode;
                                    break;
                                }
                            }

                            if (matchedEpisode != null) {
                                continue; // Already tracked in database
                            }

                            // File exists but no episode claims it - try to match by filename
                            String fileNameWithoutExt = fileName;
                            int lastDotIndex = fileName.lastIndexOf('.');
                            if (lastDotIndex > 0) {
                                fileNameWithoutExt = fileName.substring(0, lastDotIndex);
                            }

                            // Find episode with matching sanitized title
                            for (Episode episode : episodes) {
                                String sanitizedEpisodeTitle = sanitizeFileName(episode.getTitle());

                                if (sanitizedEpisodeTitle.equals(fileNameWithoutExt)) {
                                    // Check if file actually exists (defensive check)
                                    if (episodeFile.exists() && episodeFile.length() > 0) {
                                        // Only update if episode doesn't already have a download path
                                        if (episode.getDownloadPath() == null || episode.getDownloadPath().isEmpty()) {
                                            // Update database to mark as downloaded
                                            long downloadedAt = episodeFile.lastModified();
                                            int updated = episodeRepository.updateEpisodeDownload(
                                                episode.getId(),
                                                filePath,
                                                downloadedAt
                                            );

                                            if (updated > 0) {
                                                // Move to BACKLOG state
                                                episodeRepository.updateEpisodeState(episode.getId(), EpisodeState.BACKLOG);
                                                recoveredCount++;
                                                Log.d(TAG, "Recovered orphaned download: " + episode.getTitle() +
                                                      " (File: " + filePath + ")");
                                            }
                                        }
                                    }
                                    matchedEpisode = episode;
                                    break;
                                }
                            }

                            if (matchedEpisode == null) {
                                Log.w(TAG, "Found orphaned file with no matching episode: " + filePath);
                            }
                        }
                    }

                    Log.d(TAG, "Download recovery complete. Recovered: " + recoveredCount +
                          ", Verified: " + verifiedCount);

                } catch (Exception e) {
                    Log.e(TAG, "Error during orphaned download recovery", e);
                }
            }
        }).start();
    }
}
