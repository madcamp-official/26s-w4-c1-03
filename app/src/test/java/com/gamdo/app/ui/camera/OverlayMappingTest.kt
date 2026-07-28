package com.gamdo.app.ui.camera

import com.gamdo.app.ui.camera.OverlayMapping.Space
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * review_report #9 — the front lens flipped composition targets it should not have.
 *
 * The pipeline has two flips and they cancel: `CameraController` mirrors the front
 * lens into the preview *and* into the saved pixels, while the detector reads the
 * raw sensor frame. So the screen and the saved file are the same space, and a
 * bracket drawn at 1/3 on screen produces a subject at 1/3 in the photo.
 *
 * The overlay used to mirror everything, which put `third_left` presets at 2/3 on
 * the selfie camera. The user framed themselves there and the saved photo — mirrored
 * back — had them on the right third instead.
 */
class OverlayMappingTest {

    // A square view over a 3:4 portrait analysis frame: FILL_CENTER fills width.
    private val frameW = 720
    private val frameH = 960
    private val viewW = 1080f
    private val viewH = 1080f

    private fun x(nx: Float, space: Space, mirror: Boolean) =
        OverlayMapping.point(nx, 0.5f, space, mirror, frameW, frameH, viewW, viewH).x

    @Test
    fun `back lens maps both spaces identically`() {
        for (nx in listOf(0f, 1f / 3f, 0.5f, 2f / 3f, 1f)) {
            assertEquals(
                "with no mirror the space must not matter",
                x(nx, Space.ANALYSIS, mirror = false),
                x(nx, Space.COMPOSITION, mirror = false),
                0.001f,
            )
        }
    }

    /**
     * The defining property. A detection at analysis x=0.2 belongs on the right of
     * a mirrored preview; a `third_left` target belongs on the left of it.
     */
    @Test
    fun `the front lens mirrors detections but not composition targets`() {
        val detection = x(0.2f, Space.ANALYSIS, mirror = true)
        val target = x(1f / 3f, Space.COMPOSITION, mirror = true)

        assertEquals("a detection at 0.2 appears at 0.8 on a mirrored preview", 0.8f * viewW, detection, 0.5f)
        assertEquals("a third_left target stays on the left third", (1f / 3f) * viewW, target, 0.5f)
    }

    /**
     * Stated as the user-visible consequence rather than as coordinates, because
     * that is how the bug was found and how it would come back.
     */
    @Test
    fun `a third_left preset draws its bracket on the left half on the selfie camera`() {
        val bracketCentre = x(1f / 3f, Space.COMPOSITION, mirror = true)
        org.junit.Assert.assertTrue(
            "third_left must not land on the right half of the screen (was $bracketCentre of $viewW)",
            bracketCentre < viewW / 2f,
        )
    }

    @Test
    fun `a third_right preset draws its bracket on the right half on the selfie camera`() {
        val bracketCentre = x(2f / 3f, Space.COMPOSITION, mirror = true)
        org.junit.Assert.assertTrue(
            "third_right must not land on the left half of the screen (was $bracketCentre of $viewW)",
            bracketCentre > viewW / 2f,
        )
    }

    @Test
    fun `a centred target is unmoved by the mirror`() {
        assertEquals(
            x(0.5f, Space.COMPOSITION, mirror = false),
            x(0.5f, Space.COMPOSITION, mirror = true),
            0.001f,
        )
        // …and so is a centred detection, since 0.5 is the flip's fixed point.
        assertEquals(
            x(0.5f, Space.ANALYSIS, mirror = false),
            x(0.5f, Space.ANALYSIS, mirror = true),
            0.001f,
        )
    }

    @Test
    fun `rect corners stay ordered after a mirror`() {
        val r = OverlayMapping.rect(
            left = 0.2f, top = 0.1f, right = 0.6f, bottom = 0.4f,
            space = Space.ANALYSIS, mirror = true,
            frameWidth = frameW, frameHeight = frameH, viewWidth = viewW, viewHeight = viewH,
        )
        org.junit.Assert.assertTrue("left must stay ≤ right", r.left <= r.right)
        org.junit.Assert.assertTrue("top must stay ≤ bottom", r.top <= r.bottom)
        assertEquals("a mirror preserves width", 0.4f * viewW, r.width, 0.5f)
    }

    /**
     * FILL_CENTER letterboxes on the other axis. Pinning it here because the
     * overlay's coordinate accuracy depends on matching what `PreviewView` does,
     * and this is the only place that transform is written down.
     */
    @Test
    fun `a taller-than-frame view fills height and crops width`() {
        // 3:4 analysis in a 1:2 view — fills height, content overflows horizontally.
        val p = OverlayMapping.point(
            0f, 0f, Space.COMPOSITION, mirror = false,
            frameWidth = 720, frameHeight = 960, viewWidth = 540f, viewHeight = 1080f,
        )
        val contentW = 1080f * (720f / 960f)
        assertEquals("content is centred horizontally", (540f - contentW) / 2f, p.x, 0.5f)
        assertEquals("and flush to the top", 0f, p.y, 0.5f)
    }
}
