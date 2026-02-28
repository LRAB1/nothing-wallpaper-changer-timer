package com.ninecsdev.wallpaperchanger

import android.app.Application
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.BufferManager

/**
 * Custom Application class for one-time initializations.
 */
class WallpaperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WallpaperRepository.initialize(this)
        BufferManager.initialize(this)
    }
}
