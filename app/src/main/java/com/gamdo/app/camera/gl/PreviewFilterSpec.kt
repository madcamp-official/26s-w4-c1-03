package com.gamdo.app.camera.gl

import com.gamdo.app.edit.FilterEngine
import com.gamdo.app.edit.PhotoFilter
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Everything the preview fragment shader needs to reproduce [FilterEngine], in the
 * form the GPU takes it: one texture and a handful of scalars.
 *
 * ## Why a LUT and not GLSL arithmetic
 *
 * The tone curve is five composed stages — two blends against fixed target curves,
 * an endpoint remap, and an analytic S-curve whose negative branch is an `asin` of a
 * `sin`. Re-deriving that in GLSL means maintaining the same maths in two languages
 * with no test that can compare them, and the divergence would show up as a preview
 * that is subtly not the photo. Sampling [FilterEngine.toneCurve] into a texture
 * makes the shader's tone stage a *table lookup of the engine's own output*: there
 * is no second implementation to drift.
 *
 * The same argument covers the colour mixer, whose per-degree tables
 * ([FilterEngine.hueTable]) are already public and already the thing the CPU path
 * reads.
 *
 * ## Why 16-bit and not 8
 *
 * The values stored here are not display colours, they are *operands*. The channel
 * tables reach 1.96 (2 EV of exposure on top of a white-balance gain) and the tone
 * curve output is multiplied into all three channels as a ratio, so an 8-bit table
 * would quantise the operand to 1/255 and then amplify the error. Each entry is
 * therefore a 16-bit fixed-point value split across two 8-bit channels, decoded in
 * the shader. That costs one extra multiply-add per fetch and buys three more
 * decimal digits — see `PreviewFilterModelTest`, which holds the whole pipeline to
 * within one 8-bit level of the engine.
 *
 * ## Layout
 *
 * A [WIDTH] × [HEIGHT] RGBA8 texture, sampled `NEAREST` — the shader does its own
 * interpolation so it can match [FilterEngine.apply]'s interpolation exactly.
 *
 * | row | contents | entries | encoding |
 * |---|---|---|---|
 * | [ROW_TONE] | tone curve over `[0, 2]` | 512 | RG, value as-is |
 * | [ROW_R] [ROW_G] [ROW_B] | white balance × exposure, per channel | 256 | RG, value / [CHANNEL_SCALE] |
 * | [ROW_HUE_SAT] [ROW_HUE_LUM] | 색상 혼합 saturation, luminance | 360 | RG, / [HUE_SCALE] |
 * | [ROW_HUE_SHIFT] | 색상 혼합 hue shift, degrees | 360 | RG, `(v + `[SHIFT_BIAS]`) / `[SHIFT_SPAN] |
 */
object PreviewFilterLut {

    const val WIDTH = 512
    const val HEIGHT = 7

    const val ROW_TONE = 0
    const val ROW_R = 1
    const val ROW_G = 2
    const val ROW_B = 3
    const val ROW_HUE_SAT = 4
    const val ROW_HUE_LUM = 5
    const val ROW_HUE_SHIFT = 6

    /** Matches [FilterEngine]'s own tone table. Both numbers are load-bearing. */
    const val TONE_SAMPLES = 512
    const val TONE_DOMAIN = 2f

    /**
     * Divisor that brings the channel tables into `[0, 1]` for storage.
     *
     * The measured ceiling is **2.081**, and the value that produces it is not the
     * obvious one. It is not "warmest × brightest": it is 색온도 -100 together with
     * 색조 +100, which pulls the green gain down to 0.90, and since
     * [FilterEngine.whiteBalanceGains] renormalises by luminance to keep warmth from
     * doubling as an exposure change, dropping green *raises* blue — to 1.359, well
     * past the 1.183 that 색온도 alone reaches. Times the exposure control's 2 EV
     * limit and through the sRGB transfer function, that is 2.081.
     *
     * 2.25 clears it by 8% and is exact in binary. The first version of this
     * constant was 2.0, derived from the warmth-only corner, and
     * `the channel scale clears the highest value the control can produce` caught
     * it — clipping there would have silently flattened the brightest part of a
     * cool-tinted frame.
     */
    const val CHANNEL_SCALE = 2.25f

    /** Divisor for the colour mixer's multipliers. See the ceiling test. */
    const val HUE_SCALE = 4f

    /** Hue shift is signed; stored biased. ±64° covers two full-strength rows. */
    const val SHIFT_BIAS = 64f
    const val SHIFT_SPAN = 128f

    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    /** Bytes per texel (RGBA8). */
    const val BYTES_PER_TEXEL = 4

