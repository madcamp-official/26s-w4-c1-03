package com.gamdo.app.detect

/** Maps coarse detector labels into the small vocabulary used by the guide. */
object SceneRecognitionPolicy {
    private val drinkware = setOf("cup", "bottle", "glass", "drink", "mug", "wine glass")
    private val bags = setOf("handbag", "backpack", "bag", "purse", "satchel", "shoulder bag")
    private val plants = setOf("flower", "plant", "potted plant", "tree", "leaf")
    private val food = setOf("food", "plate", "bowl", "fruit", "cake", "pizza", "sandwich", "meal")

    fun categoryFor(labels: List<String>): GuideObjectCategory {
        val normalized = labels.map { it.trim().lowercase() }
        return when {
            normalized.any { label -> drinkware.any(label::contains) } -> GuideObjectCategory.DRINKWARE
            normalized.any { label -> bags.any(label::contains) } -> GuideObjectCategory.BAG
            normalized.any { label -> plants.any(label::contains) } -> GuideObjectCategory.PLANT
            normalized.any { label -> food.any(label::contains) } -> GuideObjectCategory.FOOD_TABLEWARE
            else -> GuideObjectCategory.UNKNOWN
        }
    }


    fun isValidBox(box: NormalizedBox): Boolean =
        box.left in 0f..1f && box.top in 0f..1f &&
            box.right in 0f..1f && box.bottom in 0f..1f &&
            box.right > box.left && box.bottom > box.top &&
            (box.width * box.height) in 0.01f..0.85f

    fun isSemanticMatch(category: GuideObjectCategory, classificationConfidence: Float?): Boolean =
        category != GuideObjectCategory.UNKNOWN && (classificationConfidence ?: 0f) >= 0.65f

    @Deprecated("Use isValidBox for generic layouts and isSemanticMatch for specialization.")
    fun isGuideEligible(
        category: GuideObjectCategory,
        detectionConfidence: Float,
        mask: SegmentationObservation?,
    ): Boolean = category != GuideObjectCategory.UNKNOWN &&
        detectionConfidence >= 0.65f &&
        mask?.let { it.confidence >= 0.60f && it.areaRatio in 0.01f..0.85f && it.outline.size >= 3 } == true
}

/** Source-compatible shim for old JVM tests; never used by the product pipeline. */
@Deprecated("Use StableSceneTracker")
class GuideCandidateStabilizer(
    private val windowSize: Int = 5,
    private val confirmationsRequired: Int = 3,
    private val maxCenterDistance: Float = 0.16f,
) {
    private val history = ArrayDeque<ObjectObservation>()

    fun accept(candidate: ObjectObservation?): ObjectObservation? {
        if (candidate == null) {
            history.clear()
            return null
        }
        val previous = history.lastOrNull()
        if (previous != null && !sameSubject(previous, candidate)) history.clear()
        history.addLast(candidate)
        while (history.size > windowSize) history.removeFirst()
        val matches = history.count { sameSubject(it, candidate) }
        return candidate.copy(isGuideEligible = candidate.isGuideEligible && matches >= confirmationsRequired)
    }

    fun reset() = history.clear()

    private fun sameSubject(a: ObjectObservation, b: ObjectObservation): Boolean {
        if (a.trackingId != null && b.trackingId != null) return a.trackingId == b.trackingId
        val dx = a.box.centerX - b.box.centerX
        val dy = a.box.centerY - b.box.centerY
        return dx * dx + dy * dy <= maxCenterDistance * maxCenterDistance
    }
}
