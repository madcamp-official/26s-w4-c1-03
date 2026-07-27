package com.gamdo.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStabilizerTest {

    private val frame = RectN(0.32f, 0.085f, 0.68f, 0.535f)

    @Test
    fun `pass-through config is an exact identity`() {
        // The stability harness measures the "before" baseline by running the real
        // path with this config, so any deviation would silently forge the number.
        val stabilizer = OverlayStabilizer(OverlayStabilizerConfig.PassThrough)
        val inputs = listOf(
            projection(visible = true, aligned = false, silhouette = frame),
            projection(visible = true, aligned = true, silhouette = frame),
            projection(visible = true, aligned = false, silhouette = null),
            projection(visible = false, aligned = false, silhouette = null),
            projection(visible = true, aligned = true, silhouette = frame),
        )

        inputs.forEach { input ->
            assertEquals(input, stabilizer.stabilize(input))
        }
    }

    @Test
    fun `aligned needs consecutive agreement to turn on and more to turn off`() {
        val stabilizer = OverlayStabilizer(
            OverlayStabilizerConfig(alignedEnterFrames = 3, alignedExitFrames = 5),
        )
        val on = projection(visible = true, aligned = true, silhouette = frame)
        val off = projection(visible = true, aligned = false, silhouette = frame)

        assertFalse(stabilizer.stabilize(on).aligned)
        assertFalse(stabilizer.stabilize(on).aligned)
        assertTrue("3번째 프레임에 진입", stabilizer.stabilize(on).aligned)

        // Four not-aligned frames are not enough to revoke the cue.
        repeat(4) { assertTrue(stabilizer.stabilize(off).aligned) }
        assertFalse("5번째 프레임에 해제", stabilizer.stabilize(off).aligned)
    }

    @Test
    fun `a single not-aligned frame cannot break a settled aligned state`() {
        val stabilizer = OverlayStabilizer(
            OverlayStabilizerConfig(alignedEnterFrames = 2, alignedExitFrames = 4),
        )
        val on = projection(visible = true, aligned = true, silhouette = frame)
        val off = projection(visible = true, aligned = false, silhouette = frame)

        repeat(2) { stabilizer.stabilize(on) }
        assertTrue("한 프레임 이탈로 해제되면 안 된다", stabilizer.stabilize(off).aligned)

        // Recovering resets the exit counter, so the next three dropouts are also
        // absorbed — this is what turns a 3-frame-period detector gap into nothing.
        assertTrue(stabilizer.stabilize(on).aligned)
        repeat(3) { assertTrue(stabilizer.stabilize(off).aligned) }
        assertFalse("연속 4프레임 이탈은 해제", stabilizer.stabilize(off).aligned)
    }

    @Test
    fun `silhouette survives a short detection dropout and clears after the hold`() {
        val stabilizer = OverlayStabilizer(OverlayStabilizerConfig(silhouetteHoldFrames = 3))
        val present = projection(visible = true, aligned = false, silhouette = frame)
        val missing = projection(visible = true, aligned = false, silhouette = null)

        assertNotNull(stabilizer.stabilize(present).silhouetteBounds)
        repeat(3) { assertNotNull("홀드 구간", stabilizer.stabilize(missing).silhouetteBounds) }
        assertNull("홀드 초과 후 해제", stabilizer.stabilize(missing).silhouetteBounds)
    }

    @Test
    fun `whole overlay survives a short visibility dropout`() {
        val stabilizer = OverlayStabilizer(OverlayStabilizerConfig(visibleHoldFrames = 2))
        val shown = projection(visible = true, aligned = false, silhouette = frame)
        val hidden = projection(visible = false, aligned = false, silhouette = null)

        assertTrue(stabilizer.stabilize(shown).visible)
        assertTrue(stabilizer.stabilize(hidden).visible)
        assertTrue(stabilizer.stabilize(hidden).visible)
        assertFalse(stabilizer.stabilize(hidden).visible)
    }

    @Test
    fun `a hidden overlay is never reported as aligned`() {
        val stabilizer = OverlayStabilizer(
            OverlayStabilizerConfig(alignedEnterFrames = 1, visibleHoldFrames = 0),
        )
        repeat(2) { stabilizer.stabilize(projection(visible = true, aligned = true, silhouette = frame)) }

        val hidden = stabilizer.stabilize(projection(visible = false, aligned = true, silhouette = null))

        assertFalse(hidden.visible)
        assertFalse("숨은 오버레이가 초록으로 다시 나타나면 안 된다", hidden.aligned)
    }

    @Test
    fun `a relocated target glides in at no more than the slew limit per frame`() {
        val step = 0.01f
        val stabilizer = OverlayStabilizer(OverlayStabilizerConfig(maxStepPerFrameNorm = step))
        val start = projection(visible = true, aligned = false, silhouette = frame)
        stabilizer.stabilize(start)

        val moved = RectN(0.52f, 0.085f, 0.88f, 0.535f) // +0.20 to the right
        var previous = frame
        var settled = false
        repeat(40) {
            val out = stabilizer.stabilize(projection(true, false, moved, target = moved)).targetFrame
            assertTrue(
                "프레임간 이동 %.4f > 한계 %.4f".format(out.left - previous.left, step),
                out.left - previous.left <= step + 1e-5f,
            )
            previous = out
            if (out.left >= moved.left - 1e-5f) settled = true
        }
        assertTrue("한계 내에서 결국 도달해야 한다", settled)
    }

    @Test
    fun `reset restores the cold-start state`() {
        val stabilizer = OverlayStabilizer(OverlayStabilizerConfig(alignedEnterFrames = 1))
        repeat(3) { stabilizer.stabilize(projection(visible = true, aligned = true, silhouette = frame)) }

        stabilizer.reset()
        val first = stabilizer.stabilize(projection(visible = false, aligned = true, silhouette = null))

        assertFalse(first.visible)
        assertFalse(first.aligned)
        assertNull(first.silhouetteBounds)
    }

    @Test
    fun `config rejects values that would disable the state machine`() {
        listOf(
            { OverlayStabilizerConfig(alignedEnterFrames = 0) },
            { OverlayStabilizerConfig(alignedExitFrames = 0) },
            { OverlayStabilizerConfig(silhouetteHoldFrames = -1) },
            { OverlayStabilizerConfig(visibleHoldFrames = -1) },
            { OverlayStabilizerConfig(maxStepPerFrameNorm = 0f) },
        ).forEach { build ->
            runCatching { build() }
                .onSuccess { throw AssertionError("잘못된 값이 통과했다: $it") }
        }
    }

    private fun projection(
        visible: Boolean,
        aligned: Boolean,
        silhouette: RectN?,
        target: RectN = frame,
    ) = OverlayProjection(
        targetFrame = target,
        silhouetteBounds = silhouette,
        horizonY = 0.5f,
        visible = visible,
        aligned = aligned,
    )
}
