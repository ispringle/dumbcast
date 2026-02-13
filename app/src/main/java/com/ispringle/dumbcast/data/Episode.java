package com.ispringle.dumbcast.data;

public class Episode {
    private long id;
    private long podcastId;
    private String guid;
    private String title;
    private String description;
    private String enclosureUrl;
    private String enclosureType;
    private long enclosureLength;
    private long publishedAt;
    private long fetchedAt;
    private int duration; // seconds
    private EpisodeState state;
    private Long viewedAt;
    private Long savedAt;
    private Long playedAt;
    private int playbackPosition; // seconds
    private String downloadPath;
    private Long downloadedAt;
    private boolean sessionGrace;
    private String chaptersUrl;

    // Constructor
    public Episode(long podcastId, String guid, String title, String enclosureUrl, long publishedAt) {
        this.podcastId = podcastId;
        this.guid = guid;
        this.title = title;
        this.enclosureUrl = enclosureUrl;
        this.publishedAt = publishedAt;
        this.fetchedAt = System.currentTimeMillis();
        this.state = EpisodeState.NEW;
        this.playbackPosition = 0;
        this.sessionGrace = false;
    }

    // Getters and setters (all fields)
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getPodcastId() { return podcastId; }
    public String getGuid() { return guid; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEnclosureUrl() { return enclosureUrl; }
    public String getEnclosureType() { return enclosureType; }
    public void setEnclosureType(String type) { this.enclosureType = type; }

    public long getEnclosureLength() { return enclosureLength; }
    public void setEnclosureLength(long length) { this.enclosureLength = length; }

    public long getPublishedAt() { return publishedAt; }
    public long getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(long time) { this.fetchedAt = time; }

    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }

    public EpisodeState getState() { return state; }
    public void setState(EpisodeState state) { this.state = state; }

    public Long getViewedAt() { return viewedAt; }
    public void setViewedAt(Long time) { this.viewedAt = time; }

    public Long getSavedAt() { return savedAt; }
    public void setSavedAt(Long time) { this.savedAt = time; }

    public Long getPlayedAt() { return playedAt; }
    public void setPlayedAt(Long time) { this.playedAt = time; }

    public int getPlaybackPosition() { return playbackPosition; }
    public void setPlaybackPosition(int position) { this.playbackPosition = position; }

    public String getDownloadPath() { return downloadPath; }
    public void setDownloadPath(String path) { this.downloadPath = path; }

    public Long getDownloadedAt() { return downloadedAt; }
    public void setDownloadedAt(Long time) { this.downloadedAt = time; }

    public boolean isSessionGrace() { return sessionGrace; }
    public void setSessionGrace(boolean grace) { this.sessionGrace = grace; }

    public String getChaptersUrl() { return chaptersUrl; }
    public void setChaptersUrl(String url) { this.chaptersUrl = url; }

    public boolean isDownloaded() {
        return downloadPath != null && downloadedAt != null;
    }
}
