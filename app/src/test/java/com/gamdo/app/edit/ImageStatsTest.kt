package com.gamdo.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the measurement half of §4-1. These run because nothing under
 * test imports `android.*` — the Bitmap unpack lives in `ImageMetricsExtractor`.
 */
class ImageStatsTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun flat(size: Int, level: Int): IntArray =
        IntArray(size) { argb(level, level, level) }

    @Test
    fun `luma of white and black is full range`() {
        val luma = lumaOf(intArrayOf(argb(255, 255, 255), argb(0, 0, 0)))
        assertEquals(255, luma[0])
        assertEquals(0, luma[1])
    }

    @Test
    fun `luma weights green most`() {
        val luma = lumaOf(intArrayOf(argb(255, 0, 0), argb(0, 255, 0), argb(0, 0, 255)))
        assertTrue("green must dominate", luma[1] > luma[0] && luma[0] > luma[2])
    }

    @Test
    fun `flat mid grey reports mid mean and no clipping`() {
        val stats = lumaStats(lumaHistogram(lumaOf(flat(10_000, 128))))
        assertEquals(0.502f, stats.mean, 0.01f)
        assertEquals(0f, stats.shadowClipRatio, 1e-6f)
        assertEquals(0f, stats.highlightClipRatio, 1e-6f)
        assertEquals(10_000, stats.pixelCount)
    }

    @Test
    fun `crushed image reports shadow clipping`() {
        val pixels = IntArray(1000) { if (it < 400) argb(2, 2, 2) else argb(120, 120, 120) }
        val stats = lumaStats(lumaHistogram(lumaOf(pixels)))
        assertEquals(0.4f, stats.shadowClipRatio, 0.01f)
        assertEquals(0f, stats.highlightClipRatio, 1e-6f)
    }

    @Test
    fun `blown image reports highlight clipping`() {
        val pixels = IntArray(1000) { if (it < 250) argb(255, 255, 255) else argb(120, 120, 120) }
        val stats = lumaStats(lumaHistogram(lumaOf(pixels)))
        assertEquals(0.25f, stats.highlightClipRatio, 0.01f)
    }

    @Test
    fun `black point survives a small sample`() {
        // total * 0.005 truncates to 0 here; the percentile must not collapse to level 0.
        val stats = lumaStats(lumaHistogram(lumaOf(flat(100, 128))))
        assertEquals(0.502f, stats.blackPoint, 0.01f)
        assertTrue("white point must exceed black point", stats.whitePoint > stats.blackPoint)
    }

    @Test
    fun `channel means detect a colour cast`() {
        val means = channelMeans(IntArray(100) { argb(200, 150, 100) })
        assertEquals(200f / 255f, means.r, 0.01f)
        assertEquals(150f / 255f, means.g, 0.01f)
        assertEquals(100f / 255f, means.b, 0.01f)
    }

    @Test
    fun `laplacian variance is zero on a flat frame`() {
        val luma = lumaOf(flat(64 * 64, 128))
        assertEquals(0f, laplacianVariance(luma, 64, 64), 1e-3f)
    }

    @Test
    fun `laplacian variance is high on a checkerboard`() {
        val w = 64
        val pixels = IntArray(w * w) { i ->
            val x = i % w
            val y = i / w
            if ((x + y) % 2 == 0) argb(255, 255, 255) else argb(0, 0, 0)
        }
        val variance = laplacianVariance(lumaOf(pixels), w, w)
        assertTrue("sharp frame should be far above the blur threshold, was $variance", variance > 1000f)
    }

    @Test
    fun `laplacian variance is zero for images without an interior`() {
        assertEquals(0f, laplacianVariance(IntArray(2), 2, 1), 1e-6f)
    }

    @Test
    fun `margins are zero without a subject box`() {
        val (left, right) = horizontalMargins(null)
        assertEquals(0f, left, 1e-6f)
        assertEquals(0f, right, 1e-6f)
    }

    @Test
    fun `margins measure empty space either side of the subject`() {
        val (left, right) = horizontalMargins(SubjectBox(0.3f, 0.1f, 0.6f, 0.9f))
        assertEquals(0.3f, left, 1e-4f)
        assertEquals(0.4f, right, 1e-4f)
    }

    @Test
    fun `metrics carry the sensor tilt through untouched`() {
        val metrics = computeImageMetrics(flat(32 * 32, 128), 32, 32, tiltDeg = 7.5f)
        assertEquals(7.5f, metrics.tiltDeg, 1e-6f)
        assertEquals(0.502f, metrics.brightnessMean, 0.01f)
        assertEquals(0f, metrics.laplacianVariance, 1e-3f)
    }

    @Test
    fun `backlight ratio is null without a subject`() {
        assertNull(computeImageMetrics(flat(32 * 32, 128), 32, 32).backlightRatio)
    }

    @Test
    fun `backlight ratio exceeds one when the surround is brighter`() {
        val w = 32
        val pixels = IntArray(w * w) { i ->
            val x = i % w
            val y = i / w
            val inSubject = x in 12 until 20 && y in 12 until 20
            if (inSubject) argb(40, 40, 40) else argb(200, 200, 200)
        }
        val ratio = computeImageMetrics(
            pixels,
            w,
            w,
            subject = SubjectBox(12f / 32f, 12f / 32f, 20f / 32f, 20f / 32f),
        ).backlightRatio
        requireNotNull(ratio)
        assertTrue("backlit frame should read well above 1, was $ratio", ratio > 3f)
    }
}
