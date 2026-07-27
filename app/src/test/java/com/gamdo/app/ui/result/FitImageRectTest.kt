package com.gamdo.app.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The erase mask is normalized against this rect. Getting it wrong is silent —
 * the numbers stay inside 0..1 and the on-screen overlay agrees with the error —
 * so the server erases somewhere the user did not point.
 */
class FitImageRectTest {

    @Test
    fun `a 4 to 5 photo in a taller container gets top and bottom bars`() {
        // 4:5 image (0.8) in a 1000x1600 container (0.625): width binds.
        val r = fitImageRect(1000f, 1600f, 2904, 3630)!!
        assertEquals(0f, r.left, 0.5f)
        assertEquals(1000f, r.width, 0.5f)
        assertEquals(1250f, r.height, 1f)
        assertEquals(175f, r.top, 1f)
        assertEquals("bars must be equal", r.top, 1600f - r.bottom, 0.5f)
    }

    @Test
    fun `a square photo in a taller container gets top and bottom bars`() {
        val r = fitImageRect(1000f, 1600f, 2904, 2904)!!
        assertEquals(1000f, r.width, 0.5f)
        assertEquals(1000f, r.height, 0.5f)
        assertEquals(300f, r.top, 0.5f)
    }

    @Test
    fun `a wide photo in a narrow container gets left and right bars`() {
        val r = fitImageRect(1000f, 1600f, 4000, 2000)!!
        assertEquals(1000f, r.width, 0.5f)
        assertEquals(500f, r.height, 0.5f)
        assertEquals(0f, r.left, 0.5f)
        assertEquals(550f, r.top, 0.5f)
    }

    /**
     * The actual defect, stated numerically.
     *
     * Normalizing against the container compresses the coordinate toward the
     * centre by image/container on the bound axis. **The error is zero at the
     * centre** — the image is centred, so its midpoint is the container's — and
     * grows to the bar's share of the container at the edges. That is the worst
     * possible shape for a bug like this: a developer checking "does the middle
     * work" sees a perfect match and moves on, while every real erase gesture,
     * which happens over a person somewhere off-centre, is wrong.
     *
     * Here the photo is 1250 of 1600px, so the compression is 0.781 and the top
     * edge of the photo reads 0.109 instead of 0.
     */
    @Test
    fun `container-relative normalization compresses toward the centre`() {
        val r = fitImageRect(1000f, 1600f, 2904, 3630)!!

        // Centre: the two agree, which is why this went unnoticed.
        val centreY = r.top + r.height / 2f
        assertEquals(0.5f, r.normalizeY(centreY), 1e-4f)
        assertEquals(0.5f, centreY / 1600f, 1e-4f)

        // Off-centre: they diverge, and the divergence is the bug.
        for (v in listOf(0f, 0.25f, 0.75f, 1f)) {
            val y = r.toContainerY(v)
            val correct = r.normalizeY(y)
            val containerRelative = y / 1600f
            assertEquals(v, correct, 1e-4f)
            assertEquals("compression factor is height/container", v * (1250f / 1600f) + 0.109375f, containerRelative, 1e-3f)
        }

        // Worst case, at the photo's top edge: 10.9% of the frame.
        assertTrue(kotlin.math.abs(r.top / 1600f - 0f) > 0.10f)
    }

    @Test
    fun `letterbox bars are not on the image`() {
        val r = fitImageRect(1000f, 1600f, 2904, 3630)!!
        assertFalse("top bar", r.contains(500f, 50f))
        assertFalse("bottom bar", r.contains(500f, 1550f))
        assertTrue("photo", r.contains(500f, 800f))
        assertTrue("top edge of the photo", r.contains(500f, r.top + 1f))
    }

    @Test
    fun `normalize and back is a round trip`() {
        val r = fitImageRect(1000f, 1600f, 2904, 3630)!!
        for (u in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            assertEquals(u, r.normalizeX(r.toContainerX(u)), 1e-4f)
            assertEquals(u, r.normalizeY(r.toContainerY(u)), 1e-4f)
        }
    }

    @Test
    fun `normalization clamps rather than running off the image`() {
        val r = fitImageRect(1000f, 1600f, 2904, 3630)!!
        assertEquals(0f, r.normalizeY(0f), 1e-5f)
        assertEquals(1f, r.normalizeY(1600f), 1e-5f)
        assertEquals(0f, r.normalizeX(-50f), 1e-5f)
        assertEquals(1f, r.normalizeX(9999f), 1e-5f)
    }

    @Test
    fun `matching aspects leave no bars`() {
        val r = fitImageRect(800f, 1000f, 2904, 3630)!!
        assertEquals(0f, r.left, 0.5f)
        assertEquals(0f, r.top, 0.5f)
        assertEquals(800f, r.width, 0.5f)
        assertEquals(1000f, r.height, 0.5f)
    }

    @Test
    fun `unusable sizes yield null instead of dividing by zero`() {
        assertNull(fitImageRect(0f, 1600f, 100, 100))
        assertNull(fitImageRect(1000f, 0f, 100, 100))
        assertNull(fitImageRect(1000f, 1600f, 0, 100))
        assertNull(fitImageRect(1000f, 1600f, 100, 0))
        assertNull(fitImageRect(Float.NaN, 1600f, 100, 100))
    }
}
