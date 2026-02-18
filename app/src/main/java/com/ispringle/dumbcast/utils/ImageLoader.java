package com.ispringle.dumbcast.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import com.ispringle.dumbcast.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Image loading utility with memory and disk caching.
 *
 * Features:
 * - LruCache for memory caching of recently used images
 * - Disk cache to avoid re-downloading images
 * - AsyncTask for background loading without blocking UI
 * - WeakReference to prevent memory leaks
 * - Graceful error handling with placeholder fallback
 *
 * Usage:
 *   ImageLoader.getInstance(context).loadImage(context, imageUrl, imageView);
 */
public class ImageLoader {

    private static final String TAG = "ImageLoader";
    private static final String CACHE_DIR_NAME = "artwork";
    private static final int MAX_MEMORY_CACHE_SIZE = 4 * 1024 * 1024; // 4MB
    private static final long MAX_DISK_CACHE_SIZE = 50 * 1024 * 1024; // 50MB

    private static ImageLoader instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final File diskCacheDir;

    /**
     * Private constructor for singleton pattern.
     */
    private ImageLoader(Context context) {
        // Initialize memory cache
        memoryCache = new LruCache<String, Bitmap>(MAX_MEMORY_CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // Return size in bytes
                return bitmap.getByteCount();
            }
        };

        // Initialize disk cache directory
        diskCacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
        if (!diskCacheDir.exists()) {
            if (!diskCacheDir.mkdirs()) {
                Log.w(TAG, "Failed to create disk cache directory");
            }
        }

