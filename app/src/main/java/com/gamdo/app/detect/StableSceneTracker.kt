package com.gamdo.app.detect

import kotlin.math.hypot

/** All object-guide quality thresholds live in guide_config.json. */
data class ObjectTrackerConfig(
    val windowSize: Int = 5,
    val confirmationsRequired: Int = 3,
    val maxObjects: Int = 4,
    val minimumIoU: Float = 0.30f,
    val maxCenterDistance: Float = 0.16f,
    val minimumAreaRatio: Float = 0.01f,
    val maximumAreaRatio: Float = 0.85f,
    val duplicateIou: Float = 0.75f,
    val semanticMinConfidence: Float = 0.80f,
    val semanticConfirmationsRequired: Int = 3,
    /**
     * Non-person candidate selection is deliberately centred on the
     * viewfinder. The user normally composes the intended subject around the
     * focus point, while edge detections are usually background clutter that
     * should not create a layout on their own. Faces keep the D12 person-path:
     * portrait framing naturally places a face above the viewfinder centre.
     */
    val focusRegionWidth: Float = 0.70f,
    val focusRegionHeight: Float = 0.68f,
    /** Maximum distance from the central main subject for its companion objects. */
    val subjectClusterRadius: Float = 0.38f,
    /** Reject tiny unknown clutter relative to the central main subject. */
    val subjectClusterMinimumRelativeArea: Float = 0.16f,
    /** Cables and long edges are not promoted as unknown composition subjects. */
    val maximumUnknownAspectRatio: Float = 3.50f,
    /** Nested boxes with nearly the same centre are detector duplicates, not two subjects. */
    val nestedDuplicateCenterDistance: Float = 0.08f,
    val nestedDuplicateContainment: Float = 0.78f,
) {
    init {
        require(windowSize >= 1)
        require(confirmationsRequired in 1..windowSize)
        require(maxObjects >= 1)
        require(minimumAreaRatio in 0f..1f)
        require(maximumAreaRatio in minimumAreaRatio..1f)
        require(semanticMinConfidence in 0f..1f)
        require(semanticConfirmationsRequired in 1..windowSize)
        require(focusRegionWidth in 0.20f..1f)
        require(focusRegionHeight in 0.20f..1f)
        require(subjectClusterRadius in 0.05f..0.80f)
        require(subjectClusterMinimumRelativeArea in 0f..1f)
        require(maximumUnknownAspectRatio >= 1f)
        require(nestedDuplicateCenterDistance in 0f..1f)
        require(nestedDuplicateContainment in 0f..1f)
    }
}

/**
 * Multi-object confirmation for the camera guide. Only fresh detector batches
 * advance confirmation history; cached detector output cannot manufacture a
 * 3-of-5 confirmation.
 */
