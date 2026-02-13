package com.ispringle.dumbcast.utils;

import com.ispringle.dumbcast.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class PodcastIndexApi {
    private static final String BASE_URL = "https://api.podcastindex.org/api/1.0";

    // API keys loaded from local.properties via BuildConfig
    // Get your keys from https://api.podcastindex.org/
    // Add them to local.properties (see local.properties.example)
    private static final String API_KEY = BuildConfig.PODCAST_INDEX_API_KEY;
    private static final String API_SECRET = BuildConfig.PODCAST_INDEX_API_SECRET;
    private static final String USER_AGENT = "Dumbcast/1.0";

    /**
     * Searches for podcasts using the Podcast Index API
     * @param term Search query
     * @return List of search results
     * @throws Exception if the API call fails
     */
    public static List<SearchResult> search(String term) throws Exception {
        String encodedTerm = URLEncoder.encode(term, StandardCharsets.UTF_8.toString());
        String endpoint = "/search/byterm?q=" + encodedTerm;

        JSONObject response = makeRequest(endpoint);
        List<SearchResult> results = new ArrayList<>();

        if (response.has("feeds")) {
            JSONArray feeds = response.getJSONArray("feeds");
            for (int i = 0; i < feeds.length(); i++) {
                JSONObject feed = feeds.getJSONObject(i);
                results.add(SearchResult.fromJson(feed));
            }
        }

        return results;
    }

    /**
     * Makes an authenticated request to the Podcast Index API
     * @param endpoint API endpoint (e.g., "/search/byterm?q=term")
     * @return JSON response
     * @throws Exception if the request fails
     */
    private static JSONObject makeRequest(String endpoint) throws Exception {
        String urlString = BASE_URL + endpoint;
        URL url = new URL(urlString);

        // Generate auth headers
        long timestamp = System.currentTimeMillis() / 1000;
        String authHash = generateAuthHash(timestamp);

        // Create connection
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("X-Auth-Key", API_KEY);
        conn.setRequestProperty("X-Auth-Date", String.valueOf(timestamp));
        conn.setRequestProperty("Authorization", authHash);

        // Read response
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("API request failed with code: " + responseCode);
        }

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
        );
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        return new JSONObject(response.toString());
    }

    /**
     * Generates SHA-1 authentication hash for Podcast Index API
     * @param timestamp Unix timestamp
     * @return SHA-1 hash string
     */
    private static String generateAuthHash(long timestamp) throws Exception {
        String data = API_KEY + API_SECRET + timestamp;
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));

        // Convert to hex string
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }

        return hexString.toString();
    }

    /**
     * Represents a podcast search result from the Podcast Index API
     */
    public static class SearchResult {
        private final long id;
        private final String title;
        private final String description;
        private final String feedUrl;
        private final String artworkUrl;
        private final String author;
        private final int episodeCount;

        private SearchResult(long id, String title, String description, String feedUrl,
                           String artworkUrl, String author, int episodeCount) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.feedUrl = feedUrl;
            this.artworkUrl = artworkUrl;
            this.author = author;
            this.episodeCount = episodeCount;
        }

        /**
         * Creates a SearchResult from a JSON object
         * @param json JSON object from API response
         * @return SearchResult instance
         */
        static SearchResult fromJson(JSONObject json) throws Exception {
            long id = json.optLong("id", 0);
            String title = json.optString("title", "");
            String description = json.optString("description", "");
            String feedUrl = json.optString("url", "");
            String artworkUrl = json.optString("artwork", json.optString("image", ""));
            String author = json.optString("author", "");
            int episodeCount = json.optInt("episodeCount", 0);

            return new SearchResult(id, title, description, feedUrl, artworkUrl, author, episodeCount);
        }

        // Getters
        public long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public String getFeedUrl() {
            return feedUrl;
        }

        public String getArtworkUrl() {
            return artworkUrl;
        }

        public String getAuthor() {
            return author;
        }

        public int getEpisodeCount() {
            return episodeCount;
        }
    }
}
