package com.gamdo.app.camera.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which pixels the user is actually looking at.
 *
 * The preview stream is 4:3 and the user picks 4:5 or 1:1, so "the surface" and
 * "the photograph" are not the same rectangle. Only the positional stages care —
 * but 비네팅 drawn over the wrong rectangle puts its corners where nobody can see
 * them, which is indistinguishable from not drawing it.
 */
class PreviewCropTest {

    /** The preview's forced aspect (`CameraController`'s resolution selector). */
    private val surfaceWidth = 1440
    private val surfaceHeight = 1080

    @Test
    fun `a four-by-five window sees the central sixty percent of a four-by-three surface`() {
        // The number that makes this worth testing. 4:3 is 1.333 wide per unit
        // height; 4:5 is 0.8. Fitting 0.8 into 1.333 at full height leaves
        // 0.8 / 1.333 = 60% of the width.
        val crop = PreviewCrop.fit(surfaceWidth, surfaceHeight, ratioWtoH = 0.8f)
        assertEquals(1080f, crop.heightPx, 0.01f)
        assertEquals(864f, crop.widthPx, 0.01f)
        assertEquals(0.30f, crop.halfU, 0.001f)
        assertEquals(0.50f, crop.halfV, 0.001f)
    }

    @Test
    fun `a square window is bound by the short side`() {
        val crop = PreviewCrop.fit(surfaceWidth, surfaceHeight, ratioWtoH = 1f)
        assertEquals(1080f, crop.widthPx, 0.01f)
        assertEquals(1080f, crop.heightPx, 0.01f)
        assertEquals(0.375f, crop.halfU, 0.001f)
        assertEquals(0.5f, crop.halfV, 0.001f)
    }

    @Test
    fun `the crop never leaves the surface`() {
        // Both D9 aspects, both surface orientations. A halfU above 0.5 would make
        // the shader sample outside the frame and the vignette would be computed
        // against a rectangle that does not exist.
        for (ratio in floatArrayOf(0.8f, 1f)) {
            for ((w, h) in listOf(1440 to 1080, 1080 to 1440, 640 to 480)) {
                val crop = PreviewCrop.fit(w, h, ratio)
                assertTrue("halfU ${crop.halfU} out of range", crop.halfU in 0f..0.5f)
                assertTrue("halfV ${crop.halfV} out of range", crop.halfV in 0f..0.5f)
                assertTrue(crop.widthPx <= w + 0.01f)
                assertTrue(crop.heightPx <= h + 0.01f)
                assertEquals(
                    "the fitted rect must have the requested aspect",
                    ratio,
                    crop.widthPx / crop.heightPx,
                    0.001f,
                )
            }
        }
    }

    @Test
    fun `degenerate input falls back to the whole surface instead of a NaN`() {
        // A NaN reaching the shader is a black preview, which is the one outcome
        // O-14 exists to avoid. Zero sizes happen for real: `onOutputSurface` can
        // land before a layout pass has produced anything.
        for (bad in listOf(
            PreviewCrop.fit(0, 0, 0.8f),
            PreviewCrop.fit(1440, 1080, 0f),
            PreviewCrop.fit(1440, 1080, Float.NaN),
        )) {
            assertEquals(0.5f, bad.halfU, 0f)
            assertEquals(0.5f, bad.halfV, 0f)
            assertTrue(bad.widthPx.isFinite() && bad.widthPx > 0f)
            assertTrue(bad.heightPx.isFinite() && bad.heightPx > 0f)
        }
    }
}
