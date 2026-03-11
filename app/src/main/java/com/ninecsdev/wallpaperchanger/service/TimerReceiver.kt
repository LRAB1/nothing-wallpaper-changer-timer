package com.ninecsdev.wallpaperchanger.service

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered by the AlarmManager for timed wallpaper rotation.
 * Applies the pre-processed buffer wallpaper to the lock screen and queues the next one.
 */
class TimerReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TIMER_WALLPAPER = "com.ninecsdev.wallpaperchanger.ACTION_TIMER_WALLPAPER"
        private const val TAG = "TimerReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != ACTION_TIMER_WALLPAPER) return

        // Only act when the service is alive to avoid orphan alarms doing work
        if (!WallpaperService.isAlive) {
            Log.w(TAG, "Timer fired but service is not alive. Skipping.")
            return
        }

        Log.d(TAG, "Timer alarm fired. Applying wallpaper.")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                applyBufferToLockScreen(context)
                WallpaperRepository.refillDiskBuffer()
            } catch (e: Exception) {
                Log.e(TAG, "Error during timed wallpaper change", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun applyBufferToLockScreen(context: Context) {
        try {
            val bufferFile = BufferManager.getBufferFile()

            if (!bufferFile.exists()) {
                Log.w(TAG, "Buffer file missing. Is the service initialized?")
                return
            }

            bufferFile.inputStream().use { stream ->
                val wallpaperManager = WallpaperManager.getInstance(context)
                wallpaperManager.setStream(
                    stream,
                    null,
                    true,
                    WallpaperManager.FLAG_LOCK
                )
            }
            Log.i(TAG, "Wallpaper applied successfully from timer.")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream buffer to lock screen", e)
        }
    }
}
