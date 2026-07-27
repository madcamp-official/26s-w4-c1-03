package com.gamdo.app.edit

import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.StylePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the two §4-1 style effects a colour matrix cannot express.
 *
 * grain and vignette are the parts of the style stage that would normally live
 * behind a `RadialGradient` or a `BitmapShader` and therefore never run in CI. They
 * are written as array operations precisely so these assertions can exist.
 */
class PixelEffectsTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun alpha(p: Int) = (p ushr 24) and 0xFF

    private fun flatBand(width: Int, rows: Int, level: Int): IntArray =
        IntArray(width * rows) { argb(255, level, level, level) }

    // ---- vignette -----------------------------------------------------------

    @Test
    fun `the frame centre is untouched`() {
        assertEquals(1f, vignetteScale(0f, 0f, 1f), 1e-6f)
    }

    @Test
    fun `zero amount is an exact no-op`() {
        assertEquals(1f, vignetteScale(1f, 1f, 0f), 1e-6f)
        assertEquals(1f, vignetteScale(0.7f, -0.9f, 0f), 1e-6f)
    }

    @Test
    fun `the corner is the darkest point`() {
        val corner = vignetteScale(1f, 1f, 1f)
        val edge = vignetteScale(1f, 0f, 1f)
        assertTrue("corner $corner should be darker than edge $edge", corner < edge)
        assertEquals(1f - VIGNETTE_MAX_DARKENING, corner, 1e-4f)
    }

    @Test
    fun `darkening increases monotonically with radius`() {
        var previous = 1f
        for (step in 0..20) {
            val t = step / 20f
            val scale = vignetteScale(t, t, 1f)
            assertTrue("scale rose at radius $t", scale <= previous + 1e-6f)
            previous = scale
        }
    }

    @Test
    fun `vignette never darkens to black or brightens past the source`() {
        for (step in 0..20) {
            val t = step / 20f
            val scale = vignetteScale(t, t, 1f)
            assertTrue(scale in 0.3f..1f)
        }
    }

    @Test
    fun `vignette darkens band corners and leaves the middle alone`() {
        val width = 64
        val height = 64
        val pixels = flatBand(width, height, 200)
        applyVignetteBand(pixels, width, height, bandTop = 0, rows = height, amount = 1f)

        val centre = pixels[(height / 2) * width + width / 2]
        val topLeft = pixels[0]
        assertEquals(200, red(centre))
        assertTrue("corner ${red(topLeft)} should be darker than 200", red(topLeft) < 200)
    }

    @Test
    fun `a band knows where it sits in the frame`() {
        // The same row rendered as its own band and as part of a whole-frame pass must
        // come out identical, or each band would grow its own little vignette.
        val width = 32
        val height = 64
        val whole = flatBand(width, height, 180)
        applyVignetteBand(whole, width, height, bandTop = 0, rows = height, amount = 0.8f)

        val second = flatBand(width, 32, 180)
        applyVignetteBand(second, width, height, bandTop = 32, rows = 32, amount = 0.8f)

        for (i in 0 until width * 32) {
            assertEquals("row ${i / width} differs", whole[32 * width + i], second[i])
        }
    }

    @Test
    fun `vignette preserves alpha`() {
        val pixels = intArrayOf(argb(128, 255, 255, 255))
        applyVignetteBand(pixels, 1, 1, bandTop = 0, rows = 1, amount = 1f)
        assertEquals(128, alpha(pixels[0]))
    }

    // ---- grain --------------------------------------------------------------

    @Test
    fun `grain is deterministic`() {
        // A save re-renders from scratch; non-reproducible grain would make the saved
        // file differ from what the user approved.
        assertEquals(grainNoise(17, 42, DEFAULT_GRAIN_SEED), grainNoise(17, 42, DEFAULT_GRAIN_SEED))
        assertEquals(grainNoise(0, 0, 1), grainNoise(0, 0, 1))
    }

    @Test
    fun `grain varies across neighbouring pixels`() {
        val values = (0 until 64).map { grainNoise(it, 7, DEFAULT_GRAIN_SEED) }.toSet()
        assertTrue("only ${values.size} distinct values in 64 pixels", values.size > 20)
    }

    @Test
    fun `grain stays inside one signed byte`() {
        for (x in 0 until 200) {
            for (y in 0 until 20) {
                val n = grainNoise(x, y, DEFAULT_GRAIN_SEED)
                assertTrue("noise $n out of range at ($x,$y)", n in -128..127)
            }
        }
    }

    @Test
    fun `grain is roughly centred on zero`() {
        var sum = 0L
        var count = 0
        for (x in 0 until 200) {
            for (y in 0 until 200) {
                sum += grainNoise(x, y, DEFAULT_GRAIN_SEED)
                count++
            }
        }
        val mean = sum.toDouble() / count
        // A biased hash would show up as an overall brightness shift.
        assertTrue("grain mean $mean is not near zero", kotlin.math.abs(mean) < 4.0)
    }

    @Test
    fun `zero grain leaves the band byte-identical`() {
        val pixels = flatBand(16, 4, 120)
        val before = pixels.copyOf()
        applyGrainBand(pixels, 16, bandTop = 0, rows = 4, amount = 0f)
        assertArrayEqualsInt(before, pixels)
    }

    @Test
    fun `grain perturbs a flat band`() {
        val pixels = flatBand(32, 8, 120)
        applyGrainBand(pixels, 32, bandTop = 0, rows = 8, amount = 1f)
        val distinct = pixels.toSet()
        assertTrue("flat band stayed flat under grain", distinct.size > 1)
    }

    @Test
    fun `grain is achromatic`() {
        val pixels = flatBand(32, 4, 120)
        applyGrainBand(pixels, 32, bandTop = 0, rows = 4, amount = 1f)
        // Equal deltas on R, G and B: colour speckle would read as sensor noise, not
        // as film.
        pixels.forEach { p ->
            assertEquals(red(p), green(p))
            assertEquals(green(p), blue(p))
        }
    }

    @Test
    fun `grain respects its amplitude ceiling`() {
        val base = 120
        val pixels = flatBand(64, 8, base)
        applyGrainBand(pixels, 64, bandTop = 0, rows = 8, amount = 1f)
        pixels.forEach { p ->
            assertTrue(
                "grain moved ${red(p)} more than $GRAIN_MAX_AMPLITUDE from $base",
                kotlin.math.abs(red(p) - base) <= GRAIN_MAX_AMPLITUDE,
            )
        }
    }

    @Test
    fun `grain clamps instead of wrapping`() {
        val dark = flatBand(64, 4, 2)
        applyGrainBand(dark, 64, bandTop = 0, rows = 4, amount = 1f)
        dark.forEach { assertTrue("channel ${red(it)} wrapped", red(it) in 0..255) }

        val bright = flatBand(64, 4, 253)
        applyGrainBand(bright, 64, bandTop = 0, rows = 4, amount = 1f)
        bright.forEach { assertTrue("channel ${red(it)} wrapped", red(it) in 0..255) }
    }

    @Test
    fun `grain preserves alpha`() {
        val pixels = IntArray(32) { argb(77, 120, 120, 120) }
        applyGrainBand(pixels, 32, bandTop = 0, rows = 1, amount = 1f)
        pixels.forEach { assertEquals(77, alpha(it)) }
    }

    @Test
    fun `different seeds give different grain`() {
        assertNotEquals(grainNoise(5, 5, 1), grainNoise(5, 5, 2))
    }

    // ---- software pass gating ----------------------------------------------

    @Test
    fun `a plan with no curve, grain or vignette skips the software pass`() {
        val plan = planFor(grain = 0.0, vignette = 0.0, shadowClip = 0f, highlightClip = 0f)
        assertTrue(isIdentityLut(plan.toneLut))
        assertEquals(false, needsSoftwarePass(plan))
    }

    @Test
    fun `grain alone is enough to require the software pass`() {
        val plan = planFor(grain = 0.4, vignette = 0.0, shadowClip = 0f, highlightClip = 0f)
        assertTrue(needsSoftwarePass(plan))
    }

    @Test
    fun `a clipped photo requires the software pass for its tone curve`() {
        val plan = planFor(grain = 0.0, vignette = 0.0, shadowClip = 0.3f, highlightClip = 0f)
        assertTrue(needsSoftwarePass(plan))
    }

    @Test
    fun `the software pass applies vignette after grain`() {
        // Grain that stays bright inside a darkened corner is the giveaway of a
        // stacked filter, so ordering is behaviour, not taste.
        val width = 48
        val height = 48
        val plan = planFor(grain = 1.0, vignette = 1.0, shadowClip = 0f, highlightClip = 0f)

        val pixels = flatBand(width, height, 200)
        applySoftwareBand(pixels, plan, width, height, bandTop = 0, rows = height)

        val cornerMax = (0 until 4).maxOf { red(pixels[it]) }
        val centre = red(pixels[(height / 2) * width + width / 2])
        assertTrue(
            "corner peak $cornerMax should sit below the centre $centre",
            cornerMax < centre,
        )
    }

    private fun planFor(
        grain: Double,
        vignette: Double,
        shadowClip: Float,
        highlightClip: Float,
    ): EditPlan = EditPlanner.plan(
        sourceWidth = 1000,
        sourceHeight = 1000,
        stats = LumaStats(
            pixelCount = 1000,
            mean = AUTO_EXPOSURE_TARGET,
            shadowClipRatio = shadowClip,
            highlightClipRatio = highlightClip,
            blackPoint = 0f,
            whitePoint = 1f,
        ),
        means = ChannelMeans(0.5f, 0.5f, 0.5f),
        preset = testPreset(grain = grain, vignette = vignette),
    )

    private fun testPreset(grain: Double, vignette: Double): StylePreset = StylePreset(
        id = "test",
        name = "test",
        displayName = "테스트",
        composition = Composition(
            targetAspectRatio = "4:5",
            subjectScaleRange = listOf(0.3, 0.6),
            subjectPosition = "center",
            headroomRange = listOf(0.05, 0.15),
            horizonPosition = 0.5,
            cameraPitchRange = listOf(-5.0, 5.0),
            posePattern = "standing",
            backgroundRatio = listOf(0.4, 0.7),
        ),
        color = ColorParams(
            colorTemperature = NEUTRAL_KELVIN.toDouble(),
            exposureBias = 0.0,
            contrast = 0.0,
            saturation = 0.0,
            grain = grain,
            vignette = vignette,
            blurStrength = 0.0,
            fade = 0.0,
        ),
    )

    private fun assertArrayEqualsInt(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i])
    }
}
