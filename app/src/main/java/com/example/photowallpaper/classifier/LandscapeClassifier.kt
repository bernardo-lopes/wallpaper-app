package com.example.photowallpaper.classifier

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LandscapeClassifier {

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.5f

        private val LANDSCAPE_LABELS = setOf(
            "Sky", "Cloud", "Mountain", "Nature", "Natural landscape",
            "Natural environment", "Water", "Sea", "Ocean", "Lake",
            "River", "Tree", "Forest", "Beach", "Coast",
            "Sunset", "Sunrise", "Horizon", "Field", "Grassland",
            "Prairie", "Desert", "Snow", "Ice", "Outdoor",
            "Vegetation", "Plant", "Hill", "Atmosphere"
        )
    }

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
            .build()
    )

    /**
     * Returns true if the bitmap is classified as a landscape image.
     * Uses ML Kit's bundled model to detect nature/outdoor labels.
     */
    suspend fun isLandscape(bitmap: Bitmap): Boolean =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    val isLandscape = labels.any { label ->
                        label.text in LANDSCAPE_LABELS
                    }
                    cont.resume(isLandscape)
                }
                .addOnFailureListener {
                    cont.resume(false)
                }
        }

    fun close() {
        labeler.close()
    }
}
