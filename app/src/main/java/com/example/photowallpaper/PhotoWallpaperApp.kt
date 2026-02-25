package com.example.photowallpaper

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.photowallpaper.coil.DriveImageLoaderFactory

class PhotoWallpaperApp : Application(), Configuration.Provider, ImageLoaderFactory {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun newImageLoader(): ImageLoader {
        return DriveImageLoaderFactory(this).newImageLoader()
    }
}
