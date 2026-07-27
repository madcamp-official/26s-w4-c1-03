package com.gamdo.app.edit

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * §4-1 resolution and memory budget — **platform-free**.
 *
 * Two things §4-1 asks for that cannot be measured without a device, and are
 * therefore encoded as behaviour that *can* be tested on the JVM:
 *
 *  1. **Resolution fallback.** "목표 2초 이내(4000px 기준. 초과 시 처리 해상도
 *     2000px로 낮추고 저장 시 원본 해상도 재적용)". [planRenderBudget] is the whole
 *     rule: a preview that blew the budget drops one rung, and a save request
 *     ignores that downgrade.
 *  2. **Peak-memory control.** A 4000x3000 ARGB_8888 bitmap is 48 MB; the pipeline
 *     holds the source, a scaled copy and the output at once. On a 2019 mid-range
 *     heap that is an OOM, not a slowdown. [planBands] keeps the software pass from
 *     adding a third full-frame buffer, and the ladder walk below refuses a working
 *     resolution the heap cannot hold.
 *
 * No timing is done here — the caller measures and passes `lastRenderMs` in. That
 * keeps the decision deterministic and testable while the device is missing.
 */

/** ARGB_8888. */
const val BYTES_PER_ARGB_PIXEL = 4

/**
 * Emergency rung below [PREVIEW_MAX_SIDE]. Reached only under memory pressure —
 * the *time* fallback in §4-1 stops at 2000px.
 */
const val EMERGENCY_MAX_SIDE = 1200

/** Descending working resolutions the pipeline is allowed to fall back through. */
val RESOLUTION_LADDER = intArrayOf(FULL_MAX_SIDE, PREVIEW_MAX_SIDE, EMERGENCY_MAX_SIDE)

/** §4-1 target for a full-resolution pass. */
const val RENDER_BUDGET_MS = 2000L

/** Software passes work in bands of at most this many bytes. */
const val DEFAULT_MAX_BAND_BYTES = 4 * 1024 * 1024

/**
 * Share of the reported free heap the pipeline will commit to. Bitmaps are the
 * largest allocations in the app but not the only ones, and a failed
 * `Bitmap.createBitmap` is an `OutOfMemoryError`, not a null.
 */
const val HEAP_HEADROOM = 0.6f

/** Horizontal-band split for a software pixel pass. */
data class BandPlan(
    val bandHeight: Int,
    val bandCount: Int,
    val bufferBytes: Long,
) {
    /** Rows covered by band [index], clamped to [imageHeight]. */
    fun rowsIn(index: Int, imageHeight: Int): Int {
        val top = index * bandHeight
        return (imageHeight - top).coerceIn(0, bandHeight)
    }
}

/**
 * The resolution the renderer should work at, plus how to tile the software pass.
 *
 * [downgraded] is true when [workingMaxSide] is below what the caller asked for; it
 * exists so the save path can tell "the user is looking at a preview-resolution
 * render" from "the source was small anyway". It is a debug/logging signal — never
 * surface it in the UI (R7-1).
 */
data class RenderBudget(
    val workingMaxSide: Int,
    val workingWidth: Int,
    val workingHeight: Int,
    val bands: BandPlan,
    val downgraded: Boolean,
    val estimatedBytes: Long,
)

/**
 * Size after fitting a [width]x[height] image inside [maxSide] on its longer edge.
 * Never upscales, never returns a zero dimension.
 */
fun scaledSizeForMaxSide(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "size must be positive" }
    val longSide = max(width, height)
    if (maxSide <= 0 || longSide <= maxSide) return width to height
    val scale = maxSide.toFloat() / longSide
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

/**
 * Power-of-two `BitmapFactory.Options.inSampleSize` that decodes a [width]x[height]
 * image no smaller than [maxSide] on its longer edge.
 *
 * Deliberately conservative: subsampling that undershoots the target cannot be
 * undone, so the result is the largest power of two that still leaves the long edge
 * at or above [maxSide]. The remaining fraction is handled by a normal scale.
 */
fun inSampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    if (width <= 0 || height <= 0 || maxSide <= 0) return 1
    var sample = 1
    var longSide = max(width, height)
    while (longSide / 2 >= maxSide) {
        longSide /= 2
        sample *= 2
    }
    return sample
}

/**
 * Splits a [width]x[height] frame into horizontal bands of at most [maxBandBytes].
 * A single row always fits, even if that row alone exceeds the cap — the alternative
 * would be a zero-height band.
 */
