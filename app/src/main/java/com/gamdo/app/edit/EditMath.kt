package com.gamdo.app.edit

import com.gamdo.app.data.preset.ColorParams
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * §4-1 optical + style stages — **platform-free**.
 *
 * The whole colour pipeline is reduced to two backend-neutral products:
 *
 *  1. a 4x5 colour matrix laid out exactly like `android.graphics.ColorMatrix`
 *     (row-major, `R' = m[0]R + m[1]G + m[2]B + m[3]A + m[4]`, offsets in 0..255), and
 *  2. a 256-entry tone LUT for the parts an affine matrix cannot express
 *     (shadow lift / highlight roll-off).
 *
 * Both are plain arrays, which is why the render-backend choice is cheap to
 * reverse: `ColorMatrixColorFilter`, `RenderEffect.createColorFilterEffect`, an
 * AGSL uniform, and an OpenCV `transform` all consume the same 20 floats. See the
 * decision note in `edit/LocalEditor.kt`.
 *
 * Nothing here imports `android.*`, so `applyColorMatrix` below is both the JVM
 * test oracle and the on-device software fallback — one implementation, verified
 * once.
 */

const val COLOR_MATRIX_SIZE = 20

/** Preset colour temperatures are Kelvin; 5500K is treated as neutral daylight. */
const val NEUTRAL_KELVIN = 5500f

/** Mid-grey the auto-exposure aims for (slightly below 0.5 keeps skin from blowing). */
const val AUTO_EXPOSURE_TARGET = 0.46f

/** §4-1: auto exposure is capped at ±1 EV. */
const val MAX_AUTO_EXPOSURE_EV = 1f

/** Per-channel gains and an offset, all in linear 0..1 display space. */
data class ChannelGains(val r: Float, val g: Float, val b: Float)

/** Black/white point pair produced by the contrast stretch, normalized 0..1. */
data class LevelsStretch(val black: Float, val white: Float) {
    val span: Float get() = (white - black).coerceAtLeast(1f / 255f)
}

fun identityColorMatrix(): FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

/**
 * Matrix equivalent to applying [before] and then [after] — the same composition
 * order as `ColorMatrix.postConcat(after)` on `before`.
 */
fun concatColorMatrix(after: FloatArray, before: FloatArray): FloatArray {
    require(after.size == COLOR_MATRIX_SIZE && before.size == COLOR_MATRIX_SIZE) {
        "colour matrices must have $COLOR_MATRIX_SIZE entries"
    }
    val out = FloatArray(COLOR_MATRIX_SIZE)
    for (row in 0 until 4) {
        val base = row * 5
        for (col in 0 until 4) {
            var acc = 0f
            for (k in 0 until 4) acc += after[base + k] * before[k * 5 + col]
            out[base + col] = acc
        }
        var offset = after[base + 4]
        for (k in 0 until 4) offset += after[base + k] * before[k * 5 + 4]
        out[base + 4] = offset
    }
    return out
}

/** Per-channel gain with an optional 0..255 offset. */
fun gainMatrix(gains: ChannelGains, offset255: Float = 0f): FloatArray = floatArrayOf(
    gains.r, 0f, 0f, 0f, offset255,
    0f, gains.g, 0f, 0f, offset255,
    0f, 0f, gains.b, 0f, offset255,
    0f, 0f, 0f, 1f, 0f,
)

/**
 * Exposure in stops: a +1 EV shift doubles the signal. [ev] is clamped to
 * ±[MAX_AUTO_EXPOSURE_EV] * 3 so a preset bias plus an auto correction can never
 * produce an absurd gain.
 */
fun exposureMatrix(ev: Float): FloatArray {
    val gain = 2f.pow(ev.coerceIn(-3f, 3f))
    return gainMatrix(ChannelGains(gain, gain, gain))
}

/** Contrast pivoted on mid-grey. [amount] matches the preset scale (-1..1). */
fun contrastMatrix(amount: Float): FloatArray {
    val scale = (1f + amount.coerceIn(-0.9f, 0.9f))
    val offset = (0.5f - 0.5f * scale) * 255f
    return gainMatrix(ChannelGains(scale, scale, scale), offset)
}

