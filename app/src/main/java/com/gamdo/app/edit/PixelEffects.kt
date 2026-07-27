package com.gamdo.app.edit

import kotlin.math.sqrt

/**
 * §4-1 style-stage effects that a colour matrix cannot express — **platform-free**.
 *
 * grain and vignette are the two style parameters that depend on *where* a pixel is
 * or on a random draw, so they fall outside the `FloatArray(20)` + `IntArray(256)`
 * contract the rest of the pipeline uses. They are still written as plain array
 * operations rather than as `Canvas` draw calls, for two reasons:
 *
 *  - they become JVM-testable, which nothing behind an `android.graphics` call is, and
 *  - the render-backend decision stays reversible. A `RadialGradient` shader and a
 *    tiled noise `BitmapShader` are a Canvas-specific way to say the same thing; if
 *    the backend ever changes, these functions do not.
 *
 * Both operate on one horizontal band at a time (see `planBands`) so a 4000px frame
 * never needs a full-size `IntArray` on top of the bitmaps themselves.
 *
 * ## D8-1
 *
 * Nothing here inspects or reshapes a face, a body or skin — these are frame-wide
 * light and texture effects, applied identically to every pixel at a given radius.
 * Do not add a subject-aware variant of either.
 */

/** Darkening at the extreme corner when `vignette` is 1.0. Deliberately gentle. */
const val VIGNETTE_MAX_DARKENING = 0.55f

/** Radius (0..1, normalized to the corner) at which the vignette starts. */
const val VIGNETTE_INNER_RADIUS = 0.45f

/** Peak grain excursion in 8-bit levels when `grain` is 1.0. */
const val GRAIN_MAX_AMPLITUDE = 26

/**
 * Brightness multiplier at normalized offset ([nx], [ny]) from the frame centre,
 * where ±1 is the frame edge on each axis.
 *
 * Returns 1.0 at the centre and inside [VIGNETTE_INNER_RADIUS], then falls off on a
 * smoothstep to `1 - amount * VIGNETTE_MAX_DARKENING` at the corner. Smoothstep
 * rather than a linear ramp because a linear vignette shows a visible ring edge.
 */
fun vignetteScale(nx: Float, ny: Float, amount: Float): Float {
    val a = amount.coerceIn(0f, 1f)
    if (a <= 0f) return 1f
    // Normalize so the corner - not the edge midpoint - is radius 1.
    val r = sqrt(nx * nx + ny * ny) / SQRT_2
    if (r <= VIGNETTE_INNER_RADIUS) return 1f
    val t = ((r - VIGNETTE_INNER_RADIUS) / (1f - VIGNETTE_INNER_RADIUS)).coerceIn(0f, 1f)
    val smooth = t * t * (3f - 2f * t)
    return 1f - a * VIGNETTE_MAX_DARKENING * smooth
}

private val SQRT_2 = sqrt(2f)

/**
 * Multiplies one band of an ARGB buffer by the radial vignette mask.
 *
 * [pixels] holds [rows] rows of [imageWidth] pixels starting at row [bandTop] of a
 * frame that is [imageHeight] tall — the band needs the full frame geometry, or each
 * band would get its own little vignette.
 */
fun applyVignetteBand(
    pixels: IntArray,
    imageWidth: Int,
    imageHeight: Int,
    bandTop: Int,
    rows: Int,
    amount: Float,
) {
    if (amount <= 0f || imageWidth <= 0 || imageHeight <= 0 || rows <= 0) return
    require(pixels.size >= imageWidth * rows) { "band buffer shorter than ${imageWidth}x$rows" }
    val halfW = imageWidth / 2f
    val halfH = imageHeight / 2f
    for (row in 0 until rows) {
        val y = bandTop + row
        val ny = if (halfH <= 0f) 0f else (y + 0.5f - halfH) / halfH
        val base = row * imageWidth
        for (x in 0 until imageWidth) {
            val nx = if (halfW <= 0f) 0f else (x + 0.5f - halfW) / halfW
            val scale = vignetteScale(nx, ny, amount)
            if (scale >= 1f) continue
            pixels[base + x] = scalePixel(pixels[base + x], scale)
        }
    }
}

