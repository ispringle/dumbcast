package com.ispringle.dumbcast.data;

public class Podcast {
    private long id;
    private String feedUrl;
    private String title;
    private String description;
    private String artworkUrl;
    private Long podcastIndexId;
    private long lastRefreshAt;
    private long createdAt;
    private boolean reverseOrder;

    // Constructor
    public Podcast(long id, String feedUrl, String title) {
        this.id = id;
        this.feedUrl = feedUrl;
        this.title = title;
        this.createdAt = System.currentTimeMillis();
    }

    // Getters and setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getFeedUrl() { return feedUrl; }
    public void setFeedUrl(String feedUrl) { this.feedUrl = feedUrl; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getArtworkUrl() { return artworkUrl; }
    public void setArtworkUrl(String artworkUrl) { this.artworkUrl = artworkUrl; }

    public Long getPodcastIndexId() { return podcastIndexId; }
    public void setPodcastIndexId(Long podcastIndexId) { this.podcastIndexId = podcastIndexId; }

    public long getLastRefreshAt() { return lastRefreshAt; }
    public void setLastRefreshAt(long lastRefreshAt) { this.lastRefreshAt = lastRefreshAt; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public boolean isReverseOrder() { return reverseOrder; }
    public void setReverseOrder(boolean reverseOrder) { this.reverseOrder = reverseOrder; }
}
