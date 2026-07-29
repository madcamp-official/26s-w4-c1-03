package com.gamdo.app.camera.gl

import com.gamdo.app.edit.FilterEngine
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The preview fragment shader's arithmetic, written in Kotlin.
 *
 * ## What this is for
 *
 * GLSL does not run on a JVM and this module has no `androidTest` source set, so
 * the shader itself is untestable here — saying so plainly is better than a mock GL
 * that proves only that the mock works. What *is* testable is whether the algorithm
 * the shader implements agrees with [FilterEngine], and that is what this class is:
 * a line-for-line transliteration of `preview_filter.frag`, reading the same
 * [PreviewFilterLut] bytes through the same decode, so `PreviewFilterModelTest` can
 * hold it against [FilterEngine.apply] over an RGB lattice and report the
 * disagreement in 8-bit levels.
 *
 * The claim that buys is precise, and worth stating precisely: **given the same
 * input colour, this produces the same output colour as the editor.** It does not
 * claim the preview and the saved JPEG start from the same input colour — they do
 * not (see [PreviewColorEffect]'s KDoc: different resolution, and the camera's
 * YUV→RGB happens in the GPU sampler for one and in the JPEG decoder for the
 * other). No shader can close that gap; pretending otherwise would be the "preview
 * that disagrees with the file" this work exists to remove.
 *
 * ## The two-implementations problem, and why it is bounded here
 *
 * A transliteration is still a second implementation and can still drift from the
 * `.frag` beside it. The mitigation is that both are short, both are in this
 * package, and the parity test fails loudly the moment *this* one drifts from the
 * engine — which is the drift that matters. A shader that drifts from this class
 * without drifting from the engine is not reachable by any test on this machine and
 * is called out in the DONE-DEVICE list instead of being papered over.
 *
 * @param x,y in **crop-space pixels** — the coordinate system of the photograph
 *   that would be saved, not of the preview surface. 비네팅 is a function of
 *   position, so the only way for it to land where the file has it is to measure
 *   from the visible window rather than from the full 4:3 sensor frame.
 */
class PreviewFilterModel(private val spec: PreviewFilterSpec) {

    private val lut = spec.lut

    fun apply(argb: Int, x: Int, y: Int, width: Int, height: Int): Int {
        // --- white balance × exposure, byte-indexed exactly as the engine ------
        var r = channel(PreviewFilterLut.ROW_R, (argb shr 16) and 0xff)
        var g = channel(PreviewFilterLut.ROW_G, (argb shr 8) and 0xff)
        var b = channel(PreviewFilterLut.ROW_B, argb and 0xff)

        // --- tone, on luminance, RGB scaled by the ratio so hue survives -------
        val l = PreviewFilterLut.luma(r, g, b)
        if (l > 1e-4f) {
            // Interpolated, not truncated. The engine interpolates because a
            // 512-sample table over [0, 2] has bins a whole 8-bit level wide;
            // nearest-sample lookup would band smooth gradients. Reproduced here
            // rather than delegated to GL_LINEAR so the arithmetic is identical —
            // GL_LINEAR would also interpolate the *encoded* 16-bit halves
            // independently, which is not the same function.
            val pos = (l * CURVE_SCALE).coerceIn(0f, (PreviewFilterLut.TONE_SAMPLES - 1).toFloat())
            val lo = pos.toInt()
            val hi = min(lo + 1, PreviewFilterLut.TONE_SAMPLES - 1)
            val frac = pos - lo
            val cLo = PreviewFilterLut.read16(lut, lo, PreviewFilterLut.ROW_TONE)
            val cHi = PreviewFilterLut.read16(lut, hi, PreviewFilterLut.ROW_TONE)
            val scale = (cLo + (cHi - cLo) * frac) / l
            r *= scale; g *= scale; b *= scale
        }

        // --- 생동감 / 채도 ------------------------------------------------------
        if (spec.vibrance != 0f || spec.saturation != 0f) {
            val mx = max3(r, g, b)
            val mn = min3(r, g, b)
            val current = if (mx > 1e-4f) (mx - mn) / mx else 0f
            val f = 1f + spec.saturation + spec.vibrance * (1f - current)
            val lum = PreviewFilterLut.luma(r, g, b)
            r = lum + (r - lum) * f
            g = lum + (g - lum) * f
            b = lum + (b - lum) * f
        }

        // --- 색상 혼합 -----------------------------------------------------------
        if (spec.hasHsl) {
            val mx = max3(r, g, b)
            val mn = min3(r, g, b)
            val delta = mx - mn
            if (delta > 1e-4f) {
                // Tie-breaking matters and follows the engine's `when (mx)`: red
                // wins over green wins over blue when two channels are equal.
                var deg = when {
                    mx == r -> 60f * ((g - b) / delta)
                    mx == g -> 60f * ((b - r) / delta + 2f)
                    else -> 60f * ((r - g) / delta + 4f)
                }
                if (deg < 0f) deg += 360f
                val bin = deg.toInt().coerceIn(0, 359)
                val sMul = PreviewFilterLut.read16(lut, bin, PreviewFilterLut.ROW_HUE_SAT) *
                    PreviewFilterLut.HUE_SCALE
                val lMul = PreviewFilterLut.read16(lut, bin, PreviewFilterLut.ROW_HUE_LUM) *
                    PreviewFilterLut.HUE_SCALE
                val rot = PreviewFilterLut.read16(lut, bin, PreviewFilterLut.ROW_HUE_SHIFT) *
                    PreviewFilterLut.SHIFT_SPAN - PreviewFilterLut.SHIFT_BIAS
                val lum = PreviewFilterLut.luma(r, g, b)
                r = (lum + (r - lum) * sMul) * lMul
                g = (lum + (g - lum) * sMul) * lMul
                b = (lum + (b - lum) * sMul) * lMul
                // The engine reads nine coefficients out of a per-bin table; here
                // they are rebuilt from the same angle with the same expressions.
                // A 360×9 table would have cost three more texture rows to store a
                // sine the GPU computes in one cycle.
                val rad = rot * PI_OVER_180
                val c = cos(rad)
                val s = sin(rad)
                val nr = r * (WR + c * (1 - WR) - s * WR) +
                    g * (WG - c * WG - s * WG) +
                    b * (WB - c * WB + s * (1 - WB))
                val ng = r * (WR - c * WR + s * 0.143f) +
                    g * (WG + c * (1 - WG) + s * 0.140f) +
                    b * (WB - c * WB - s * 0.283f)
                val nb = r * (WR - c * WR - s * (1 - WR)) +
                    g * (WG - c * WG + s * WG) +
                    b * (WB + c * (1 - WB) + s * WB)
                r = nr; g = ng; b = nb
            }
        }

        // --- 페이드 / 입자 / 비네팅 -----------------------------------------------
        if (spec.fade != 0f) {
            r = spec.fade + r * (1f - spec.fade)
            g = spec.fade + g * (1f - spec.fade)
            b = spec.fade + b * (1f - spec.fade)
        }
        if (spec.grain != 0f) {
            val n = grainAt(x.toFloat(), y.toFloat())
            r += n * spec.grain; g += n * spec.grain; b += n * spec.grain
        }
        if (spec.vignette != 0f) {
            val cx = width * 0.5f
            val cy = height * 0.5f
            val maxDist = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
            val dx = x - cx
            val dy = y - cy
            val d = sqrt(dx * dx + dy * dy) / maxDist
            val v = 1f + spec.vignette * smoothstep01((d - 0.45f) / 0.55f)
            r *= v; g *= v; b *= v
        }

        return (argb and -0x1000000) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    }

