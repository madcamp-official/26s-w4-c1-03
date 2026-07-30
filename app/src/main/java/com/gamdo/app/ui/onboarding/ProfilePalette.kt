package com.gamdo.app.ui.onboarding

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One bundled card's measured tone, as the palette needs it.
 *
 * @param brightness mean L\* / 100 of the image, 0..1 — the same number `cards.json`
 *   already carries as `brightness`.
 * @param colorA mean CIELAB a\* of the image: negative green, positive red.
 * @param colorB mean CIELAB b\* of the image: negative blue, positive yellow.
 */
data class CardTone(val brightness: Float, val colorA: Float, val colorB: Float)

/**
 * The three swatches shown under "당신의 감도를 저장했어요" (§6-2), derived from the
 * colour the user's picks actually contain.
 *
 * ## Why this is not built from colour temperature
 *
 * It used to be. `swatches()` took `colorTemperatureK` and ran it through a Planckian
 * (black-body) approximation, which traces a curve from orange through white to blue —
 * **one axis.** Green is not on it. Sweeping the whole domain, 1500 K to 12000 K, the
 * best the tint could do was `max(G − max(R, B)) = 0.0000`, at 6400 K, where green
 * merely ties red. No temperature could make a green swatch, so no selection could
 * either.
 *
 * The owner reported it on 2026-07-30: picked the blue-green photographs, got grey.
 * Reproduced exactly — cards 05, 09 and 16 average to 6040 K and produced
 * `#62605A / #8A887F / #B3B1A5`. Three greys, as reported.
 *
 * The deeper error is that **colour temperature is not the colour of a photograph.**
 * A forest has no meaningful CCT; the measurement pushes anything off the Planckian
 * locus toward neutral, and averaging several such numbers pushes it further. So the
 * axis carried almost no signal and could not have expressed it if it had.
 *
 * ## What it is built from instead
 *
 * Each card now carries its own measured colour in CIELAB opponent coordinates
 * (`cards.json` `colorA`/`colorB`; see `docs/감도_카드_에셋_라이선스_기록.md` for the
 * measurement definition). The selection is averaged **in that opponent space**, which
 * gives the honest behaviour for free:
 *
 * - agree on a hue → the average keeps it, and the swatch is saturated;
 * - pick green *and* red → a\* cancels and the swatch goes neutral. That grey is a
 *   true statement about the selection, unlike the old one.
 *
 * Cards are weighted by their own chroma when averaging. A black-and-white photograph
 * (card_10 measures C\* = 0.04) expresses no hue preference, and averaging it in as
 * "grey" would record no opinion as an opinion. Cancellation survives the weighting
 * intact: two equally colourful opposites still sum to zero.
 *
 * Pure Kotlin (`android.*` import 0) so the mapping is JVM-testable; the screen wraps
 * the packed ARGB values in Compose `Color`.
 */
object ProfilePalette {

    /** Swatches for a selection of cards, darkest first. */
    fun swatches(tones: List<CardTone>): List<Int> = swatches(average(tones))

    /**
     * Swatches for one already-averaged tone, darkest first.
     *
     * Lightness is a band anchored on the profile but kept inside a readable window:
     * these are drawn on charcoal, and a genuinely dark selection would otherwise
     * produce three swatches nobody can see, which communicates less than a wrong
     * colour would.
     */
    fun swatches(tone: CardTone): List<Int> {
        val centre = L_BASE + tone.brightness.coerceIn(0f, 1f) * L_SPAN
        val chroma = (hypot(tone.colorA, tone.colorB) * CHROMA_GAIN).coerceAtMost(CHROMA_CAP)
        val hue = atan2(tone.colorB, tone.colorA)
        return listOf(-L_STEP, 0f, L_STEP).map { offset ->
            pack((centre + offset).coerceIn(L_MIN, L_MAX), chroma, hue)
        }
    }

