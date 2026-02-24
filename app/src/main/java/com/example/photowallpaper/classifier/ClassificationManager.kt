package com.example.photowallpaper.classifier

import android.util.Log
import com.example.photowallpaper.api.DriveFile
import com.example.photowallpaper.preferences.AppPreferences
import com.example.photowallpaper.repository.PhotosRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ClassificationProgress(
    val classified: Int,
    val total: Int,
    val isComplete: Boolean
)

class ClassificationManager(
    private val repository: PhotosRepository,
    private val preferences: AppPreferences
) {
    private val classifier = LandscapeClassifier()

    /**
     * Classify photos that haven't been classified yet.
     * Calls [onProgress] after each photo is processed.
     * Returns the final set of landscape file IDs.
     */
    suspend fun classifyPhotos(
        files: List<DriveFile>,
        onProgress: (ClassificationProgress) -> Unit
    ): Set<String> = withContext(Dispatchers.IO) {
        val alreadyClassified = preferences.classifiedFileIds.first()
        val currentLandscapeIds = preferences.landscapeFileIds.first().toMutableSet()
        val currentClassifiedIds = alreadyClassified.toMutableSet()

        // Only classify files not yet classified
        val toClassify = files.filter { it.id !in alreadyClassified }

        if (toClassify.isEmpty()) {
            onProgress(ClassificationProgress(0, 0, isComplete = true))
            // Prune IDs that are no longer in the file list
            val fileIdSet = files.map { it.id }.toSet()
            val prunedLandscape = currentLandscapeIds.filter { it in fileIdSet }.toSet()
            val prunedClassified = currentClassifiedIds.filter { it in fileIdSet }.toSet()
            if (prunedLandscape != currentLandscapeIds || prunedClassified != currentClassifiedIds) {
                preferences.setLandscapeFileIds(prunedLandscape)
                preferences.setClassifiedFileIds(prunedClassified)
            }
            return@withContext prunedLandscape
        }

        val total = toClassify.size
        var completed = 0

        for (file in toClassify) {
            val thumbnail = repository.downloadThumbnail(file)
            if (thumbnail != null) {
                val isLandscape = classifier.isLandscape(thumbnail)
                if (isLandscape) {
                    currentLandscapeIds.add(file.id)
                }
                thumbnail.recycle()
            }
            // Even if thumbnail is null, mark as classified to avoid retrying
            currentClassifiedIds.add(file.id)
            completed++

            onProgress(ClassificationProgress(completed, total, isComplete = completed == total))

            // Save progress every 10 photos (or at the end) to persist partially
            if (completed % 10 == 0 || completed == total) {
                preferences.setLandscapeFileIds(currentLandscapeIds)
                preferences.setClassifiedFileIds(currentClassifiedIds)
            }
        }

        // Final prune: remove IDs not in current file list
        val fileIdSet = files.map { it.id }.toSet()
        val finalLandscape = currentLandscapeIds.filter { it in fileIdSet }.toSet()
        val finalClassified = currentClassifiedIds.filter { it in fileIdSet }.toSet()
        preferences.setLandscapeFileIds(finalLandscape)
        preferences.setClassifiedFileIds(finalClassified)

        Log.d("ClassificationManager", "Classification complete: ${finalLandscape.size}/${files.size} are landscape")
        finalLandscape
    }

    fun close() {
        classifier.close()
    }
}
