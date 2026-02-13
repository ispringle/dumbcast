package com.ispringle.dumbcast.utils;

import android.util.Log;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Utility class for fetching RSS feeds with HTTP redirect support.
 */
public class RssFeedUtils {
    private static final String TAG = "RssFeedUtils";
    private static final int MAX_HTTP_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final String USER_AGENT = "Dumbcast/1.0";

    /**
     * Fetch RSS feed from URL with automatic redirect following.
     *
     * @param feedUrl The URL of the RSS feed
     * @return Parsed RssFeed object
     * @throws IOException If network or I/O error occurs
     * @throws XmlPullParserException If XML parsing error occurs
     */
    public static RssFeed fetchFeed(String feedUrl) throws IOException, XmlPullParserException {
        return fetchFeedWithRedirects(feedUrl, MAX_HTTP_REDIRECTS);
    }

    /**
     * Fetch RSS feed with manual redirect following.
     *
     * @param feedUrl The URL of the RSS feed
     * @param maxRedirects Maximum number of redirects to follow
     * @return Parsed RssFeed object
     * @throws IOException If network or I/O error occurs or too many redirects
     * @throws XmlPullParserException If XML parsing error occurs
     */
    private static RssFeed fetchFeedWithRedirects(String feedUrl, int maxRedirects)
            throws IOException, XmlPullParserException {
        if (maxRedirects <= 0) {
            throw new IOException("Too many redirects");
        }

        URL url = new URL(feedUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setInstanceFollowRedirects(false);

            int responseCode = connection.getResponseCode();

            // Handle redirects
            if (responseCode >= 300 && responseCode < 400) {
                String newUrl = connection.getHeaderField("Location");
                if (newUrl == null) {
                    throw new IOException("Redirect with no Location header");
                }

                Log.d(TAG, "Following redirect: " + feedUrl + " -> " + newUrl);
                connection.disconnect();
                return fetchFeedWithRedirects(newUrl, maxRedirects - 1);
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error code: " + responseCode);
            }

            InputStream inputStream = connection.getInputStream();
            RssParser parser = new RssParser();
            return parser.parse(inputStream);
        } finally {
            connection.disconnect();
        }
    }
}
