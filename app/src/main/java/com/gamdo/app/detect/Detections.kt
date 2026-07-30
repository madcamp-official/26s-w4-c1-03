package com.gamdo.app.detect

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage

/**
 * Detection domain models (§2-2). All coordinates are **normalized 0~1** in the
 * upright (display-oriented) frame, so downstream code never touches raw pixels
 * or ML Kit types. Kept free of Android/ML Kit types so they unit-test on the JVM.
 */

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class FaceObservation(
    val box: NormalizedBox,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val headEulerAngleZ: Float, // roll degrees
)

data class PoseLandmarkPoint(
    val type: Int, // ML Kit PoseLandmark type constant
    val x: Float,
    val y: Float,
    val inFrameLikelihood: Float,
)

data class PoseObservation(
    val landmarks: List<PoseLandmarkPoint>,
    val averageInFrameLikelihood: Float,
)

/** A tracked on-device object candidate in normalized upright coordinates. */
data class ObjectObservation(
    val box: NormalizedBox,
    /** Optional objectness confidence. ML Kit does not expose one. */
    val detectionConfidence: Float? = null,
    @Deprecated("Use detectionConfidence or classificationConfidence.")
    val confidence: Float = 0f,
    val trackingId: Int? = null,
    /** Stable id assigned by the scene tracker; unlike spatial keys it survives box jitter. */
    val sceneTrackId: Long? = null,
    val labels: List<String> = emptyList(),
    val classificationConfidence: Float? = null,
    val category: GuideObjectCategory = GuideObjectCategory.UNKNOWN,
    val mask: SegmentationObservation? = null,
    /**
     * True only after the same semantic category was observed with sufficient
     * confidence across the tracker confirmation window. Generic layout
     * selection never depends on this flag; it is only allowed to unlock a
     * specialised template such as a drink pair.
     */
    val semanticConfirmed: Boolean = false,
    @Deprecated("Guide eligibility is derived by the scene policy.")
    val isGuideEligible: Boolean = false,
) {
    /** Stable identity for composition matching when the detector has no track id. */
    val stableObjectKey: String
        get() = sceneTrackId?.let { "scene:$it" }
            ?: trackingId?.let { "track:$it" } ?: buildString {
            append(category.name.lowercase())
            append(':')
            append((box.centerX * 100).toInt())
            append(':')
            append((box.centerY * 100).toInt())
            append(':')
            append((box.width / box.height.coerceAtLeast(0.01f) * 20).toInt())
        }
}

/** The intentionally small vocabulary for reliable GAMDO composition guides. */
enum class GuideObjectCategory {
    PERSON,
    DRINKWARE,
    BAG,
    PLANT,
    FOOD_TABLEWARE,
    UNKNOWN,
}

data class SegmentationPoint(val x: Float, val y: Float)

/** Reduced foreground mask used by the scene guide; the full pixel mask is not retained. */
data class SegmentationObservation(
    val outline: List<SegmentationPoint>,
    val bounds: NormalizedBox,
    val confidence: Float,
    val areaRatio: Float,
)

data class DetectionResult(
    val faces: List<FaceObservation>,
    val pose: PoseObservation?,
    val objects: List<ObjectObservation> = emptyList(),
    val segmentation: SegmentationObservation? = null,
    /** True only when the object detector actually ran for this frame. */
    val objectsFresh: Boolean = true,
    val objectSequenceId: Long = 0L,
)

/**
 * A camera frame handed to the detector interfaces. [image] is an opaque platform
 * image (ML Kit `InputImage` at runtime; `null` in tests) so the interface stays
 * ML-Kit-free. [width]/[height] are the upright dimensions used to normalize.
 * [cropBitmapProvider] is invoked synchronously on the analysis thread while its
 * source ImageProxy is still open; it is absent in JVM tests and normal callers.
 */
data class AnalysisFrame(
    val image: Any?,
    val width: Int,
    val height: Int,
    val cropBitmapProvider: ((ObjectDetectionCrop) -> Bitmap?)? = null,
)

/**
 * Builds an [AnalysisFrame] from a CameraX analysis frame, wrapping the media
 * image as an ML Kit [InputImage] and swapping W/H when rotated so the upright
 * dimensions are used for normalization. Returns null if the frame has no image.
 */
@ExperimentalGetImage
fun ImageProxy.toAnalysisFrame(
    cropBitmapProvider: ((ObjectDetectionCrop) -> Bitmap?)? = null,
): AnalysisFrame? {
    val media = image ?: return null
    val rotation = imageInfo.rotationDegrees
    val rotated = rotation == 90 || rotation == 270
    val uprightWidth = if (rotated) height else width
    val uprightHeight = if (rotated) width else height
    return AnalysisFrame(
        image = InputImage.fromMediaImage(media, rotation),
        width = uprightWidth,
        height = uprightHeight,
        cropBitmapProvider = cropBitmapProvider,
    )
}
