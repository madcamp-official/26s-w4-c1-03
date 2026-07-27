package com.gamdo.app.edit

import com.gamdo.app.detect.ImageMetrics
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * §4-1 measurement stage — the **platform-free half** of the local editor.
 *
 * Everything here takes and returns plain arrays / primitives so it runs on the
 * JVM under `:app:testDebugUnitTest`. Nothing in this file may import
 * `android.*`: the module has no `androidTest` source set and no Robolectric, so
 * any Android-typed code is unverifiable (see `edit/LocalEditor.kt` header for the
 * full boundary rule). The Bitmap side lives in `edit/ImageMetricsExtractor.kt`
 * and does nothing but unpack pixels and call into here.
 */

const val HISTOGRAM_BINS = 256

/** At/below this 8-bit level a pixel counts as crushed shadow. */
const val SHADOW_CLIP_LEVEL = 8

/** At/above this 8-bit level a pixel counts as blown highlight. */
const val HIGHLIGHT_CLIP_LEVEL = 247

/**
 * Normalized subject box (0..1 of image width/height), origin top-left.
 *
 * Serializable because it crosses an agent boundary: guide-capture-agent writes it
 * into `captures.conditions_json` at the shutter and this vertical reads it back.
 * Both sides go through `CaptureConditions`, so neither types a JSON key.
 *
 * **The coordinates are the stored file's**, not the analysis frame's. The camera
 * forces analysis and preview to 4:3 and then centre-crops the capture to 4:5 or
 * 1:1 before saving, so a box in analysis space would be wrong by the crop.
 * guide-capture-agent owns that conversion.
 */
@Serializable
data class SubjectBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Per-channel means in 0..1, used by the gray-world white balance estimate. */
data class ChannelMeans(val r: Float, val g: Float, val b: Float)

/** Luma distribution summary. All levels are normalized to 0..1. */
data class LumaStats(
    val pixelCount: Int,
    val mean: Float,
    val shadowClipRatio: Float,
    val highlightClipRatio: Float,
    val blackPoint: Float,
    val whitePoint: Float,
)

/**
 * BT.601 luma (0..255) for each packed ARGB pixel. Integer weights sum to 256 so
 * the divide is a shift — this loop runs over every pixel of a 4000px image on
 * device, so it stays allocation-free when [out] is reused.
 */
fun lumaOf(pixels: IntArray, out: IntArray = IntArray(pixels.size)): IntArray {
    require(out.size >= pixels.size) { "out must hold at least ${pixels.size} entries" }
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        out[i] = (r * 77 + g * 151 + b * 28) shr 8
    }
    return out
}

/** 256-bin histogram of 8-bit luma values. */
fun lumaHistogram(luma: IntArray, count: Int = luma.size): IntArray {
    val hist = IntArray(HISTOGRAM_BINS)
    for (i in 0 until count) {
        hist[luma[i].coerceIn(0, 255)]++
    }
    return hist
}

/**
 * Summarizes a luma histogram. [clipPercentile] is the tail fraction ignored when
 * locating the black/white points, so a handful of stuck pixels cannot drive the
 * contrast stretch to an extreme.
 */
fun lumaStats(histogram: IntArray, clipPercentile: Float = 0.005f): LumaStats {
    require(histogram.size == HISTOGRAM_BINS) { "histogram must have $HISTOGRAM_BINS bins" }
    var count = 0L
    var weighted = 0L
    for (level in histogram.indices) {
        count += histogram[level]
        weighted += histogram[level].toLong() * level
    }
    if (count == 0L) return LumaStats(0, 0f, 0f, 0f, 0f, 1f)

    var shadow = 0L
    for (level in 0..SHADOW_CLIP_LEVEL) shadow += histogram[level]
    var highlight = 0L
    for (level in HIGHLIGHT_CLIP_LEVEL..255) highlight += histogram[level]

    val pct = clipPercentile.coerceIn(0f, 0.2f)
    val black = percentileLevel(histogram, count, pct)
    val white = percentileLevel(histogram, count, 1f - pct)

    return LumaStats(
        pixelCount = count.toInt(),
        mean = (weighted.toDouble() / count / 255.0).toFloat(),
        shadowClipRatio = (shadow.toDouble() / count).toFloat(),
        highlightClipRatio = (highlight.toDouble() / count).toFloat(),
        blackPoint = black,
        whitePoint = maxOf(white, black + 1f / 255f),
    )
}

/**
 * Luma level (0..1) below which [fraction] of the pixels fall.
 *
 * The target is floored at one pixel: on a small sample `total * 0.005` truncates
 * to zero, which would otherwise report a black point of 0 for every image and
 * silently disable the contrast stretch.
 */
fun percentileLevel(histogram: IntArray, total: Long, fraction: Float): Float {
    if (total <= 0L) return 0f
    val target = maxOf(1L, (total * fraction.coerceIn(0f, 1f)).toLong())
    var running = 0L
    for (level in histogram.indices) {
        running += histogram[level]
        if (running >= target) return level / 255f
    }
    return 1f
}