fun planBands(width: Int, height: Int, maxBandBytes: Int = DEFAULT_MAX_BAND_BYTES): BandPlan {
    require(width > 0 && height > 0) { "size must be positive" }
    val rowBytes = width.toLong() * BYTES_PER_ARGB_PIXEL
    val rows = if (rowBytes <= 0) height else (maxBandBytes / rowBytes).toInt()
    val bandHeight = rows.coerceIn(1, height)
    val bandCount = (height + bandHeight - 1) / bandHeight
    return BandPlan(
        bandHeight = bandHeight,
        bandCount = bandCount,
        bufferBytes = bandHeight.toLong() * rowBytes,
    )
}

/**
 * Bytes the renderer will allocate *on top of* the caller's source bitmap: the
 * downscaled working copy (only when downscaling actually happens), the output
 * bitmap, and one band buffer.
 *
 * The output is estimated at full working size rather than at crop size. That
 * over-estimates by the crop ratio, which is the direction to err in — an
 * under-estimate here is an OOM on device.
 */
fun estimateRenderBytes(
    sourceWidth: Int,
    sourceHeight: Int,
    workingMaxSide: Int,
    maxBandBytes: Int = DEFAULT_MAX_BAND_BYTES,
): Long {
    val (w, h) = scaledSizeForMaxSide(sourceWidth, sourceHeight, workingMaxSide)
    val framePixels = w.toLong() * h
    val frameBytes = framePixels * BYTES_PER_ARGB_PIXEL
    val downscaled = w != sourceWidth || h != sourceHeight
    val workingCopy = if (downscaled) frameBytes else 0L
    return workingCopy + frameBytes + planBands(w, h, maxBandBytes).bufferBytes
}

/**
 * The §4-1 fallback rule in one function.
 *
 * @param forSave true for the final full-resolution pass. §4-1: "저장 시 원본
 *   해상도 재적용" — a save ignores a time-based downgrade, because the user waited
 *   for it on purpose. It still honours [availableBytes]: a slow save beats a crash.
 * @param lastRenderMs how long the previous pass took, or null if none has run.
 *   Above [budgetMs] the preview path drops to [PREVIEW_MAX_SIDE].
 * @param availableBytes free heap the caller is willing to see used, before
 *   [HEAP_HEADROOM] is applied.
 */
fun planRenderBudget(
    sourceWidth: Int,
    sourceHeight: Int,
    availableBytes: Long,
    forSave: Boolean = false,
    lastRenderMs: Long? = null,
    requestedMaxSide: Int = FULL_MAX_SIDE,
    budgetMs: Long = RENDER_BUDGET_MS,
    maxBandBytes: Int = DEFAULT_MAX_BAND_BYTES,
): RenderBudget {
    require(sourceWidth > 0 && sourceHeight > 0) { "source must be non-empty" }
    val sourceLong = max(sourceWidth, sourceHeight)

    // Never upscale: asking for 4000px from a 1500px photo is still a 1500px job.
    var target = minOf(requestedMaxSide, sourceLong)

    // Time fallback (preview only). §4-1 names the 4000 -> 2000 step; when the
    // target was already at or below 2000 the rule would otherwise be a no-op, so it
    // takes one more rung down instead. Bounded by the bottom of the ladder.
    if (!forSave && lastRenderMs != null && lastRenderMs > budgetMs) {
        target = if (target > PREVIEW_MAX_SIDE) {
            PREVIEW_MAX_SIDE
        } else {
            nextRungBelow(target) ?: target
        }
    }

    // Memory fallback: walk down the ladder until the estimate fits.
    val ceiling = (availableBytes * HEAP_HEADROOM).toLong().coerceAtLeast(0L)
    var chosen = target
    var estimate = estimateRenderBytes(sourceWidth, sourceHeight, chosen, maxBandBytes)
    while (estimate > ceiling) {
        val next = nextRungBelow(chosen) ?: break
        chosen = next
        estimate = estimateRenderBytes(sourceWidth, sourceHeight, chosen, maxBandBytes)
    }

    val (w, h) = scaledSizeForMaxSide(sourceWidth, sourceHeight, chosen)
    return RenderBudget(
        workingMaxSide = chosen,
        workingWidth = w,
        workingHeight = h,
        bands = planBands(w, h, maxBandBytes),
        downgraded = chosen < minOf(requestedMaxSide, sourceLong),
        estimatedBytes = estimate,
    )
}

/**
 * Next lower rung of [RESOLUTION_LADDER] strictly below [maxSide], or null at the
 * bottom. Also used as the retry step after an `OutOfMemoryError`.
 */
fun nextRungBelow(maxSide: Int): Int? = RESOLUTION_LADDER.firstOrNull { it < maxSide }