    private fun channel(row: Int, byte: Int): Float =
        PreviewFilterLut.read16(lut, byte, row) * PreviewFilterLut.CHANNEL_SCALE

    private companion object {
        val CURVE_SCALE = (PreviewFilterLut.TONE_SAMPLES - 1) / PreviewFilterLut.TONE_DOMAIN
        const val PI_OVER_180 = 0.017453292f
        const val WR = PreviewFilterLut.WEIGHT_R
        const val WG = PreviewFilterLut.WEIGHT_G
        const val WB = PreviewFilterLut.WEIGHT_B
    }
}

/**
 * Deterministic per-pixel noise in `[-0.5, 0.5)`, from position alone.
 *
 * ## Why this is not [FilterEngine.grainAt]
 *
 * The engine hashes the pixel's **index in the buffer** with a 32-bit integer
 * avalanche (xor-shift, two odd multipliers). Neither half of that survives the
 * crossing:
 *
 *  - GLSL ES 1.00 has no integer bitwise operators at all, so the avalanche cannot
 *    be written. (ES 3.0 has them; this pipeline deliberately targets ES 2.0 — see
 *    [GlPreviewRenderer].)
 *  - Even with them, the index is `y * width + x`, and the preview is not the
 *    saved file's size. The same pixel of the same scene has a different index and
 *    therefore a different sample. **Grain is not per-pixel reproducible across a
 *    resize, by construction.**
 *
 * So what is reproduced is grain's *statistics*, which is all grain has: uniform on
 * `[-0.5, 0.5)`, zero mean, added equally to all three channels, scaled by the same
 * `그레인/100 × 0.16`. `PreviewFilterModelTest` pins those. The pattern differs from
 * the file's, and at a preview's resolution the grains are also physically larger —
 * one preview pixel covers several file pixels. Both are stated in the report
 * rather than hidden.
 *
 * The construction is multiply/add/`fract` only — no `sin`, whose result is
 * implementation-defined in GLSL — so this Kotlin and the shader compute bit-equal
 * values in fp32, and the model really does mirror the shader here too.
 */
internal fun grainAt(x: Float, y: Float): Float {
    var px = fract(x * 0.1031f)
    var py = fract(y * 0.1030f)
    var pz = fract(x * 0.0973f)
    val dot = px * (py + 33.33f) + py * (pz + 33.33f) + pz * (px + 33.33f)
    px += dot
    py += dot
    pz += dot
    return fract((px + py) * pz) - 0.5f
}

private fun fract(v: Float): Float = v - kotlin.math.floor(v)

private fun smoothstep01(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private fun min3(a: Float, b: Float, c: Float) = min(a, min(b, c))

private fun to8(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
