package com.gamdo.app.detect

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

data class DetectionResult(
    val faces: List<FaceObservation>,
    val pose: PoseObservation?,
)

/**
 * A camera frame handed to the detector interfaces. [image] is an opaque platform
 * image (ML Kit `InputImage` at runtime; `null` in tests) so the interface stays
 * ML-Kit-free. [width]/[height] are the upright dimensions used to normalize.
 */
data class AnalysisFrame(
    val image: Any?,
    val width: Int,
    val height: Int,
)

/**
 * Builds an [AnalysisFrame] from a CameraX analysis frame, wrapping the media
 * image as an ML Kit [InputImage] and swapping W/H when rotated so the upright
 * dimensions are used for normalization. Returns null if the frame has no image.
 */
@ExperimentalGetImage
fun ImageProxy.toAnalysisFrame(): AnalysisFrame? {
    val media = image ?: return null
    val rotation = imageInfo.rotationDegrees
    val rotated = rotation == 90 || rotation == 270
    val uprightWidth = if (rotated) height else width
    val uprightHeight = if (rotated) width else height
    return AnalysisFrame(
        image = InputImage.fromMediaImage(media, rotation),
        width = uprightWidth,
        height = uprightHeight,
    )
}
