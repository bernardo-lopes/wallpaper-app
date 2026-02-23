package com.example.photowallpaper

import android.app.Application
import androidx.work.Configuration

class PhotoWallpaperApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
