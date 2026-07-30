package com.gamdo.app.detect

import com.gamdo.app.camera.TiltReading

/** Brightness values sampled by the Android camera adapter. All values are 0..1. */
data class BrightnessSample(
    val frameMean: Float,
    val faceMean: Float? = null,
    val backgroundMean: Float? = null,
)

/** Platform-free input boundary for the P2 feature calculator. */
data class FrameFeatureInput(
    val detection: DetectionResult,
    val tilt: TiltReading = TiltReading(rollDeg = 0f, pitchDeg = 0f),
    val brightness: BrightnessSample = BrightnessSample(frameMean = 0f),
    val shake: Float = 0f,
    /** Optional multi-person boxes supplied by a future detector adapter. */
    val personCandidates: List<NormalizedBox> = emptyList(),
)

data class PointN(val x: Float, val y: Float)

data class SideMargins(val left: Float, val right: Float)

/** Normalized frame features consumed by the later guide and scoring modules. */
data class FrameFeatures(
    val personBox: NormalizedBox?,
    val faceBox: NormalizedBox?,
    val personCenter: PointN?,
    val personAreaRatio: Float,
    val headroom: Float,
    val sideMargins: SideMargins,
    val tiltDeg: Float,
    val pitchDeg: Float,
    val brightnessMean: Float,
    val backlightFlag: Boolean,
    val lowLightFlag: Boolean,
    val poseConfidence: Float,
    val shake: Float,
)

/**
 * Converts detector and sensor output into stable, normalized features.
 *
 * This class deliberately has no Android or ML Kit dependency. The camera layer
 * owns conversion to [FrameFeatureInput]; this class only applies geometry and
 * threshold rules that can be tested on the JVM.
 */
class FrameFeatureCalculator(
    @Suppress("UNUSED_PARAMETER")
    private val minLandmarkLikelihood: Float = 0.3f,
    private val lowLightThreshold: Float = 0.18f,
    private val backlightRatioThreshold: Float = 1.8f,
) {
    fun calculate(input: FrameFeatureInput): FrameFeatures {
        val face = selectPrimaryFace(input.detection.faces)
        val person = selectPrimaryPerson(input.personCandidates, face?.box)
        val personCenter = person?.let { PointN(it.centerX, it.centerY) }
        val faceTop = face?.box?.top ?: person?.top ?: 0f
        val brightness = input.brightness.frameMean.coerceIn(0f, 1f)
        val backgroundMean = input.brightness.backgroundMean
        val faceMean = input.brightness.faceMean
        val backlight = backgroundMean != null && faceMean != null &&
            faceMean > 0f && backgroundMean / faceMean >= backlightRatioThreshold

        return FrameFeatures(
            personBox = person,
            faceBox = face?.box,
            personCenter = personCenter,
            personAreaRatio = person?.let { area(it) } ?: 0f,
            headroom = faceTop.coerceIn(0f, 1f),
            sideMargins = SideMargins(
                left = person?.left?.coerceIn(0f, 1f) ?: 0f,
                right = person?.let { (1f - it.right).coerceIn(0f, 1f) } ?: 0f,
            ),
            tiltDeg = input.tilt.rollDeg,
            pitchDeg = input.tilt.pitchDeg,
            brightnessMean = brightness,
            backlightFlag = backlight,
            lowLightFlag = brightness <= lowLightThreshold,
            // V3.1 uses a face/person box or fixed framing, never live pose.
            poseConfidence = 0f,
            shake = input.shake.coerceAtLeast(0f),
        )
    }

    private fun selectPrimaryFace(faces: List<FaceObservation>): FaceObservation? =
        faces.maxByOrNull { face ->
            val box = face.box.clamped()
            val areaScore = area(box)
            val distanceFromCenter =
                kotlin.math.abs(box.centerX - 0.5f) + kotlin.math.abs(box.centerY - 0.5f)
            val centralityScore = (1f - distanceFromCenter).coerceIn(0f, 1f)
            areaScore * (1f + 0.35f * centralityScore)
        }

    private fun selectPrimaryPerson(
        candidates: List<NormalizedBox>,
        fallbackFace: NormalizedBox?,
    ): NormalizedBox? {
        val allCandidates = candidates.map { it.clamped() }
        if (allCandidates.isNotEmpty()) {
            return allCandidates.maxByOrNull { box ->
                val centrality = (1f - (kotlin.math.abs(box.centerX - 0.5f) +
                    kotlin.math.abs(box.centerY - 0.5f))).coerceIn(0f, 1f)
                area(box) * (1f + 0.35f * centrality)
            }
        }
        return fallbackFace?.clamped()
    }

    private fun area(box: NormalizedBox): Float =
        (box.right - box.left).coerceAtLeast(0f) * (box.bottom - box.top).coerceAtLeast(0f)

    private fun NormalizedBox.clamped(): NormalizedBox {
        val left = left.coerceIn(0f, 1f)
        val top = top.coerceIn(0f, 1f)
        val right = right.coerceIn(left, 1f)
        val bottom = bottom.coerceIn(top, 1f)
        return NormalizedBox(left, top, right, bottom)
    }
}
