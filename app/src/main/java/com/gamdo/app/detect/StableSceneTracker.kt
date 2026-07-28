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
     * Candidate selection is deliberately centred on the viewfinder. The user
     * normally composes the intended subject around the focus point, while
     * edge detections are usually background clutter that should not create a
     * layout on their own.
     */
    val focusRegionWidth: Float = 0.70f,
    val focusRegionHeight: Float = 0.68f,
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
            .filter { it.history.count { present -> present } >= config.confirmationsRequired }
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

    /** Remove full-frame/background proposals and duplicate boxes before tracking. */
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
            .filter(::isInsideFocusRegion)
            .sortedByDescending(::rankingScore)
            .forEach { candidate ->
                val duplicate = selected.any { existing ->
                    intersectionOverUnion(existing.box, candidate.box) >= config.duplicateIou
                }
                if (!duplicate) selected += candidate
            }
        return selected
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
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = ((right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f))
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
