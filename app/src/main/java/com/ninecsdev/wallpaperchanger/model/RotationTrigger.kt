package com.ninecsdev.wallpaperchanger.model

/**
 * Defines what event triggers the wallpaper rotation.
 */
enum class RotationTrigger {
    /** Change wallpaper every time the screen turns off (default, original behavior). */
    ON_LOCK,

    /** Change wallpaper on a fixed time interval via AlarmManager. */
    TIMED
}
