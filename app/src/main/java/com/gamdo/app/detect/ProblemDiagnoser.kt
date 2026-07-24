package com.gamdo.app.detect

import kotlin.math.abs

/**
 * Platform-free measurements extracted by the Android image adapter.
 * All ratios are normalized to 0..1; variance is the grayscale Laplacian variance.
 */
data class ImageMetrics(
    val tiltDeg: Float,
    val brightnessMean: Float,
    val shadowClipRatio: Float = 0f,
    val highlightClipRatio: Float = 0f,
    val laplacianVariance: Float,
    val leftMargin: Float,
    val rightMargin: Float,
    val backlightRatio: Float? = null,
)

enum class ProblemCode {
    TILT,
    UNDEREXPOSED,
    OVEREXPOSED,
    BLUR_SUSPECT,
    EXCESS_MARGIN,
    BACKLIGHT,
}

enum class ProblemSeverity { LOW, MEDIUM, HIGH }

/** Internal diagnostic result. UI copy belongs to the Android presentation layer. */
data class Problem(
    val code: ProblemCode,
    val severity: ProblemSeverity,
    val value: Float,
)

data class DiagnoserConfig(
    val tiltDegrees: Float = 3f,
    val severeTiltDegrees: Float = 8f,
    val lowBrightness: Float = 0.18f,
    val severeLowBrightness: Float = 0.08f,
    val highBrightness: Float = 0.86f,
    val severeHighBrightness: Float = 0.96f,
    val shadowClipRatio: Float = 0.28f,
    val highlightClipRatio: Float = 0.28f,
    val blurVariance: Float = 80f,
    val severeBlurVariance: Float = 25f,
    val excessMargin: Float = 0.62f,
    val severeExcessMargin: Float = 0.8f,
    val backlightRatio: Float = 1.8f,
)

/**
 * Diagnoses likely rescue targets from image measurements. It never edits pixels,
 * emits user-facing copy, or depends on Android Bitmap/ML Kit types.
 */
class ProblemDiagnoser(
    private val config: DiagnoserConfig = DiagnoserConfig(),
) {
    fun diagnose(metrics: ImageMetrics, frameFeatures: FrameFeatures? = null): List<Problem> {
        val problems = buildList {
            val tilt = abs(metrics.tiltDeg)
            if (tilt >= config.tiltDegrees) {
                add(Problem(ProblemCode.TILT, severity(tilt, config.severeTiltDegrees), tilt))
            }

            val dark = maxOf(metrics.brightnessMean, 0f)
            if (dark <= config.lowBrightness || metrics.shadowClipRatio >= config.shadowClipRatio) {
                val value = minOf(dark, metrics.shadowClipRatio)
                add(
                    Problem(
                        ProblemCode.UNDEREXPOSED,
                        if (dark <= config.severeLowBrightness || metrics.shadowClipRatio >= 0.5f) {
                            ProblemSeverity.HIGH
                        } else {
                            ProblemSeverity.MEDIUM
                        },
                        value,
                    ),
                )
            }

            val bright = metrics.brightnessMean
            if (bright >= config.highBrightness || metrics.highlightClipRatio >= config.highlightClipRatio) {
                val value = maxOf(bright, metrics.highlightClipRatio)
                add(
                    Problem(
                        ProblemCode.OVEREXPOSED,
                        if (bright >= config.severeHighBrightness || metrics.highlightClipRatio >= 0.5f) {
                            ProblemSeverity.HIGH
                        } else {
                            ProblemSeverity.MEDIUM
                        },
                        value,
                    ),
                )
            }

            val variance = metrics.laplacianVariance.coerceAtLeast(0f)
            if (variance <= config.blurVariance) {
                add(
                    Problem(
                        ProblemCode.BLUR_SUSPECT,
                        severity(config.blurVariance - variance, config.blurVariance - config.severeBlurVariance),
                        variance,
                    ),
                )
            }

            val margins = (metrics.leftMargin + metrics.rightMargin).coerceAtLeast(0f)
            if (margins >= config.excessMargin) {
                add(
                    Problem(
                        ProblemCode.EXCESS_MARGIN,
                        severity(margins, config.severeExcessMargin),
                        margins,
                    ),
                )
            }

            val ratio = metrics.backlightRatio ?: 0f
            if (frameFeatures?.backlightFlag == true || ratio >= config.backlightRatio) {
                add(
                    Problem(
                        ProblemCode.BACKLIGHT,
                        if (ratio >= config.backlightRatio * 1.5f) ProblemSeverity.HIGH else ProblemSeverity.MEDIUM,
                        ratio,
                    ),
                )
            }
        }
        return problems
    }

    private fun severity(value: Float, highThreshold: Float): ProblemSeverity =
        if (value >= highThreshold) ProblemSeverity.HIGH else ProblemSeverity.MEDIUM
}
