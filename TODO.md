# Dumbcast TODO

## Pending Tasks

### Task #41: Unsubscribe Podcast
- Add unsubscribe functionality to podcast context menu
- Delete podcast and all its episodes from database
- Show confirmation dialog before deletion
- Refresh UI after unsubscribe

### Task #42: Final Testing and Cleanup
- Test all features end-to-end
- Clean up debug logging
- Review code for TODOs and FIXMEs
- Verify all edge cases

### Task #46: Add "Remove from Backlog" context menu option
- Add menu item to episode context menu
- Only show when episode is in BACKLOG state
- Change episode state from BACKLOG to AVAILABLE
- Show confirmation toast

### Task #47: Auto-remove from BACKLOG on playback completion
- When episode finishes playing (reaches end)
- Automatically change state from BACKLOG to AVAILABLE
- Update UI to reflect state change
- Consider adding user preference to disable this behavior

### Task #48: Add episode search to episode lists
- Add search functionality to all episode list views
- Search by episode title
- Filter results in real-time as user types
- Clear search with dedicated button/key

### Task #49: Add 3x3 GridView with number hotkeys to all podcast fragments
Replace ListView with GridView (3 columns) in all podcast-viewing fragments:

**Affected Fragments:**
1. NewFragment - shows podcasts with new episodes
2. SubscriptionsFragment - shows all subscribed podcasts
3. DiscoverFragment - shows available podcasts to subscribe to

**Implementation per fragment:**
- Change layout XML from ListView to GridView with numColumns="3"
- Add horizontalSpacing and verticalSpacing (8dp)
- Update Java code: ListView -> GridView references
- Add number key support (1-9) to directly open/subscribe to podcast at that position
- Keep existing D-pad center and menu button functionality

**Grid layout benefits:**
- Better visual overview of podcasts
- Number keys enable quick access on feature phones
- 3x3 grid maps naturally to phone keypad (123, 456, 789)

### Task #50: Fix new subscription episodes - don't mark as NEW
When subscribing to a new podcast, episodes should NOT be marked as NEW state.

**Current behavior:**
- New subscription sets all episodes to NEW

**Expected behavior:**
- New subscription sets episodes to AVAILABLE
- Apply session grace for old episodes if needed
- NEW state should ONLY be used when refreshing existing subscriptions

**NEW state should only apply when:**
- Refreshing an existing podcast
- Finding episodes we don't have yet
- That are newer than the last refresh timestamp

**Why:**
- Prevents overwhelming user with hundreds of "new" episodes on first subscribe
- NEW is meant to highlight truly new content from subscriptions
- Initial subscription is a discovery action, not a notification

**Files to modify:**
- PodcastRepository.java: insertEpisodesFromFeed() method already fixed for this, verify it's correct
- RefreshPodcastTask in SubscriptionsFragment.java: Should set NEW for existing podcast refreshes

## Completed Recently

### SubscriptionsFragment Refresh Bypass Fix ✓
- Fixed SubscriptionsFragment bypassing PodcastRepository refresh methods
- RefreshPodcastTask now uses PodcastRepository.refreshPodcast()
- RefreshAllPodcastsTask now uses PodcastRepository.refreshAllPodcasts()
- Ensures timestamp-based filtering is actually applied when refreshing from UI
- Removed 114 lines of duplicate manual refresh logic
- Closes dumbcast-3tz

### Release Build Setup ✓
- Created release keystore: app/dumbcast-release.keystore
- Added signing configuration to build.gradle
- Fixed Lint error: Made DownloadCompleteReceiver static inner class
- Release APK builds successfully
- APK location: app/build/outputs/apk/release/app-release.apk (1.4MB)

### Reverse Episode Order ✓
- Added database column for per-podcast reverse order setting
- Press * key in episode list to toggle order
- Persists in database per podcast
- Shows toast with current setting

### Notification Navigation Fix ✓
- Tapping Now Playing notification now goes to Now Playing tab
- Uses Intent extras to specify target tab

### Initial Subscription BACKLOG Fix ✓
- Episodes no longer auto-added to BACKLOG on first subscribe
- BACKLOG is now manual-only

### Downloaded Badge Styling ✓
- "DL" badge now matches NEW/BACKLOG badge styling
- Consistent visual appearance across all badges
