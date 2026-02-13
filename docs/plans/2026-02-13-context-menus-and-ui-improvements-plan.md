# Context Menus and UI Improvements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add context menu system, redesign tab navigation, and improve Now Playing UI for KaiOS feature phone.

**Architecture:** AlertDialog-based context menus triggered by menu button, single-tab navigation header replacing TabLayout, marquee episode titles with podcast artwork loaded asynchronously.

**Tech Stack:** Android SDK, AlertDialog, ImageView with AsyncTask loading, TextView marquee, SQLite for state management.

---

## Task 1: Tab Navigation Redesign

Remove TabLayout and Dumbcast header, replace with single tab indicator showing current tab with arrows.

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/ispringle/dumbcast/MainActivity.java`

**Step 1: Update activity_main.xml layout**

Replace TabLayout with simple TextView for tab indicator:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <!-- Single tab indicator replacing TabLayout -->
    <TextView
        android:id="@+id/tab_indicator"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="← Subscriptions →"
        android:textSize="18sp"
        android:textStyle="bold"
        android:gravity="center"
        android:padding="12dp"
        android:background="?android:attr/colorPrimary"
        android:textColor="?android:attr/textColorPrimaryInverse" />

    <!-- Fragment container -->
    <FrameLayout
        android:id="@+id/fragment_container"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

</LinearLayout>
```

**Step 2: Update MainActivity.java for single tab display**

Remove TabLayout references, add tab indicator TextView, update tab names array:

```java
public class MainActivity extends AppCompatActivity {

    private TextView tabIndicator;
    private EpisodeRepository episodeRepository;

    // Tab indices
    private static final int TAB_NEW = 0;
    private static final int TAB_BACKLOG = 1;
    private static final int TAB_SUBSCRIPTIONS = 2;
    private static final int TAB_DISCOVER = 3;
    private static final int TAB_NOW_PLAYING = 4;
    private static final int TAB_COUNT = 5;

    // Tab names
    private static final String[] TAB_NAMES = {
        "New",
        "Backlog",
        "Subscriptions",
        "Discover",
        "Now Playing"
    };

    private int currentTab = TAB_SUBSCRIPTIONS; // Start on Subscriptions

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize database and repository
        DatabaseHelper dbHelper = DatabaseManager.getInstance(this);
        episodeRepository = new EpisodeRepository(dbHelper);

        // Run episode maintenance on background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                episodeRepository.decayNewEpisodes();
                episodeRepository.fixDownloadedEpisodesState();
            }
        }).start();

        // Get tab indicator
        tabIndicator = findViewById(R.id.tab_indicator);
        updateTabIndicator();

        // Set initial fragment if this is first creation
        if (savedInstanceState == null) {
            loadFragmentForTab(currentTab);
        }
    }

    /**
     * Update tab indicator text with arrows
     */
    private void updateTabIndicator() {
        String tabName = TAB_NAMES[currentTab];
        tabIndicator.setText("← " + tabName + " →");
    }

    /**
     * Navigate to next/previous tab
     * @param direction -1 for previous, 1 for next
     */
    private void navigateTab(int direction) {
        currentTab = currentTab + direction;

        // Wrap around at boundaries
        if (currentTab < 0) {
            currentTab = TAB_COUNT - 1; // Wrap to Now Playing
        } else if (currentTab >= TAB_COUNT) {
            currentTab = 0; // Wrap to New
        }

        updateTabIndicator();
        loadFragmentForTab(currentTab);
    }

    /**
     * Load fragment for current tab
     */
    private void loadFragmentForTab(int tabIndex) {
        Fragment fragment = null;

        switch (tabIndex) {
            case TAB_NEW:
                fragment = NewFragment.newInstance();
                break;
            case TAB_BACKLOG:
                fragment = EpisodeListFragment.newInstanceForState(EpisodeState.BACKLOG);
                break;
            case TAB_SUBSCRIPTIONS:
                fragment = SubscriptionsFragment.newInstance();
                break;
            case TAB_DISCOVER:
                fragment = DiscoveryFragment.newInstance();
                break;
            case TAB_NOW_PLAYING:
                fragment = PlayerFragment.newInstance();
                break;
        }

        if (fragment != null) {
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, fragment);
            transaction.commit();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // Navigate to previous tab
                navigateTab(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Navigate to next tab
                navigateTab(1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                // Handle back button
                return super.onKeyDown(keyCode, event);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
```

**Step 3: Remove old TabLayout setup code**

Delete the old `setupTabs()` and `handleTabSelection()` methods from MainActivity.

**Step 4: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: App shows "← Subscriptions →" header, left/right D-pad navigates tabs with wrapping.

**Step 5: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/java/com/ispringle/dumbcast/MainActivity.java
git commit -m "feat: redesign tab navigation with single tab indicator

- Remove Dumbcast header and TabLayout
- Add single tab indicator showing current tab with arrows
- Tab order: New → Backlog → Subscriptions → Discover → Now Playing
- Left/right D-pad navigation with wrapping

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 2: Context Menu Infrastructure

Add base context menu handling infrastructure with AlertDialog.

**Files:**
- Modify: `app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java`

**Step 1: Remove test menu code from SubscriptionsFragment**

Remove the `showMenuStyleTest()`, `showAlertDialogMenu()`, and `showPopupMenuTest()` methods that were added for testing.

**Step 2: Add real context menu for Subscriptions**

Replace the test code with actual context menu:

```java
/**
 * Show context menu for selected podcast
 */
private void showContextMenu(final Podcast podcast) {
    if (podcast == null) {
        return;
    }

    final String[] menuItems = {
        "Refresh",
        "Refresh All",
        "Unsubscribe",
        "Remove NEW from all episodes"
    };

    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle(podcast.getTitle());
    builder.setItems(menuItems, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            handleContextMenuAction(podcast, which);
        }
    });
    builder.show();
}

/**
 * Handle context menu action selection
 */
private void handleContextMenuAction(Podcast podcast, int actionIndex) {
    switch (actionIndex) {
        case 0: // Refresh
            refreshPodcast(podcast);
            break;
        case 1: // Refresh All
            refreshAllPodcasts();
            break;
        case 2: // Unsubscribe
            unsubscribePodcast(podcast);
            break;
        case 3: // Remove NEW from all episodes
            removeNewFromAllEpisodes(podcast);
            break;
    }
}

// Stub methods (will implement in later tasks)
private void refreshPodcast(Podcast podcast) {
    Toast.makeText(getContext(), "Refresh: " + podcast.getTitle(), Toast.LENGTH_SHORT).show();
}

private void refreshAllPodcasts() {
    Toast.makeText(getContext(), "Refresh All", Toast.LENGTH_SHORT).show();
}

private void unsubscribePodcast(Podcast podcast) {
    Toast.makeText(getContext(), "Unsubscribe: " + podcast.getTitle(), Toast.LENGTH_SHORT).show();
}

private void removeNewFromAllEpisodes(Podcast podcast) {
    Toast.makeText(getContext(), "Remove NEW from: " + podcast.getTitle(), Toast.LENGTH_SHORT).show();
}
```

**Step 3: Update key listener to show context menu**

```java
// Add key listener for D-pad and menu button
listView.setOnKeyListener(new View.OnKeyListener() {
    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int position = listView.getSelectedItemPosition();
                if (position >= 0) {
                    Podcast podcast = adapter.getItem(position);
                    if (podcast != null) {
                        navigateToEpisodeList(podcast);
                        return true;
                    }
                }
            }
        }
        // Menu button shows context menu
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int position = listView.getSelectedItemPosition();
                if (position >= 0) {
                    Podcast podcast = adapter.getItem(position);
                    if (podcast != null) {
                        showContextMenu(podcast);
                        return true;
                    }
                }
            }
        }
        return false;
    }
});
```

**Step 4: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Menu button on Subscriptions shows context menu with 4 options, selecting shows toast.

**Step 5: Commit**

```bash
git add app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java
git commit -m "feat: add context menu infrastructure to Subscriptions

- Remove test menu code
- Add AlertDialog-based context menu with 4 actions
- Menu button triggers context menu for selected podcast
- Stub implementations show toasts

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 3: Episode List Context Menu

Add context menu to EpisodeListFragment with conditional options based on episode state.

**Files:**
- Modify: `app/src/main/java/com/ispringle/dumbcast/fragments/EpisodeListFragment.java`

**Step 1: Add context menu method to EpisodeListFragment**

```java
/**
 * Show context menu for selected episode
 */
private void showContextMenu(final Episode episode) {
    if (episode == null) {
        return;
    }

    // Build menu items based on episode state
    List<String> menuItemsList = new ArrayList<>();

    if (episode.isDownloaded()) {
        menuItemsList.add("Delete Download");
        menuItemsList.add("Play");
    } else {
        menuItemsList.add("Download");
    }

    menuItemsList.add("Add to Backlog");

    if (episode.getState() == EpisodeState.NEW) {
        menuItemsList.add("Remove NEW");
    }

    final String[] menuItems = menuItemsList.toArray(new String[0]);

    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle(episode.getTitle());
    builder.setItems(menuItems, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            handleContextMenuAction(episode, menuItems[which]);
        }
    });
    builder.show();
}

/**
 * Handle context menu action selection
 */
private void handleContextMenuAction(Episode episode, String action) {
    if ("Download".equals(action)) {
        downloadEpisode(episode);
    } else if ("Delete Download".equals(action)) {
        deleteDownload(episode);
    } else if ("Play".equals(action)) {
        handleEpisodeClick(episode);
    } else if ("Add to Backlog".equals(action)) {
        addToBacklog(episode);
    } else if ("Remove NEW".equals(action)) {
        removeNew(episode);
    }
}

// Stub/existing methods
private void downloadEpisode(Episode episode) {
    DownloadService.startDownload(getContext(), episode.getId());
    Toast.makeText(getContext(), "Download started", Toast.LENGTH_SHORT).show();
}

private void deleteDownload(final Episode episode) {
    // Confirmation dialog
    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle("Delete Download");
    builder.setMessage("Delete downloaded file for " + episode.getTitle() + "?");
    builder.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            // Delete file and update database
            if (episode.getDownloadPath() != null) {
                new File(episode.getDownloadPath()).delete();
                episodeRepository.updateEpisodeDownload(episode.getId(), null, 0);
                Toast.makeText(getContext(), "Download deleted", Toast.LENGTH_SHORT).show();
                loadEpisodes(); // Refresh list
            }
        }
    });
    builder.setNegativeButton("Cancel", null);
    builder.show();
}

private void addToBacklog(final Episode episode) {
    new AsyncTask<Void, Void, Integer>() {
        @Override
        protected Integer doInBackground(Void... voids) {
            return episodeRepository.updateEpisodeState(episode.getId(), EpisodeState.BACKLOG);
        }

        @Override
        protected void onPostExecute(Integer rowsUpdated) {
            if (rowsUpdated > 0) {
                Toast.makeText(getContext(), "Added to backlog", Toast.LENGTH_SHORT).show();
                loadEpisodes(); // Refresh list
            } else {
                Toast.makeText(getContext(), "Failed to add to backlog", Toast.LENGTH_SHORT).show();
            }
        }
    }.execute();
}

