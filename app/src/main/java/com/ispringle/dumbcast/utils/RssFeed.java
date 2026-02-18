package com.ispringle.dumbcast.utils;

import java.util.ArrayList;
import java.util.List;

public class RssFeed {
    private String title;
    private String description;
    private String link;
    private String imageUrl;
    private List<RssItem> items;

    public RssFeed() {
        this.items = new ArrayList<>();
    }

    // Getters and setters
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public List<RssItem> getItems() {
        return items;
    }

    public void addItem(RssItem item) {
        this.items.add(item);
    }

    // Nested RssItem class
    public static class RssItem {
        private String guid;
        private String title;
        private String description;
        private String contentEncoded;  // Rich HTML show notes from <content:encoded>
        private String itunesSummary;   // iTunes-specific summary from <itunes:summary>
        private String link;
        private String enclosureUrl;
        private String enclosureType;
        private long enclosureLength;
        private long publishedAt;
        private int duration;
        private String imageUrl;
        private String chaptersUrl;

        public RssItem() {
            this.enclosureLength = 0;
            this.publishedAt = 0;
            this.duration = 0;
        }

        // Getters and setters
        public String getGuid() {
            return guid;
        }

        public void setGuid(String guid) {
            this.guid = guid;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getContentEncoded() {
            return contentEncoded;
        }

        public void setContentEncoded(String contentEncoded) {
            this.contentEncoded = contentEncoded;
        }

        public String getItunesSummary() {
            return itunesSummary;
        }

        public void setItunesSummary(String itunesSummary) {
            this.itunesSummary = itunesSummary;
        }

        /**
         * Get the best available show notes content.
         * Priority: content:encoded > itunes:summary > description
         * This ensures we get the richest content available.
         */
        public String getBestDescription() {
            if (contentEncoded != null && !contentEncoded.trim().isEmpty()) {
                return contentEncoded;
            }
            if (itunesSummary != null && !itunesSummary.trim().isEmpty()) {
                return itunesSummary;
            }
            return description;
        }

        public String getLink() {
            return link;
        }

        public void setLink(String link) {
            this.link = link;
        }

        public String getEnclosureUrl() {
            return enclosureUrl;
        }

        public void setEnclosureUrl(String enclosureUrl) {
            this.enclosureUrl = enclosureUrl;
        }

        public String getEnclosureType() {
            return enclosureType;
        }

        public void setEnclosureType(String enclosureType) {
            this.enclosureType = enclosureType;
        }

        public long getEnclosureLength() {
            return enclosureLength;
        }

        public void setEnclosureLength(long enclosureLength) {
            this.enclosureLength = enclosureLength;
        }

        public long getPublishedAt() {
            return publishedAt;
        }

        public void setPublishedAt(long publishedAt) {
            this.publishedAt = publishedAt;
        }

        public int getDuration() {
            return duration;
        }

        public void setDuration(int duration) {
            this.duration = duration;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        public String getChaptersUrl() {
            return chaptersUrl;
        }

        public void setChaptersUrl(String chaptersUrl) {
            this.chaptersUrl = chaptersUrl;
        }
    }
}