/** Saturation using the BT.601 luma axis. [amount] is the preset delta (-1..1). */
fun saturationMatrix(amount: Float): FloatArray {
    val s = (1f + amount).coerceIn(0f, 3f)
    val inv = 1f - s
    val r = 0.299f * inv
    val g = 0.587f * inv
    val b = 0.114f * inv
    return floatArrayOf(
        r + s, g, b, 0f, 0f,
        r, g + s, b, 0f, 0f,
        r, g, b + s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/**
 * Film-style fade: lifts the black point and slightly compresses the top, which is
 * still affine so it stays in the matrix. [amount] is the preset value (0..1).
 */
fun fadeMatrix(amount: Float): FloatArray {
    val a = amount.coerceIn(0f, 1f)
    val lift = 0.12f * a
    val scale = 1f - lift
    return gainMatrix(ChannelGains(scale, scale, scale), lift * 255f)
}

/** Maps normalized black/white points onto 0..1 (the contrast stretch). */
fun levelsMatrix(levels: LevelsStretch): FloatArray {
    val scale = 1f / levels.span
    val offset = -levels.black * scale * 255f
    return gainMatrix(ChannelGains(scale, scale, scale), offset)
}

/**
 * Auto exposure from the histogram mean, in stops, clamped to ±[limitEv]
 * (§4-1 requires the auto stage to stay inside ±1 EV).
 */
fun autoExposureEv(
    mean: Float,
    target: Float = AUTO_EXPOSURE_TARGET,
    limitEv: Float = MAX_AUTO_EXPOSURE_EV,
): Float {
    if (mean <= 0.002f || target <= 0f) return 0f
    val ev = (ln((target / mean).toDouble()) / ln(2.0)).toFloat()
    return ev.coerceIn(-limitEv, limitEv)
}

/**
 * Gray-world white balance: assumes the scene averages to neutral and scales R and
 * B onto the green mean. [strength] blends toward identity (1 = full correction),
 * and gains are clamped so a legitimately monochrome scene (a sunset, a green wall)
 * cannot be bleached into grey.
 */
fun grayWorldGains(
    means: ChannelMeans,
    strength: Float = 0.8f,
    maxGain: Float = 1.6f,
): ChannelGains {
    val g = means.g
    if (g <= 0.002f) return ChannelGains(1f, 1f, 1f)
    val s = strength.coerceIn(0f, 1f)
    fun gain(channel: Float): Float {
        if (channel <= 0.002f) return 1f
        val raw = (g / channel).coerceIn(1f / maxGain, maxGain)
        return 1f + (raw - 1f) * s
    }
    return ChannelGains(gain(means.r), 1f, gain(means.b))
}

/**
 * Contrast stretch bounded by [maxStretch]: the black/white points are pulled at
 * most that far toward the measured percentiles, so a flat scene is not forced to
 * full range.
 */
fun contrastStretch(
    stats: LumaStats,
    maxStretch: Float = 0.6f,
): LevelsStretch {
    val s = maxStretch.coerceIn(0f, 1f)
    val black = (stats.blackPoint * s).coerceIn(0f, 0.45f)
    val white = (1f - (1f - stats.whitePoint) * s).coerceIn(0.55f, 1f)
    return LevelsStretch(black, white)
}

/**
 * Relative channel gains for a target colour temperature, normalized so green is
 * unchanged. Uses the standard piecewise blackbody approximation, then divides by
 * the same curve at [reference] so a preset at 5500K is a no-op.
 */
fun kelvinGains(kelvin: Float, reference: Float = NEUTRAL_KELVIN): ChannelGains {
    val target = blackbodyRgb(kelvin)
    val base = blackbodyRgb(reference)
    fun ratio(a: Float, b: Float): Float = if (b <= 0.001f) 1f else (a / b).coerceIn(0.5f, 2f)
    val r = ratio(target.r, base.r)
    val g = ratio(target.g, base.g)
    val b = ratio(target.b, base.b)
    // Re-normalize onto green so temperature only tints, never brightens.
    return ChannelGains(r / g, 1f, b / g)
}

/** Tanner Helland's blackbody approximation, output normalized to 0..1. */
private fun blackbodyRgb(kelvin: Float): ChannelMeans {
    val t = (kelvin.coerceIn(1000f, 40000f) / 100f).toDouble()
    val r = if (t <= 66.0) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66.0) {
        99.4708025861 * ln(t) - 161.1195681661
    } else {
        288.1221695283 * (t - 60).pow(-0.0755148492)
    }
    val b = when {
        t >= 66.0 -> 255.0
        t <= 19.0 -> 0.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    return ChannelMeans(
        r = (r.coerceIn(0.0, 255.0) / 255.0).toFloat(),
        g = (g.coerceIn(0.0, 255.0) / 255.0).toFloat(),
        b = (b.coerceIn(0.0, 255.0) / 255.0).toFloat(),
    )
}

/**
 * The optical-stage matrix: white balance, then auto exposure, then the contrast
 * stretch. Ordering matters — WB before exposure keeps the stretch working on an
 * already-neutral image.
 */
fun opticalColorMatrix(
    wb: ChannelGains,
    exposureEv: Float,
    levels: LevelsStretch,
): FloatArray {
    var m = gainMatrix(wb)
    m = concatColorMatrix(exposureMatrix(exposureEv), m)
    m = concatColorMatrix(levelsMatrix(levels), m)
    return m
}

/**
 * The style-stage matrix built from a preset's [ColorParams]. grain, vignette and
 * blurStrength are *not* here — they are draw operations, not colour transforms,
 * and belong to the renderer (see `EditPlan.style`).
 */
fun styleColorMatrix(color: ColorParams): FloatArray {
    var m = gainMatrix(kelvinGains(color.colorTemperature.toFloat()))
    m = concatColorMatrix(exposureMatrix(color.exposureBias.toFloat()), m)
    m = concatColorMatrix(contrastMatrix(color.contrast.toFloat()), m)
    m = concatColorMatrix(saturationMatrix(color.saturation.toFloat()), m)
    if (color.fade > 0.0) {
        m = concatColorMatrix(fadeMatrix(color.fade.toFloat()), m)
    }
    return m
}

/**
 * 256-entry tone LUT for the non-affine part of the optical stage: [shadowLift]
 * opens crushed shadows, [highlightRolloff] soft-clips the top instead of letting
 * the stretch clip it flat. Identity when both are 0.
 */
fun toneCurveLut(shadowLift: Float, highlightRolloff: Float): IntArray {
    val lift = shadowLift.coerceIn(0f, 1f)
    val roll = highlightRolloff.coerceIn(0f, 1f)
    val lut = IntArray(256)
    for (i in 0..255) {
        var v = i / 255f
        if (lift > 0f) {
            // Pull the lower half up along a gamma curve; no effect at v = 1.
            v = v.pow(1f - 0.5f * lift)
        }
        if (roll > 0f) {
            // Blend toward a smooth shoulder so near-white detail survives.
            val shoulder = 1f - (1f - v) * (1f - v)
            v = v + (shoulder - v) * roll * v
        }
        lut[i] = (v * 255f).roundToInt().coerceIn(0, 255)
    }
    return lut
}

/** True when [lut] leaves every level unchanged (lets the renderer skip a pass). */
fun isIdentityLut(lut: IntArray): Boolean {
    if (lut.size != 256) return false
    for (i in 0..255) if (lut[i] != i) return false
    return true
}

/** True when [matrix] is the identity within [epsilon]. */
fun isIdentityColorMatrix(matrix: FloatArray, epsilon: Float = 1e-4f): Boolean {
    if (matrix.size != COLOR_MATRIX_SIZE) return false
    val identity = identityColorMatrix()
    for (i in 0 until COLOR_MATRIX_SIZE) {
        if (abs(matrix[i] - identity[i]) > epsilon) return false
    }
    return true
}

/**
 * Applies a 4x5 colour matrix to one packed ARGB pixel. Alpha is carried through
 * the matrix like `ColorMatrixColorFilter` does, and every channel is clamped.
 */
fun applyColorMatrix(argb: Int, matrix: FloatArray): Int {
    val a = (argb ushr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF

    val outR = channel(matrix, 0, r, g, b, a)
    val outG = channel(matrix, 1, r, g, b, a)
    val outB = channel(matrix, 2, r, g, b, a)
    val outA = channel(matrix, 3, r, g, b, a)

    return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
}

private fun channel(m: FloatArray, row: Int, r: Int, g: Int, b: Int, a: Int): Int {
    val base = row * 5
    val v = m[base] * r + m[base + 1] * g + m[base + 2] * b + m[base + 3] * a + m[base + 4]
    return v.roundToInt().coerceIn(0, 255)
}

/**
 * In-place colour matrix + optional tone LUT over a packed ARGB buffer.
 *
 * This is the **software reference path**: the JVM tests run it, and the Android
 * renderer falls back to it on the tiles a hardware backend cannot take. Because
 * both use this same function, a backend swap cannot silently change the output.
 */
fun applyColorPipeline(
    pixels: IntArray,
    matrix: FloatArray,
    lut: IntArray? = null,
    count: Int = pixels.size,
) {
    val useLut = lut != null && lut.size == 256 && !isIdentityLut(lut)
    val useMatrix = !isIdentityColorMatrix(matrix)
    if (!useLut && !useMatrix) return
    for (i in 0 until count) {
        var p = pixels[i]
        if (useMatrix) p = applyColorMatrix(p, matrix)
        if (useLut) {
            val table = lut!!
            val a = (p ushr 24) and 0xFF
            val r = table[(p shr 16) and 0xFF]
            val g = table[(p shr 8) and 0xFF]
            val b = table[p and 0xFF]
            p = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        pixels[i] = p
    }
}
