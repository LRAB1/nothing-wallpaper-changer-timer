package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * In charge of preparing the next wallpaper that will be set.
 * Handles downsampling, aspect-ratio cropping, and WebP compression.
 */
object BufferManager {
    private const val TAG = "BufferManager"
    private const val BUFFER_FILENAME = "buffer_next.webp"
    private const val TEMP_FILENAME = "buffer_temp.webp"
    private const val COMPRESSION_QUALITY = 95

    private lateinit var appContext: Context

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    /**
     * Prepares the next wallpaper file on disk.
     * Prefers the user-edited version ([WallpaperImage.editedUri]) when available.
     * If the wallpaper is already in internal storage it uses that file to not overcompress.
     * Does all the processing first on a temp file and then renames to the actual file that will be used
     */
    suspend fun prepareNextWallpaper(wallpaper: WallpaperImage, cropRule: CropRule): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val metrics = appContext.resources.displayMetrics
                val targetW = metrics.widthPixels
                val targetH = metrics.heightPixels

                // Prefer user-edited image; fall back to original
                val sourceUri = wallpaper.editedUri ?: wallpaper.uri

                // Load the Source Bitmap
                val sourceBitmap: Bitmap? = if (sourceUri.toString().contains("internal_wallpapers")) {
                    appContext.contentResolver.openInputStream(sourceUri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    appContext.contentResolver.openInputStream(sourceUri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }

                    options.inSampleSize = calculateInSampleSize(options, targetW, targetH)
                    options.inJustDecodeBounds = false

                    appContext.contentResolver.openInputStream(sourceUri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }

                if (sourceBitmap == null) return@withContext false

                // Process with crop rule
                val finalBitmap = processBitmap(sourceBitmap, targetW, targetH, cropRule)

                // Atomic Write to Disk
                val bufferFile = File(appContext.cacheDir, BUFFER_FILENAME)
                val tempFile = File(appContext.cacheDir, TEMP_FILENAME)

                FileOutputStream(tempFile).use { out ->
                    finalBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, COMPRESSION_QUALITY, out)
                }

                val success = tempFile.renameTo(bufferFile)
                if (success) {
                    Log.d(TAG, "Buffer ready: ${bufferFile.length() / 1024} KB | Rule: $cropRule")
                }

                // Clean up
                if (finalBitmap != sourceBitmap) sourceBitmap.recycle()
                finalBitmap.recycle()

                success
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare buffer: ${e.message}")
                false
            }
        }
    }

    fun getBufferFile(): File {
        return File(appContext.cacheDir, BUFFER_FILENAME)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            val halfH = height / 2
            val halfW = width / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun processBitmap(source: Bitmap, targetW: Int, targetH: Int, rule: CropRule): Bitmap {
        val sourceW = source.width
        val sourceH = source.height

        // Calculate scale depending on croprule
        val scale = if (rule == CropRule.FIT) {
            // FIT: Entire image visible
            minOf(targetW.toFloat() / sourceW, targetH.toFloat() / sourceH)
        } else {
            // CENTER/LEFT/RIGHT: Fill screen
            maxOf(targetW.toFloat() / sourceW, targetH.toFloat() / sourceH)
        }
        val scaledW = sourceW * scale
        val scaledH = sourceH * scale

        // Calculate Offsets
        val xOffset = when (rule) {
            CropRule.LEFT -> 0f
            CropRule.RIGHT -> targetW - scaledW
            else -> (targetW - scaledW) / 2f
        }
        val yOffset = (targetH - scaledH) / 2f

        // Render
        val result = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK) // Paint the background black for the fit option
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        val matrix = android.graphics.Matrix().apply {
            postScale(scale, scale)
            postTranslate(xOffset, yOffset)
        }

        canvas.drawBitmap(source, matrix, paint)
        return result
    }
}