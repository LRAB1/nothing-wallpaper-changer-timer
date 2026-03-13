package com.ninecsdev.wallpaperchanger.model

/**
 * Preset rotation intervals for the [RotationTrigger.TIMED] mode.
 *
 * @param displayName Human-readable label shown in the UI.
 * @param milliseconds Duration in milliseconds used by AlarmManager.
 */
enum class TimerInterval(val displayName: String, val milliseconds: Long) {
    ONE_HOUR("Every 1 hour", 60 * 60_000L),
    SIX_HOURS("Every 6 hours", 6 * 60 * 60_000L),
    TWELVE_HOURS("Every 12 hours", 12 * 60 * 60_000L),
    DAILY("Daily (every 24h)", 24 * 60 * 60_000L)
}