    /**
     * Builds the texture payload.
     *
     * [adjustments] is the same object the editor's sliders hold, seeded by
     * [FilterEngine.seedFrom] — which is what keeps the preview and the saved file
     * reading from one recipe. [filter] contributes its colour-mixer rows, exactly
     * as it does in [FilterEngine.apply].
     */
    fun build(filter: PhotoFilter, adjustments: FilterEngine.Adjustments): ByteArray {
        val out = ByteArray(WIDTH * HEIGHT * BYTES_PER_TEXEL)

        // --- tone -------------------------------------------------------------
        // Straight from the engine. Note the Tone is rebuilt from `adjustments`
        // and not from `filter.tone`: the sliders are absolute and seeding has
        // already copied the recipe into them (see FilterEngine.Adjustments).
        val curve = FilterEngine.toneCurve(
            PhotoFilter.Tone(
                contrast = adjustments.contrast,
                highlights = adjustments.highlights,
                shadows = adjustments.shadows,
                whites = adjustments.whites,
                blacks = adjustments.blacks,
            ),
        )
        for (i in 0 until TONE_SAMPLES) put16(out, i, ROW_TONE, curve[i])

        // --- white balance × exposure ------------------------------------------
        val gains = FilterEngine.whiteBalanceGains(adjustments.warmth, adjustments.tint)
        val expScale = 2f.pow((adjustments.exposure / 100f) * FilterEngine.MANUAL_EXPOSURE_EV)
        val rows = intArrayOf(ROW_R, ROW_G, ROW_B)
        for (c in 0 until 3) {
            val gain = gains[c] * expScale
            for (v in 0 until 256) {
                put16(out, v, rows[c], channelValue(v, gain) / CHANNEL_SCALE)
            }
        }

        // --- 색상 혼합 -----------------------------------------------------------
        val hue = FilterEngine.hueTable(filter.hsl)
        for (deg in 0 until 360) {
            put16(out, deg, ROW_HUE_SAT, hue[0][deg] / HUE_SCALE)
            put16(out, deg, ROW_HUE_LUM, hue[2][deg] / HUE_SCALE)
            put16(out, deg, ROW_HUE_SHIFT, (hue[1][deg] + SHIFT_BIAS) / SHIFT_SPAN)
        }
        return out
    }

