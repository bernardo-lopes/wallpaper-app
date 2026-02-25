package com.example.photowallpaper

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.example.photowallpaper.worker.WallpaperWorker

/**
 * Invisible activity that triggers a wallpaper change via WorkManager and finishes immediately.
 * Used by the app shortcut so the wallpaper changes without opening the main UI.
 */
class ChangeWallpaperActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WallpaperWorker.runOnce(this)
        Toast.makeText(this, "Changing wallpaper...", Toast.LENGTH_SHORT).show()
        finish()
    }
}
