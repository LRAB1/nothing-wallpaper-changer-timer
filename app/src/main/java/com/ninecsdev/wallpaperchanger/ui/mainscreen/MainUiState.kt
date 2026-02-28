package com.ninecsdev.wallpaperchanger.ui.mainscreen

import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperConfig
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Snapshot of the Main Dashboard screen.
 * Built reactively via combine() in MainViewModel.
 */
data class MainUiState(
    // Service & System State
    val serviceState: ServiceState = ServiceState.Loading,

    // Active collection card data
    val activeCollection: WallpaperCollection? = null,
    val previewImages: List<WallpaperImage> = emptyList(),
    val activeCollectionSize: Int = 0,

    // Default wallpaper data
    val config: WallpaperConfig = WallpaperConfig(),

    // Top-level navigation (will be overhauled with Jetpack Navigation)
    val isShowingLists: Boolean = false
) {
    val isStartEnabled: Boolean
        get() = serviceState is ServiceState.Stopped

    val isStopEnabled: Boolean
        get() = serviceState is ServiceState.Running || serviceState is ServiceState.Paused
}