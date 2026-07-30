package com.gamdo.app.detect

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

enum class DetectionSource { FULL_FRAME, SCOPE_CROP, MULTI_SUBJECT, MASK_SPLIT }

data class CompactConfidenceMask(
    val width: Int = 64,
    val height: Int = 64,
    val values: FloatArray,
    val frameBounds: NormalizedBox,
) {
    init { require(values.size == width * height) }
}

data class InstanceMaskObservation(
    val bounds: NormalizedBox,
    val mask: CompactConfidenceMask,
    val confidence: Float,
    val source: DetectionSource = DetectionSource.MULTI_SUBJECT,
)

data class SceneObjectCandidate(
    val box: NormalizedBox,
    val detectionConfidence: Float,
    val category: GuideObjectCategory,
    val classificationConfidence: Float? = null,
    val source: DetectionSource = DetectionSource.FULL_FRAME,
    val instanceMask: CompactConfidenceMask? = null,
    val nativeTrackingId: Int? = null,
)

data class TrackedSceneObject(
    val trackId: Long,
    val box: NormalizedBox,
    val category: GuideObjectCategory,
    val confidence: Float,
    val instanceMask: CompactConfidenceMask?,
    val hitCount: Int,
    val missedFrames: Int,
    val sourceSet: Set<DetectionSource>,
)

data class V4ObjectTrackConfig(
    val maxMissedFrames: Int = 3,
    val boxSmoothingAlpha: Float = .55f,
    val minimumMatchIou: Float = .15f,
    val maximumMatchCenterDistance: Float = .18f,
    val minimumAreaScale: Float = .40f,
    val maximumAreaScale: Float = 2.50f,
) {
    init { require(maxMissedFrames >= 1); require(boxSmoothingAlpha in 0f..1f) }
}

class ObjectTrackManager(private val config: V4ObjectTrackConfig = V4ObjectTrackConfig()) {
    private data class MutableTrack(
        val id: Long, var box: NormalizedBox, var category: GuideObjectCategory,
        var confidence: Float, var hits: Int, var misses: Int,
        var mask: CompactConfidenceMask?, val sources: MutableSet<DetectionSource>,
    )
    private val tracks = linkedMapOf<Long, MutableTrack>()
    private var nextId = 1L

    fun reset() { tracks.clear() }
    fun update(sequenceId: Long, candidates: List<SceneObjectCandidate>): List<TrackedSceneObject> {
        val remaining = candidates.take(12).toMutableList()
        val matched = mutableSetOf<Long>()
        tracks.values.forEach { track ->
            val index = remaining.indices.maxByOrNull { score(track, remaining[it]) }
            if (index != null && canMatch(track, remaining[index])) {
                val candidate = remaining.removeAt(index)
                track.box = smooth(track.box, candidate.box)
                track.category = if (candidate.detectionConfidence >= track.confidence) candidate.category else track.category
                track.confidence = track.confidence * .7f + candidate.detectionConfidence * .3f
                track.hits++; track.misses = 0; track.mask = candidate.instanceMask; track.sources += candidate.source
                matched += track.id
            } else track.misses++
        }
        remaining.forEach { c -> tracks[nextId] = MutableTrack(nextId++, c.box, c.category, c.detectionConfidence, 1, 0, c.instanceMask, mutableSetOf(c.source)) }
        tracks.entries.removeIf { it.value.misses > config.maxMissedFrames }
        return tracks.values.filter { it.id in matched || it.hits > 0 }.map {
            TrackedSceneObject(it.id, it.box, it.category, it.confidence, it.mask, it.hits, it.misses, it.sources.toSet())
        }
    }
    private fun canMatch(t: MutableTrack, c: SceneObjectCandidate): Boolean {
        val iou = iou(t.box, c.box); val d = hypot(t.box.centerX-c.box.centerX, t.box.centerY-c.box.centerY)
        val ratio = area(c.box) / area(t.box).coerceAtLeast(.0001f)
        return iou >= config.minimumMatchIou || (d <= config.maximumMatchCenterDistance && ratio in config.minimumAreaScale..config.maximumAreaScale)
    }
    private fun score(t: MutableTrack, c: SceneObjectCandidate): Float {
        val ar = abs(ln(area(c.box).coerceAtLeast(.0001f) / area(t.box).coerceAtLeast(.0001f)))
        return (1f-iou(t.box,c.box))*.4f + hypot(t.box.centerX-c.box.centerX,t.box.centerY-c.box.centerY)*.25f + ar*.15f
    }
    private fun smooth(a: NormalizedBox,b:NormalizedBox)=NormalizedBox(
        lerp(a.left,b.left),lerp(a.top,b.top),lerp(a.right,b.right),lerp(a.bottom,b.bottom))
    private fun lerp(a:Float,b:Float)=a*(1-config.boxSmoothingAlpha)+b*config.boxSmoothingAlpha
    private fun area(b:NormalizedBox)=b.width*b.height
    private fun iou(a:NormalizedBox,b:NormalizedBox):Float { val x=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f); val y=(minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f); val inter=x*y; return inter/(area(a)+area(b)-inter).coerceAtLeast(.0001f) }
}

class DetectionFusion {
    fun fuse(detections: List<SceneObjectCandidate>): List<SceneObjectCandidate> {
        val result = mutableListOf<SceneObjectCandidate>()
        detections.sortedByDescending { it.detectionConfidence }.forEach { candidate ->
            val duplicate = result.indexOfFirst { shouldMerge(it, candidate) }
            if (duplicate < 0) result += candidate else {
                val old = result[duplicate]
                result[duplicate] = old.copy(
                    box = weighted(old.box, old.detectionConfidence, candidate.box, candidate.detectionConfidence),
                    detectionConfidence = maxOf(old.detectionConfidence, candidate.detectionConfidence),
                    source = old.source,
                )
            }
        }
        return result
    }
    private fun shouldMerge(a:SceneObjectCandidate,b:SceneObjectCandidate):Boolean {
        if (a.nativeTrackingId != null && a.nativeTrackingId == b.nativeTrackingId) return true
        val i = iou(a.box,b.box); val overlap = overlapOnSmaller(a.box,b.box)
        return (a.category == b.category && i >= .45f) || (overlap >= .75f && centerDistance(a.box,b.box) <= .08f)
    }
    private fun weighted(a: NormalizedBox, aw: Float, b: NormalizedBox, bw: Float): NormalizedBox {
        val s = (aw + bw).coerceAtLeast(.001f)
        fun w(x: Float, y: Float): Float = (x * aw + y * bw) / s
        return NormalizedBox(w(a.left, b.left), w(a.top, b.top), w(a.right, b.right), w(a.bottom, b.bottom))
    }
    private fun area(b:NormalizedBox)=b.width*b.height
    private fun centerDistance(a:NormalizedBox,b:NormalizedBox)=hypot(a.centerX-b.centerX,a.centerY-b.centerY)
    private fun overlapOnSmaller(a:NormalizedBox,b:NormalizedBox):Float { val x=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f); val y=(minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f); return x*y/minOf(area(a),area(b)).coerceAtLeast(.0001f) }
    private fun iou(a:NormalizedBox,b:NormalizedBox):Float { val x=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f); val y=(minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f); val z=x*y; return z/(area(a)+area(b)-z).coerceAtLeast(.0001f) }
}