    /**
     * The selection's tone: brightness averaged plainly, hue averaged in opponent space
     * with each card weighted by its own chroma.
     *
     * The weighted mean is bounded by the largest chroma in the selection (|a| ≤ C for
     * every card), so it can dilute or cancel but never invent colour that no picked
     * photograph contained.
     */
    fun average(tones: List<CardTone>): CardTone {
        if (tones.isEmpty()) return CardTone(NEUTRAL_BRIGHTNESS, 0f, 0f)
        val brightness = tones.map { it.brightness.toDouble() }.average().toFloat()
        var weightedA = 0f
        var weightedB = 0f
        var totalWeight = 0f
        for (tone in tones) {
            val weight = hypot(tone.colorA, tone.colorB)
            weightedA += tone.colorA * weight
            weightedB += tone.colorB * weight
            totalWeight += weight
        }
        // Every pick was colourless. Dividing here would be 0/0; a grey palette is
        // also the right answer, so say it directly.
        if (totalWeight < CHROMA_EPSILON) return CardTone(brightness, 0f, 0f)
        return CardTone(brightness, weightedA / totalWeight, weightedB / totalWeight)
    }

    // A faithful rendering of a photograph's average colour is very close to grey —
    // most pixels in most photographs are. Scaling chroma exaggerates how colourful
    // the swatch is without moving its hue, so the direction the selection expresses
    // survives and the ordering between selections is unchanged. This is a display
    // decision, not a measurement: the numbers behind the recommendation are untouched.
    private const val CHROMA_GAIN = 2.2f

    // Past this, gamut mapping starts doing the work instead of the measurement, and
    // the swatches read as poster paint rather than as a photograph's colour.
    private const val CHROMA_CAP = 38f

    private const val L_BASE = 36f
    private const val L_SPAN = 38f
    private const val L_STEP = 14f
    private const val L_MIN = 24f
    private const val L_MAX = 88f

    private const val NEUTRAL_BRIGHTNESS = 0.5f
    private const val CHROMA_EPSILON = 1e-3f

    /**
     * Packs a CIELAB colour into 0xAARRGGBB, reducing chroma until it fits sRGB.
     *
     * Clamping the channels instead would be shorter and would shift the hue: an
     * out-of-gamut green clips its red channel up to 0 and comes back yellower than
     * the photographs it came from. Scaling chroma along a fixed hue angle keeps the
     * direction and only gives up saturation, which is the part the user cannot check.
     */
    private fun pack(lightness: Float, chroma: Float, hue: Float): Int {
        val cosH = cos(hue)
        val sinH = sin(hue)
        var scale = 1f
        if (!inGamut(labToRgb(lightness, chroma * cosH, chroma * sinH))) {
            var low = 0f
            var high = 1f
            repeat(GAMUT_STEPS) {
                val mid = (low + high) / 2f
                if (inGamut(labToRgb(lightness, mid * chroma * cosH, mid * chroma * sinH))) {
                    low = mid
                } else {
                    high = mid
                }
            }
            scale = low
        }
        val rgb = labToRgb(lightness, scale * chroma * cosH, scale * chroma * sinH)
        return (0xFF shl 24) or (channel(rgb[0]) shl 16) or (channel(rgb[1]) shl 8) or channel(rgb[2])
    }

    private const val GAMUT_STEPS = 20
    private const val GAMUT_SLACK = 5e-4f

    private fun inGamut(rgb: FloatArray): Boolean =
        rgb.all { it >= -GAMUT_SLACK && it <= 1f + GAMUT_SLACK }

    private fun channel(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

    /** CIELAB (D65) to non-linear sRGB, un-clamped so [inGamut] can see the overshoot. */
    private fun labToRgb(lightness: Float, a: Float, b: Float): FloatArray {
        val fy = (lightness + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        val x = fInverse(fx) * D65_X
        val y = fInverse(fy)
        val z = fInverse(fz) * D65_Z
        return floatArrayOf(
            gamma(3.2404542f * x - 1.5371385f * y - 0.4985314f * z),
            gamma(-0.9692660f * x + 1.8760108f * y + 0.0415560f * z),
            gamma(0.0556434f * x - 0.2040259f * y + 1.0572252f * z),
        )
    }

    private const val D65_X = 0.95047f
    private const val D65_Z = 1.08883f
    private const val LAB_DELTA = 6f / 29f

    private fun fInverse(t: Float): Float =
        if (t > LAB_DELTA) t * t * t else 3f * LAB_DELTA * LAB_DELTA * (t - 4f / 29f)

    private fun gamma(v: Float): Float =
        if (v <= 0.0031308f) 12.92f * v else 1.055f * v.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f
}
