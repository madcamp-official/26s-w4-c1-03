package com.gamdo.app.guide

import com.gamdo.app.detect.NormalizedBox
import kotlin.math.abs

/** Low-cost statistics an Android camera adapter can extract from a thumbnail. */
data class SceneStructureInput(
    val rowLuminance: List<Float>,
    val sideEdgeDensity: List<Float>,
    val subjectBox: NormalizedBox?,
    val subjectKind: SubjectKind = SubjectKind.UNKNOWN,
    val subjectConfidence: Float = 0f,
)

/** Deterministic structure metrics; no Bitmap or Android type crosses this seam. */
class SceneStructureAnalyzer(
    private val minHorizonContrast: Float = 0.08f,
) {
    fun analyze(input: SceneStructureInput): SceneObservation {
        val rows = input.rowLuminance.map { it.coerceIn(0f, 1f) }
        val horizon = strongestTransition(rows)
        val subject = input.subjectBox?.clamped()
        val leftMargin = subject?.left ?: 0.5f
        val rightMargin = subject?.let { 1f - it.right } ?: 0.5f
        val leftTexture = input.sideEdgeDensity.getOrNull(0)?.coerceIn(0f, 1f) ?: 1f
        val rightTexture = input.sideEdgeDensity.getOrNull(1)?.coerceIn(0f, 1f) ?: 1f
        val leftOpen = (leftMargin * (1f - leftTexture)).coerceIn(0f, 1f)
        val rightOpen = (rightMargin * (1f - rightTexture)).coerceIn(0f, 1f)
        val direction = when {
            rightOpen >= leftOpen + 0.12f -> LeadingDirection.RIGHT
            leftOpen >= rightOpen + 0.12f -> LeadingDirection.LEFT
            else -> LeadingDirection.NONE
        }
        return SceneObservation(
            subjectBox = subject,
            subjectKind = input.subjectKind,
            subjectConfidence = input.subjectConfidence,
            horizonPosition = horizon?.first,
            leadingDirection = direction,
            openSpaceLeft = leftOpen,
            openSpaceRight = rightOpen,
            dominantLineConfidence = horizon?.second ?: 0f,
        )
    }

    private fun strongestTransition(rows: List<Float>): Pair<Float, Float>? {
        if (rows.size < 3) return null
        var bestIndex = 1
        var bestDelta = 0f
        for (index in 1 until rows.lastIndex) {
            val delta = abs(rows[index] - rows[index - 1])
            if (delta > bestDelta) {
                bestDelta = delta
                bestIndex = index
            }
        }
        if (bestDelta < minHorizonContrast) return null
        return (bestIndex.toFloat() / rows.size).coerceIn(0.05f, 0.95f) to
            (bestDelta / 0.5f).coerceIn(0f, 1f)
    }
}

private fun NormalizedBox.clamped(): NormalizedBox {
    val leftValue = left.coerceIn(0f, 1f)
    val topValue = top.coerceIn(0f, 1f)
    return NormalizedBox(
        left = leftValue,
        top = topValue,
        right = right.coerceIn(leftValue, 1f),
        bottom = bottom.coerceIn(topValue, 1f),
    )
}