private void removeNew(final Episode episode) {
    new AsyncTask<Void, Void, Integer>() {
        @Override
        protected Integer doInBackground(Void... voids) {
            return episodeRepository.updateEpisodeState(episode.getId(), EpisodeState.AVAILABLE);
        }

        @Override
        protected void onPostExecute(Integer rowsUpdated) {
            if (rowsUpdated > 0) {
                Toast.makeText(getContext(), "Removed NEW tag", Toast.LENGTH_SHORT).show();
                loadEpisodes(); // Refresh list
            } else {
                Toast.makeText(getContext(), "Failed to remove NEW tag", Toast.LENGTH_SHORT).show();
            }
        }
    }.execute();
}
```

**Step 2: Add menu key listener to EpisodeListFragment**

```java
// Add to existing key listener in onCreateView
listView.setOnKeyListener(new View.OnKeyListener() {
    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        Log.d(TAG, "Key event: keyCode=" + keyCode + ", action=" + event.getAction());

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int position = listView.getSelectedItemPosition();
                Log.d(TAG, "Selected position: " + position);
                if (position >= 0) {
                    Episode episode = adapter.getItem(position);
                    if (episode != null) {
                        handleEpisodeClick(episode);
                        return true;
                    }
                }
            }
        }

        // Menu button shows context menu
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int position = listView.getSelectedItemPosition();
                if (position >= 0) {
                    Episode episode = adapter.getItem(position);
                    if (episode != null) {
                        showContextMenu(episode);
                        return true;
                    }
                }
            }
        }

        return false;
    }
});
```

**Step 3: Add import for File**

```java
import java.io.File;
```

**Step 4: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Menu button on episode shows context menu with conditional options. Downloaded episodes show "Delete/Play", non-downloaded show "Download", NEW episodes show "Remove NEW".

**Step 5: Commit**

```bash
git add app/src/main/java/com/ispringle/dumbcast/fragments/EpisodeListFragment.java
git commit -m "feat: add context menu to episode lists

- Conditional menu items based on episode state
- Download, Delete, Play, Add to Backlog, Remove NEW
- Confirmation dialog for delete
- Refresh list after state changes

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 4: Discover Fragment Context Menu

Add context menu to DiscoveryFragment for podcast subscription.

**Files:**
- Modify: `app/src/main/java/com/ispringle/dumbcast/fragments/DiscoveryFragment.java`

**Step 1: Read current DiscoveryFragment to understand structure**

Check existing file to see how podcasts are displayed and selected.

**Step 2: Add context menu method**

```java
/**
 * Show context menu for selected podcast in discovery
 */
private void showContextMenu(final Podcast podcast) {
    if (podcast == null) {
        return;
    }

    final String[] menuItems = {
        "Subscribe",
        "View Episodes",
        "View Podcast Info"
    };

    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle(podcast.getTitle());
    builder.setItems(menuItems, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            handleContextMenuAction(podcast, which);
        }
    });
    builder.show();
}

/**
 * Handle context menu action
 */
private void handleContextMenuAction(Podcast podcast, int actionIndex) {
    switch (actionIndex) {
        case 0: // Subscribe
            subscribeToPodcast(podcast);
            break;
        case 1: // View Episodes
            viewEpisodes(podcast);
            break;
        case 2: // View Podcast Info
            viewPodcastInfo(podcast);
            break;
    }
}

private void subscribeToPodcast(Podcast podcast) {
    // Subscribe logic here - will implement in podcast subscription task
    Toast.makeText(getContext(), "Subscribe: " + podcast.getTitle(), Toast.LENGTH_SHORT).show();
}

private void viewEpisodes(Podcast podcast) {
    // Navigate to episode list
    Toast.makeText(getContext(), "View Episodes: " + podcast.getTitle(), Toast.LENGTH_SHORT).show();
}

private void viewPodcastInfo(Podcast podcast) {
    // Show podcast description in dialog
    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle(podcast.getTitle());
    builder.setMessage(podcast.getDescription());
    builder.setPositiveButton("OK", null);
    builder.show();
}
```

**Step 3: Add menu key listener**

Add KEYCODE_MENU handling to existing key listener or create new one.

**Step 4: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Menu button in Discover shows context menu with Subscribe, View Episodes, View Info options.

**Step 5: Commit**

```bash
git add app/src/main/java/com/ispringle/dumbcast/fragments/DiscoveryFragment.java
git commit -m "feat: add context menu to Discover fragment

- Subscribe, View Episodes, View Info options
- Show podcast description in dialog
- Prepare for subscription implementation

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 5: Now Playing Context Menu

Add context menu to PlayerFragment with episode-specific actions.

**Files:**
- Modify: `app/src/main/java/com/ispringle/dumbcast/fragments/PlayerFragment.java`

**Step 1: Add context menu method to PlayerFragment**

```java
/**
 * Show context menu for currently playing episode
 */
private void showContextMenu() {
    if (!serviceBound || playbackService == null) {
        return;
    }

    Episode episode = playbackService.getCurrentEpisode();
    if (episode == null) {
        Toast.makeText(getContext(), "No episode loaded", Toast.LENGTH_SHORT).show();
        return;
    }

    List<String> menuItemsList = new ArrayList<>();

    if (episode.isDownloaded()) {
        menuItemsList.add("Delete Episode");
    }

    if (episode.getChaptersUrl() != null) {
        menuItemsList.add("View Chapters");
    }

    menuItemsList.add("Skip to Timestamp");
    menuItemsList.add("View Show Notes");

    final String[] menuItems = menuItemsList.toArray(new String[0]);

    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle(episode.getTitle());
    builder.setItems(menuItems, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            handleContextMenuAction(menuItems[which]);
        }
    });
    builder.show();
}

/**
 * Handle context menu action
 */
private void handleContextMenuAction(String action) {
    Episode episode = playbackService.getCurrentEpisode();
    if (episode == null) return;

    if ("Delete Episode".equals(action)) {
        deleteEpisode(episode);
    } else if ("View Chapters".equals(action)) {
        viewChapters(episode);
    } else if ("Skip to Timestamp".equals(action)) {
        skipToTimestamp();
    } else if ("View Show Notes".equals(action)) {
        viewShowNotes(episode);
    }
}