        Log.d(TAG, "ImageLoader initialized with cache dir: " + diskCacheDir.getAbsolutePath());
    }

    /**
     * Get singleton instance of ImageLoader.
     */
    public static synchronized ImageLoader getInstance(Context context) {
        if (instance == null) {
            instance = new ImageLoader(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Load image from URL into ImageView with caching.
     * Shows placeholder while loading and on error.
     *
     * @param context Context for accessing resources
     * @param imageUrl URL of the image to load (can be null)
     * @param imageView ImageView to load image into
     */
    public void loadImage(Context context, String imageUrl, ImageView imageView) {
        loadImageWithFallback(context, imageUrl, null, imageView);
    }

    /**
     * Load image with fallback support.
     * Tries to load primaryUrl first, falls back to fallbackUrl if primary fails or is null.
     * Shows placeholder while loading and on error.
     *
     * @param context Context for accessing resources
     * @param primaryUrl Primary image URL to try first (e.g., episode artwork)
     * @param fallbackUrl Fallback image URL if primary fails (e.g., podcast artwork)
     * @param imageView ImageView to load image into
     */
    public void loadImageWithFallback(Context context, String primaryUrl, String fallbackUrl, ImageView imageView) {
        // Handle case where both URLs are null/empty
        if ((primaryUrl == null || primaryUrl.trim().isEmpty()) &&
            (fallbackUrl == null || fallbackUrl.trim().isEmpty())) {
            setPlaceholder(context, imageView);
            return;
        }

        // Check memory cache for both URLs
        // Try primary URL first
        if (primaryUrl != null && !primaryUrl.trim().isEmpty()) {
            Bitmap cachedBitmap = memoryCache.get(primaryUrl);
            if (cachedBitmap != null) {
                Log.d(TAG, "Image loaded from memory cache (primary): " + primaryUrl);
                imageView.setImageBitmap(cachedBitmap);
                imageView.setBackgroundColor(0); // Clear background
                imageView.setTag(primaryUrl); // Mark which URL is displayed
                return;
            }
        }

        // Try fallback URL in cache
        if (fallbackUrl != null && !fallbackUrl.trim().isEmpty()) {
            Bitmap cachedBitmap = memoryCache.get(fallbackUrl);
            if (cachedBitmap != null) {
                Log.d(TAG, "Image loaded from memory cache (fallback): " + fallbackUrl);
                imageView.setImageBitmap(cachedBitmap);
                imageView.setBackgroundColor(0); // Clear background
                imageView.setTag(fallbackUrl); // Mark which URL is displayed
                return;
            }
        }

        // Show placeholder while loading
        setPlaceholder(context, imageView);

        // Set tag to a composite key representing both URLs for race condition checking
        String compositeKey = (primaryUrl != null ? primaryUrl : "") + "|" + (fallbackUrl != null ? fallbackUrl : "");
        imageView.setTag(compositeKey);

        // Load image in background with fallback support
        new LoadImageTask(context, imageView, primaryUrl, fallbackUrl).execute();
    }

    /**
     * Set placeholder image on ImageView.
     * Uses the podcast-brain icon as a fallback when no artwork is available.
     */
    private void setPlaceholder(Context context, ImageView imageView) {
        imageView.setBackgroundColor(0); // Clear any background color
        imageView.setImageResource(com.ispringle.dumbcast.R.drawable.ic_podcast_brain);
    }

    /**
     * Get cached bitmap from memory.
     */
    private Bitmap getBitmapFromMemCache(String key) {
        return memoryCache.get(key);
    }

    /**
     * Add bitmap to memory cache.
     */
    private void addBitmapToMemCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null && bitmap != null) {
            memoryCache.put(key, bitmap);
        }
    }

    /**
     * Get file path for disk cache entry.
     */
    private File getDiskCacheFile(String imageUrl) {
        String filename = md5(imageUrl);
        return new File(diskCacheDir, filename);
    }

    /**
     * Load bitmap from disk cache.
     */
    private Bitmap loadFromDiskCache(String imageUrl) {
        File cacheFile = getDiskCacheFile(imageUrl);
        if (cacheFile.exists()) {
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                if (bitmap != null) {
                    Log.d(TAG, "Image loaded from disk cache: " + imageUrl);
                    return bitmap;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading from disk cache: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Save bitmap to disk cache.
     */
    private void saveToDiskCache(String imageUrl, Bitmap bitmap) {
        File cacheFile = getDiskCacheFile(imageUrl);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(cacheFile);
            // Use JPEG for smaller cache size (PNG ignores quality parameter)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.flush();
            Log.d(TAG, "Image saved to disk cache: " + imageUrl);

            // Clean up disk cache if needed
            cleanupDiskCacheIfNeeded();
        } catch (IOException e) {
            Log.e(TAG, "Error saving to disk cache: " + e.getMessage());
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing file output stream: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Download bitmap from URL.
     */
    private Bitmap downloadBitmap(String imageUrl) {
        HttpURLConnection connection = null;
        InputStream inputStream = null;

        try {
            URL url = new URL(imageUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000); // 10 seconds
            connection.setReadTimeout(10000); // 10 seconds
            connection.setDoInput(true);
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error downloading image: " + responseCode);
                return null;
            }

            inputStream = connection.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

            if (bitmap != null) {
                Log.d(TAG, "Image downloaded from URL: " + imageUrl);
            } else {
                Log.e(TAG, "Failed to decode bitmap from stream");
            }

            return bitmap;

        } catch (IOException e) {
            Log.e(TAG, "Error downloading image: " + e.getMessage());
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing input stream: " + e.getMessage());
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Generate MD5 hash of string for cache filename.
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "MD5 algorithm not available: " + e.getMessage());
            // Fallback to simple hash
            return String.valueOf(input.hashCode());
        }
    }

    /**
     * Clean up disk cache if it exceeds the maximum size.
     * Deletes oldest files first until cache size is under limit.
     */
    private void cleanupDiskCacheIfNeeded() {
        if (!diskCacheDir.exists()) {
            return;
        }

        // Calculate current cache size
        File[] cacheFiles = diskCacheDir.listFiles();
        if (cacheFiles == null || cacheFiles.length == 0) {
            return;
        }

        long totalSize = 0;
        for (File file : cacheFiles) {
            totalSize += file.length();
        }

        // If under limit, no cleanup needed
        if (totalSize <= MAX_DISK_CACHE_SIZE) {
            Log.d(TAG, "Disk cache size: " + (totalSize / 1024) + "KB (under limit)");
            return;
        }

        Log.d(TAG, "Disk cache size: " + (totalSize / 1024) + "KB (exceeds limit, cleaning up)");

        // Sort files by last modified time (oldest first)
        Arrays.sort(cacheFiles, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return Long.compare(f1.lastModified(), f2.lastModified());
            }
        });

        // Delete oldest files until we're under the limit
        for (File file : cacheFiles) {
            if (totalSize <= MAX_DISK_CACHE_SIZE) {
                break;
            }

            long fileSize = file.length();
            if (file.delete()) {
                totalSize -= fileSize;
                Log.d(TAG, "Deleted old cache file: " + file.getName());
            } else {
                Log.w(TAG, "Failed to delete cache file: " + file.getName());
            }
        }

        Log.d(TAG, "Disk cache cleanup complete. New size: " + (totalSize / 1024) + "KB");
    }

    /**
     * AsyncTask to load image in background thread with fallback support.
     * Uses WeakReference to prevent memory leaks.
     */
    private class LoadImageTask extends AsyncTask<Void, Void, Bitmap> {

        private final WeakReference<Context> contextRef;
        private final WeakReference<ImageView> imageViewRef;
        private final String primaryUrl;
        private final String fallbackUrl;
        private String successfulUrl; // Track which URL successfully loaded

        LoadImageTask(Context context, ImageView imageView, String primaryUrl, String fallbackUrl) {
            this.contextRef = new WeakReference<>(context);
            this.imageViewRef = new WeakReference<>(imageView);
            this.primaryUrl = primaryUrl;
            this.fallbackUrl = fallbackUrl;
            this.successfulUrl = null;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {
            Bitmap bitmap = null;

            // Try primary URL first (if valid)
            if (primaryUrl != null && !primaryUrl.trim().isEmpty()) {
                // Check if task was cancelled before expensive operation
                if (isCancelled()) {
                    Log.d(TAG, "Task cancelled before loading primary URL");
                    return null;
                }

                Log.d(TAG, "Trying primary URL: " + primaryUrl);
                bitmap = loadBitmapFromUrl(primaryUrl);
                if (bitmap != null) {
                    successfulUrl = primaryUrl;
                    return bitmap;
                }
                Log.d(TAG, "Primary URL failed, trying fallback");
            }

            // Try fallback URL if primary failed or was null
            if (fallbackUrl != null && !fallbackUrl.trim().isEmpty()) {
                // Check if task was cancelled before expensive operation
                if (isCancelled()) {
                    Log.d(TAG, "Task cancelled before loading fallback URL");
                    return null;
                }

                Log.d(TAG, "Trying fallback URL: " + fallbackUrl);
                bitmap = loadBitmapFromUrl(fallbackUrl);
                if (bitmap != null) {
                    successfulUrl = fallbackUrl;
                    return bitmap;
                }
                Log.d(TAG, "Fallback URL also failed");
            }

            return null;
        }

        /**
         * Load bitmap from a single URL (check cache, then download).
         */
        private Bitmap loadBitmapFromUrl(String url) {
            // Try disk cache first
            Bitmap bitmap = loadFromDiskCache(url);
            if (bitmap != null) {
                return bitmap;
            }

            // Download from network
            bitmap = downloadBitmap(url);
            if (bitmap != null) {
                // Save to disk cache
                saveToDiskCache(url, bitmap);
            }

            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            ImageView imageView = imageViewRef.get();
            Context context = contextRef.get();

            if (imageView == null || context == null) {
                // ImageView or Context was garbage collected
                return;
            }

            // Check if ImageView is still waiting for this URL pair (race condition check)
            String expectedCompositeKey = (primaryUrl != null ? primaryUrl : "") + "|" + (fallbackUrl != null ? fallbackUrl : "");
            Object tag = imageView.getTag();
            if (tag == null || !tag.equals(expectedCompositeKey)) {
                // ImageView is now being used for a different URL, don't set bitmap
                Log.d(TAG, "ImageView tag mismatch, skipping update (race condition avoided)");
                return;
            }

            if (bitmap != null && successfulUrl != null) {
                // Add to memory cache using the successful URL as key
                addBitmapToMemCache(successfulUrl, bitmap);

                // Set bitmap to ImageView
                imageView.setImageBitmap(bitmap);
                imageView.setBackgroundColor(0); // Clear background
                imageView.setTag(successfulUrl); // Update tag to successful URL

                Log.d(TAG, "Successfully loaded image from: " + successfulUrl);
            } else {
                // Error loading image from both URLs, show placeholder
                Log.w(TAG, "Failed to load image from primary and fallback URLs, showing placeholder");
                setPlaceholder(context, imageView);
            }
        }
    }
}
