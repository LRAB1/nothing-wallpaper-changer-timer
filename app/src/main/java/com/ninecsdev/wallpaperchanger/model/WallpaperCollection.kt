package com.ninecsdev.wallpaperchanger.model

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a logical list of wallpapers created by the user.
 *
 * [CollectionType.FOLDER] types are synced with a physical directory on the device.
 * [CollectionType.MANUAL] types have images handpicked by the user and are not synced.
 */
@Entity(tableName = "collections")
data class WallpaperCollection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: CollectionType,
    val isActive: Boolean = false,
    val rootUri: Uri? = null,
    val defaultCropRule: CropRule = CropRule.CENTER,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

/**
 * Defines how images are added to a collection.
 */
enum class CollectionType {
    /** Images are automatically retrieved from an external folder. */
    FOLDER,
    /** Images are individually selected by the user. */
    MANUAL
}
