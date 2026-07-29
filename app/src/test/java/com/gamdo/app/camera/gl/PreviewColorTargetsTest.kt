package com.gamdo.app.camera.gl

import androidx.camera.core.CameraEffect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The preview effect must not reach the capture or the analysis stream.
 *
 * This is a two-line test guarding a one-character mistake with two expensive
 * consequences: a saved JPEG coloured twice (the effect *and* `FilterEngine` in the
 * result screen), or a guide measuring a filtered image instead of the scene.
 *
 * It works on the JVM because the targets are compile-time `int` constants — no
 * Android class is loaded and no `CameraEffect` is constructed, which is also why
 * everything *else* about this class is device-only.
 */
class PreviewColorTargetsTest {

    @Test
    fun `the effect targets the preview and only the preview`() {
        assertEquals(CameraEffect.PREVIEW, PreviewColorEffect.TARGETS)
    }

    @Test
    fun `the capture stream is never a target`() {
        // FilterEngine already colours the saved file in the result screen. Both
        // would mean the look applied twice — visibly, since every stage from the
        // tone curve to saturation compounds.
        assertEquals(0, PreviewColorEffect.TARGETS and CameraEffect.IMAGE_CAPTURE)
        assertEquals(0, PreviewColorEffect.TARGETS and CameraEffect.VIDEO_CAPTURE)
    }

    @Test
    fun `CameraX has no analysis target to aim at`() {
        // Pins the structural fact the report leans on: the three constants are
        // distinct powers of two and there is no fourth. An IMAGE_ANALYSIS constant
        // appearing in a future camera-core is exactly the upgrade that would need
        // this whole argument re-checked.
        assertEquals(1, CameraEffect.PREVIEW)
        assertEquals(2, CameraEffect.VIDEO_CAPTURE)
        assertEquals(4, CameraEffect.IMAGE_CAPTURE)
    }
}
