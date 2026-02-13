package com.ispringle.dumbcast.data;

import android.content.Context;

/**
 * Singleton manager for DatabaseHelper to prevent resource leaks.
 * Ensures only one DatabaseHelper instance exists across the entire application.
 */
public class DatabaseManager {

    private static DatabaseHelper instance;

    /**
     * Get the singleton DatabaseHelper instance.
     * @param context Application or activity context
     * @return Shared DatabaseHelper instance
     */
    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            // Use application context to avoid memory leaks
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Close the database connection.
     * Should only be called when the application is terminating.
     */
    public static synchronized void closeDatabase() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    // Private constructor to prevent instantiation
    private DatabaseManager() {
    }
}
