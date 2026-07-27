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

    fun isGuideEligible(
        category: GuideObjectCategory,
        detectionConfidence: Float,
        mask: SegmentationObservation?,
    ): Boolean {
        val area = mask?.areaRatio ?: return false
        return category != GuideObjectCategory.UNKNOWN &&
            detectionConfidence >= 0.65f &&
            mask.confidence >= 0.60f &&
            area in 0.01f..0.85f &&
            mask.outline.size >= 3
    }
}

/** Requires 3 of the last 5 observations to identify the same subject. */
class GuideCandidateStabilizer(
    private val windowSize: Int = 5,
    private val confirmationsRequired: Int = 3,
    private val maxCenterDistance: Float = 0.16f,
) {
    init {
        require(windowSize >= 1)
        require(confirmationsRequired in 1..windowSize)
    }

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