class StableSceneTracker(
    private val config: ObjectTrackerConfig = ObjectTrackerConfig(),
) {
    private data class Track(
        val key: String,
        var latest: ObjectObservation,
        val history: ArrayDeque<Boolean> = ArrayDeque(),
        val semanticHistory: ArrayDeque<GuideObjectCategory?> = ArrayDeque(),
        var lastSequence: Long = 0L,
    )

    private val tracks = linkedMapOf<String, Track>()
    private var lastStable: List<ObjectObservation> = emptyList()

    fun accept(batch: ObjectDetectionBatch): List<ObjectObservation> {
        if (!batch.isFresh) return lastStable

        val candidates = sanitize(batch.objects)
        val matchedKeys = mutableSetOf<String>()
        candidates.forEachIndexed { index, candidate ->
            val track = tracks.values
                .filter { it.key !in matchedKeys }
                .minByOrNull { distance(it.latest, candidate) }
                ?.takeIf { sameSubject(it.latest, candidate) }
            val key = track?.key ?: candidate.trackingId?.let { "track:$it" } ?: "candidate:$index:${batch.sequenceId}"
            val target = track ?: Track(key = key, latest = candidate)
            target.latest = candidate
            target.lastSequence = batch.sequenceId
            target.history.addLast(true)
            target.semanticHistory.addLast(candidate.confirmedSemanticCandidate())
            while (target.history.size > config.windowSize) target.history.removeFirst()
            while (target.semanticHistory.size > config.windowSize) target.semanticHistory.removeFirst()
            tracks[key] = target
            matchedKeys += key
        }

        tracks.values
            .filter { it.key !in matchedKeys && it.lastSequence != batch.sequenceId }
            .forEach { track ->
                track.history.addLast(false)
                track.semanticHistory.addLast(null)
                while (track.history.size > config.windowSize) track.history.removeFirst()
                while (track.semanticHistory.size > config.windowSize) track.semanticHistory.removeFirst()
            }

        val stable = tracks.values
            .filter {
                it.lastSequence == batch.sequenceId &&
                    it.history.count { present -> present } >= config.confirmationsRequired
            }
            .map { track ->
                track.latest.copy(
                    semanticConfirmed = track.semanticHistory
                        .filterNotNull()
                        .groupingBy { it }
                        .eachCount()
                        .values
                        .any { count -> count >= config.semanticConfirmationsRequired },
                )
            }
            .let(::limitWithPersonPriority)
        lastStable = stable
        return stable
    }

    fun reset() {
        tracks.clear()
        lastStable = emptyList()
    }

    private fun rankingScore(objectObservation: ObjectObservation): Float {
        val area = (objectObservation.box.width * objectObservation.box.height).coerceIn(0f, 1f)
        val centerDistance = hypot(
            objectObservation.box.centerX - 0.5f,
            objectObservation.box.centerY - 0.5f,
        ).coerceIn(0f, 0.7072f) / 0.7072f
        return area * 0.7f + (1f - centerDistance) * 0.3f
    }

    /**
     * Keeps one central, visually coherent group of major subjects. The object
     * detector can still report cables, a laptop edge, or nested duplicate
     * boxes; those are useful detector diagnostics but must not become extra
     * composition slots.
     */
    private fun sanitize(objects: List<ObjectObservation>): List<ObjectObservation> {
        val selected = mutableListOf<ObjectObservation>()
        objects
            .filter { SceneRecognitionPolicy.isValidBox(it.box) }
            .filter { candidate ->
                val area = candidate.box.width * candidate.box.height
                area in config.minimumAreaRatio..config.maximumAreaRatio
            }
            .filter { candidate ->
                val area = candidate.box.width * candidate.box.height
                val fullWidthBackground = candidate.box.width >= 0.92f && area >= 0.45f
                val fullHeightBackground = candidate.box.height >= 0.92f && area >= 0.45f
                candidate.mask != null || (!fullWidthBackground && !fullHeightBackground)
            }
            .filter { candidate ->
                candidate.category == GuideObjectCategory.PERSON || isInsideFocusRegion(candidate)
            }
            .sortedByDescending(::rankingScore)
            .forEach { candidate ->
                val duplicate = selected.any { existing ->
                    isDuplicate(existing, candidate)
                }
                if (!duplicate) selected += candidate
            }
        val people = selected.filter { it.category == GuideObjectCategory.PERSON }
        val objectCluster = selectMainObjectCluster(selected.filter { it.category != GuideObjectCategory.PERSON })
        return people + objectCluster
    }

    /**
     * The central/high-importance object is the anchor. Nearby objects of a
     * meaningful size form its scene cluster; isolated or thin unknown boxes
     * are discarded. This keeps labels optional while preserving a genuine
     * 1~4 object scene such as a drink, pouch, and snack on one table.
     */
    private fun selectMainObjectCluster(objects: List<ObjectObservation>): List<ObjectObservation> {
        if (objects.size <= 1) return objects
        val plausible = objects.filter(::isPlausibleSubject).ifEmpty { objects }
        val anchor = plausible.maxByOrNull(::rankingScore) ?: return emptyList()
        val anchorArea = area(anchor.box).coerceAtLeast(config.minimumAreaRatio)
        return plausible.filter { candidate ->
            candidate === anchor || (
                distance(anchor, candidate) <= config.subjectClusterRadius &&
                    (candidate.category != GuideObjectCategory.UNKNOWN ||
                        area(candidate.box) >= anchorArea * config.subjectClusterMinimumRelativeArea)
                )
            }
            .sortedByDescending(::rankingScore)
    }

    private fun isPlausibleSubject(candidate: ObjectObservation): Boolean {
        if (candidate.category != GuideObjectCategory.UNKNOWN) return true
        val width = candidate.box.width.coerceAtLeast(0.0001f)
        val height = candidate.box.height.coerceAtLeast(0.0001f)
        val aspect = maxOf(width / height, height / width)
        return aspect <= config.maximumUnknownAspectRatio
    }

    private fun isDuplicate(existing: ObjectObservation, candidate: ObjectObservation): Boolean {
        if (intersectionOverUnion(existing.box, candidate.box) >= config.duplicateIou) return true
        val intersection = intersectionArea(existing.box, candidate.box)
        val smallerArea = minOf(area(existing.box), area(candidate.box))
        val contained = smallerArea > 0f && intersection / smallerArea >= config.nestedDuplicateContainment
        return contained && distance(existing, candidate) <= config.nestedDuplicateCenterDistance
    }

    /**
     * A box belongs to the composition candidate set only when its centre is
     * inside the central focus region. Using the centre instead of any overlap
     * avoids promoting a large edge/background box that merely touches the
     * viewfinder's middle.
     */
    private fun isInsideFocusRegion(candidate: ObjectObservation): Boolean {
        val halfWidth = config.focusRegionWidth / 2f
        val halfHeight = config.focusRegionHeight / 2f
        return candidate.box.centerX in (0.5f - halfWidth)..(0.5f + halfWidth) &&
            candidate.box.centerY in (0.5f - halfHeight)..(0.5f + halfHeight)
    }

    private fun sameSubject(a: ObjectObservation, b: ObjectObservation): Boolean =
        if (a.trackingId != null && b.trackingId != null) {
            a.trackingId == b.trackingId
        } else {
            intersectionOverUnion(a.box, b.box) >= config.minimumIoU ||
                distance(a, b) <= config.maxCenterDistance
        }

    /** Keep one confirmed person and then the most visually important objects. */
    private fun limitWithPersonPriority(stable: List<ObjectObservation>): List<ObjectObservation> {
        val ranked = stable.sortedByDescending(::rankingScore)
        val person = ranked.firstOrNull { it.category == GuideObjectCategory.PERSON }
        val objects = ranked.filter { it.category != GuideObjectCategory.PERSON }
        return if (person == null) {
            objects.take(config.maxObjects)
        } else {
            listOf(person) + objects.take((config.maxObjects - 1).coerceAtLeast(0))
        }
    }

    private fun ObjectObservation.confirmedSemanticCandidate(): GuideObjectCategory? =
        category.takeIf {
            it != GuideObjectCategory.UNKNOWN &&
                (classificationConfidence ?: 0f) >= config.semanticMinConfidence
        }

    private fun distance(a: ObjectObservation, b: ObjectObservation): Float = hypot(
        a.box.centerX - b.box.centerX,
        a.box.centerY - b.box.centerY,
    )

    private fun intersectionOverUnion(a: NormalizedBox, b: NormalizedBox): Float {
        val intersection = intersectionArea(a, b)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun intersectionArea(a: NormalizedBox, b: NormalizedBox): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        return (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
    }

    private fun area(box: NormalizedBox): Float = box.width * box.height
}
