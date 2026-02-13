package com.ispringle.dumbcast.fragments;

/**
 * Result object for subscription operations.
 * Provides type-safe error handling instead of string parsing.
 */
public class SubscribeResult {
    public enum Status {
        SUCCESS,
        ALREADY_SUBSCRIBED,
        NETWORK_ERROR,
        PARSE_ERROR,
        UNKNOWN_ERROR
    }

    private final Status status;
    private final String errorDetails;

    private SubscribeResult(Status status, String errorDetails) {
        this.status = status;
        this.errorDetails = errorDetails;
    }

    public static SubscribeResult success() {
        return new SubscribeResult(Status.SUCCESS, null);
    }

    public static SubscribeResult alreadySubscribed() {
        return new SubscribeResult(Status.ALREADY_SUBSCRIBED, null);
    }

    public static SubscribeResult networkError(String details) {
        return new SubscribeResult(Status.NETWORK_ERROR, details);
    }

    public static SubscribeResult parseError(String details) {
        return new SubscribeResult(Status.PARSE_ERROR, details);
    }

    public static SubscribeResult unknownError(String details) {
        return new SubscribeResult(Status.UNKNOWN_ERROR, details);
    }

    public Status getStatus() {
        return status;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
