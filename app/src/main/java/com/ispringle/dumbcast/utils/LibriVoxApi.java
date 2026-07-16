package com.ispringle.dumbcast.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * API client for LibriVox public domain audiobooks.
 * LibriVox provides free audiobooks in the public domain.
 */
public class LibriVoxApi {
    private static final String BASE_URL = "https://librivox.org/api/feed/audiobooks";
    private static final String USER_AGENT = "Dumbcast/1.0";

    /**
     * Searches for audiobooks on LibriVox
     * @param term Search query (searches title and author)
     * @return List of search results
     * @throws Exception if the API call fails
     */
    public static List<SearchResult> search(String term) throws Exception {
        String encodedTerm = URLEncoder.encode(term, StandardCharsets.UTF_8.toString());
        // LibriVox API supports title, author searches
        // Using title search with extended_search for better results
        String urlString = BASE_URL + "?title=^" + encodedTerm + "&extended=1&format=json&limit=20";

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);

        // Read response
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("LibriVox API request failed with code: " + responseCode);
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

        JSONObject jsonResponse = new JSONObject(response.toString());
        List<SearchResult> results = new ArrayList<>();

        if (jsonResponse.has("books")) {
            JSONArray books = jsonResponse.getJSONArray("books");
            for (int i = 0; i < books.length(); i++) {
                JSONObject book = books.getJSONObject(i);
                SearchResult result = SearchResult.fromJson(book);
                if (result != null) {
                    results.add(result);
                }
            }
        }

        return results;
    }

    /**
     * Represents an audiobook search result from LibriVox
     */
    public static class SearchResult {
        private final String id;
        private final String title;
        private final String description;
        private final String feedUrl;
        private final String artworkUrl;
        private final String author;
        private final String language;
        private final int totalTime; // Duration in seconds
        private final String copyrightYear;

        private SearchResult(String id, String title, String description, String feedUrl,
                           String artworkUrl, String author, String language, int totalTime,
                           String copyrightYear) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.feedUrl = feedUrl;
            this.artworkUrl = artworkUrl;
            this.author = author;
            this.language = language;
            this.totalTime = totalTime;
            this.copyrightYear = copyrightYear;
        }

        /**
         * Creates a SearchResult from a JSON object
         * @param json JSON object from API response
         * @return SearchResult instance, or null if RSS feed is not available
         */
        static SearchResult fromJson(JSONObject json) throws Exception {
            String id = json.optString("id", "");
            String title = json.optString("title", "");
            String description = json.optString("description", "");

            // LibriVox provides RSS feeds in the url_rss field
            String feedUrl = json.optString("url_rss", "");

            // Skip books without RSS feeds
            if (feedUrl == null || feedUrl.isEmpty()) {
                return null;
            }

            // Get artwork from url_zip_file or construct default
            String artworkUrl = "";
            if (json.has("url_zip_file")) {
                String zipUrl = json.optString("url_zip_file", "");
                // LibriVox artwork is typically at a predictable URL pattern
                if (!zipUrl.isEmpty()) {
                    artworkUrl = zipUrl.replaceAll("_64kb_mp3\\.zip$", ".jpg");
                }
            }

            // Get authors - LibriVox can have multiple authors
            StringBuilder authors = new StringBuilder();
            if (json.has("authors")) {
                JSONArray authorsArray = json.getJSONArray("authors");
                for (int i = 0; i < authorsArray.length(); i++) {
                    JSONObject authorObj = authorsArray.getJSONObject(i);
                    if (i > 0) {
                        authors.append(", ");
                    }
                    String firstName = authorObj.optString("first_name", "").trim();
                    String lastName = authorObj.optString("last_name", "").trim();
                    if (!firstName.isEmpty()) {
                        authors.append(firstName);
                    }
                    if (!firstName.isEmpty() && !lastName.isEmpty()) {
                        authors.append(" ");
                    }
                    if (!lastName.isEmpty()) {
                        authors.append(lastName);
                    }
                }
            }

            String language = json.optString("language", "English");
            int totalTime = json.optInt("totaltimesecs", 0);
            String copyrightYear = json.optString("copyright_year", "");

            return new SearchResult(id, title, description, feedUrl, artworkUrl,
                                  authors.toString().trim(), language, totalTime, copyrightYear);
        }

        // Getters
        public String getId() {
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

        public String getLanguage() {
            return language;
        }

        public int getTotalTime() {
            return totalTime;
        }

        public String getCopyrightYear() {
            return copyrightYear;
        }

        /**
         * Format duration for display
         * @return Human-readable duration string (e.g., "5h 30m")
         */
        public String getFormattedDuration() {
            if (totalTime <= 0) {
                return "";
            }
            int hours = totalTime / 3600;
            int minutes = (totalTime % 3600) / 60;

            if (hours > 0 && minutes > 0) {
                return hours + "h " + minutes + "m";
            } else if (hours > 0) {
                return hours + "h";
            } else if (minutes > 0) {
                return minutes + "m";
            }
            return "";
        }
    }
}
