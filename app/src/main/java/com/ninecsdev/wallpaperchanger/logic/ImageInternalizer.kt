package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Handles copying external images into the app's private storage.
 * Used for manual collections to not get limited by android persistence file access.
 */
object ImageInternalizer {
    private const val TAG = "ImageInternalizer"
    private const val INTERNAL_FOLDER = "internal_wallpapers"
    private const val QUALITY_HIGH = 95
    private const val QUALITY_LOW = 80
    private const val LARGE_FILE_THRESHOLD = 2L * 1024 * 1024

    /**
     * Copies a list of URIs into internal storage as WebP files.
     * Uses inSampleSize subsampling to avoid loading full-res bitmaps and
     * processes all images concurrently.
     * @return List of internal file URIs.
     */
    suspend fun internalizeImages(context: Context, uris: List<Uri>): List<Uri> {
        return withContext(Dispatchers.IO) {
            val internalDir = File(context.filesDir, INTERNAL_FOLDER)
            if (!internalDir.exists()) internalDir.mkdirs()

            val metrics = context.resources.displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            coroutineScope {
                uris.map { uri ->
                    async {
                        val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                        val quality = if (fileSize > LARGE_FILE_THRESHOLD) QUALITY_LOW else QUALITY_HIGH
                        internalizeImage(context, uri, internalDir, screenW, screenH, quality)
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }

    private fun internalizeImage(
        context: Context,
        uri: Uri,
        internalDir: File,
        screenW: Int,
        screenH: Int,
        quality: Int
    ): Uri? {
        return try {
            val fileName = "img_${UUID.randomUUID()}.webp"
            val outputFile = File(internalDir, fileName)

            // Read bounds only
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            // Calculate inSampleSize to rough-downsample during decode
            options.inSampleSize = calculateInSampleSize(options, screenW, screenH)
            options.inJustDecodeBounds = false

            // Decode at reduced resolution
            val sampled = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null

            // Fine-resize to exact screen dimensions
            val processedBitmap = resizeToFitScreen(sampled, screenW, screenH)

            FileOutputStream(outputFile).use { out ->
                processedBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            }

            if (processedBitmap != sampled) sampled.recycle()
            processedBitmap.recycle()

            Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to internalize image: $uri", e)
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
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

    private fun resizeToFitScreen(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val width = src.width
        val height = src.height

        val scale = maxOf(targetW.toFloat() / width, targetH.toFloat() / height)

        if (scale >= 1f) return src

        return src.scale((width * scale).toInt(), (height * scale).toInt())
    }

    /**
     * Safely deletes internal wallpaper files.
     */
    fun deleteInternalFile(path: String?) {
        if (path == null) return
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed for: $path", e)
        }
    }
}