package com.gamdo.app.guide

import com.gamdo.app.data.preset.StylePreset
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.detect.FrameFeatures
import kotlin.math.abs

/**
 * Explainable internal guide metric. The result is for KPI/tuning logs only;
 * it is intentionally not part of the camera UI state.
 */
class MatchScoreCalculator {
    fun calculate(
        features: FrameFeatures,
        target: StyleTarget,
        observedHorizonPosition: Float = 0.5f,
    ): Float {
        val composition = features.personCenter?.let { center ->
            val distance = abs(center.x - target.subjectAnchorX) +
                abs(center.y - target.subjectAnchorY)
            (1f - distance / 1.0f).coerceIn(0f, 1f)
        } ?: 0f
        val subjectScale = features.personBox?.height?.let {
            rangeScore(it, target.subjectScaleRange)
        } ?: 0f
        val headroom = rangeScore(features.headroom, target.headroomRange)
        val horizon = (1f - abs(observedHorizonPosition - target.horizonPosition) / 0.5f)
            .coerceIn(0f, 1f)
        val lighting = when {
            features.backlightFlag -> 0.25f
            features.lowLightFlag -> 0.45f
            else -> 1f
        }
        return (
            0.35f * composition +
                0.25f * subjectScale +
                0.15f * headroom +
                0.15f * horizon +
                0.10f * lighting
            ).coerceIn(0f, 1f)
    }

    private fun rangeScore(value: Float, range: ClosedFloatingPointRange<Float>): Float {
        if (value in range) return 1f
        val span = (range.endInclusive - range.start).coerceAtLeast(0.05f)
        val distance = if (value < range.start) range.start - value else value - range.endInclusive
        return (1f - distance / (span * 2f)).coerceIn(0f, 1f)
    }
}

fun StylePreset.toStyleTarget(): StyleTarget {
    val aspect = composition.targetAspectRatio.split(':').let { parts ->
        if (parts.size == 2) {
            parts[0].toFloatOrNull()?.div(parts[1].toFloatOrNull() ?: 1f)
        } else {
            null
        }
    } ?: (4f / 5f)
    val scale = composition.subjectScaleRange.toFloatRange(default = 0.35f..0.55f)
    val headroom = composition.headroomRange.toFloatRange(default = 0.05f..0.12f)
    val pitch = composition.cameraPitchRange.toFloatRange(default = -5f..5f)
    val anchorX = when (composition.subjectPosition) {
        "third_left" -> 1f / 3f
        "third_right" -> 2f / 3f
        else -> 0.5f
    }
    return StyleTarget(
        targetAspectRatio = aspect,
        subjectScaleRange = scale,
        subjectAnchorX = anchorX,
        subjectAnchorY = 0.5f,
        headroomRange = headroom,
        horizonPosition = composition.horizonPosition.toFloat(),
        cameraPitchRange = pitch,
    )
}

/** Reference remix adapter; composition stays independent from color rendering. */
fun ResolvedStyle.toStyleTarget(): StyleTarget {
    val aspect = composition.targetAspectRatio.split(':').let { parts ->
        if (parts.size == 2) parts[0].toFloatOrNull()?.div(parts[1].toFloatOrNull() ?: 1f) else null
    } ?: (4f / 5f)
    val scale = composition.subjectScaleRange.toFloatRange(default = 0.35f..0.55f)
    val headroom = composition.headroomRange.toFloatRange(default = 0.05f..0.12f)
    val pitch = composition.cameraPitchRange.toFloatRange(default = -5f..5f)
    val anchorX = when (composition.subjectPosition) {
        "third_left" -> 1f / 3f
        "third_right" -> 2f / 3f
        else -> 0.5f
    }
    return StyleTarget(
        targetAspectRatio = aspect,
        subjectScaleRange = scale,
        subjectAnchorX = anchorX,
        subjectAnchorY = 0.5f,
        headroomRange = headroom,
        horizonPosition = composition.horizonPosition.toFloat(),
        cameraPitchRange = pitch,
    )
}

private fun List<Double>.toFloatRange(default: ClosedFloatingPointRange<Float>): ClosedFloatingPointRange<Float> =
    if (size >= 2) this[0].toFloat()..this[1].toFloat() else default
