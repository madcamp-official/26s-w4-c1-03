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
    /**
     * A detail pass is different from the small-subject fallback: it is for a
     * single, broad central box that may actually contain two touching objects.
     * It is deliberately rate-limited and must confirm the split twice before
     * it can replace the ordinary detector result.
     */
    val overlapDetailEnabled: Boolean = true,
    val overlapDetailEveryFrames: Int = 4,
    val overlapDetailCropScale: Float = 1.85f,
    val overlapDetailMinimumAreaRatio: Float = 0.09f,
    val overlapDetailMinimumSpan: Float = 0.34f,
    val overlapDetailFocusWidth: Float = 0.70f,
    val overlapDetailFocusHeight: Float = 0.68f,
    val overlapDetailMaxChildAreaRatio: Float = 0.72f,
    val overlapDetailChildDuplicateIou: Float = 0.45f,
    val overlapDetailCacheFrames: Int = 8,
) {
    init {
        require(fallbackEveryFrames >= 1)
        require(cropScale in 1.10f..2.0f)
        require(smallObjectAreaRatio in 0f..1f)
        require(duplicateIou in 0f..1f)
        require(overlapDetailEveryFrames >= 1)
        require(overlapDetailCropScale in 1.10f..2.5f)
        require(overlapDetailMinimumAreaRatio in 0f..1f)
        require(overlapDetailMinimumSpan in 0f..1f)
        require(overlapDetailFocusWidth in 0.20f..1f)
        require(overlapDetailFocusHeight in 0.20f..1f)
        require(overlapDetailMaxChildAreaRatio in 0f..1f)
        require(overlapDetailChildDuplicateIou in 0f..1f)
        require(overlapDetailCacheFrames >= 1)
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

        /**
         * Enlarges the detector view around one already-central group without
         * cutting it off. The minimum crop side supplies actual magnification
         * for a moderately broad group, while the padding preserves context.
         */
        fun around(subject: NormalizedBox, scale: Float, padding: Float = 1.15f): ObjectDetectionCrop {
            require(scale >= 1f)
            require(padding >= 1f)
            val minSide = (1f / scale).coerceIn(0.1f, 1f)
            val cropWidth = (subject.width * padding).coerceIn(minSide, 1f)
            val cropHeight = (subject.height * padding).coerceIn(minSide, 1f)
            val (left, right) = centeredBounds(subject.centerX, cropWidth)
            val (top, bottom) = centeredBounds(subject.centerY, cropHeight)
            return ObjectDetectionCrop(left, top, right, bottom)
        }

        private fun centeredBounds(center: Float, size: Float): Pair<Float, Float> {
            val left = (center - size / 2f).coerceIn(0f, 1f - size)
            return left to (left + size).coerceIn(size, 1f)
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

    /** A broad single box near the focus point is eligible for a detail pass. */
    fun overlapDetailCandidate(
        primary: List<ObjectObservation>,
        config: MultiScaleObjectDetectionConfig,
    ): ObjectObservation? {
        if (!config.overlapDetailEnabled || primary.size != 1) return null
        val candidate = primary.single()
        val area = candidate.box.width * candidate.box.height
        val broadEnough = area >= config.overlapDetailMinimumAreaRatio &&
            (candidate.box.width >= config.overlapDetailMinimumSpan ||
                candidate.box.height >= config.overlapDetailMinimumSpan)
        val insideFocus = candidate.box.centerX in focusRange(config.overlapDetailFocusWidth) &&
            candidate.box.centerY in focusRange(config.overlapDetailFocusHeight)
        return candidate.takeIf { broadEnough && insideFocus }
    }

    /**
     * Keeps only individual detail detections that materially sit inside the
     * broad primary group. A crop-level copy of the original group is rejected
     * by the child-area check rather than becoming an extra fake subject.
     */
    fun overlapDetailChildren(
        parent: ObjectObservation,
        detailed: List<ObjectObservation>,
        config: MultiScaleObjectDetectionConfig,
    ): List<ObjectObservation> {
        val parentArea = parent.box.width * parent.box.height
        val children = mutableListOf<ObjectObservation>()
        detailed
            .filter { child ->
                val childArea = child.box.width * child.box.height
                childArea <= parentArea * config.overlapDetailMaxChildAreaRatio &&
                    contains(parent.box, child.box.centerX, child.box.centerY) &&
                    overlapByChild(parent.box, child.box) >= 0.60f
            }
            .sortedWith(compareBy<ObjectObservation> { it.box.centerX }.thenBy { it.box.centerY })
            .forEach { child ->
                if (children.none { kept ->
                        intersectionOverUnion(kept.box, child.box) >= config.overlapDetailChildDuplicateIou
                    }
                ) {
                    children += child
                }
            }
        return children.take(4).takeIf { it.size >= 2 }.orEmpty()
    }

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

    internal fun intersectionOverUnion(a: NormalizedBox, b: NormalizedBox): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun focusRange(size: Float): ClosedFloatingPointRange<Float> {
        val half = size / 2f
        return (0.5f - half)..(0.5f + half)
    }

    private fun contains(parent: NormalizedBox, x: Float, y: Float): Boolean =
        x in parent.left..parent.right && y in parent.top..parent.bottom

    private fun overlapByChild(parent: NormalizedBox, child: NormalizedBox): Float {
        val left = maxOf(parent.left, child.left)
        val top = maxOf(parent.top, child.top)
        val right = minOf(parent.right, child.right)
        val bottom = minOf(parent.bottom, child.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val childArea = child.width * child.height
        return if (childArea <= 0f) 0f else intersection / childArea
    }
}

/** Limits the expensive crop pass while allowing the first empty scene to recover immediately. */
class MultiScaleFallbackScheduler(
    private val config: MultiScaleObjectDetectionConfig,
) {
    private var remainingFrames = 0

    fun shouldRun(primary: List<ObjectObservation>): Boolean {
        if (!MultiScaleObjectDetection.shouldRunFallback(primary, config)) {
            remainingFrames = 0
            return false
        }
        if (remainingFrames > 0) {
            remainingFrames--
            return false
        }
        remainingFrames = (config.fallbackEveryFrames - 1).coerceAtLeast(0)
        return true
    }

    fun reset() {
        remainingFrames = 0
    }
}

/** Rate limiter for broad-group detail inference. */
class OverlapDetailPassScheduler(
    private val config: MultiScaleObjectDetectionConfig,
) {
    private var remainingFrames = 0

    fun shouldRun(candidate: ObjectObservation?): Boolean {
        if (candidate == null) {
            remainingFrames = 0
            return false
        }
        if (remainingFrames > 0) {
            remainingFrames--
            return false
        }
        remainingFrames = (config.overlapDetailEveryFrames - 1).coerceAtLeast(0)
        return true
    }

    fun reset() {
        remainingFrames = 0
    }
}

/**
 * Confirms that a broad primary box is consistently decomposed into the same
 * child geometry before replacing it. This prevents one noisy enlarged crop
 * from inventing an extra slot, while allowing the confirmed split to feed the
 * normal 3/5 [StableSceneTracker] on every intervening frame.
 */
class OverlapDetailRefiner(
    private val config: MultiScaleObjectDetectionConfig,
) {
    private data class RelativeChild(
        val observation: ObjectObservation,
        val relativeBox: NormalizedBox,
    )

    private data class DetailPattern(
        val parent: NormalizedBox,
        val children: List<RelativeChild>,
    )

    private var pending: DetailPattern? = null
    private var confirmed: DetailPattern? = null
    private var cacheFramesRemaining = 0

    /** Emits a replacement only after two matching detail passes. */
    fun recordDetailPass(
        primary: List<ObjectObservation>,
        parent: ObjectObservation,
        detailed: List<ObjectObservation>,
    ): List<ObjectObservation> {
        val children = MultiScaleObjectDetection.overlapDetailChildren(parent, detailed, config)
        if (children.isEmpty()) {
            pending = null
            return reuseConfirmed(primary, parent) ?: primary
        }
        val pattern = DetailPattern(
            parent = parent.box,
            children = children.map { child ->
                RelativeChild(child, toRelative(child.box, parent.box))
            },
        )
        val previous = pending
        return when {
            previous != null && sameDecomposition(previous, pattern) -> {
                confirmed = pattern
                pending = null
                cacheFramesRemaining = config.overlapDetailCacheFrames
                replace(primary, parent, pattern)
            }
            confirmed != null && sameDecomposition(confirmed!!, pattern) -> {
                confirmed = pattern
                pending = null
                cacheFramesRemaining = config.overlapDetailCacheFrames
                replace(primary, parent, pattern)
            }
            else -> {
                pending = pattern
                reuseConfirmed(primary, parent) ?: primary
            }
        }
    }

    /** Reuses only a previously double-confirmed split between detail passes. */
    fun reuseConfirmed(
        primary: List<ObjectObservation>,
        parent: ObjectObservation?,
    ): List<ObjectObservation>? {
        val pattern = confirmed ?: return null
        if (parent == null || cacheFramesRemaining <= 0 || !matchesParent(pattern.parent, parent.box)) {
            confirmed = null
            cacheFramesRemaining = 0
            return null
        }
        cacheFramesRemaining--
        return replace(primary, parent, pattern)
    }

    fun reset() {
        pending = null
        confirmed = null
        cacheFramesRemaining = 0
    }

    private fun replace(
        primary: List<ObjectObservation>,
        parent: ObjectObservation,
        pattern: DetailPattern,
    ): List<ObjectObservation> = primary.filterNot { it === parent }.plus(
        pattern.children.map { child -> child.observation.copy(box = fromRelative(child.relativeBox, parent.box)) },
    )

    private fun sameDecomposition(a: DetailPattern, b: DetailPattern): Boolean =
        a.children.size == b.children.size &&
            matchesParent(a.parent, b.parent) &&
            a.children.zip(b.children).all { (first, second) ->
                val centerDistance = kotlin.math.hypot(
                    first.relativeBox.centerX - second.relativeBox.centerX,
                    first.relativeBox.centerY - second.relativeBox.centerY,
                )
                val areaDelta = kotlin.math.abs(area(first.relativeBox) - area(second.relativeBox))
                centerDistance <= 0.14f && areaDelta <= 0.20f
            }

    private fun matchesParent(a: NormalizedBox, b: NormalizedBox): Boolean =
        MultiScaleObjectDetection.intersectionOverUnion(a, b) >= 0.30f ||
            kotlin.math.hypot(a.centerX - b.centerX, a.centerY - b.centerY) <= 0.12f

    private fun toRelative(child: NormalizedBox, parent: NormalizedBox): NormalizedBox =
        NormalizedBox(
            left = ((child.left - parent.left) / parent.width).coerceIn(0f, 1f),
            top = ((child.top - parent.top) / parent.height).coerceIn(0f, 1f),
            right = ((child.right - parent.left) / parent.width).coerceIn(0f, 1f),
            bottom = ((child.bottom - parent.top) / parent.height).coerceIn(0f, 1f),
        )

    private fun fromRelative(relative: NormalizedBox, parent: NormalizedBox): NormalizedBox =
        NormalizedBox(
            left = (parent.left + relative.left * parent.width).coerceIn(0f, 1f),
            top = (parent.top + relative.top * parent.height).coerceIn(0f, 1f),
            right = (parent.left + relative.right * parent.width).coerceIn(0f, 1f),
            bottom = (parent.top + relative.bottom * parent.height).coerceIn(0f, 1f),
        )

    private fun area(box: NormalizedBox): Float = box.width * box.height
}
