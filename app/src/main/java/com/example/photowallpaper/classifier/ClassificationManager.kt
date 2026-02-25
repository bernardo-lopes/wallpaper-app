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
    private val classifier = PhotoClassifier()

    /**
     * Classify photos that haven't been classified yet.
     * Stores per-photo category labels (e.g. "Mountains", "Outdoor") in the cache.
     * Returns the full photo-labels map for all files.
     */
    suspend fun classifyPhotos(
        files: List<DriveFile>,
        onProgress: (ClassificationProgress) -> Unit
    ): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        val alreadyClassified = preferences.classifiedFileIds.first()
        val currentLabelsMap = preferences.photoLabelsMap.first().toMutableMap()
        val currentClassifiedIds = alreadyClassified.toMutableSet()

        // Only classify files not yet classified
        val toClassify = files.filter { it.id !in alreadyClassified }

        if (toClassify.isEmpty()) {
            onProgress(ClassificationProgress(0, 0, isComplete = true))
            // Prune IDs no longer in the file list
            val fileIdSet = files.map { it.id }.toSet()
            val prunedMap = currentLabelsMap.filterKeys { it in fileIdSet }
            val prunedClassified = currentClassifiedIds.filter { it in fileIdSet }.toSet()
            if (prunedMap.size != currentLabelsMap.size || prunedClassified != currentClassifiedIds) {
                preferences.setPhotoLabelsMap(prunedMap)
                preferences.setClassifiedFileIds(prunedClassified)
            }
            return@withContext prunedMap
        }

        val total = toClassify.size
        var completed = 0

        for (file in toClassify) {
            val thumbnail = repository.downloadThumbnail(file)
            if (thumbnail != null) {
                Log.d("ClassificationManager", "Classifying ${file.name} (thumbnail: ${thumbnail.width}x${thumbnail.height})")
                val labels = classifier.classify(thumbnail, file.name)
                if (labels.isNotEmpty()) {
                    currentLabelsMap[file.id] = labels
                }
                thumbnail.recycle()
            } else {
                Log.w("ClassificationManager", "No thumbnail available for ${file.name}")
            }
            currentClassifiedIds.add(file.id)
            completed++

            onProgress(ClassificationProgress(completed, total, isComplete = completed == total))

            // Save progress every 10 photos (or at the end) to persist partially
            if (completed % 10 == 0 || completed == total) {
                preferences.setPhotoLabelsMap(currentLabelsMap)
                preferences.setClassifiedFileIds(currentClassifiedIds)
            }
        }

        // Final prune
        val fileIdSet = files.map { it.id }.toSet()
        val finalMap = currentLabelsMap.filterKeys { it in fileIdSet }
        val finalClassified = currentClassifiedIds.filter { it in fileIdSet }.toSet()
        preferences.setPhotoLabelsMap(finalMap)
        preferences.setClassifiedFileIds(finalClassified)

        Log.d("ClassificationManager", "Classification complete: ${finalMap.size}/${files.size} photos have labels")
        finalMap
    }

    fun close() {
        classifier.close()
    }
}
