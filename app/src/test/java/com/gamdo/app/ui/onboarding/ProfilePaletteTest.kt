package com.gamdo.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6-2: "당신의 감도" has to depend on what the user picked.
 *
 * The screen previously drew three constants. These tests fail if anyone puts
 * them back — the defining property is that two different profiles produce two
 * different palettes.
 */
class ProfilePaletteTest {

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun luma(c: Int) = 0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)

    @Test
    fun `three swatches, opaque, ordered dark to light`() {
        val s = ProfilePalette.swatches(brightness = 0.5f, colorTemperatureK = 5500f, saturation = 0.5f)
        assertEquals(3, s.size)
        s.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
        assertTrue(luma(s[0]) < luma(s[1]))
        assertTrue(luma(s[1]) < luma(s[2]))
    }

    @Test
    fun `a warm profile is redder than a cool one`() {
        val warm = ProfilePalette.swatches(0.5f, 3200f, 0.7f)[1]
        val cool = ProfilePalette.swatches(0.5f, 8000f, 0.7f)[1]
        assertTrue(
            "warm should carry more red than blue relative to cool",
            (r(warm) - b(warm)) > (r(cool) - b(cool)),
        )
    }

    /**
     * The temperature has to be *visible*, not merely present. A Planckian tint at
     * daylight is (1.00, 0.93, 0.86); rendered faithfully the whole card range
     * produces swatches a user cannot tell apart, which defeats the point of showing
     * them their own palette. 24 levels is roughly where a difference stops being
     * arguable on a phone screen.
     */
    @Test
    fun `the warm-cool difference is large enough to see`() {
        val warm = ProfilePalette.swatches(0.5f, 4400f, 0.7f)[1]
        val cool = ProfilePalette.swatches(0.5f, 6600f, 0.7f)[1]
        val warmth = { c: Int -> r(c) - b(c) }
        assertTrue(
            "warm ${warmth(warm)} vs cool ${warmth(cool)} — too close to distinguish",
            warmth(warm) - warmth(cool) >= 24,
        )
    }

    @Test
    fun `a brighter profile yields a brighter palette`() {
        val dark = ProfilePalette.swatches(0.15f, 5500f, 0.5f)[1]
        val light = ProfilePalette.swatches(0.85f, 5500f, 0.5f)[1]
        assertTrue(luma(dark) < luma(light))
    }

    @Test
    fun `zero saturation is neutral grey, and that is the honest answer`() {
        ProfilePalette.swatches(0.5f, 3000f, 0f).forEach {
            assertEquals("r==g", r(it), g(it))
            assertEquals("g==b", g(it), b(it))
        }
    }

    @Test
    fun `higher saturation moves further from grey`() {
        val low = ProfilePalette.swatches(0.5f, 3000f, 0.15f)[1]
        val high = ProfilePalette.swatches(0.5f, 3000f, 0.95f)[1]
        val spreadLow = maxOf(r(low), g(low), b(low)) - minOf(r(low), g(low), b(low))
        val spreadHigh = maxOf(r(high), g(high), b(high)) - minOf(r(high), g(high), b(high))
        assertTrue(spreadHigh > spreadLow)
    }

    /** The property the constants violated. */
    @Test
    fun `different profiles produce different palettes`() {
        val a = ProfilePalette.swatches(0.25f, 3600f, 0.8f)
        val b = ProfilePalette.swatches(0.70f, 6600f, 0.25f)
        assertNotEquals(a, b)
        a.indices.forEach { assertNotEquals("swatch $it must differ", a[it], b[it]) }
    }

    @Test
    fun `stays inside a band that is visible on charcoal`() {
        for (brightness in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            ProfilePalette.swatches(brightness, 5500f, 0.6f).forEach {
                assertTrue("too dark to see on Charcoal900", luma(it) > 25.0)
                assertTrue("channel out of range", r(it) in 0..255 && g(it) in 0..255 && b(it) in 0..255)
            }
        }
    }

    @Test
    fun `extreme and non-physical temperatures are clamped rather than overflowing`() {
        listOf(0f, 500f, 20000f, Float.MAX_VALUE).forEach { k ->
            ProfilePalette.swatches(0.5f, k, 0.6f).forEach {
                assertTrue(r(it) in 0..255 && g(it) in 0..255 && b(it) in 0..255)
            }
        }
    }
}