private void deleteEpisode(final Episode episode) {
    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle("Delete Episode");
    builder.setMessage("Delete downloaded file for " + episode.getTitle() + "?");
    builder.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (episode.getDownloadPath() != null) {
                new File(episode.getDownloadPath()).delete();
                // Update database
                DatabaseHelper dbHelper = DatabaseManager.getInstance(getContext());
                EpisodeRepository repo = new EpisodeRepository(dbHelper);
                repo.updateEpisodeDownload(episode.getId(), null, 0);
                Toast.makeText(getContext(), "Episode deleted", Toast.LENGTH_SHORT).show();
            }
        }
    });
    builder.setNegativeButton("Cancel", null);
    builder.show();
}

private void viewChapters(Episode episode) {
    // Stub for now - will implement chapter support later
    Toast.makeText(getContext(), "Chapters: " + episode.getChaptersUrl(), Toast.LENGTH_SHORT).show();
}

private void skipToTimestamp() {
    // Show dialog to enter timestamp
    final EditText input = new EditText(getContext());
    input.setHint("MM:SS or HH:MM:SS");

    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle("Skip to Timestamp");
    builder.setView(input);
    builder.setPositiveButton("Go", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            String timestamp = input.getText().toString();
            int seconds = parseTimestamp(timestamp);
            if (seconds >= 0 && playbackService != null) {
                playbackService.seekTo(seconds);
                Toast.makeText(getContext(), "Jumped to " + timestamp, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Invalid timestamp", Toast.LENGTH_SHORT).show();
            }
        }
    });
    builder.setNegativeButton("Cancel", null);
    builder.show();
}

private int parseTimestamp(String timestamp) {
    try {
        String[] parts = timestamp.split(":");
        if (parts.length == 2) {
            // MM:SS
            int minutes = Integer.parseInt(parts[0]);
            int seconds = Integer.parseInt(parts[1]);
            return minutes * 60 + seconds;
        } else if (parts.length == 3) {
            // HH:MM:SS
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = Integer.parseInt(parts[2]);
            return hours * 3600 + minutes * 60 + seconds;
        }
    } catch (NumberFormatException e) {
        Log.e(TAG, "Invalid timestamp format", e);
    }
    return -1;
}

private void viewShowNotes(Episode episode) {
    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle(episode.getTitle());
    builder.setMessage(episode.getDescription());
    builder.setPositiveButton("OK", null);
    builder.show();
}
```

**Step 2: Add menu key listener to PlayerFragment**

Update the existing onKey handler in onCreateView:

```java
view.setOnKeyListener(new View.OnKeyListener() {
    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showContextMenu();
                return true;
            }
            return handleKeyDown(keyCode);
        }
        return false;
    }
});
```

**Step 3: Add imports**

```java
import android.widget.EditText;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
```

**Step 4: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Menu button in Now Playing shows context menu with conditional options. Skip to Timestamp shows input dialog.

**Step 5: Commit**

```bash
git add app/src/main/java/com/ispringle/dumbcast/fragments/PlayerFragment.java
git commit -m "feat: add context menu to Now Playing

- Delete Episode, View Chapters, Skip to Timestamp, Show Notes
- Timestamp input dialog with MM:SS or HH:MM:SS format
- Conditional menu based on episode state

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 6: Now Playing UI Improvements - Layout

Redesign Now Playing layout with image, marquee title, and simplified controls.

**Files:**
- Modify: `app/src/main/res/layout/fragment_player.xml`

**Step 1: Create new layout for Now Playing**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp"
    android:focusable="true"
    android:focusableInTouchMode="true"
    android:gravity="center_horizontal">

    <!-- Episode/Podcast Image -->
    <ImageView
        android:id="@+id/player_episode_image"
        android:layout_width="200dp"
        android:layout_height="200dp"
        android:layout_marginTop="24dp"
        android:layout_marginBottom="24dp"
        android:scaleType="centerCrop"
        android:contentDescription="Episode artwork" />

    <!-- Episode Title (marquee on overflow) -->
    <TextView
        android:id="@+id/player_episode_title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="No episode loaded"
        android:textSize="14sp"
        android:textStyle="bold"
        android:singleLine="true"
        android:ellipsize="marquee"
        android:marqueeRepeatLimit="marquee_forever"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:scrollHorizontally="true"
        android:gravity="center"
        android:paddingBottom="4dp" />

    <!-- Podcast Name -->
    <TextView
        android:id="@+id/player_podcast_name"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text=""
        android:textSize="12sp"
        android:textColor="?android:attr/textColorSecondary"
        android:gravity="center"
        android:paddingBottom="16dp" />

    <!-- Progress Bar -->
    <ProgressBar
        android:id="@+id/player_progress_bar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:max="100"
        android:progress="0"
        android:paddingBottom="8dp" />

    <!-- Progress text (current time / total duration) -->
    <TextView
        android:id="@+id/player_progress_text"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="00:00 / 00:00"
        android:textSize="14sp"
        android:gravity="center"
        android:paddingBottom="24dp" />

    <!-- Play/Pause button -->
    <TextView
        android:id="@+id/player_play_pause"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="▶ Play"
        android:textSize="24sp"
        android:gravity="center"
        android:padding="16dp"
        android:clickable="true"
        android:focusable="true"
        android:background="?android:attr/selectableItemBackground"
        android:textStyle="bold" />

    <!-- Status message (hidden by default) -->
    <TextView
        android:id="@+id/player_status_message"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text=""
        android:textSize="12sp"
        android:textColor="?android:attr/textColorSecondary"
        android:gravity="center"
        android:paddingTop="16dp"
        android:visibility="gone" />

</LinearLayout>
```

**Step 2: Remove old UI elements from PlayerFragment.java**

Remove references to:
- `player_header`
- `player_chapter_name`
- `player_skip_backward`
- `player_skip_forward`

**Step 3: Add ImageView reference**

```java
private ImageView episodeImageView;

