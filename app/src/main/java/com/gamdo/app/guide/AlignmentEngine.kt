package com.gamdo.app.guide

import com.gamdo.app.detect.FrameFeatures
import com.gamdo.app.detect.NormalizedBox
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot

data class RectN(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun clamped(): RectN {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(l, 1f)
        val b = bottom.coerceIn(t, 1f)
        return RectN(l, t, r, b)
    }
}

data class SilhouetteSpec(
    val bounds: RectN,
    val opacity: Float = 0.22f,
)

/** Composition-only target. Color parameters are intentionally not part of this contract. */
data class StyleTarget(
    val targetAspectRatio: Float = 4f / 5f,
    val subjectScaleRange: ClosedFloatingPointRange<Float> = 0.35f..0.55f,
    val subjectAnchorX: Float = 0.5f,
    val subjectAnchorY: Float = 0.5f,
    val headroomRange: ClosedFloatingPointRange<Float> = 0.05f..0.12f,
    val horizonPosition: Float = 0.5f,
    val cameraPitchRange: ClosedFloatingPointRange<Float> = -5f..5f,
    val backgroundRatioRange: ClosedFloatingPointRange<Float>? = 0.25f..0.85f,
    /** Optional fixed-layout ID; null keeps the legacy single-subject contract. */
    val layoutTemplateId: String? = null,
)

data class GuideConfig(
    val smoothingWindow: Int = 5,
    val alignedIouThreshold: Float = 0.7f,
    val recomputeMovementThreshold: Float = 0.08f,
    val minPoseConfidence: Float = 0.3f,
    val maxUnstableFrames: Int = 5,
) {
    init {
        require(smoothingWindow > 0)
        require(alignedIouThreshold in 0f..1f)
        require(recomputeMovementThreshold >= 0f)
        require(maxUnstableFrames > 0)
    }
}

data class OverlayState(
    val targetFrame: RectN,
    val silhouette: SilhouetteSpec?,
    val horizonY: Float,
    val visible: Boolean,
    val aligned: Boolean,
)

data class GuideMetrics(
    val matchScore: Float,
    val targetMovement: Float,
    val unstableFrames: Int,
)

/**
 * Converts frame features into a stable visual guide. This class is deliberately
 * platform-free: the camera layer owns rendering and user-facing copy.
 */
class AlignmentEngine {
    private val targetHistory = ArrayDeque<RectN>()
    private var lastStableTarget: RectN? = null
    private var lastMetrics = GuideMetrics(0f, 0f, 0)

    fun align(
        features: FrameFeatures,
        target: StyleTarget,
        config: GuideConfig = GuideConfig(),
        observedSubjectBox: NormalizedBox? = null,
    ): OverlayState {
        val desired = targetFrame(target)
        // A detected object has its own confidence; it must not be rejected just
        // because there is no human pose in the frame.
        val confidenceUsable = observedSubjectBox != null ||
            features.poseConfidence >= config.minPoseConfidence
        val previous = lastStableTarget

        if (!confidenceUsable) {
            val fallback = previous ?: desired
            lastMetrics = lastMetrics.copy(targetMovement = 0f)
            return state(fallback, features, target, config, visible = previous != null, observedSubjectBox = observedSubjectBox)
        }

        val movement = previous?.let { distance(it, desired) } ?: 0f
        if (previous != null && movement > config.recomputeMovementThreshold) {
            lastMetrics = lastMetrics.copy(
                targetMovement = movement,
                unstableFrames = lastMetrics.unstableFrames + 1,
            )
            if (lastMetrics.unstableFrames >= config.maxUnstableFrames) {
                return state(previous, features, target, config, visible = false, observedSubjectBox = observedSubjectBox)
            }
        } else {
            lastMetrics = lastMetrics.copy(targetMovement = movement, unstableFrames = 0)
        }

        targetHistory.addLast(desired)
        while (targetHistory.size > config.smoothingWindow) targetHistory.removeFirst()
        val smoothed = average(targetHistory).clamped()
        lastStableTarget = smoothed
        return state(smoothed, features, target, config, visible = true, observedSubjectBox = observedSubjectBox)
    }

    fun metrics(): GuideMetrics = lastMetrics

    fun reset() {
        targetHistory.clear()
        lastStableTarget = null
        lastMetrics = GuideMetrics(0f, 0f, 0)
    }

    private fun state(
        frame: RectN,
        features: FrameFeatures,
        target: StyleTarget,
        config: GuideConfig,
        visible: Boolean,
        observedSubjectBox: NormalizedBox?,
    ): OverlayState {
        val subjectBox = observedSubjectBox ?: features.personBox
        val iou = subjectBox?.let { intersectionOverUnion(it, frame) } ?: 0f
        val aligned = visible && iou >= config.alignedIouThreshold
        val score = iou.coerceIn(0f, 1f)
        lastMetrics = lastMetrics.copy(matchScore = score)
        return OverlayState(
            targetFrame = frame,
            silhouette = if (visible && subjectBox != null) {
                SilhouetteSpec(frame)
            } else {
                null
            },
            horizonY = target.horizonPosition.coerceIn(0f, 1f),
            visible = visible,
            aligned = aligned,
        )
    }

    // B 모듈 리드 승인 수정(오너 결정 O-7, 2026-07-28): 브래킷 폭 단위 오류.
    // `targetAspectRatio`는 픽셀 비인데 정규화 폭에 그대로 곱하고 있었다. 프레임
    // 종횡비로 나누지 않아 4:3 분석 스트림에서 모든 브래킷이 선언된 폭의 정확히
    // 75%로 그려졌다 — 4:5 목표가 대략 3:5로 나왔다. 계산은 SceneLayoutGuide와
    // 공유하도록 CompositionFrame으로 뽑았다(두 곳에 같은 버그가 복제돼 있었다).
    private fun targetFrame(target: StyleTarget): RectN {
        val height = CompositionFrame.height(target)
        val width = CompositionFrame.width(target, height)
        val centerX = target.subjectAnchorX.coerceIn(width / 2f, 1f - width / 2f)
        val headroom = target.headroomRange.midpoint().coerceIn(0f, 0.8f)
        val centerY = (headroom + height / 2f).coerceIn(height / 2f, 1f - height / 2f)
        return RectN(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            right = centerX + width / 2f,
            bottom = centerY + height / 2f,
        ).clamped()
    }

    private fun average(rects: Collection<RectN>): RectN {
        if (rects.isEmpty()) return lastStableTarget ?: RectN(0f, 0f, 1f, 1f)
        val count = rects.size.toFloat()
        return RectN(
            rects.sumOf { it.left.toDouble() }.toFloat() / count,
            rects.sumOf { it.top.toDouble() }.toFloat() / count,
            rects.sumOf { it.right.toDouble() }.toFloat() / count,
            rects.sumOf { it.bottom.toDouble() }.toFloat() / count,
        )
    }

    private fun distance(a: RectN, b: RectN): Float = hypot(
        abs(a.left - b.left) + abs(a.right - b.right),
        abs(a.top - b.top) + abs(a.bottom - b.bottom),
    )

    private fun intersectionOverUnion(a: NormalizedBox, b: RectN): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = ((right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f))
        val union = (a.width * a.height) + (b.width * b.height) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun ClosedFloatingPointRange<Float>.midpoint(): Float = (start + endInclusive) / 2f
}
