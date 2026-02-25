package com.example.photowallpaper.classifier

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Classifies a photo using ML Kit's bundled image labeling model.
 * Returns the raw ML Kit labels directly — no hardcoded mapping layer.
 * The available filter labels are built dynamically at runtime from
 * whatever labels ML Kit actually detects across the user's photos.
 */
class PhotoClassifier {

    companion object {
        private const val TAG = "PhotoClassifier"
        private const val CONFIDENCE_THRESHOLD = 0.8f
    }

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
            .build()
    )

    /**
     * Returns the set of raw ML Kit label strings detected in the given image.
     * For example, a mountain lake photo might return {"Mountain", "Lake", "Sky", "Forest"}.
     */
    suspend fun classify(bitmap: Bitmap, fileName: String? = null): Set<String> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    val detected = labels.map { it.text }.toSet()

                    // Log raw ML Kit output for debugging
                    val rawLabelsStr = labels.joinToString { "${it.text}(${String.format("%.2f", it.confidence)})" }
                    Log.d(TAG, "Photo=${fileName ?: "?"} | ML Kit: [$rawLabelsStr]")

                    cont.resume(detected)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Classification failed for ${fileName ?: "?"}", e)
                    cont.resume(emptySet())
                }
        }

    fun close() {
        labeler.close()
    }
}