// In onCreateView:
episodeImageView = view.findViewById(R.id.player_episode_image);
```

**Step 4: Update updateUI() to enable marquee**

```java
private void updateUI() {
    if (!serviceBound || playbackService == null) {
        showNoEpisodeState();
        return;
    }

    Episode currentEpisode = playbackService.getCurrentEpisode();
    if (currentEpisode == null) {
        showNoEpisodeState();
        return;
    }

    // Update episode info
    episodeTitleText.setText(currentEpisode.getTitle());
    episodeTitleText.setSelected(true); // Enable marquee

    // Load podcast name
    Podcast podcast = podcastRepository.getPodcastById(currentEpisode.getPodcastId());
    if (podcast != null) {
        podcastNameText.setText(podcast.getTitle());
        podcastNameText.setVisibility(View.VISIBLE);

        // Load image (will implement in next task)
        loadEpisodeImage(currentEpisode, podcast);
    } else {
        podcastNameText.setVisibility(View.GONE);
    }

    // Update play/pause button
    if (playbackService.isPlaying()) {
        playPauseButton.setText("⏸ Pause");
    } else {
        playPauseButton.setText("▶ Play");
    }

    // Update progress
    int position = playbackService.getCurrentPosition();
    int duration = playbackService.getDuration();
    updateProgress(position, duration);

    // Hide status message
    statusMessage.setVisibility(View.GONE);
}
```

**Step 5: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Now Playing shows centered layout with image placeholder, marquee title, progress bar, and single play/pause button.

**Step 6: Commit**

```bash
git add app/src/main/res/layout/fragment_player.xml app/src/main/java/com/ispringle/dumbcast/fragments/PlayerFragment.java
git commit -m "feat: redesign Now Playing UI layout

- Add large centered episode/podcast image
- Episode title with marquee on overflow
- Remove skip buttons (keypad only)
- Simplified vertical layout
- Progress bar and time display prominent

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 7: Image Loading System

Add AsyncTask-based image loading with caching for episode and podcast artwork.

**Files:**
- Create: `app/src/main/java/com/ispringle/dumbcast/utils/ImageLoader.java`
- Modify: `app/src/main/java/com/ispringle/dumbcast/fragments/PlayerFragment.java`

**Step 1: Create ImageLoader utility class**

```java
package com.ispringle.dumbcast.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Utility class for loading and caching images from URLs.
 * Uses LruCache for memory caching and disk cache for persistence.
 */
public class ImageLoader {

    private static final String TAG = "ImageLoader";
    private static final int CACHE_SIZE = 4 * 1024 * 1024; // 4MB

    private final Context context;
    private final LruCache<String, Bitmap> memoryCache;

    public ImageLoader(Context context) {
        this.context = context.getApplicationContext();
        this.memoryCache = new LruCache<String, Bitmap>(CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount();
            }
        };
    }

    /**
     * Load image from URL into ImageView
     * @param url Image URL
     * @param imageView Target ImageView
     */
    public void loadImage(String url, ImageView imageView) {
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "Empty URL, skipping image load");
            return;
        }

        // Check memory cache first
        Bitmap cached = memoryCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        // Load asynchronously
        new LoadImageTask(imageView, url, memoryCache, context).execute();
    }

    /**
     * AsyncTask to load image in background
     */
    private static class LoadImageTask extends AsyncTask<Void, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewRef;
        private final String url;
        private final LruCache<String, Bitmap> cache;
        private final Context context;

        LoadImageTask(ImageView imageView, String url, LruCache<String, Bitmap> cache, Context context) {
            this.imageViewRef = new WeakReference<>(imageView);
            this.url = url;
            this.cache = cache;
            this.context = context;
        }

        @Override
        protected Bitmap doInBackground(Void... voids) {
            // Check disk cache
            File cacheFile = getCacheFile();
            if (cacheFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                if (bitmap != null) {
                    Log.d(TAG, "Loaded from disk cache: " + url);
                    return bitmap;
                }
            }

            // Download from network
            try {
                Log.d(TAG, "Downloading image: " + url);
                URL imageUrl = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) imageUrl.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.connect();

                InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                input.close();
                connection.disconnect();

                if (bitmap != null) {
                    // Save to disk cache
                    saveToDiskCache(bitmap);
                    Log.d(TAG, "Downloaded and cached: " + url);
                }

                return bitmap;
            } catch (Exception e) {
                Log.e(TAG, "Error loading image: " + url, e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            ImageView imageView = imageViewRef.get();
            if (imageView != null && bitmap != null) {
                imageView.setImageBitmap(bitmap);
                cache.put(url, bitmap);
            }
        }

        private File getCacheFile() {
            File cacheDir = new File(context.getCacheDir(), "images");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            String filename = String.valueOf(url.hashCode());
            return new File(cacheDir, filename);
        }

        private void saveToDiskCache(Bitmap bitmap) {
            try {
                File cacheFile = getCacheFile();
                FileOutputStream out = new FileOutputStream(cacheFile);
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
                out.close();
            } catch (Exception e) {
                Log.e(TAG, "Error saving to disk cache", e);
            }
        }
    }
}
```

**Step 2: Add ImageLoader to PlayerFragment**

```java
import com.ispringle.dumbcast.utils.ImageLoader;

public class PlayerFragment extends Fragment implements PlaybackService.PlaybackListener {

    private ImageLoader imageLoader;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DatabaseHelper dbHelper = DatabaseManager.getInstance(getContext());
        podcastRepository = new PodcastRepository(dbHelper);
        imageLoader = new ImageLoader(getContext());
    }

    /**
     * Load episode or podcast image
     */
    private void loadEpisodeImage(Episode episode, Podcast podcast) {
        // TODO: Episode-specific artwork not yet in database
        // For now, just load podcast artwork
        if (podcast.getArtworkUrl() != null) {
            imageLoader.loadImage(podcast.getArtworkUrl(), episodeImageView);
        }
    }
}
```

