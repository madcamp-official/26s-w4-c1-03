package com.gamdo.app.detect

import com.gamdo.app.guide.ScenePolygonRegion
import kotlin.math.hypot

/** All object-guide quality thresholds live in guide_config.json. */
data class ObjectTrackerConfig(
    val windowSize: Int = 5,
    val confirmationsRequired: Int = 3,
    val companionConfirmationsRequired: Int = 2,
    val maxObjects: Int = 4,
    val minimumIoU: Float = 0.30f,
    val maxCenterDistance: Float = 0.16f,
    val minimumAreaRatio: Float = 0.01f,
    val maximumAreaRatio: Float = 0.85f,
    val duplicateIou: Float = 0.75f,
    val semanticMinConfidence: Float = 0.80f,
    val semanticConfirmationsRequired: Int = 3,
    val focusRegionWidth: Float = 0.70f,
    val focusRegionHeight: Float = 0.68f,
    // The anchor neighborhood is slightly wider than the tap ROI so a
    // composed three-item tabletop scene can span the central viewfinder.
    val subjectClusterRadius: Float = 0.38f,
    val subjectClusterMinimumRelativeArea: Float = 0.10f,
    val sceneAnchorMaxDistance: Float = 0.36f,
    val maximumUnknownAspectRatio: Float = 3.50f,
    val nestedDuplicateCenterDistance: Float = 0.08f,
    val nestedDuplicateContainment: Float = 0.78f,
    val interestRegion: SceneInterestRegion = SceneInterestRegion.Default,
    val tapInterestRadiusX: Float = 0.32f,
    val tapInterestRadiusY: Float = 0.28f,
) {
    init {
        require(windowSize >= 1)
        require(confirmationsRequired in 1..windowSize)
        require(companionConfirmationsRequired in 1..windowSize)
        require(maxObjects >= 1)
        require(minimumAreaRatio in 0f..1f)
        require(maximumAreaRatio in minimumAreaRatio..1f)
        require(semanticMinConfidence in 0f..1f)
        require(semanticConfirmationsRequired in 1..windowSize)
        require(focusRegionWidth in 0.20f..1f)
        require(focusRegionHeight in 0.20f..1f)
        require(subjectClusterRadius in 0.05f..0.80f)
        require(subjectClusterMinimumRelativeArea in 0f..1f)
        require(sceneAnchorMaxDistance in 0.10f..0.70f)
        require(maximumUnknownAspectRatio >= 1f)
        require(nestedDuplicateCenterDistance in 0f..1f)
        require(nestedDuplicateContainment in 0f..1f)
        require(tapInterestRadiusX in 0.10f..0.50f)
        require(tapInterestRadiusY in 0.10f..0.50f)
    }
}

/**
 * Scene-level confirmation. It deliberately does not freeze on the first
 * object that happens to be detected: one anchor needs 3/5 sightings and
 * nearby companions need 2/5 sightings before they enter the fixed layout.
 */
