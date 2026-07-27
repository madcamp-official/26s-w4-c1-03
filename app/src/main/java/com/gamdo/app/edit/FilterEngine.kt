package com.gamdo.app.edit

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Applies a [PhotoFilter] to a pixel buffer.
 *
 * ## Pure on purpose
 *
 * Everything here operates on an `IntArray` of packed ARGB and nothing else. There
 * is no `android.graphics` import, so the entire filter — tone curve, white
 * balance, vibrance, colour mixer, grain — runs in JVM unit tests. That matters
 * more here than anywhere else in the app: a filter has no crash to catch and no
 * exception to log. It is either the look the photographer published or it is a
 * wash, and the only way to tell the difference in CI is to run the numbers on
 * synthetic images and assert on the histogram.
 *
 * The Android side is [QuickFilterEditor], which is two calls to `getPixels` and
 * `setPixels` around [apply].
 *
 * ## Pipeline order
 *
 * Follows the order photographers actually work in, which is also the order the
 * source articles teach: **노출 → 밝은 영역 → 어두운 영역 → 흰색/검정 → 대비 →
 * 생동감 → 채도 → 색온도**. Order is not cosmetic — lifting shadows after setting
 * the black point undoes the black point.
 *
 * Concretely:
 *  1. white balance + exposure, in **linear light** (multiplicative, folded into a
 *     per-channel 256-entry table)
 *  2. tone curve on **luminance**, RGB scaled by the ratio so hue survives
 *  3. vibrance / saturation
 *  4. colour mixer (색상 혼합), only when the filter has rows
 *  5. grain / fade / vignette
 *
 * ## Why luminance-ratio and not three independent curves
 *
 * Running the tone curve separately per channel is one line shorter and shifts hue
 * on every pixel that is not grey — a warm highlight gets warmer as it brightens,
 * because red hits the steep part of the curve before blue does. Scaling RGB by
 * `curve(L)/L` keeps the chromaticity and puts hue changes where they were asked
 * for: the colour mixer.
 *
 * ## Why linear light for exposure and white balance
 *
 * Both are physically multiplications on light. Doing them on gamma-encoded bytes
 * — which is what the code this replaced did, `red += warmth * 22f` — tints the
 * shadows hardest, because a fixed offset is a huge relative change at the bottom
 * of the range and a negligible one at the top. That is the "muddy" look, and it
 * is why the old warmth control had to be kept near zero to be tolerable, which in
 * turn made it invisible.
 */
object FilterEngine {

    /** Extended tone-curve domain. Exposure can push luminance above 1.0 and the
     *  highlight recovery needs to see it before anything clips. */
    private const val TONE_DOMAIN = 2f
    private const val TONE_SAMPLES = 512

    /** Where `밝은 영역 -100` lands a blown highlight. Not 0 — recovery compresses
     *  the top end toward the upper midtones, it does not delete it. */
    private const val HIGHLIGHT_FLOOR = 0.75f

    /** Where `어두운 영역 +100` lands a crushed shadow. */
    private const val SHADOW_CEILING = 0.32f

    /** 흰색/검정 계열 at ±100 move the endpoint by this much of the range. */
    private const val ENDPOINT_RANGE = 0.25f

    /** Highlight clipping tolerated when honouring a preset's exposure. Creators
     *  clip a little; refusing to is how "bright" becomes "grey". */
    private const val CLIP_TOLERANCE_EV = 0.35f

    /** Luminance weights (Rec. 709). */
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    /**
     * What the image is, before deciding what to do to it.
     *
     * [p99] is the 99th percentile of luminance and exists for one reason: to cap a
     * published exposure. See [PhotoFilter]'s KDoc for why exposure is the one
     * slider that cannot be copied literally.
     */
    data class Measure(val meanLuma: Float, val p99: Float)

