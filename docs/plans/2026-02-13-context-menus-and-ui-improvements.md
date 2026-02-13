# Context Menus and UI Improvements Design

**Goal:** Add context menu system, redesign tab navigation, and improve Now Playing UI for KaiOS feature phone.

**Architecture:** AlertDialog-based context menus triggered by menu button, single-tab navigation header, marquee episode titles with podcast artwork.

**Tech Stack:** Android AlertDialog, ImageView with URL loading, TextView marquee, existing PlaybackService integration.

---

## 1. Context Menu System

### Architecture
- AlertDialog-based menus triggered by KEYCODE_MENU button
- Each fragment implements its own context menu with relevant actions
- D-pad navigates menu options, center button selects
- Back button dismisses menu

### Menu Options by Fragment

#### Subscriptions Fragment (on a podcast)
- Refresh (this podcast only)
- Refresh All (all subscriptions)
- Unsubscribe
- Remove NEW from all episodes (moves NEW → AVAILABLE for this podcast)

#### Episode List Fragment (on an episode)
- Download (if not downloaded)
- Delete Download (if downloaded)
- Add to Backlog (moves to BACKLOG without download)
- Remove NEW (moves NEW → AVAILABLE)
- Play (if downloaded)

#### New/Backlog Fragments
- Same as Episode List since they show episode lists

#### Discover Fragment (on a podcast)
- Subscribe
- View Episodes (preview before subscribing)
- View Podcast Info/Description

#### Now Playing Fragment
- Delete Episode (delete downloaded file)
- View Chapters (show chapter list if available)
- Skip to Timestamp (enter timestamp to jump to)
- View Show Notes (show episode description)

### Key Behaviors
- Menu button shows context menu for currently selected item
- D-pad navigates menu options, center button selects
- Back button dismisses menu
- Actions happen immediately, no confirmation dialogs (except Unsubscribe and Delete)

---

## 2. Tab Navigation Redesign

### Current State
Shows all tabs (New, Backlog, Subscriptions, Discovery) with "Dumbcast" header.

### New Design
- **Remove "Dumbcast" header completely** from all screens
- **Single tab display:** Show only the current tab name with arrows indicating more tabs exist
- **Format:** `← New →` or `← Backlog →` etc.
- **Tab order (with wrapping):** New → Backlog → Subscriptions → Discover → Now Playing → (wraps back to New)

### Navigation
- Left D-pad: Previous tab (wraps from New to Now Playing)
- Right D-pad: Next tab (wraps from Now Playing to New)
- Tab indicator always shows current tab with arrows

### Implementation
- Replace TabLayout with custom header TextView
- Handle left/right D-pad in MainActivity
- Update header text on tab changes

### Visual Example
```
Before:
┌─────────────────────────┐
│       Dumbcast          │
├─────────────────────────┤
│ New | Backlog | Subs... │
└─────────────────────────┘

After:
┌─────────────────────────┐
│      ← Backlog →        │
└─────────────────────────┘
```

---

## 3. Now Playing UI Improvements

### Current Issues
- Episode title too long, pushes progress bar off screen
- No podcast/episode artwork shown
- Layout doesn't prioritize the most important info

### New Design

#### Layout (top to bottom)
1. **Episode/Podcast Image** - Large, centered
   - Show episode artwork if available, fallback to podcast artwork
   - Load from URL, cache locally
2. **Episode Title** - Single line, marquee on overflow
   - Smaller font size than current
   - Scrolling marquee/carousel effect if text too long
3. **Podcast Name** - Small, secondary text (below title)
4. **Progress Bar** - Horizontal bar showing playback progress
5. **Time Display** - "5:30 / 20:50" format (elapsed / total)
6. **Play/Pause Button** - Centered, large

#### Controls (via keypad)
- 5/Enter/D-pad center: Play/Pause
- *: Skip backward 30s (existing)
- #: Skip forward 30s (existing)
- Menu button: Context menu (Delete, Chapters, Skip to Time, Show Notes)

#### Key Changes
- Remove "Now Playing" header text (follows new tab design)
- Remove skip backward/forward buttons from UI (keypad only)
- Episode title uses `android:ellipsize="marquee"` for auto-scroll
- Image view with placeholder while loading
- Time format: elapsed / total (e.g., "5:30 / 20:50")

---

## 4. Episode State Management

### Current States
NEW, AVAILABLE, BACKLOG, LISTENED

### New State Transitions

#### Manual Actions (via context menu)
- "Remove NEW" → NEW to AVAILABLE
- "Add to Backlog" → Any state to BACKLOG (no download required)
- "Delete Download" → Removes file, keeps episode state

#### Automatic Actions (existing behavior)
- Download completes → Move to BACKLOG
- Episode played >90% → Move to LISTENED
- NEW episodes older than 7 days → Decay to AVAILABLE

### Key Changes
- Add ability to move episodes to BACKLOG without downloading
- "Remove NEW" action moves to AVAILABLE (not BACKLOG)
- "Remove NEW from all episodes" (on podcast) moves all NEW episodes to AVAILABLE

### Database
- No schema changes needed
- Use existing `updateEpisodeState()` method in EpisodeRepository

### Podcast Refresh
- "Refresh" (single): Fetch latest episodes for selected podcast only
- "Refresh All": Fetch latest episodes for all subscribed podcasts
- Both use existing RSS fetching logic
- Show progress/completion toast

---

## 5. Image Loading

### Requirements
- Load episode artwork from URL if available
- Fallback to podcast artwork
- Cache images locally to avoid re-downloading
- Show placeholder while loading
- Handle loading errors gracefully

### Implementation
- Use AsyncTask to load images in background
- Cache to app's cache directory
- LRU cache in memory for quick access
- Placeholder drawable while loading

---

## Error Handling

### Context Menu Actions
- Show toast on success/failure
- Log errors for debugging
- Refresh UI after successful actions

### Image Loading
- Show placeholder on error
- Log error but don't crash
- Retry on next view

### State Transitions
- Validate state before updating
- Show error toast if update fails
- Rollback UI state on failure

---

## Testing Strategy

### Manual Testing
- Test each context menu action in each fragment
- Verify D-pad navigation works in menus
- Test tab navigation wrapping
- Verify marquee scrolling on long titles
- Test image loading with/without network

### Edge Cases
- No episodes in list
- No internet connection for images
- Very long episode titles
- Episode with no artwork
- Podcast with no episodes