private fun scalePixel(argb: Int, scale: Float): Int {
    val a = argb ushr 24 and 0xFF
    val r = ((argb shr 16 and 0xFF) * scale).toInt().coerceIn(0, 255)
    val g = ((argb shr 8 and 0xFF) * scale).toInt().coerceIn(0, 255)
    val b = ((argb and 0xFF) * scale).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Deterministic noise in -128..127 for pixel ([x], [y]) under [seed].
 *
 * A hash rather than a `Random`: bands are processed independently and a save
 * re-renders the frame from scratch, so the noise has to be reproducible from
 * coordinates alone. Same reason the grain of a preview and of the saved file agree
 * wherever their resolutions do.
 */
fun grainNoise(x: Int, y: Int, seed: Int): Int {
    var h = x * -0x3361d2af + y * 0x27d4eb2f + seed * 0x165667b1
    h = h xor (h ushr 15)
    h *= 0x2545f491
    h = h xor (h ushr 13)
    return (h and 0xFF) - 128
}

/**
 * Adds achromatic film grain to one band. The same delta goes to R, G and B —
 * per-channel noise reads as colour speckle, not as film.
 *
 * Grain is applied at working resolution, so a preview-resolution render has
 * slightly coarser grain than the saved full-resolution file. That is how optical
 * grain behaves under enlargement and it is not worth a resolution-invariant hash.
 */
fun applyGrainBand(
    pixels: IntArray,
    imageWidth: Int,
    bandTop: Int,
    rows: Int,
    amount: Float,
    seed: Int = DEFAULT_GRAIN_SEED,
) {
    val a = amount.coerceIn(0f, 1f)
    if (a <= 0f || imageWidth <= 0 || rows <= 0) return
    require(pixels.size >= imageWidth * rows) { "band buffer shorter than ${imageWidth}x$rows" }
    val amplitude = a * GRAIN_MAX_AMPLITUDE
    for (row in 0 until rows) {
        val y = bandTop + row
        val base = row * imageWidth
        for (x in 0 until imageWidth) {
            val delta = (grainNoise(x, y, seed) * amplitude / 128f).toInt()
            if (delta == 0) continue
            pixels[base + x] = offsetPixel(pixels[base + x], delta)
        }
    }
}

/** Fixed so a re-render of the same photo produces the same grain. */
const val DEFAULT_GRAIN_SEED = 0x5A6D

private fun offsetPixel(argb: Int, delta: Int): Int {
    val a = argb ushr 24 and 0xFF
    val r = ((argb shr 16 and 0xFF) + delta).coerceIn(0, 255)
    val g = ((argb shr 8 and 0xFF) + delta).coerceIn(0, 255)
    val b = ((argb and 0xFF) + delta).coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Everything the software pass has to do for one band, in pipeline order: tone curve
 * (the non-affine part of the optical stage), then grain, then vignette.
 *
 * Vignette last so it darkens the grain too — grain that stays bright in a darkened
 * corner is the classic giveaway of a stacked filter.
 *
 * Returns false when the plan needs no software pass at all, which lets the renderer
 * skip reading the pixels back out of the bitmap entirely.
 */
fun needsSoftwarePass(plan: EditPlan): Boolean =
    !isIdentityLut(plan.toneLut) || plan.style.grain > 0f || plan.style.vignette > 0f

/** Applies the software pass to one band. See [needsSoftwarePass]. */
fun applySoftwareBand(
    pixels: IntArray,
    plan: EditPlan,
    imageWidth: Int,
    imageHeight: Int,
    bandTop: Int,
    rows: Int,
) {
    if (rows <= 0 || imageWidth <= 0) return
    val count = imageWidth * rows
    if (!isIdentityLut(plan.toneLut)) {
        applyColorPipeline(pixels, identityColorMatrix(), plan.toneLut, count)
    }
    applyGrainBand(pixels, imageWidth, bandTop, rows, plan.style.grain)
    applyVignetteBand(pixels, imageWidth, imageHeight, bandTop, rows, plan.style.vignette)
}
