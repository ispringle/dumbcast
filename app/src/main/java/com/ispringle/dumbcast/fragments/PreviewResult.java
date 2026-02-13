package com.ispringle.dumbcast.fragments;

import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.Podcast;

import java.util.List;
import java.util.Map;

/**
 * Result object for episode preview operations.
 * Contains either episode data or error information.
 */
public class PreviewResult {
    public enum Status {
        SUCCESS,
        NETWORK_ERROR,
        PARSE_ERROR,
        UNKNOWN_ERROR
    }

    private final Status status;
    private final List<Episode> episodes;
    private final Map<Long, Podcast> podcastCache;
    private final String errorMessage;

    private PreviewResult(Status status, List<Episode> episodes, Map<Long, Podcast> podcastCache, String errorMessage) {
        this.status = status;
        this.episodes = episodes;
        this.podcastCache = podcastCache;
        this.errorMessage = errorMessage;
    }

    public static PreviewResult success(List<Episode> episodes, Map<Long, Podcast> podcastCache) {
        return new PreviewResult(Status.SUCCESS, episodes, podcastCache, null);
    }

    public static PreviewResult networkError(String message) {
        return new PreviewResult(Status.NETWORK_ERROR, null, null, message);
    }

    public static PreviewResult parseError(String message) {
        return new PreviewResult(Status.PARSE_ERROR, null, null, message);
    }

    public static PreviewResult unknownError(String message) {
        return new PreviewResult(Status.UNKNOWN_ERROR, null, null, message);
    }

    public Status getStatus() {
        return status;
    }

    public List<Episode> getEpisodes() {
        return episodes;
    }

    public Map<Long, Podcast> getPodcastCache() {
        return podcastCache;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