    /**
     * The full set of slider positions, in Lightroom's units and **absolute**.
     *
     * ## Absolute, not a delta on the filter
     *
     * Choosing a filter *seeds* these from its published recipe (see [seedFrom]),
     * exactly as applying a preset in Lightroom moves the sliders. What the user
     * sees on a slider is therefore what the renderer uses — there is no hidden
     * second contribution.
     *
     * The first version made these deltas added on top of the filter, and the sum
     * was clamped. On `soft_film`, which publishes 밝은 영역 -100, the 밝은 영역
     * slider then did nothing at all in the negative direction: the control was
     * already at the floor, and a slider that moves while the photo does not is
     * indistinguishable from a broken one. Seeding removes the failure mode
     * instead of annotating it.
     *
     * It also removes the reason 강도 existed. That control was reintroduced
     * specifically because lowering it was the only way to reopen a clamped range;
     * with nothing to clamp it has no job, and a control that silently rewrites
     * thirteen others is worse than its absence.
     *
     * Units: -100..+100 for everything, except [exposure], where 100 units is
     * [MANUAL_EXPOSURE_EV] — see [seedFrom] for why exposure is seeded from a
     * measurement rather than copied.
     */
    data class Adjustments(
        val exposure: Int = 0,
        val contrast: Int = 0,
        val highlights: Int = 0,
        val shadows: Int = 0,
        val whites: Int = 0,
        val blacks: Int = 0,
        val warmth: Int = 0,
        val tint: Int = 0,
        val vibrance: Int = 0,
        val saturation: Int = 0,
        val fade: Int = 0,
        val grain: Int = 0,
        val vignette: Int = 0,
    ) {
        /** True when every slider is at zero — the unedited state. */
        val isNeutral: Boolean get() = this == NEUTRAL

        companion object {
            val NEUTRAL = Adjustments()
        }
    }

    /**
     * The slider positions a filter starts from, for this particular photograph.
     *
     * Every value is copied straight from the published recipe except **exposure**,
     * which is measured. A preset's exposure was chosen against one photograph:
     * `bright_review` publishes +2.18 EV because its author was rescuing a backlit
     * subject, and applied blind to an already-bright frame that is a white
     * rectangle. [effectiveExposureEv] caps it by the highlight headroom actually
     * present, and the *capped* number is what the slider is seeded with — so the
     * position the user sees is the exposure the photo is getting, and they can
     * still push past it by hand if that is what they want.
     */
    fun seedFrom(filter: PhotoFilter, measure: Measure): Adjustments = Adjustments(
        exposure = evToSlider(effectiveExposureEv(filter.tone.exposureEv, measure)),
        contrast = filter.tone.contrast,
        highlights = filter.tone.highlights,
        shadows = filter.tone.shadows,
        whites = filter.tone.whites,
        blacks = filter.tone.blacks,
        warmth = filter.color.temp,
        tint = filter.color.tint,
        vibrance = filter.color.vibrance,
        saturation = filter.color.saturation,
        fade = filter.effects.fade,
        grain = filter.effects.grain,
        vignette = filter.effects.vignette,
    )

    /** Slider units for an exposure in EV, clamped to the control's range. */
    fun evToSlider(ev: Float): Int =
        (ev / MANUAL_EXPOSURE_EV * 100f).roundToInt().coerceIn(-100, 100)

