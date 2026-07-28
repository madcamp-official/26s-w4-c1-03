package com.gamdo.app.detect

/**
 * Policy for the occasional enlarged centre-crop pass.
 *
 * The normal full-frame detector always runs first. This policy only permits a
 * second pass when it found no subject or only tiny subjects, which protects the
 * 8 FPS camera-guide target from paying the crop cost on every frame.
 */
data class MultiScaleObjectDetectionConfig(
    val enabled: Boolean = true,
    val fallbackEveryFrames: Int = 6,
    val cropScale: Float = 1.60f,
    val smallObjectAreaRatio: Float = 0.05f,
    val duplicateIou: Float = 0.55f,
) {
    init {
        require(fallbackEveryFrames >= 1)
        require(cropScale in 1.10f..2.0f)
        require(smallObjectAreaRatio in 0f..1f)
        require(duplicateIou in 0f..1f)
    }
}

/** Normalized crop in the same upright coordinate system as [NormalizedBox]. */
data class ObjectDetectionCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f)
        require(right in left..1f && bottom in top..1f)
        require(right > left && bottom > top)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        fun centered(scale: Float): ObjectDetectionCrop {
            require(scale >= 1f)
            val side = (1f / scale).coerceIn(0.1f, 1f)
            val inset = (1f - side) / 2f
            return ObjectDetectionCrop(inset, inset, 1f - inset, 1f - inset)
        }
    }
}

/** Pure coordinate and merge logic shared by Android ML Kit adapters and JVM tests. */
object MultiScaleObjectDetection {
    fun shouldRunFallback(
        primary: List<ObjectObservation>,
        config: MultiScaleObjectDetectionConfig,
    ): Boolean = config.enabled && (
        primary.isEmpty() ||
            primary.maxOf { it.box.width * it.box.height } < config.smallObjectAreaRatio
        )

    /** Converts crop-local detector coordinates back into full-frame coordinates. */
    fun remapToFrame(
        cropDetections: List<ObjectObservation>,
        crop: ObjectDetectionCrop,
    ): List<ObjectObservation> = cropDetections.map { observation ->
        observation.copy(
            box = NormalizedBox(
                left = (crop.left + observation.box.left * crop.width).coerceIn(0f, 1f),
                top = (crop.top + observation.box.top * crop.height).coerceIn(0f, 1f),
                right = (crop.left + observation.box.right * crop.width).coerceIn(0f, 1f),
                bottom = (crop.top + observation.box.bottom * crop.height).coerceIn(0f, 1f),
            ),
        )
    }

    /**
     * Keeps full-frame boxes for broad-scene context and appends only distinct
     * crop detections. The tracker performs the final max-four ranking later.
     */
    fun mergeDistinct(
        primary: List<ObjectObservation>,
        secondary: List<ObjectObservation>,
        duplicateIou: Float,
    ): List<ObjectObservation> {
        require(duplicateIou in 0f..1f)
        val merged = primary.toMutableList()
        secondary.forEach { candidate ->
            if (merged.none { existing -> intersectionOverUnion(existing.box, candidate.box) >= duplicateIou }) {
                merged += candidate
            }
        }
        return merged
    }

    private fun intersectionOverUnion(a: NormalizedBox, b: NormalizedBox): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