**Step 3: Add placeholder drawable**

Create a simple placeholder in res/drawable/ic_placeholder.xml:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#CCCCCC" />
</shape>
```

**Step 4: Set placeholder in layout**

Update fragment_player.xml:

```xml
<ImageView
    android:id="@+id/player_episode_image"
    android:layout_width="200dp"
    android:layout_height="200dp"
    android:layout_marginTop="24dp"
    android:layout_marginBottom="24dp"
    android:scaleType="centerCrop"
    android:src="@drawable/ic_placeholder"
    android:contentDescription="Episode artwork" />
```

**Step 5: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Now Playing loads and displays podcast artwork. Images are cached in memory and on disk.

**Step 6: Commit**

```bash
git add app/src/main/java/com/ispringle/dumbcast/utils/ImageLoader.java app/src/main/java/com/ispringle/dumbcast/fragments/PlayerFragment.java app/src/main/res/drawable/ic_placeholder.xml app/src/main/res/layout/fragment_player.xml
git commit -m "feat: add image loading with caching

- Create ImageLoader utility with LruCache and disk cache
- Load podcast artwork in Now Playing
- Placeholder while loading
- Background AsyncTask for network requests

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 8: Remove NEW State Management

Implement "Remove NEW from all episodes" action for podcasts.

**Files:**
- Modify: `app/src/main/java/com/ispringle/dumbcast/data/EpisodeRepository.java`
- Modify: `app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java`

**Step 1: Add method to EpisodeRepository**

```java
/**
 * Remove NEW state from all episodes of a podcast.
 * Moves NEW episodes to AVAILABLE state.
 * @param podcastId The podcast ID
 * @return Number of episodes updated
 */
public int removeNewFromPodcast(long podcastId) {
    SQLiteDatabase db = dbHelper.getWritableDatabase();
    ContentValues values = new ContentValues();
    values.put(DatabaseHelper.COL_EPISODE_STATE, EpisodeState.AVAILABLE.name());
    values.put(DatabaseHelper.COL_EPISODE_VIEWED_AT, System.currentTimeMillis());

    String whereClause = DatabaseHelper.COL_EPISODE_PODCAST_ID + " = ? AND " +
        DatabaseHelper.COL_EPISODE_STATE + " = ?";
    String[] whereArgs = {String.valueOf(podcastId), EpisodeState.NEW.name()};

    db.beginTransaction();
    try {
        int updated = db.update(DatabaseHelper.TABLE_EPISODES, values, whereClause, whereArgs);
        db.setTransactionSuccessful();
        Log.d(TAG, "Removed NEW from " + updated + " episodes for podcast " + podcastId);
        return updated;
    } finally {
        db.endTransaction();
    }
}
```

**Step 2: Implement in SubscriptionsFragment**

```java
private void removeNewFromAllEpisodes(final Podcast podcast) {
    new AsyncTask<Void, Void, Integer>() {
        @Override
        protected Integer doInBackground(Void... voids) {
            return episodeRepository.removeNewFromPodcast(podcast.getId());
        }

        @Override
        protected void onPostExecute(Integer count) {
            if (count > 0) {
                Toast.makeText(getContext(),
                    "Removed NEW from " + count + " episodes",
                    Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(),
                    "No NEW episodes to remove",
                    Toast.LENGTH_SHORT).show();
            }
        }
    }.execute();
}
```

**Step 3: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: "Remove NEW from all episodes" changes all NEW episodes to AVAILABLE for that podcast.

**Step 4: Commit**

```bash
git add app/src/main/java/com/ispringle/dumbcast/data/EpisodeRepository.java app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java
git commit -m "feat: implement remove NEW from all episodes

- Add removeNewFromPodcast() to EpisodeRepository
- Move all NEW episodes to AVAILABLE for podcast
- Show count of updated episodes
- Set viewed_at timestamp

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 9: Podcast Refresh - Single

Implement "Refresh" action to fetch latest episodes for a single podcast.

**Files:**
- Modify: `app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java`

**Step 1: Add imports for RSS parsing**

```java
import com.ispringle.dumbcast.data.PodcastParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
```

**Step 2: Implement refreshPodcast method**

```java
private void refreshPodcast(final Podcast podcast) {
    Toast.makeText(getContext(), "Refreshing " + podcast.getTitle() + "...", Toast.LENGTH_SHORT).show();

    new RefreshPodcastTask(this, podcast, podcastRepository, episodeRepository).execute();
}

/**
 * AsyncTask to refresh a single podcast
 */
private static class RefreshPodcastTask extends AsyncTask<Void, Void, RefreshResult> {
    private final WeakReference<SubscriptionsFragment> fragmentRef;
    private final Podcast podcast;
    private final PodcastRepository podcastRepo;
    private final EpisodeRepository episodeRepo;

    RefreshPodcastTask(SubscriptionsFragment fragment, Podcast podcast,
                      PodcastRepository podcastRepo, EpisodeRepository episodeRepo) {
        this.fragmentRef = new WeakReference<>(fragment);
        this.podcast = podcast;
        this.podcastRepo = podcastRepo;
        this.episodeRepo = episodeRepo;
    }

    @Override
    protected RefreshResult doInBackground(Void... voids) {
        try {
            // Fetch RSS feed
            URL url = new URL(podcast.getFeedUrl());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.connect();

            InputStream input = connection.getInputStream();
            PodcastParser parser = new PodcastParser();
            List<Episode> episodes = parser.parseEpisodes(input, podcast.getId());
            input.close();
            connection.disconnect();

            // Insert new episodes
            int newCount = 0;
            for (Episode episode : episodes) {
                long id = episodeRepo.insertEpisode(episode);
                if (id != -1) {
                    newCount++;
                }
            }

            // Update last refresh time
            podcastRepo.updateLastRefresh(podcast.getId(), System.currentTimeMillis());

            return new RefreshResult(true, newCount, null);
        } catch (IOException | XmlPullParserException e) {
            Log.e(TAG, "Error refreshing podcast", e);
            return new RefreshResult(false, 0, e.getMessage());
        }
    }

