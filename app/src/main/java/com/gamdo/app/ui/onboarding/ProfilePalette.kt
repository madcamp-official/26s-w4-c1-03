package com.gamdo.app.ui.onboarding

import kotlin.math.pow

/**
 * The three swatches shown under "당신의 감도를 저장했어요" (§6-2), derived from the
 * profile the user's picks actually produced.
 *
 * They used to be three hard-coded colours. Under a heading that says *your*
 * palette, that is a claim the screen cannot support — every user saw the same
 * sage/beige/cream no matter which photos they chose, which is the sort of thing
 * AGENTS.md §7-6 rules out and the sort of thing a demo gets asked about.
 *
 * Pure Kotlin (`android.*` import 0) so the mapping is JVM-testable; the screen
 * wraps the packed ARGB values in Compose `Color`.
 */
object ProfilePalette {

    /**
     * Swatches for a profile, darkest first.
     *
     * @param brightness profile brightness, 0..1.
     * @param colorTemperatureK profile colour temperature in Kelvin.
     * @param saturation profile saturation, 0..1 — how far the swatches sit from
     *   neutral grey. A user who picked uniformly desaturated photos gets a grey
     *   palette, which is the honest answer rather than a decorative one.
     */
    fun swatches(brightness: Float, colorTemperatureK: Float, saturation: Float): List<Int> {
        val tint = kelvinTint(colorTemperatureK)
        val sat = saturation.coerceIn(0f, 1f)
        // Lightness band. Anchored on the profile but kept inside a readable
        // window: these are drawn on charcoal, and a genuinely dark profile would
        // otherwise produce three swatches nobody can see — which communicates
        // less than a wrong colour would.
        val centre = (0.42f + brightness.coerceIn(0f, 1f) * 0.40f)
        return listOf(centre - 0.16f, centre, centre + 0.16f).map { level ->
            pack(tint, level.coerceIn(0.14f, 0.96f), sat)
        }
    }

    /**
     * Normalized RGB for a Planckian radiator at [kelvin], via Tanner Helland's
     * widely-used approximation. Scaled so the largest channel is 1, because only
     * the *hue* is wanted here — the lightness comes from the profile.
     */
    private fun kelvinTint(kelvin: Float): Triple<Float, Float, Float> {
        val k = kelvin.coerceIn(1500f, 12000f) / 100f
        val r = if (k <= 66f) 1f else (329.698727446f * (k - 60f).pow(-0.1332047592f) / 255f)
        val g = if (k <= 66f) {
            (99.4708025861f * kotlin.math.ln(k.toDouble()).toFloat() - 161.1195681661f) / 255f
        } else {
            288.1221695283f * (k - 60f).pow(-0.0755148492f) / 255f
        }
        val b = when {
            k >= 66f -> 1f
            k <= 19f -> 0f
            else -> (138.5177312231f * kotlin.math.ln((k - 10f).toDouble()).toFloat() - 305.0447927307f) / 255f
        }
        val rc = r.coerceIn(0f, 1f)
        val gc = g.coerceIn(0f, 1f)
        val bc = b.coerceIn(0f, 1f)
        val peak = maxOf(rc, gc, bc).coerceAtLeast(1e-3f)
        var nr = rc / peak
        var ng = gc / peak
        var nb = bc / peak

        // Chroma gain. A Planckian tint at daylight is (1.00, 0.93, 0.86) — nearly
        // neutral — so a physically faithful swatch set reads as three greys and
        // tells the user nothing about their own choices. Pushing each channel away
        // from the tint's own mean exaggerates the hue without changing its
        // direction, so warm still reads warmer than cool and the ordering the
        // profile expresses survives. This is a display decision, not a measurement:
        // the numbers behind the recommendation are untouched.
        val mean = (nr + ng + nb) / 3f
        nr = (mean + (nr - mean) * CHROMA_GAIN).coerceIn(0f, 1f)
        ng = (mean + (ng - mean) * CHROMA_GAIN).coerceIn(0f, 1f)
        nb = (mean + (nb - mean) * CHROMA_GAIN).coerceIn(0f, 1f)
        return Triple(nr, ng, nb)
    }

    private const val CHROMA_GAIN = 4f

    /** Packs a tint at a given lightness and saturation into 0xAARRGGBB. */
    private fun pack(tint: Triple<Float, Float, Float>, level: Float, saturation: Float): Int {
        fun ch(v: Float): Int {
            // Mix toward neutral by (1 - saturation), then scale to the level.
            val mixed = v * saturation + (1f - saturation)
            return (mixed * level * 255f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(tint.first) shl 16) or (ch(tint.second) shl 8) or ch(tint.third)
    }
}