class StableSceneTracker(
    private val config: ObjectTrackerConfig = ObjectTrackerConfig(),
) {
    private data class Sample(
        val objects: List<ObjectObservation>,
        val people: List<ObjectObservation>,
        val sequenceId: Long,
    )

    private val window = ArrayDeque<Sample>()
    private var region = config.interestRegion
    private var polygonRegion: ScenePolygonRegion? = null
    private var lastStable = emptyList<ObjectObservation>()

    fun accept(batch: ObjectDetectionBatch): List<ObjectObservation> {
        if (!batch.isFresh) return lastStable
        val candidates = sanitize(batch.objects)
        val people = candidates.filter { it.category == GuideObjectCategory.PERSON }
        val objects = candidates.filter { it.category != GuideObjectCategory.PERSON }
        window += Sample(objects, people, batch.sequenceId)
        while (window.size > config.windowSize) window.removeFirst()

        // A polygon is an explicit user scope, not an anchor neighborhood.
        // Every independently tracked object inside it gets its own 3/5 vote;
        // objects outside the lasso can never be pulled in as companions.
        if (polygonRegion != null) {
            val all = window.asReversed().flatMap { it.objects + it.people }
                .groupBy { it.stableObjectKey }
                .mapNotNull { (_, observations) ->
                    val representative = observations.maxByOrNull(::rankingScore) ?: return@mapNotNull null
                    val sightings = window.count { sample ->
                        sample.objects.any { sameSubject(it, representative) } ||
                            sample.people.any { sameSubject(it, representative) }
                    }
                    representative.takeIf { sightings >= config.confirmationsRequired }
                }
                .sortedByDescending(::rankingScore)
            if (window.size < config.windowSize) { lastStable = emptyList(); return lastStable }
            lastStable = limitWithPersonPriority(all).map { it.copy(semanticConfirmed = semanticConfirmed(it)) }
            return lastStable
        }

        val anchor = chooseAnchor(window.lastOrNull()?.objects.orEmpty())
        val anchorSightings = anchor?.let { selected ->
            window.count { sample -> sample.objects.any { sameSubject(it, selected) } }
        } ?: window.count { it.people.isNotEmpty() }
        if (window.size < config.windowSize || anchorSightings < config.confirmationsRequired) {
            lastStable = emptyList()
            return lastStable
        }

        val companions = if (anchor == null) emptyList() else window.asReversed()
            .flatMap { it.objects }
            .filter { !sameSubject(it, anchor) }
            .filter { candidate -> distance(anchor, candidate) <= config.subjectClusterRadius }
            .filter { candidate ->
                window.count { sample -> sample.objects.any { sameSubject(it, candidate) } } >=
                    config.companionConfirmationsRequired
            }
            .distinctBy { candidate -> stableKey(candidate) }
            .sortedByDescending(::rankingScore)

        val representative = listOfNotNull(anchor) + companions
        val person = window.mapNotNull { it.people.maxByOrNull(::rankingScore) }
            .maxByOrNull(::rankingScore)
        lastStable = limitWithPersonPriority(
            listOfNotNull(person) + representative,
        ).map { it.copy(semanticConfirmed = semanticConfirmed(it)) }
        return lastStable
    }

    /** Starts automatic search around the supplied normalized preview point. */
    fun rescanAt(anchorX: Float, anchorY: Float) {
        polygonRegion = null
        region = SceneInterestRegion(
            anchorX.coerceIn(0.16f, 0.84f),
            anchorY.coerceIn(0.18f, 0.82f),
            config.tapInterestRadiusX,
            config.tapInterestRadiusY,
            SceneInterestRegion.Source.TAP,
        )
        window.clear()
        lastStable = emptyList()
    }

    /** Starts a fresh 3/5 confirmation window restricted to a user lasso. */
    fun rescanInPolygon(polygon: ScenePolygonRegion) {
        polygonRegion = polygon
        window.clear()
        lastStable = emptyList()
    }

    fun cancelPolygonSearch() {
        polygonRegion = null
        region = config.interestRegion
        window.clear()
        lastStable = emptyList()
    }

    fun reset() {
        window.clear()
        lastStable = emptyList()
        region = config.interestRegion
        polygonRegion = null
    }

    private fun sanitize(objects: List<ObjectObservation>): List<ObjectObservation> {
        val selected = mutableListOf<ObjectObservation>()
        objects
            .asSequence()
            .filter { SceneRecognitionPolicy.isValidBox(it.box) }
            .filter { area(it.box) in config.minimumAreaRatio..config.maximumAreaRatio }
            .filter { it.category == GuideObjectCategory.PERSON || !isClippedBackground(it) }
            .filter { candidate ->
                polygonRegion?.accepts(candidate.box)
                    ?: (candidate.category == GuideObjectCategory.PERSON || region.contains(candidate.box))
            }
            .filter { it.category == GuideObjectCategory.PERSON || isPlausibleSubject(it) }
            .sortedByDescending(::rankingScore)
            .forEach { candidate ->
                if (selected.none { isDuplicate(it, candidate) }) selected += candidate
            }

        val people = selected.filter { it.category == GuideObjectCategory.PERSON }
        val objectsInRegion = selected.filter { it.category != GuideObjectCategory.PERSON }
        val anchor = chooseAnchor(objectsInRegion)
        val group = if (anchor == null) emptyList() else objectsInRegion
            .filter { distance(anchor, it) <= config.subjectClusterRadius }
            .filter { it === anchor || area(it.box) >= area(anchor.box) * config.subjectClusterMinimumRelativeArea }
            .take(config.maxObjects)
        return people + group
    }

    private fun chooseAnchor(objects: List<ObjectObservation>): ObjectObservation? =
        objects.maxByOrNull(::rankingScore)

    private fun semanticConfirmed(observation: ObjectObservation): Boolean =
        observation.category != GuideObjectCategory.UNKNOWN &&
            (observation.classificationConfidence ?: 0f) >= config.semanticMinConfidence &&
            window.count { sample -> sample.objects.any { sameSubject(it, observation) } } >=
            config.semanticConfirmationsRequired

    private fun stableKey(objectObservation: ObjectObservation): String = objectObservation.stableObjectKey

    private fun rankingScore(objectObservation: ObjectObservation): Float {
        val centrality = 1f - (hypot(
            objectObservation.box.centerX - region.centerX,
            objectObservation.box.centerY - region.centerY,
        ) / 0.7072f).coerceIn(0f, 1f)
        return area(objectObservation.box).coerceIn(0f, 1f) * 0.55f + centrality * 0.45f
    }

    private fun isPlausibleSubject(candidate: ObjectObservation): Boolean {
        val width = candidate.box.width.coerceAtLeast(0.0001f)
        val height = candidate.box.height.coerceAtLeast(0.0001f)
        return maxOf(width / height, height / width) <= config.maximumUnknownAspectRatio
    }

    private fun isClippedBackground(candidate: ObjectObservation): Boolean {
        val b = candidate.box
        val touched = listOf(b.left <= 0.02f, b.top <= 0.02f, b.right >= 0.98f, b.bottom >= 0.98f).count { it }
        return touched >= 2 && area(b) >= 0.08f
    }

    private fun isDuplicate(a: ObjectObservation, b: ObjectObservation): Boolean {
        if (intersectionOverUnion(a.box, b.box) >= config.duplicateIou) return true
        val intersection = intersectionArea(a.box, b.box)
        val smaller = minOf(area(a.box), area(b.box))
        return smaller > 0f && intersection / smaller >= config.nestedDuplicateContainment &&
            distance(a, b) <= config.nestedDuplicateCenterDistance
    }

    private fun sameSubject(a: ObjectObservation, b: ObjectObservation): Boolean {
        if (a.sceneTrackId != null && b.sceneTrackId != null) return a.sceneTrackId == b.sceneTrackId
        if (a.trackingId != null && b.trackingId != null) return a.trackingId == b.trackingId
        // Nearby centers are not identity. Two cups placed next to each other
        // routinely fall within the old distance threshold, which collapsed a
        // real scene into one slot. Without a tracker id, require spatial overlap
        // or a genuinely nested duplicate box.
        val iou = intersectionOverUnion(a.box, b.box)
        if (iou >= config.minimumIoU) return true
        val intersection = intersectionArea(a.box, b.box)
        val smaller = minOf(area(a.box), area(b.box))
        return smaller > 0f && intersection / smaller >= config.nestedDuplicateContainment &&
            distance(a, b) <= config.nestedDuplicateCenterDistance
    }

    private fun limitWithPersonPriority(items: List<ObjectObservation>): List<ObjectObservation> {
        val person = items.firstOrNull { it.category == GuideObjectCategory.PERSON }
        val objects = items.filter { it.category != GuideObjectCategory.PERSON }
            .distinctBy(::stableKey)
            .sortedByDescending(::rankingScore)
        return if (person == null) objects.take(config.maxObjects)
        else listOf(person) + objects.take((config.maxObjects - 1).coerceAtLeast(0))
    }

    private fun distance(a: ObjectObservation, b: ObjectObservation): Float = hypot(
        a.box.centerX - b.box.centerX, a.box.centerY - b.box.centerY,
    )

    private fun area(b: NormalizedBox): Float = b.width * b.height

    private fun intersectionOverUnion(a: NormalizedBox, b: NormalizedBox): Float {
        val intersection = intersectionArea(a, b)
        val union = area(a) + area(b) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun intersectionArea(a: NormalizedBox, b: NormalizedBox): Float =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
}