    @Override
    protected void onPostExecute(RefreshResult result) {
        SubscriptionsFragment fragment = fragmentRef.get();
        if (fragment != null && fragment.getContext() != null) {
            if (result.success) {
                Toast.makeText(fragment.getContext(),
                    "Found " + result.newEpisodes + " new episodes",
                    Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(fragment.getContext(),
                    "Failed to refresh: " + result.error,
                    Toast.LENGTH_LONG).show();
            }
        }
    }
}

/**
 * Result of refresh operation
 */
private static class RefreshResult {
    final boolean success;
    final int newEpisodes;
    final String error;

    RefreshResult(boolean success, int newEpisodes, String error) {
        this.success = success;
        this.newEpisodes = newEpisodes;
        this.error = error;
    }
}
```

**Step 3: Add updateLastRefresh to PodcastRepository**

```java
/**
 * Update the last refresh timestamp for a podcast.
 * @param podcastId The podcast ID
 * @param timestamp The refresh timestamp
 * @return Number of rows updated
 */
public int updateLastRefresh(long podcastId, long timestamp) {
    SQLiteDatabase db = dbHelper.getWritableDatabase();
    ContentValues values = new ContentValues();
    values.put(DatabaseHelper.COL_PODCAST_LAST_REFRESH, timestamp);

    return db.update(
        DatabaseHelper.TABLE_PODCASTS,
        values,
        DatabaseHelper.COL_PODCAST_ID + " = ?",
        new String[]{String.valueOf(podcastId)}
    );
}
```

**Step 4: Add import for WeakReference**

```java
import java.lang.ref.WeakReference;
```

**Step 5: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: "Refresh" on a podcast fetches latest episodes and shows count. Updates last_refresh_at timestamp.

**Step 6: Commit**

```bash
git add app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java app/src/main/java/com/ispringle/dumbcast/data/PodcastRepository.java
git commit -m "feat: implement single podcast refresh

- Fetch RSS feed and parse episodes
- Insert new episodes only (duplicates ignored)
- Update last_refresh_at timestamp
- Show count of new episodes found

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 10: Podcast Refresh - All

Implement "Refresh All" action to refresh all subscribed podcasts.

**Files:**
- Modify: `app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java`

**Step 1: Implement refreshAllPodcasts method**

```java
private void refreshAllPodcasts() {
    Toast.makeText(getContext(), "Refreshing all podcasts...", Toast.LENGTH_SHORT).show();

    new RefreshAllPodcastsTask(this, podcastRepository, episodeRepository).execute();
}

/**
 * AsyncTask to refresh all subscribed podcasts
 */
private static class RefreshAllPodcastsTask extends AsyncTask<Void, String, RefreshAllResult> {
    private final WeakReference<SubscriptionsFragment> fragmentRef;
    private final PodcastRepository podcastRepo;
    private final EpisodeRepository episodeRepo;

    RefreshAllPodcastsTask(SubscriptionsFragment fragment,
                          PodcastRepository podcastRepo, EpisodeRepository episodeRepo) {
        this.fragmentRef = new WeakReference<>(fragment);
        this.podcastRepo = podcastRepo;
        this.episodeRepo = episodeRepo;
    }

    @Override
    protected RefreshAllResult doInBackground(Void... voids) {
        List<Podcast> podcasts = podcastRepo.getAllPodcasts();
        int totalNew = 0;
        int successCount = 0;
        int failCount = 0;

        for (Podcast podcast : podcasts) {
            try {
                // Fetch RSS feed
                URL url = new URL(podcast.getFeedUrl());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.connect();

                InputStream input = connection.getInputStream();
                PodcastParser parser = new PodcastParser();
                List<Episode> episodes = parser.parseEpisodes(input, podcast.getId());
                input.close();
                connection.disconnect();

                // Insert new episodes
                int newCount = 0;
                for (Episode episode : episodes) {
                    long id = episodeRepo.insertEpisode(episode);
                    if (id != -1) {
                        newCount++;
                    }
                }

                totalNew += newCount;
                successCount++;

                // Update last refresh time
                podcastRepo.updateLastRefresh(podcast.getId(), System.currentTimeMillis());

                // Publish progress
                publishProgress("Refreshed " + podcast.getTitle() + " (" + newCount + " new)");

            } catch (Exception e) {
                Log.e(TAG, "Error refreshing podcast: " + podcast.getTitle(), e);
                failCount++;
                publishProgress("Failed: " + podcast.getTitle());
            }
        }

        return new RefreshAllResult(totalNew, successCount, failCount);
    }

    @Override
    protected void onProgressUpdate(String... progress) {
        SubscriptionsFragment fragment = fragmentRef.get();
        if (fragment != null && fragment.getContext() != null) {
            Toast.makeText(fragment.getContext(), progress[0], Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPostExecute(RefreshAllResult result) {
        SubscriptionsFragment fragment = fragmentRef.get();
        if (fragment != null && fragment.getContext() != null) {
            String message = "Refresh complete: " + result.totalNew + " new episodes\n" +
                "Success: " + result.successCount + ", Failed: " + result.failCount;
            Toast.makeText(fragment.getContext(), message, Toast.LENGTH_LONG).show();
        }
    }
}

/**
 * Result of refresh all operation
 */
private static class RefreshAllResult {
    final int totalNew;
    final int successCount;
    final int failCount;

    RefreshAllResult(int totalNew, int successCount, int failCount) {
        this.totalNew = totalNew;
        this.successCount = successCount;
        this.failCount = failCount;
    }
}
```

**Step 2: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: "Refresh All" refreshes all podcasts sequentially, showing progress toasts. Final toast shows total new episodes and success/fail counts.

**Step 3: Commit**

```bash
git add app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java
git commit -m "feat: implement refresh all podcasts

- Refresh all subscribed podcasts sequentially
- Show progress toast for each podcast
- Report total new episodes and success/fail counts
- Update all last_refresh_at timestamps

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 11: Unsubscribe Podcast

Implement "Unsubscribe" action with confirmation dialog.

**Files:**
- Modify: `app/src/main/java/com/ispringle/dumbcast/data/PodcastRepository.java`
- Modify: `app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java`

**Step 1: Add deletePodcast to PodcastRepository**

```java
/**
 * Delete a podcast and all its episodes (cascade).
 * @param podcastId The podcast ID
 * @return Number of podcasts deleted
 */
public int deletePodcast(long podcastId) {
    SQLiteDatabase db = dbHelper.getWritableDatabase();

    db.beginTransaction();
    try {
        // Delete episodes first (or rely on CASCADE)
        int episodesDeleted = db.delete(
            DatabaseHelper.TABLE_EPISODES,
            DatabaseHelper.COL_EPISODE_PODCAST_ID + " = ?",
            new String[]{String.valueOf(podcastId)}
        );

        // Delete podcast
        int podcastsDeleted = db.delete(
            DatabaseHelper.TABLE_PODCASTS,
            DatabaseHelper.COL_PODCAST_ID + " = ?",
            new String[]{String.valueOf(podcastId)}
        );

        db.setTransactionSuccessful();
        Log.d(TAG, "Deleted podcast " + podcastId + " and " + episodesDeleted + " episodes");
        return podcastsDeleted;
    } finally {
        db.endTransaction();
    }
}
```

**Step 2: Implement unsubscribePodcast in SubscriptionsFragment**

```java
private void unsubscribePodcast(final Podcast podcast) {
    // Show confirmation dialog
    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle("Unsubscribe");
    builder.setMessage("Unsubscribe from " + podcast.getTitle() + "? This will delete all episodes.");
    builder.setPositiveButton("Unsubscribe", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            new UnsubscribeTask(SubscriptionsFragment.this, podcast, podcastRepository).execute();
        }
    });
    builder.setNegativeButton("Cancel", null);
    builder.show();
}

