package com.gamdo.app.edit

import com.gamdo.app.data.preset.ColorParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM coverage for the colour half of §4-1.
 *
 * `applyColorMatrix` here is the same function the on-device software path uses, so
 * these assertions pin the actual rendered numbers, not a test-only model.
 */
class EditMathTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun alpha(p: Int) = (p ushr 24) and 0xFF

    private fun neutralColorParams() = ColorParams(
        colorTemperature = NEUTRAL_KELVIN.toDouble(),
        exposureBias = 0.0,
        contrast = 0.0,
        saturation = 0.0,
        grain = 0.0,
        vignette = 0.0,
        blurStrength = 0.0,
        fade = 0.0,
    )

    @Test
    fun `identity matrix leaves a pixel alone`() {
        val pixel = argb(37, 128, 219)
        assertEquals(pixel, applyColorMatrix(pixel, identityColorMatrix()))
    }

    @Test
    fun `identity matrix is recognised`() {
        assertTrue(isIdentityColorMatrix(identityColorMatrix()))
        assertFalse(isIdentityColorMatrix(contrastMatrix(0.2f)))
    }

    @Test
    fun `alpha survives the pipeline`() {
        val pixel = (0x80 shl 24) or (100 shl 16) or (110 shl 8) or 120
        assertEquals(0x80, alpha(applyColorMatrix(pixel, contrastMatrix(0.3f))))
    }

    @Test
    fun `concat with identity is a no-op either way`() {
        val m = styleColorMatrix(neutralColorParams().copy(contrast = 0.2, saturation = 0.1))
        val identity = identityColorMatrix()
        concatColorMatrix(identity, m).forEachIndexed { i, v -> assertEquals(m[i], v, 1e-5f) }
        concatColorMatrix(m, identity).forEachIndexed { i, v -> assertEquals(m[i], v, 1e-5f) }
    }

    @Test
    fun `concat applies before then after`() {
        val before = exposureMatrix(0.5f)
        val after = contrastMatrix(0.25f)
        val pixel = argb(90, 120, 150)

        val sequential = applyColorMatrix(applyColorMatrix(pixel, before), after)
        val combined = applyColorMatrix(pixel, concatColorMatrix(after, before))

        // Sequential application rounds twice, so allow a quantisation step.
        assertTrue(abs(red(sequential) - red(combined)) <= 2)
        assertTrue(abs(green(sequential) - green(combined)) <= 2)
        assertTrue(abs(blue(sequential) - blue(combined)) <= 2)
    }

    @Test
    fun `one stop of exposure doubles mid grey`() {
        val out = applyColorMatrix(argb(100, 100, 100), exposureMatrix(1f))
        assertEquals(200, red(out))
    }

    @Test
    fun `exposure clamps rather than wrapping`() {
        val out = applyColorMatrix(argb(200, 200, 200), exposureMatrix(3f))
        assertEquals(255, red(out))
        assertEquals(255, blue(out))
    }

    @Test
    fun `contrast pivots on mid grey`() {
        val m = contrastMatrix(0.5f)
        // 128 is the pivot and must barely move; the extremes spread outward.
        assertTrue(abs(red(applyColorMatrix(argb(128, 128, 128), m)) - 128) <= 1)
        assertTrue(red(applyColorMatrix(argb(180, 180, 180), m)) > 180)
        assertTrue(red(applyColorMatrix(argb(60, 60, 60), m)) < 60)
    }

    @Test
    fun `full desaturation collapses to luma`() {
        val out = applyColorMatrix(argb(255, 0, 0), saturationMatrix(-1f))
        assertTrue("red should collapse to its luma, was ${red(out)}", abs(red(out) - 76) <= 1)
        assertEquals(red(out), green(out))
        assertEquals(green(out), blue(out))
    }

    @Test
    fun `auto exposure is zero when the frame is already on target`() {
        assertEquals(0f, autoExposureEv(AUTO_EXPOSURE_TARGET), 1e-4f)
    }

    @Test
    fun `auto exposure never exceeds one stop`() {
        assertEquals(MAX_AUTO_EXPOSURE_EV, autoExposureEv(0.02f), 1e-4f)
        assertEquals(-MAX_AUTO_EXPOSURE_EV, autoExposureEv(0.99f), 1e-4f)
    }

    @Test
    fun `auto exposure lifts a dark frame and pulls back a bright one`() {
        assertTrue(autoExposureEv(0.30f) > 0f)
        assertTrue(autoExposureEv(0.70f) < 0f)
    }

    @Test
    fun `auto exposure ignores a black frame instead of dividing by zero`() {
        assertEquals(0f, autoExposureEv(0f), 1e-6f)
    }

    @Test
    fun `gray world pulls a warm cast back toward neutral`() {
        val gains = grayWorldGains(ChannelMeans(r = 0.6f, g = 0.5f, b = 0.4f), strength = 1f)
        assertEquals(1f, gains.g, 1e-6f)
        assertTrue("over-red channel must be pulled down", gains.r < 1f)
        assertTrue("under-blue channel must be pushed up", gains.b > 1f)
    }

    @Test
    fun `gray world is a no-op on an already neutral frame`() {
        val gains = grayWorldGains(ChannelMeans(0.5f, 0.5f, 0.5f))
        assertEquals(1f, gains.r, 1e-4f)
        assertEquals(1f, gains.b, 1e-4f)
    }

    @Test
    fun `gray world gains are bounded so a monochrome scene is not bleached`() {
        val gains = grayWorldGains(ChannelMeans(r = 0.9f, g = 0.5f, b = 0.02f), strength = 1f)
        assertTrue(gains.r >= 1f / 1.6f - 1e-4f)
        assertTrue(gains.b <= 1.6f + 1e-4f)
    }

    @Test
    fun `gray world strength blends toward identity`() {
        val full = grayWorldGains(ChannelMeans(0.6f, 0.5f, 0.4f), strength = 1f)
        val half = grayWorldGains(ChannelMeans(0.6f, 0.5f, 0.4f), strength = 0.5f)
        assertTrue("half strength must sit between identity and full", half.b < full.b && half.b > 1f)
    }

    @Test
    fun `neutral kelvin is a no-op`() {
        val gains = kelvinGains(NEUTRAL_KELVIN)
        assertEquals(1f, gains.r, 1e-4f)
        assertEquals(1f, gains.g, 1e-4f)
        assertEquals(1f, gains.b, 1e-4f)
    }

    @Test
    fun `warmer kelvin favours red over blue`() {
        // Preset temperatures below neutral are the warm end (night_street is 4600K).
        val warm = kelvinGains(4600f)
        assertTrue("warm target should raise red relative to blue", warm.r > warm.b)
    }

    @Test
    fun `cooler kelvin favours blue over red`() {
        val cool = kelvinGains(6200f)
        assertTrue("cool target should raise blue relative to red", cool.b > cool.r)
    }

    @Test
    fun `contrast stretch stays inside its bounds`() {
        val stats = LumaStats(
            pixelCount = 100,
            mean = 0.5f,
            shadowClipRatio = 0f,
            highlightClipRatio = 0f,
            blackPoint = 0.9f,
            whitePoint = 0.1f,
        )
        val levels = contrastStretch(stats)
        assertTrue(levels.black <= 0.45f)
        assertTrue(levels.white >= 0.55f)
        assertTrue(levels.span > 0f)
    }

    @Test
    fun `neutral preset produces the identity style matrix`() {
        assertTrue(isIdentityColorMatrix(styleColorMatrix(neutralColorParams())))
    }

    @Test
    fun `a real preset produces a non-identity style matrix`() {
        val softFilm = ColorParams(
            colorTemperature = 5200.0,
            exposureBias = 0.1,
            contrast = -0.05,
            saturation = -0.05,
            grain = 0.22,
            vignette = 0.15,
            blurStrength = 0.0,
            fade = 0.25,
        )
        assertFalse(isIdentityColorMatrix(styleColorMatrix(softFilm)))
    }

    @Test
    fun `flat tone curve is the identity lut`() {
        assertTrue(isIdentityLut(toneCurveLut(0f, 0f)))
    }

    @Test
    fun `shadow lift raises the low end and leaves white alone`() {
        val lut = toneCurveLut(shadowLift = 0.5f, highlightRolloff = 0f)
        assertFalse(isIdentityLut(lut))
        assertTrue("shadows must open up", lut[40] > 40)
        assertEquals(255, lut[255])
        assertEquals(0, lut[0])
    }

    @Test
    fun `tone curve stays monotonic`() {
        val lut = toneCurveLut(shadowLift = 0.4f, highlightRolloff = 0.4f)
        for (i in 1..255) {
            assertTrue("lut must not fold at $i", lut[i] >= lut[i - 1])
        }
    }

    @Test
    fun `pipeline skips work when there is nothing to do`() {
        val pixels = intArrayOf(argb(10, 20, 30), argb(200, 100, 50))
        val original = pixels.copyOf()
        applyColorPipeline(pixels, identityColorMatrix(), toneCurveLut(0f, 0f))
        assertArrayEqualsInt(original, pixels)
    }

    @Test
    fun `pipeline applies matrix and lut together`() {
        val pixels = intArrayOf(argb(100, 100, 100))
        applyColorPipeline(pixels, exposureMatrix(1f), toneCurveLut(0f, 0f))
        assertEquals(200, red(pixels[0]))
    }

    @Test
    fun `optical matrix composes white balance exposure and levels`() {
        val wb = ChannelGains(0.9f, 1f, 1.1f)
        val levels = LevelsStretch(0.05f, 0.95f)
        val m = opticalColorMatrix(wb, 0.5f, levels)
        assertFalse(isIdentityColorMatrix(m))
        // Blue was boosted by WB, so it must end up above red for a neutral input.
        val out = applyColorMatrix(argb(120, 120, 120), m)
        assertTrue(blue(out) > red(out))
    }

    private fun assertArrayEqualsInt(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i])
    }
}