/** Mean of each channel in 0..1 — the input to the gray-world WB estimate. */
fun channelMeans(pixels: IntArray, count: Int = pixels.size): ChannelMeans {
    if (count <= 0) return ChannelMeans(0f, 0f, 0f)
    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    for (i in 0 until count) {
        val p = pixels[i]
        sumR += (p shr 16) and 0xFF
        sumG += (p shr 8) and 0xFF
        sumB += p and 0xFF
    }
    val n = count.toDouble() * 255.0
    return ChannelMeans(
        r = (sumR / n).toFloat(),
        g = (sumG / n).toFloat(),
        b = (sumB / n).toFloat(),
    )
}

/**
 * Variance of the 4-neighbour Laplacian response over the interior pixels — the
 * blur estimate `ProblemDiagnoser` consumes as `laplacianVariance`. Returns 0 for
 * images too small to have an interior.
 */
fun laplacianVariance(luma: IntArray, width: Int, height: Int): Float {
    if (width < 3 || height < 3) return 0f
    require(luma.size >= width * height) { "luma shorter than ${width}x$height" }
    var sum = 0.0
    var sumSq = 0.0
    var n = 0
    for (y in 1 until height - 1) {
        val row = y * width
        for (x in 1 until width - 1) {
            val i = row + x
            val response = (
                luma[i - width] + luma[i + width] + luma[i - 1] + luma[i + 1] - 4 * luma[i]
                ).toDouble()
            sum += response
            sumSq += response * response
            n++
        }
    }
    if (n == 0) return 0f
    val mean = sum / n
    return ((sumSq / n) - mean * mean).coerceAtLeast(0.0).toFloat()
}

/**
 * Mean luma inside vs. outside [subject]. Values above ~1.8 read as a backlit
 * frame; `ProblemDiagnoser` owns the threshold. Returns null when there is no
 * subject box or either region is empty, which makes the diagnoser skip BACKLIGHT.
 */
fun backlightRatio(luma: IntArray, width: Int, height: Int, subject: SubjectBox?): Float? {
    if (subject == null || width <= 0 || height <= 0) return null
    val x0 = (subject.left * width).toInt().coerceIn(0, width - 1)
    val x1 = (subject.right * width).toInt().coerceIn(x0 + 1, width)
    val y0 = (subject.top * height).toInt().coerceIn(0, height - 1)
    val y1 = (subject.bottom * height).toInt().coerceIn(y0 + 1, height)

    var inSum = 0L
    var inN = 0
    var allSum = 0L
    var allN = 0
    for (y in 0 until height) {
        val row = y * width
        val inRow = y in y0 until y1
        for (x in 0 until width) {
            val v = luma[row + x]
            allSum += v
            allN++
            if (inRow && x in x0 until x1) {
                inSum += v
                inN++
            }
        }
    }
    val outN = allN - inN
    if (inN == 0 || outN <= 0) return null
    val inMean = inSum.toDouble() / inN
    val outMean = (allSum - inSum).toDouble() / outN
    if (inMean <= 1.0) return null
    return (outMean / inMean).toFloat()
}

/**
 * Assembles the [ImageMetrics] contract 담당 B's `ProblemDiagnoser` expects
 * (P2_Plan §0.5: "the module never receives a Bitmap"). Pure — the Bitmap→IntArray
 * unpack happens one layer up in `ImageMetricsExtractor`.
 *
 * [tiltDeg] comes from the capture-time sensor snapshot, not from the pixels; a
 * gallery import has no sensor reading and passes 0.
 */
fun computeImageMetrics(
    pixels: IntArray,
    width: Int,
    height: Int,
    tiltDeg: Float = 0f,
    subject: SubjectBox? = null,
): ImageMetrics {
    val luma = lumaOf(pixels)
    val stats = lumaStats(lumaHistogram(luma))
    val margins = horizontalMargins(subject)
    return ImageMetrics(
        tiltDeg = tiltDeg,
        brightnessMean = stats.mean,
        shadowClipRatio = stats.shadowClipRatio,
        highlightClipRatio = stats.highlightClipRatio,
        laplacianVariance = laplacianVariance(luma, width, height),
        leftMargin = margins.first,
        rightMargin = margins.second,
        backlightRatio = backlightRatio(luma, width, height, subject),
    )
}

/**
 * Empty width to the left and right of the subject, as fractions of image width.
 * With no subject box both are 0, which keeps EXCESS_MARGIN from firing on a
 * measurement we never made.
 */
fun horizontalMargins(subject: SubjectBox?): Pair<Float, Float> {
    if (subject == null) return 0f to 0f
    val left = subject.left.coerceIn(0f, 1f)
    val right = (1f - subject.right).coerceIn(0f, 1f)
    return left to right
}

/** Standard deviation of a luma array — handy for tests and debug overlays. */
fun lumaStdDev(luma: IntArray): Float {
    if (luma.isEmpty()) return 0f
    var sum = 0.0
    var sumSq = 0.0
    for (v in luma) {
        sum += v
        sumSq += v.toDouble() * v
    }
    val n = luma.size
    val mean = sum / n
    return sqrt(((sumSq / n) - mean * mean).coerceAtLeast(0.0)).toFloat()
}
