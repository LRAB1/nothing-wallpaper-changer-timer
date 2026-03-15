package com.ninecsdev.wallpaperchanger.model

/**
 * Defines how images are added to a collection.
 */
enum class CollectionType {
    /** Images are automatically retrieved from an external folder. */
    FOLDER,
    /** Images are individually selected by the user. */
    MANUAL
}
