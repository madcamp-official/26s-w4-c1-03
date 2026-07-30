package com.gamdo.app.ui.onboarding

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6-2: "당신의 감도" has to depend on what the user picked, and it has to be able to
 * say what they picked.
 *
 * ## The defect these were extended to catch
 *
 * The palette used to be built from `colorTemperature` through a Planckian
 * approximation — a single orange-to-blue axis. Every test in the original file
 * passed: the swatches did change with the profile, warm was redder than cool, zero
 * saturation was grey. What none of them asked was whether the *set of reachable
 * colours* covered what a user could pick. It did not. Sweeping 1500..12000 K,
 * `max(G − max(R, B)) = 0.0000`: green was not merely unlikely, it was impossible.
 *
 * The owner picked the blue-green photographs and got grey (2026-07-30).
 *
 * So the tests below assert reachability — a green selection must produce a green
 * swatch, and every hue direction must survive the round trip — not just variation.
 * A one-axis implementation cannot satisfy them.
 */
class ProfilePaletteTest {

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun luma(c: Int) = 0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)

    private fun tone(brightness: Float, a: Float, bStar: Float) = CardTone(brightness, a, bStar)

    /** Real measurements from `assets/cards.json`, so these track the shipped deck. */
    private val greenCafe = tone(0.35f, -16.47f, 18.87f) // card_05
    private val blueSky = tone(0.45f, -1.13f, -16.96f) // card_09
    private val lakeAndGrass = tone(0.47f, -5.44f, -1.02f) // card_16
    private val warmLamp = tone(0.25f, -2.39f, 16.50f) // card_12
    private val monochrome = tone(0.28f, 0.04f, 0.00f) // card_10
    private val warmPortrait = tone(0.61f, 17.77f, 16.95f) // card_01

    // ---------------------------------------------------------------- reachability

    /**
     * The defect, stated as a test.
     *
     * These are the owner's own picks — the blue-green photographs of the bundled
     * deck. Under the Planckian implementation this produced `#8A887F`, a warm grey.
     */
    @Test
    fun `a green selection produces a green swatch`() {
        val swatches = ProfilePalette.swatches(listOf(greenCafe, blueSky, lakeAndGrass))
        swatches.forEach { c ->
            assertTrue(
                "expected green to dominate, got #%02X%02X%02X".format(r(c), g(c), b(c)),
                g(c) > r(c) && g(c) > b(c),
            )
        }
    }

    /** The same property on the other diagonal, so nothing passes by leaning green. */
    @Test
    fun `a blue selection produces a blue swatch`() {
        ProfilePalette.swatches(listOf(blueSky)).forEach { c ->
            assertTrue(
                "expected blue to dominate, got #%02X%02X%02X".format(r(c), g(c), b(c)),
                b(c) > r(c) && b(c) > g(c),
            )
        }
    }

    @Test
    fun `a warm selection produces a warm swatch`() {
        ProfilePalette.swatches(listOf(warmPortrait)).forEach { c ->
            assertTrue(
                "expected red to dominate, got #%02X%02X%02X".format(r(c), g(c), b(c)),
                r(c) > g(c) && r(c) > b(c),
            )
        }
    }

    /**
     * The general statement: the palette's colour space is two-dimensional.
     *
     * A hue that no input can reach is a preference the screen cannot report. Walking
     * the full circle at a chroma any real selection can produce, each of the three
     * channels must take a turn dominating — which is exactly what a Planckian curve
     * cannot do, since it never lets green win.
     */
    @Test
    fun `every hue direction survives the round trip`() {
        val dominants = mutableSetOf<String>()
        for (degrees in 0 until 360 step 5) {
            val radians = Math.toRadians(degrees.toDouble()).toFloat()
            val chroma = 12f
            val swatch = ProfilePalette.swatches(
                tone(0.45f, chroma * cos(radians), chroma * sin(radians)),
            )[1]
            val channels = listOf("R" to r(swatch), "G" to g(swatch), "B" to b(swatch))
            dominants += channels.maxBy { it.second }.first
        }
        assertEquals(
            "one or more channels can never dominate, so those hues are unreportable",
            setOf("R", "G", "B"),
            dominants,
        )
    }

    /**
     * The hue a selection asked for is the hue that gets drawn.
     *
     * Measured by inverting the packed sRGB back to CIELAB rather than by eyeballing
     * channel order, because the interesting failures are 15–30° drifts — green
     * arriving as yellow-green — and a channel-order check cannot see those. 8-bit
     * quantisation alone costs up to 6.5°, so the bound is set just above it.
     */
    @Test
    fun `the rendered hue matches the hue that was asked for`() {
        for (degrees in 0 until 360 step 15) {
            for (chroma in listOf(6f, 10f, 16f, 22f)) {
                val radians = Math.toRadians(degrees.toDouble()).toFloat()
                val requested = tone(0.5f, chroma * cos(radians), chroma * sin(radians))
                val delta = hueDegrees(ProfilePalette.swatches(requested)[1]) - degrees
                assertTrue(
                    "hue $degrees° at chroma $chroma came back " +
                        "${"%.1f".format(wrap(delta))}° away",
                    abs(wrap(delta)) <= 8.0,
                )
            }
        }
    }

    /**
     * Hue survives a colour the screen cannot show at that lightness.
     *
     * This is the guard on gamut handling specifically, and it has to push on
     * **lightness**, not chroma: `swatches()` caps chroma, so asking for a more and
     * more colourful selection stops changing anything past the cap. What does go out
     * of range is a saturated colour at the ends of the lightness band — a very dark
     * or very bright selection — and both ends are reachable (card_15 alone averages
     * to brightness 0.06, which puts a swatch at L\* 24).
     *
     * There, clamping the channels — the obvious one-liner — keeps the colour in range
     * by pinning whichever channel overflows, and that drags the hue: **29.0°** off
     * the requested angle, against **1.7°** for reducing chroma along a fixed hue.
     * A 29° shift is the original defect in miniature, a green selection rendered as
     * something else.
     */
    @Test
    fun `hue survives a colour too saturated to render at that lightness`() {
        // card_05's own measured chroma. Above the cap, so the rendering is the same
        // as for any stronger selection — this is what one green photograph produces.
        val chroma = 25f
        for (brightness in listOf(0f, 0.06f, 0.35f, 0.75f, 1f)) {
            for (degrees in 0 until 360 step 15) {
                val radians = Math.toRadians(degrees.toDouble()).toFloat()
                val requested = tone(brightness, chroma * cos(radians), chroma * sin(radians))
                ProfilePalette.swatches(requested).forEach { swatch ->
                    val error = wrap(hueDegrees(swatch) - degrees)
                    assertTrue(
                        "at brightness $brightness, hue $degrees° came back " +
                            "${"%.1f".format(error)}° away — the gamut is being handled by " +
                            "clamping channels rather than by giving up saturation",
                        abs(error) <= 8.0,
                    )
                }
            }
        }
    }

    private fun wrap(degrees: Double) = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** CIELAB hue angle of a packed sRGB colour, in degrees. */
    private fun hueDegrees(packed: Int): Double {
        fun linear(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }

        val rl = linear(r(packed))
        val gl = linear(g(packed))
        val bl = linear(b(packed))
        val x = (0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl) / 0.95047
        val y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl
        val z = (0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl) / 1.08883
        fun f(t: Double): Double {
            val d = 6.0 / 29.0
            return if (t > d * d * d) Math.cbrt(t) else t / (3 * d * d) + 4.0 / 29.0
        }
        return Math.toDegrees(atan2(200.0 * (f(y) - f(z)), 500.0 * (f(x) - f(y))))
    }

    // ------------------------------------------------------------------- averaging

    /**
     * Opposites cancel. This is the one grey the screen is entitled to draw: the user
     * picked photographs that disagree, and saying so is honest.
     */
    @Test
    fun `opposing picks average to neutral`() {
        val mirrored = tone(greenCafe.brightness, -greenCafe.colorA, -greenCafe.colorB)
        ProfilePalette.swatches(listOf(greenCafe, mirrored)).forEach { c ->
            assertTrue("r=${r(c)} g=${g(c)} b=${b(c)} should be neutral", abs(r(c) - g(c)) <= 2)
            assertTrue("r=${r(c)} g=${g(c)} b=${b(c)} should be neutral", abs(g(c) - b(c)) <= 2)
        }
    }

    @Test
    fun `a selection with no colour in it is grey, and that is the honest answer`() {
        ProfilePalette.swatches(listOf(monochrome, monochrome)).forEach {
            assertTrue("r≈g", abs(r(it) - g(it)) <= 1)
            assertTrue("g≈b", abs(g(it) - b(it)) <= 1)
        }
    }

    /**
     * A black-and-white photograph states no hue preference, so it must not dilute one.
     *
     * Averaging it in as neutral grey — the obvious implementation — records "no
     * opinion" as "opinion: grey", and five picks with one monochrome among them would
     * come back 20% washed out for no reason the user could name.
     */
    @Test
    fun `a colourless pick does not wash out a colourful one`() {
        val alone = ProfilePalette.average(listOf(greenCafe))
        val withMono = ProfilePalette.average(listOf(greenCafe, monochrome, monochrome))
        val chromaAlone = hypot(alone.colorA, alone.colorB)
        val chromaWith = hypot(withMono.colorA, withMono.colorB)
        assertTrue(
            "chroma fell from $chromaAlone to $chromaWith by adding two grey photographs",
            chromaWith > chromaAlone * 0.97f,
        )
    }

    /**
     * The average can dilute or cancel, but never invent. No selection may produce a
     * colour more saturated than the most colourful photograph in it.
     */
    @Test
    fun `the average never exceeds the strongest pick`() {
        val selections = listOf(
            listOf(greenCafe, blueSky, lakeAndGrass),
            listOf(warmLamp, warmPortrait),
            listOf(greenCafe, warmPortrait, monochrome, blueSky, warmLamp),
            listOf(monochrome),
        )
        for (selection in selections) {
            val strongest = selection.maxOf { hypot(it.colorA, it.colorB) }
            val averaged = ProfilePalette.average(selection)
            assertTrue(
                "averaged chroma ${hypot(averaged.colorA, averaged.colorB)} exceeds the " +
                    "strongest pick's $strongest",
                hypot(averaged.colorA, averaged.colorB) <= strongest + 1e-3f,
            )
        }
    }

    @Test
    fun `an empty selection is handled instead of dividing by zero`() {
        val swatches = ProfilePalette.swatches(emptyList<CardTone>())
        assertEquals(3, swatches.size)
        swatches.forEach {
            assertTrue(r(it) in 0..255 && g(it) in 0..255 && b(it) in 0..255)
            assertTrue("r≈g", abs(r(it) - g(it)) <= 1)
        }
    }

    // ------------------------------------------------------------ shape and safety

    @Test
    fun `three swatches, opaque, ordered dark to light`() {
        val s = ProfilePalette.swatches(listOf(greenCafe, warmLamp))
        assertEquals(3, s.size)
        s.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
        assertTrue(luma(s[0]) < luma(s[1]))
        assertTrue(luma(s[1]) < luma(s[2]))
    }

    @Test
    fun `a brighter selection yields a brighter palette`() {
        val dark = ProfilePalette.swatches(tone(0.15f, -8f, 4f))[1]
        val light = ProfilePalette.swatches(tone(0.85f, -8f, 4f))[1]
        assertTrue(luma(dark) < luma(light))
    }

    /**
     * The colour has to be *visible*, not merely correct. A faithful rendering of a
     * photograph's mean colour is nearly grey — that was half of what the owner saw —
     * so the swatch exaggerates chroma. This pins that it stays exaggerated.
     */
    @Test
    fun `a real single-photograph selection is visibly coloured`() {
        listOf(greenCafe, blueSky, warmPortrait, warmLamp).forEach { card ->
            val c = ProfilePalette.swatches(listOf(card))[1]
            val spread = maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c))
            assertTrue(
                "#%02X%02X%02X has a channel spread of only $spread".format(r(c), g(c), b(c)),
                spread >= 24,
            )
        }
    }

    @Test
    fun `a more colourful selection moves further from grey`() {
        val faint = ProfilePalette.swatches(tone(0.5f, -2f, 1f))[1]
        val strong = ProfilePalette.swatches(tone(0.5f, -20f, 10f))[1]
        val spread = { c: Int -> maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c)) }
        assertTrue(spread(strong) > spread(faint))
    }

    /** The property the three hard-coded constants violated. */
    @Test
    fun `different selections produce different palettes`() {
        val a = ProfilePalette.swatches(listOf(greenCafe, blueSky))
        val b = ProfilePalette.swatches(listOf(warmPortrait, warmLamp))
        assertNotEquals(a, b)
        a.indices.forEach { assertNotEquals("swatch $it must differ", a[it], b[it]) }
    }

    @Test
    fun `stays inside a band that is visible on charcoal`() {
        for (brightness in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            ProfilePalette.swatches(tone(brightness, -12f, 6f)).forEach {
                assertTrue("too dark to see on Ink900", luma(it) > 25.0)
                assertTrue("channel out of range", r(it) in 0..255 && g(it) in 0..255 && b(it) in 0..255)
            }
        }
    }

    @Test
    fun `non-physical measurements are clamped rather than overflowing`() {
        val extremes = listOf(0f, -200f, 200f, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (a in extremes) {
            for (bStar in extremes) {
                for (brightness in listOf(-5f, 0f, 0.5f, 1f, 9f)) {
                    ProfilePalette.swatches(tone(brightness, a, bStar)).forEach {
                        assertTrue(
                            "out of range for a=$a b=$bStar brightness=$brightness",
                            r(it) in 0..255 && g(it) in 0..255 && b(it) in 0..255,
                        )
                        assertEquals(0xFF, (it ushr 24) and 0xFF)
                    }
                }
            }
        }
    }
}
