package com.ispringle.dumbcast.data;

public enum EpisodeState {
    NEW,
    BACKLOG,
    AVAILABLE,
    LISTENED;

    public static EpisodeState fromString(String state) {
        if (state == null) return NEW;
        try {
            return valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NEW;
        }
    }
}
