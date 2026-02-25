package com.example.photowallpaper.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.photowallpaper.auth.GoogleAuthManager
import com.example.photowallpaper.preferences.AppPreferences
import com.example.photowallpaper.repository.PhotosRepository
import com.example.photowallpaper.ui.MainViewModel
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class WallpaperWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WallpaperWorker"
        const val WORK_NAME = "wallpaper_change"

        fun schedule(context: Context, intervalMinutes: Int) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WallpaperWorker>(
                intervalMinutes.toLong(), TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Scheduled wallpaper change every $intervalMinutes minutes")
        }

        fun runOnce(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<WallpaperWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Enqueued one-time wallpaper change")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled wallpaper change work")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting wallpaper change work")

        val authManager = GoogleAuthManager(applicationContext)
        if (!authManager.isSignedIn()) {
            Log.w(TAG, "User not signed in, skipping")
            return Result.failure()
        }

        return try {
            val prefs = AppPreferences(applicationContext)
            val folderId = prefs.folderId.first()
            val folderName = prefs.folderName.first()
            val selectedLabels = prefs.selectedFilterLabels.first()
            val photoLabelsMap = prefs.photoLabelsMap.first()

            // Compute matching file IDs from label filters
            val matchingFileIds = if (selectedLabels.isEmpty()) {
                emptySet()
            } else {
                photoLabelsMap.filterValues { labels ->
                    labels.any { it in selectedLabels }
                }.keys
            }

            val repository = PhotosRepository(applicationContext)
            val bitmap = repository.getRandomPhotoBitmapFiltered(
                folderId = folderId,
                folderName = if (folderId == null) folderName else null,
                matchingFileIds = matchingFileIds
            )

            if (bitmap != null) {
                val homeBlur = prefs.blurHomePercent.first()
                val lockBlur = prefs.blurLockPercent.first()
                MainViewModel.setWallpaperWithBlur(applicationContext, bitmap, homeBlur, lockBlur)
                prefs.setLastChanged(System.currentTimeMillis())
                val filterInfo = if (selectedLabels.isNotEmpty()) "filters=${selectedLabels.joinToString(",")}" else "no filters"
                Log.d(TAG, "Wallpaper changed successfully (blur: home=$homeBlur%, lock=$lockBlur%, $filterInfo)")
                Result.success()
            } else {
                Log.w(TAG, "No photo available (filters=${selectedLabels.joinToString(",")})")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change wallpaper", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

}
