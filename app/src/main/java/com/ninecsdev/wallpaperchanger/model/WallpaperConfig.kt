package com.ninecsdev.wallpaperchanger.model

import android.net.Uri


/**
 * A snapshot of the user's global settings.
 * Used by the UI to represent the current default wallpaper configuration state.
 * TODO: there is a responsibility conflict between this and AppPreferences this may be eliminated in the future.
 */
data class WallpaperConfig(
    val defaultWallpaperUri: Uri? = null,
    val revertToDefaultOnStop: Boolean = true,
    val rotationTrigger: RotationTrigger = RotationTrigger.ON_LOCK,
    val timerInterval: TimerInterval = TimerInterval.DAILY,
    val followFocusMode: Boolean = false
) {
    /** Helper to check if a fallback wallpaper has been configured. */
    val hasDefaultWallpaper: Boolean get() = defaultWallpaperUri != null
}