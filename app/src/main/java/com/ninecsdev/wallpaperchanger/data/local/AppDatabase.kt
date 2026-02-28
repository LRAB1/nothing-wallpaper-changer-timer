package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Main Database for the application.
 * Using Room to persist collections and image metadata.
 */
@Database(entities = [
    WallpaperCollection::class,
    WallpaperImage::class],
    version = 1,
    // When changing the schema, increment the version number
    // and add: autoMigrations = [AutoMigration(from = 1, to = 2)]
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wallpaperDao(): WallpaperDao

    companion object {
        private const val DB_NAME = "smart_wallpaper_database.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}