# Architecture

## Project Structure

```
com.ninecsdev.wallpaperchanger/
├── data/                       # Data layer
│   ├── local/                  # Room database, DAO, type converters, SharedPreferences
│   │   ├── AppDatabase         # Room database definition (collections + wallpapers tables)
│   │   ├── AppPreferences      # SharedPreferences wrapper for lightweight flags
│   │   ├── Converters          # Room type converters (Uri, enums)
│   │   └── WallpaperDao        # Data access object for collections & images
│   └── WallpaperRepository     # Central coordinator — rotation engine, collection management, service state
├── logic/                      # Image processing
│   ├── BufferManager           # Prepares the next wallpaper on disk (downsample → crop → WebP)
│   └── ImageInternalizer       # Copies picker images into private storage concurrently
├── model/                      # Data classes & enums
│   ├── WallpaperCollection     # Room entity — a named list of wallpapers (Folder or Manual)
│   ├── WallpaperImage          # Room entity — a single wallpaper linked to a collection
│   ├── WallpaperConfig         # Snapshot of user preferences (default wallpaper, revert toggle)
│   ├── ServiceState            # Sealed class — all possible service states
│   ├── CollectionType          # FOLDER | MANUAL
│   └── CropRule                # CENTER | LEFT | RIGHT | FIT
├── service/                    # Background services & receivers
│   ├── WallpaperService        # Foreground service — keeps ScreenOffReceiver alive, manages notifications
│   ├── ScreenOffReceiver       # BroadcastReceiver — applies the buffered wallpaper on SCREEN_OFF
│   ├── WallpaperTileService    # Quick Settings tile integration
│   └── BootReceiver            # Restarts the service after reboot
└── ui/                         # Jetpack Compose UI (single-Activity, Material 3)
    ├── MainActivity            # Single-activity host, handles permissions & system pickers
    ├── mainscreen/             # Dashboard: status header, collection card, default wallpaper card, controls
    │   ├── MainScreen          # Root composable for the dashboard
    │   ├── MainViewModel       # MVVM ViewModel — combines flows into MainUiState
    │   ├── MainUiState         # Single source of truth for the dashboard UI
    │   └── ...                 # ServiceStatusHeader, ServiceControlButtons, etc.
    ├── collectionscreen/       # Collection list grid, create/edit dialogs
    │   ├── CollectionListScreen
    │   ├── CollectionViewModel
    │   └── ...                 # CreateListCard, EditCollectionCard, CollectionGridItem
    ├── components/             # Reusable composables (buttons, text fields, crop selector, loading overlay)
    └── theme/                  # Colors & design tokens (Nothing-inspired black/white/red palette)
```

## Key Design Decisions

| Concern | Approach |
|---|---|
| **State management** | `StateFlow` + single `MainUiState` data class drives the entire UI |
| **Service ↔ UI sync** | `SharedFlow` events emitted by `WallpaperRepository`; no local broadcasts |
| **Persistence** | Room for collections & images; SharedPreferences for lightweight flags |
| **Image pipeline** | Atomic disk buffer (`temp.webp` → rename to `buffer_next.webp`) so the wallpaper is never half-written |
| **Concurrency** | `SupervisorJob` scope in the service; `AtomicBoolean` guard in `ScreenOffReceiver` prevents race conditions |
| **Rotation engine** | In-memory "magazine" list with a pointer — O(1) next-image, full-cycle guarantee before reshuffle |
