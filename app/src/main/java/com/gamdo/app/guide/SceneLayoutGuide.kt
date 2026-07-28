package com.gamdo.app.guide

import com.gamdo.app.detect.NormalizedBox
import kotlin.math.abs

/** Normalized point used by the rendering-neutral layout guide. */
data class LayoutGuidePoint(val x: Float, val y: Float)

enum class LayoutGuideLevel {
    /** No reliable subject: keep the normal style frame and ask for a clearer scene. */
    STATIC,
    /** A subject exists, but the detector is not confident enough to project it. */
    DETECTING,
    /** The subject outline can be projected into the recommended composition. */
    CONFIDENT,
}

/**
 * Rendering-neutral output for the scene-specific guide.
 *
 * [outline] is a ghost of the subject in the recommended composition, not a
 * claim that an object detector produced a pixel-perfect segmentation mask. For
 * objects it is a bounded outline; for people it is a convex hull of pose
 * landmarks when those landmarks are available.
 */
data class SceneLayoutGuide(
    val level: LayoutGuideLevel,
    val outline: List<LayoutGuidePoint> = emptyList(),
    val bounds: NormalizedBox? = null,
    val subjectKind: SubjectKind = SubjectKind.UNKNOWN,
    val stabilized: Boolean = false,
    /** Fixed slots are rendered by the camera owner; they never follow detections. */
    val fixedLayout: FixedLayoutGuide? = null,
)

/**
 * Converts a selected composition proposal into a useful subject-shaped guide.
 * This is deliberately platform-free so the camera UI can render it without
 * owning any detection or composition policy.
 */
class SceneLayoutGuideEngine(
    private val confidentThreshold: Float = 0.65f,
    private val minimumOutlineStep: Float = 0.045f,
) {
    private var previous: SceneLayoutGuide? = null

    fun build(
        observation: SceneObservation,
        proposal: CompositionProposal,
    ): SceneLayoutGuide {
        val scene = observation.normalized()
        val box = scene.subjectBox
        if (box == null || scene.subjectConfidence < 0.35f) {
            // D2 forbids instruction copy, so this state says "nothing to draw"
            // and draws nothing. There used to be a `prompt` field here whose only
            // consumer rendered "피사체를 화면에 보여주세요" at TopCenter; the field
            // is gone with it, because an unread field is how the text comes back.
            return SceneLayoutGuide(level = LayoutGuideLevel.STATIC).also { previous = it }
        }

        val sourceOutline = convexHull(scene.subjectOutline)
            .takeIf { it.size >= 3 }
            ?: box.corners()
        val confident = scene.subjectConfidence >= confidentThreshold &&
            scene.hasReliableOutline &&
            !proposal.fallback
        val raw = if (confident) {
            val target = targetFrame(proposal.target)
            val projected = sourceOutline.map { point -> project(point, box, target) }
            SceneLayoutGuide(
                level = LayoutGuideLevel.CONFIDENT,
                outline = projected,
                bounds = target.toBox(),
                subjectKind = scene.subjectKind,
            )
        } else {
            SceneLayoutGuide(
                level = LayoutGuideLevel.DETECTING,
                outline = sourceOutline,
                bounds = box,
                subjectKind = scene.subjectKind,
            )
        }
        return stabilize(raw).also { previous = it }
    }

    fun reset() {
        previous = null
    }

    private fun stabilize(raw: SceneLayoutGuide): SceneLayoutGuide {
        val prior = previous
        if (prior == null || prior.level != raw.level || prior.outline.size != raw.outline.size) {
            return raw
        }
        val outline = raw.outline.mapIndexed { index, point ->
            val old = prior.outline[index]
            LayoutGuidePoint(
                x = approach(old.x, point.x, minimumOutlineStep),
                y = approach(old.y, point.y, minimumOutlineStep),
            )
        }
        val bounds = raw.bounds?.let { next ->
            prior.bounds?.let { old ->
                NormalizedBox(
                    left = approach(old.left, next.left, minimumOutlineStep),
                    top = approach(old.top, next.top, minimumOutlineStep),
                    right = approach(old.right, next.right, minimumOutlineStep),
                    bottom = approach(old.bottom, next.bottom, minimumOutlineStep),
                )
            } ?: next
        }
        val moved = outline.zip(prior.outline).any { (a, b) ->
            abs(a.x - b.x) >= minimumOutlineStep || abs(a.y - b.y) >= minimumOutlineStep
        }
        return raw.copy(outline = outline, bounds = bounds, stabilized = !moved)
    }

    private fun project(point: LayoutGuidePoint, source: NormalizedBox, target: RectN): LayoutGuidePoint {
        val x = if (source.width > 0f) (point.x - source.left) / source.width else 0.5f
        val y = if (source.height > 0f) (point.y - source.top) / source.height else 0.5f
        return LayoutGuidePoint(
            x = (target.left + x * target.width).coerceIn(0f, 1f),
            y = (target.top + y * target.height).coerceIn(0f, 1f),
        )
    }

    // 같은 단위 오류가 여기에도 복제돼 있었다 — 리뷰 M1. 이제 AlignmentEngine과
    // 같은 CompositionFrame을 쓰므로 한쪽만 고쳐질 수 없다.
    private fun targetFrame(target: StyleTarget): RectN {
        val height = CompositionFrame.height(target)
        val width = CompositionFrame.width(target, height)
        val centerX = target.subjectAnchorX.coerceIn(width / 2f, 1f - width / 2f)
        val headroom = target.headroomRange.start
            .plus(target.headroomRange.endInclusive)
            .div(2f)
            .coerceIn(0f, 0.8f)
        val centerY = (headroom + height / 2f).coerceIn(height / 2f, 1f - height / 2f)
        return RectN(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            right = centerX + width / 2f,
            bottom = centerY + height / 2f,
        ).clamped()
    }

    private fun RectN.toBox() = NormalizedBox(left, top, right, bottom)

    private fun NormalizedBox.corners() = listOf(
        LayoutGuidePoint(left, top),
        LayoutGuidePoint(right, top),
        LayoutGuidePoint(right, bottom),
        LayoutGuidePoint(left, bottom),
    )

    /** Monotonic-chain hull prevents the 33 pose landmarks from making a zigzag. */
    private fun convexHull(points: List<LayoutGuidePoint>): List<LayoutGuidePoint> {
        val sorted = points
            .map { LayoutGuidePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .distinctBy { it.x to it.y }
            .sortedWith(compareBy<LayoutGuidePoint> { it.x }.thenBy { it.y })
        if (sorted.size <= 2) return sorted

        fun cross(a: LayoutGuidePoint, b: LayoutGuidePoint, c: LayoutGuidePoint): Float =
            (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

        val lower = mutableListOf<LayoutGuidePoint>()
        sorted.forEach { point ->
            while (lower.size >= 2 && cross(lower[lower.lastIndex - 1], lower.last(), point) <= 0f) {
                lower.removeAt(lower.lastIndex)
            }
            lower += point
        }
        val upper = mutableListOf<LayoutGuidePoint>()
        sorted.asReversed().forEach { point ->
            while (upper.size >= 2 && cross(upper[upper.lastIndex - 1], upper.last(), point) <= 0f) {
                upper.removeAt(upper.lastIndex)
            }
            upper += point
        }
        return (lower.dropLast(1) + upper.dropLast(1))
    }

    private fun approach(from: Float, to: Float, step: Float): Float {
        val delta = to - from
        return if (abs(delta) <= step) to else from + if (delta > 0f) step else -step
    }
}