/**
 * AsyncTask to unsubscribe from podcast
 */
private static class UnsubscribeTask extends AsyncTask<Void, Void, Integer> {
    private final WeakReference<SubscriptionsFragment> fragmentRef;
    private final Podcast podcast;
    private final PodcastRepository repository;

    UnsubscribeTask(SubscriptionsFragment fragment, Podcast podcast, PodcastRepository repository) {
        this.fragmentRef = new WeakReference<>(fragment);
        this.podcast = podcast;
        this.repository = repository;
    }

    @Override
    protected Integer doInBackground(Void... voids) {
        return repository.deletePodcast(podcast.getId());
    }

    @Override
    protected void onPostExecute(Integer deleted) {
        SubscriptionsFragment fragment = fragmentRef.get();
        if (fragment != null && fragment.getContext() != null) {
            if (deleted > 0) {
                Toast.makeText(fragment.getContext(),
                    "Unsubscribed from " + podcast.getTitle(),
                    Toast.LENGTH_SHORT).show();
                fragment.loadPodcasts(); // Refresh list
            } else {
                Toast.makeText(fragment.getContext(),
                    "Failed to unsubscribe",
                    Toast.LENGTH_SHORT).show();
            }
        }
    }
}
```

**Step 3: Build and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: "Unsubscribe" shows confirmation dialog. Confirming deletes podcast and all episodes, refreshes list.

**Step 4: Commit**

```bash
git add app/src/main/java/com/ispringle/dumbcast/data/PodcastRepository.java app/src/main/java/com/ispringle/dumbcast/fragments/SubscriptionsFragment.java
git commit -m "feat: implement unsubscribe podcast

- Add deletePodcast() to PodcastRepository
- Confirmation dialog before unsubscribe
- Delete podcast and all episodes (cascade)
- Refresh list after deletion

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 12: Final Testing and Cleanup

Remove any remaining test code and verify all features work.

**Step 1: Test all context menus**

- Subscriptions: Refresh, Refresh All, Unsubscribe, Remove NEW
- Episode List: Download, Delete, Play, Add to Backlog, Remove NEW
- Discover: Subscribe, View Episodes, View Info
- Now Playing: Delete, Chapters, Skip to Time, Show Notes

**Step 2: Test tab navigation**

- All 5 tabs: New → Backlog → Subscriptions → Discover → Now Playing → (wrap to New)
- Left/right D-pad wrapping works correctly
- Tab indicator shows current tab with arrows

**Step 3: Test Now Playing UI**

- Image loads from podcast artwork
- Title marquees on long text
- Progress bar updates
- Time display shows elapsed / total
- Keypad controls work (5=play/pause, *=skip back, #=skip forward)

**Step 4: Test episode state management**

- Downloaded episodes move to BACKLOG
- "Remove NEW" moves to AVAILABLE
- "Add to Backlog" works without download
- "Remove NEW from all" updates multiple episodes

**Step 5: Final build and install**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Step 6: Commit**

```bash
git commit -m "test: verify all context menu and UI features

- Tested all context menus in all fragments
- Verified tab navigation and wrapping
- Confirmed Now Playing UI improvements
- Validated episode state management

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Summary

This implementation plan covers:

1. **Tab Navigation Redesign** - Single tab indicator with arrows, wrapping navigation
2. **Context Menu System** - AlertDialog-based menus for all fragments
3. **Now Playing UI** - Image, marquee title, simplified layout
4. **Image Loading** - AsyncTask with memory and disk caching
5. **Episode State Management** - Remove NEW, Add to Backlog actions
6. **Podcast Refresh** - Single and all podcast refresh
7. **Unsubscribe** - Delete podcast with confirmation

Each task is broken into small steps with clear testing instructions. The plan assumes no TDD infrastructure but includes manual testing at each step.
