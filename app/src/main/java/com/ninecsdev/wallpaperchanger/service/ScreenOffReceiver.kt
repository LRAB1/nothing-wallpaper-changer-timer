package com.ninecsdev.wallpaperchanger.service

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.shouldRotateAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ScreenOffReceiver : BroadcastReceiver() {

    private val tag = "ScreenOffReceiver"

    companion object {
        // Prevents multiple concurrent swaps if the power button is clicked many times
        private val isWorkInProgress = AtomicBoolean(false)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_SCREEN_OFF) return

        if (!isWorkInProgress.compareAndSet(false, true)) {
            Log.d(tag, "Work already in progress. Skipping.")
            return
        }

        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Apply user-selected animation delay
                val selectedDelay = WallpaperRepository.getDelayLabel().milliseconds
                delay(selectedDelay)

                // Safety check: if the user woke the screen during the delay abort
                if (powerManager.isInteractive) {
                    Log.w(tag, "Screen woke up during ${selectedDelay}ms delay. Aborting.")
                    return@launch
                }

                val activeCollection = WallpaperRepository.getActiveCollectionOnce()
                if (activeCollection == null) {
                    Log.w(tag, "No active collection found. Skipping wallpaper change.")
                    return@launch
                }

                // Evaluate focus/DND state after delay and against the active collection setting.
                // Some OEMs toggle focus state slightly after screen-off, so checking here is safer.
                if (activeCollection.skipOnDnd && WallpaperRepository.isDndActive(failSafeWhenUnknown = true)) {
                    Log.d(tag, "DND/Focus mode active. Skipping wallpaper change for collection '${activeCollection.name}'.")
                    return@launch
                }

                // Some Nothing/Wellbeing builds publish focus state a bit later than screen-off.
                // Perform one short retry before applying wallpaper.
                if (activeCollection.skipOnDnd) {
                    delay(350)
                    if (WallpaperRepository.isDndActive(failSafeWhenUnknown = true)) {
                        Log.d(tag, "DND/Focus mode became active shortly after lock. Skipping wallpaper change.")
                        return@launch
                    }
                }

                if (!activeCollection.shouldRotateAt()) {
                    val frequencyLabel = when (activeCollection.rotationFrequency) {
                        RotationFrequency.PER_LOCK -> "per lock"
                        RotationFrequency.HOURLY -> "hourly"
                        RotationFrequency.PER_DAY -> "daily"
                    }
                    Log.d(tag, "Rotation skipped. Timer for $frequencyLabel not met yet.")
                    return@launch
                }

                // Apply the pre-processed buffer image and prepare next image
                val applied = applyBufferToLockScreen(context)
                if (applied) {
                    WallpaperRepository.markWallpaperChanged(activeCollection.id)
                    WallpaperRepository.refillDiskBuffer()
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during wallpaper change", e)
            } finally {
                isWorkInProgress.set(false)
                pendingResult.finish()
            }
        }
    }

    /**
     * Reads the .webp buffer from disk and streams it to the WallpaperManager.
     * This bypasses the Bitmap heap, preventing OutOfMemory errors
     * and instantly changes the wallpaper.
     */
    private fun applyBufferToLockScreen(context: Context): Boolean {
        try {
            val bufferFile = BufferManager.getBufferFile()

            if (!bufferFile.exists()) {
                Log.w(tag, "Buffer file missing. Is the service initialized?")
                return false
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
            Log.i(tag, "Wallpaper applied successfully from disk buffer.")
            return true

        } catch (e: Exception) {
            Log.e(tag, "Failed to stream buffer to lock screen", e)
            return false
        }
    }
}