    /**
     * One entry of [FilterEngine]'s private `channelTable`, reproduced.
     *
     * The engine's copy is private and this file may not modify it (the colour maths
     * is pinned by golden checksums). Duplicating four lines of standard sRGB is the
     * lesser evil versus widening that API — and the duplication is not on trust:
     * `PreviewFilterModelTest` runs the whole pipeline against
     * [FilterEngine.apply] over an RGB lattice, so any divergence here fails there.
     *
     * Left **unclamped** at the top, exactly as the engine leaves it, so the tone
     * curve's highlight recovery still has over-range to recover.
     */
    fun channelValue(byte: Int, gain: Float): Float =
        linearToSrgbUnclamped(srgbToLinear(byte / 255f) * gain)

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgbUnclamped(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f

    /**
     * Writes a normalised value as 16-bit fixed point into the R and G bytes of
     * `texel(x, row)`.
     *
     * B and A are left at zero and deliberately unused. Packing a second value into
     * them would have saved two rows of a 14 KB texture and made every fetch depend
     * on the alpha channel surviving upload untouched — which is exactly the kind of
     * assumption that turns into a wrong colour on one driver and nowhere else.
     */
    private fun put16(out: ByteArray, x: Int, row: Int, normalised: Float) {
        val q = (normalised.coerceIn(0f, 1f) * 65535f).roundToInt().coerceIn(0, 65535)
        val base = (row * WIDTH + x) * BYTES_PER_TEXEL
        out[base] = ((q shr 8) and 0xff).toByte()
        out[base + 1] = (q and 0xff).toByte()
    }

    /** Reads back what [put16] wrote, the way the shader decodes it. */
    fun read16(lut: ByteArray, x: Int, row: Int): Float {
        val base = (row * WIDTH + x) * BYTES_PER_TEXEL
        val hi = lut[base].toInt() and 0xff
        val lo = lut[base + 1].toInt() and 0xff
        return (hi * 256 + lo) / 65535f
    }

    /** Luminance, Rec. 709 — the engine's weights. */
    fun luma(r: Float, g: Float, b: Float): Float = LR * r + LG * g + LB * b

    internal const val WEIGHT_R = LR
    internal const val WEIGHT_G = LG
    internal const val WEIGHT_B = LB
}

/**
 * The complete uniform set for one preset, as the renderer uploads it.
 *
 * The scalars are the stages that are cheaper as arithmetic than as a table — they
 * are single multiplies, and a texture fetch to avoid a multiply is a bad trade on a
 * mobile GPU.
 *
 * `equals` is what decides whether the GL thread re-uploads anything, so [lut]'s
 * array identity is deliberately **not** what is compared: [presetId] plus the
 * [adjustments] it was built from determine the bytes completely.
 */
class PreviewFilterSpec(
    val presetId: String,
    val adjustments: FilterEngine.Adjustments,
    val lut: ByteArray,
    val vibrance: Float,
    val saturation: Float,
    val fade: Float,
    val grain: Float,
    val vignette: Float,
    val hasHsl: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is PreviewFilterSpec &&
        other.presetId == presetId && other.adjustments == adjustments

    override fun hashCode(): Int = 31 * presetId.hashCode() + adjustments.hashCode()

    override fun toString(): String = "PreviewFilterSpec($presetId, $adjustments)"

    companion object {
        /**
         * The slider positions the **preview** runs a filter at: the editor's
         * recipe with 노출 removed.
         *
         * ## Why exposure is dropped — O-15 (2), owner decision, do not compensate
         *
         * [FilterEngine.effectiveExposureEv] caps a preset's published exposure by
         * `log2(1 / p99)`, the highlight headroom **of the photograph being
         * edited**. That cap is not a detail: it is the only reason
         * `bright_review` can publish +2.18 EV without turning an already-bright
         * frame into a white rectangle, and its own KDoc says so.
         *
         * A live preview has no photograph to measure, and every way of guessing
         * one is wrong in a different direction. Using the published value
         * uncapped reproduces exactly the failure the cap exists to prevent —
         * the preview blows out where the file is fine. Estimating from analysis
         * frames does not even have the right statistic (`frameMean`, not `p99`)
         * and makes preview brightness wobble as the scene moves.
         *
         * Removing the term instead makes the disagreement **one-directional and
         * predictable**: since every shipped preset publishes a non-negative
         * exposure, the preview is darker than or equal to the file, never
         * brighter. The accepted cost is that a preset whose identity is
         * "brighter" reads as less distinct on the preview than in the result.
         *
         * **If you are here because preview and file disagree on brightness: that
         * is this, and it is deliberate.** Adding a fudge factor back would be a
         * number nobody can justify — the honest fix is a real `p99`, which needs
         * a photograph.
         *
         * Everything else still flows through [FilterEngine.seedFrom], the same
         * call the editor makes, so the two cannot drift apart on any other term.
         */
        fun previewAdjustments(filter: PhotoFilter): FilterEngine.Adjustments =
            FilterEngine.seedFrom(filter, EXPOSURE_SINK).copy(exposure = 0)

        /**
         * Handed to [FilterEngine.seedFrom] purely to satisfy its signature.
         *
         * Exposure is the only field a [FilterEngine.Measure] can influence — every
         * other slider is copied verbatim from the recipe — and exposure is zeroed
         * on the next line. So this value cannot reach the preview, which
         * `the measure cannot influence the preview` pins rather than assumes.
         */
        private val EXPOSURE_SINK = FilterEngine.Measure(meanLuma = 0.5f, p99 = 1f)

        /** The spec the preview runs for [filter]. */
        fun of(filter: PhotoFilter): PreviewFilterSpec = of(filter, previewAdjustments(filter))

        /**
         * Builds the spec for a filter at a given slider position.
         *
         * The three effect scalars carry [FilterEngine]'s own unit conversions —
         * `100 그레인` is ±16% of range, `100 페이드` lifts the floor 14% — rather
         * than re-deriving them, because a different constant here would be a
         * different look with the same name.
         */
        fun of(
            filter: PhotoFilter,
            adjustments: FilterEngine.Adjustments,
        ): PreviewFilterSpec = PreviewFilterSpec(
            presetId = filter.id,
            adjustments = adjustments,
            lut = PreviewFilterLut.build(filter, adjustments),
            vibrance = adjustments.vibrance / 100f,
            saturation = adjustments.saturation / 100f,
            fade = adjustments.fade / 100f * 0.14f,
            grain = adjustments.grain / 100f * 0.16f,
            vignette = (adjustments.vignette / 100f).coerceIn(-1f, 1f),
            hasHsl = filter.hsl.isNotEmpty(),
        )
    }
}

/** `max` on three floats, spelled once. */
internal fun max3(a: Float, b: Float, c: Float) = max(a, max(b, c))