    /** Luminance histogram over packed ARGB, 256 bins. */
    fun measure(pixels: IntArray): Measure {
        if (pixels.isEmpty()) return Measure(0f, 0f)
        val hist = IntArray(256)
        var sum = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val y = (LR * r + LG * g + LB * b).toInt().coerceIn(0, 255)
            hist[y]++
            sum += y
        }
        var cumulative = 0
        val target = (pixels.size * 0.99f).toInt()
        var p99 = 255
        for (i in 0..255) {
            cumulative += hist[i]
            if (cumulative >= target) {
                p99 = i
                break
            }
        }
        return Measure(
            meanLuma = (sum / pixels.size / 255.0).toFloat(),
            p99 = p99 / 255f,
        )
    }

    /**
     * The exposure actually used, in EV.
     *
     * A preset's exposure was chosen against one photograph. Positive exposure is
     * therefore treated as an upper bound and capped by how much headroom this
     * image has above [Measure.p99], plus [CLIP_TOLERANCE_EV]. A dark backlit frame
     * has metres of headroom and gets the full published lift; a frame that is
     * already bright gets almost none, instead of getting a white rectangle.
     *
     * Negative exposure passes through: darkening never destroys information the
     * way clipping does, and no preset here asks for it anyway.
     */
    fun effectiveExposureEv(published: Float, measure: Measure): Float {
        if (published <= 0f) return published
        val headroom = log2(1f / max(measure.p99, 1f / 255f))
        return min(published, headroom + CLIP_TOLERANCE_EV).coerceAtLeast(0f)
    }

    /**
     * Per-channel white-balance gains, normalised to preserve luminance.
     *
     * Without the normalisation, warming a photo also brightens it, and every
     * warmth change would have to be paid back with an exposure change — which is
     * precisely the coupling that makes a slider feel broken.
     */
    fun whiteBalanceGains(temp: Int, tint: Int): FloatArray {
        val t = (temp / 100f).coerceIn(-1f, 1f)
        val u = (tint / 100f).coerceIn(-1f, 1f)
        var r = 1f + 0.22f * t
        var g = 1f - 0.10f * u
        var b = 1f - 0.22f * t
        val luma = LR * r + LG * g + LB * b
        if (luma > 1e-4f) {
            r /= luma; g /= luma; b /= luma
        }
        return floatArrayOf(r, g, b)
    }

    /**
     * The tone curve, sampled over `[0, TONE_DOMAIN]`.
     *
     * Split out and public so tests can assert curve *shape* — monotonicity, fixed
     * points, that `밝은 영역 -100` actually pulls 1.0 down — without rendering an
     * image.
     */
    fun toneCurve(tone: PhotoFilter.Tone): FloatArray {
        val curve = FloatArray(TONE_SAMPLES)
        for (i in 0 until TONE_SAMPLES) {
            curve[i] = toneAt(i * TONE_DOMAIN / (TONE_SAMPLES - 1), tone)
        }
        return curve
    }

    /**
     * One sample of the tone curve. `x` may exceed 1.0.
     *
     * **Every stage is a convex blend between the identity and a fixed monotone
     * target curve**, and a convex combination of monotone functions is monotone,
     * as is their composition. So the curve cannot fold back on itself for any
     * combination of sliders — no tuning, no constants to get wrong.
     *
     * That property is load-bearing rather than tidy. The first version of this
     * function masked each slider with a window `w(y)` and added
     * `s * w(y) * (target - y)`; with `어두운 영역 +100` it mapped 0.00 to 0.320 and
     * 0.10 to 0.315, because the window fell off faster than the input rose. A
     * decreasing tone curve is posterisation — flat patches with hard edges through
     * gradients — and nothing in a preview would have named the cause.
     */
    fun toneAt(x: Float, tone: PhotoFilter.Tone): Float {
        var y = x

        // 밝은 영역
        val h = (tone.highlights / 100f).coerceIn(-1f, 1f)
        if (h > 0f) y = blend(y, highlightsUp(y), h)
        else if (h < 0f) y = blend(y, highlightsDown(y), -h)

        // 어두운 영역
        val s = (tone.shadows / 100f).coerceIn(-1f, 1f)
        if (s > 0f) y = blend(y, shadowsUp(y), s)
        else if (s < 0f) y = blend(y, shadowsDown(y), -s)

        // 흰색 계열 / 검정 계열 are endpoints, not pushes: they move the white and
        // black points and everything between is stretched onto the new range.
        val blackPoint = -(tone.blacks / 100f).coerceIn(-1f, 1f) * ENDPOINT_RANGE
        val whitePoint = 1f - (tone.whites / 100f).coerceIn(-1f, 1f) * ENDPOINT_RANGE
        y = (y - blackPoint) / max(whitePoint - blackPoint, 1e-3f)

        // 대비 last, on the finished range.
        y = contrastAt(y.coerceIn(0f, 1f), (tone.contrast / 100f).coerceIn(-1f, 1f))
        return y.coerceIn(0f, 1f)
    }

    private fun blend(identity: Float, target: Float, amount: Float) =
        identity + (target - identity) * amount

    /**
     * 밝은 영역 -100: a rational soft-clip above mid grey.
     *
     * Chosen over a hard knee because it is smooth everywhere, monotone everywhere,
     * and asymptotic to 1.0 — so exposure-created over-range (which is where
     * highlight recovery earns its keep, see [effectiveExposureEv]) compresses
     * instead of clipping. Constants set so 1.0 lands on [HIGHLIGHT_FLOOR].
     */
    private fun highlightsDown(y: Float): Float {
        val knee = 0.5f
        if (y <= knee) return y
        val span = 0.5f
        val t = y - knee
        return knee + span * t / (t + span)
    }

    /** 밝은 영역 +100: pushes the upper range toward white, leaving shadows alone. */
    private fun highlightsUp(y: Float): Float =
        y + 0.28f * smoothstep01((y - 0.30f) / 0.50f) * (1f - y).coerceAtLeast(0f)

    /** 어두운 영역 +100: lifts the floor to [SHADOW_CEILING], fading out by 0.6. */
    private fun shadowsUp(y: Float): Float =
        y + SHADOW_CEILING * (1f - smoothstep01(y / 0.60f))

    /** 어두운 영역 -100: gamma, which deepens shadows and fixes both endpoints. */
    private fun shadowsDown(y: Float): Float = y.coerceAtLeast(0f).pow(1.8f)

    /**
     * Symmetric S-curve about mid grey.
     *
     * Positive contrast is `smoothstep`; negative is its exact analytic inverse, so
     * `+40` followed by `-40` returns the original curve rather than something that
     * merely looks similar. Both fix 0, 0.5 and 1, which is what keeps a contrast
     * change from also being an exposure change.
     */
    fun contrastAt(x: Float, amount: Float): Float {
        if (amount == 0f) return x
        val c = x.coerceIn(0f, 1f)
        return if (amount > 0f) {
            val s = c * c * (3f - 2f * c)
            c + (s - c) * amount
        } else {
            val inv = 0.5f - sin(asin((1f - 2f * c).coerceIn(-1f, 1f)) / 3f)
            c + (inv - c) * (-amount)
        }
    }

    private fun smoothstep01(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun log2(v: Float) = (ln(v.toDouble()) / ln(2.0)).toFloat()

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    /**
     * Hue → (saturation multiplier, hue shift in degrees, luminance multiplier),
     * one entry per degree.
     *
     * Bands blend with a raised-cosine falloff rather than snapping at a boundary:
     * a hard edge in hue is visible as a contour across a sky or a cheek, which is
     * the classic giveaway of a naive colour mixer.
     */
    fun hueTable(rows: List<PhotoFilter.HueAdjust>): Array<FloatArray> {
        val satMul = FloatArray(360) { 1f }
        val hueShift = FloatArray(360)
        val lumMul = FloatArray(360) { 1f }
        if (rows.isEmpty()) return arrayOf(satMul, hueShift, lumMul)
        for (deg in 0 until 360) {
            for (row in rows) {
                val halfWidth = bandHalfWidth(row.band)
                val d = angularDistance(deg.toFloat(), row.band.centreDeg)
                if (d >= halfWidth) continue
                val w = 0.5f * (1f + kotlin.math.cos(Math.PI * d / halfWidth).toFloat())
                satMul[deg] += w * (row.saturation / 100f)
                hueShift[deg] += w * (row.hueShift / 100f) * 30f
                lumMul[deg] += w * (row.luminance / 100f) * 0.5f
            }
            satMul[deg] = satMul[deg].coerceAtLeast(0f)
            lumMul[deg] = lumMul[deg].coerceIn(0.1f, 2f)
        }
        return arrayOf(satMul, hueShift, lumMul)
    }

    /**
     * How far a band reaches: to its nearest neighbour's centre, no further.
     *
     * A single width for every band does not work, because Lightroom's centres are
     * unevenly spaced. At a flat 45° the YELLOW band (60°) reached down to 15° and
     * so covered skin, which sits around 25°. `clean_social` publishes 노란색 채도
     * **+100**; with that overlap it doubled the saturation of every face in the
     * frame and the look came out sunburnt. Clamping each band at its neighbour is
     * what keeps a yellow row about foliage and a skin row about skin.
     */
    private fun bandHalfWidth(band: HueBand): Float {
        var nearest = 180f
        for (other in HueBand.entries) {
            if (other == band) continue
            val d = angularDistance(band.centreDeg, other.centreDeg)
            if (d < nearest) nearest = d
        }
        return nearest.coerceAtLeast(30f)
    }

    private fun angularDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * How much exposure one full turn of the 노출 control is worth.
     *
     * ±2 EV, which is two stops each way — enough to rescue a badly metered phone
     * frame and not so much that the useful part of the control is squeezed into
     * the middle few percent.
     */
    const val MANUAL_EXPOSURE_EV = 2f

    /**
     * Renders [pixels] in place.
     *
     * Every scalar comes from [adjustments], which is what the user's sliders say.
     * [filter] contributes **only its colour-mixer rows** (색상 혼합) — those have
     * no slider, and they are what makes `soft_film`'s yellow/blue pair or
     * `clean_social`'s foliage distinct from a plain tone move.
     *
     * Note there is no [measure] call here. The one thing that needed measuring was
     * capping a preset's exposure, and that now happens once in [seedFrom] rather
     * than on every render.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        filter: PhotoFilter,
        adjustments: Adjustments = Adjustments.NEUTRAL,
    ) {
        if (pixels.isEmpty()) return

        val ev = (adjustments.exposure / 100f) * MANUAL_EXPOSURE_EV
        val tone = PhotoFilter.Tone(
            contrast = adjustments.contrast,
            highlights = adjustments.highlights,
            shadows = adjustments.shadows,
            whites = adjustments.whites,
            blacks = adjustments.blacks,
        )
        val temp = adjustments.warmth
        val tint = adjustments.tint
        val vibrance = adjustments.vibrance / 100f
        val saturation = adjustments.saturation / 100f

        // --- fold white balance + exposure into one table per channel ------------
        val gains = whiteBalanceGains(temp, tint)
        val expScale = 2f.pow(ev)
        val lutR = channelTable(gains[0] * expScale)
        val lutG = channelTable(gains[1] * expScale)
        val lutB = channelTable(gains[2] * expScale)

        val curve = toneCurve(tone)
        val curveScale = (TONE_SAMPLES - 1) / TONE_DOMAIN

        val hasHsl = filter.hsl.isNotEmpty()
        val hue = hueTable(filter.hsl)
        val hueSat = hue[0]
        val hueRot = hue[1]
        val hueLum = hue[2]

        // The 0.16 / 0.14 factors are what "100" means for effects that have no
        // Lightroom-equivalent unit: 100 grain is ±16% of range of noise, 100 fade
        // lifts the floor by 14%. Past those the image stops being a photograph.
        val grain = adjustments.grain / 100f * 0.16f
        val fade = adjustments.fade / 100f * 0.14f
        val vignette = (adjustments.vignette / 100f).coerceIn(-1f, 1f)

        val cx = width * 0.5f
        val cy = height * 0.5f
        val maxDist = kotlin.math.sqrt(cx * cx + cy * cy).coerceAtLeast(1f)

        for (i in pixels.indices) {
            val p = pixels[i]
            var r = lutR[(p shr 16) and 0xff]
            var g = lutG[(p shr 8) and 0xff]
            var b = lutB[p and 0xff]

            // --- tone, applied to luminance so hue survives ----------------------
            // Interpolated, not truncated: a 512-sample table over [0, 2] has bins
            // 0.004 wide, which is a whole 8-bit level. Nearest-sample lookup made
            // the identity filter shift pixels by ±1 and would have banded smooth
            // gradients — visible as rings in a sky, invisible in any single pixel.
            val l = LR * r + LG * g + LB * b
            if (l > 1e-4f) {
                val pos = (l * curveScale).coerceIn(0f, (TONE_SAMPLES - 1).toFloat())
                val lo = pos.toInt()
                val hi = min(lo + 1, TONE_SAMPLES - 1)
                val frac = pos - lo
                val mapped = curve[lo] + (curve[hi] - curve[lo]) * frac
                val scale = mapped / l
                r *= scale; g *= scale; b *= scale
            }

            // --- 생동감 / 채도 ----------------------------------------------------
            if (vibrance != 0f || saturation != 0f) {
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val current = if (mx > 1e-4f) (mx - mn) / mx else 0f
                // Vibrance is saturation weighted by how unsaturated the pixel
                // already is — the whole point is that it leaves skin alone.
                val f = 1f + saturation + vibrance * (1f - current)
                val lum = LR * r + LG * g + LB * b
                r = lum + (r - lum) * f
                g = lum + (g - lum) * f
                b = lum + (b - lum) * f
            }

            // --- 색상 혼합 ---------------------------------------------------------
            if (hasHsl) {
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val delta = mx - mn
                if (delta > 1e-4f) {
                    var deg = when (mx) {
                        r -> 60f * (((g - b) / delta) % 6f)
                        g -> 60f * ((b - r) / delta + 2f)
                        else -> 60f * ((r - g) / delta + 4f)
                    }
                    if (deg < 0f) deg += 360f
                    val bin = deg.toInt().coerceIn(0, 359)
                    val sMul = hueSat[bin]
                    val lMul = hueLum[bin]
                    val rot = hueRot[bin]
                    if (sMul != 1f || lMul != 1f) {
                        val lum = LR * r + LG * g + LB * b
                        r = (lum + (r - lum) * sMul) * lMul
                        g = (lum + (g - lum) * sMul) * lMul
                        b = (lum + (b - lum) * sMul) * lMul
                    }
                    if (rot != 0f) {
                        val rotated = rotateHue(r, g, b, rot)
                        r = rotated[0]; g = rotated[1]; b = rotated[2]
                    }
                }
            }

            // --- fade / grain / vignette -----------------------------------------
            if (fade != 0f) {
                r = fade + r * (1f - fade)
                g = fade + g * (1f - fade)
                b = fade + b * (1f - fade)
            }
            if (grain != 0f) {
                val n = grainAt(i)
                r += n * grain; g += n * grain; b += n * grain
            }
            if (vignette != 0f) {
                val x = (i % width) - cx
                val y = (i / width) - cy
                val d = kotlin.math.sqrt(x * x + y * y) / maxDist
                val v = 1f + vignette * smoothstep01((d - 0.45f) / 0.55f)
                r *= v; g *= v; b *= v
            }

            pixels[i] = (p and -0x1000000) or
                (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
        }
    }

    /** sRGB byte → linear → gain → sRGB, left **unclamped** so the tone curve's
     *  highlight recovery still has something above 1.0 to recover. */
    private fun channelTable(gain: Float) = FloatArray(256) { v ->
        linearToSrgbUnclamped(srgbToLinear(v / 255f) * gain)
    }

    private fun linearToSrgbUnclamped(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f

    /**
     * Deterministic per-pixel noise in `[-0.5, 0.5]`.
     *
     * Deterministic so the preview and the saved file get the same grain, and so a
     * test can assert on it. A `Random` here would make every re-render of the same
     * photo a different image.
     */
    fun grainAt(index: Int): Float {
        var h = index * -0x61c88647
        h = h xor (h ushr 15)
        h *= -0x7ee3623b
        h = h xor (h ushr 13)
        return ((h ushr 8) and 0xff) / 255f - 0.5f
    }

    /** Rotates a colour's hue by [deg], preserving luminance and chroma length. */
    private fun rotateHue(r: Float, g: Float, b: Float, deg: Float): FloatArray {
        val rad = deg * Math.PI.toFloat() / 180f
        val c = kotlin.math.cos(rad)
        val s = sin(rad)
        // YIQ-style rotation: cheap, and exact enough for the ±30° a colour mixer
        // row can ask for.
        val m0 = LR + c * (1 - LR) - s * LR
        val m1 = LG - c * LG - s * LG
        val m2 = LB - c * LB + s * (1 - LB)
        val m3 = LR - c * LR + s * 0.143f
        val m4 = LG + c * (1 - LG) + s * 0.140f
        val m5 = LB - c * LB - s * 0.283f
        val m6 = LR - c * LR - s * (1 - LR)
        val m7 = LG - c * LG + s * LG
        val m8 = LB + c * (1 - LB) + s * LB
        return floatArrayOf(
            r * m0 + g * m1 + b * m2,
            r * m3 + g * m4 + b * m5,
            r * m6 + g * m7 + b * m8,
        )
    }

    private fun to8(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
}
