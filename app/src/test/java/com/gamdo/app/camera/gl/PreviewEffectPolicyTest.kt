package com.gamdo.app.camera.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * O-14's fallback, which is the only part of the GL vertical a JVM can hold an
 * opinion about — and the part that matters most.
 *
 * ## Why this file exists before the shader does
 *
 * This device fails GPU inference with `GL_INVALID_VALUE … glMapBufferRange`,
 * reproducibly, 3 of 3 (W3-4 — it is why cold start now builds the detector on CPU
 * first). O-14 adds a *second* EGL context and GL pipeline to that same process on
 * that same driver. A camera that shows nothing is far worse than a camera that
 * shows uncoloured frames, so the rule is: **any GL failure detaches the effect and
 * the preview keeps running without colour.**
 *
 * Everything else in the vertical — EGL, shader compilation, the draw — is
 * untestable here: there is no GL implementation on a JVM and the module has no
 * `androidTest` source set. Writing a mock EGL and asserting against it would prove
 * only that the mock does what the mock does. So the decision is extracted instead:
 * *when* the effect detaches is pure Kotlin, and that is what is pinned.
 *
 * ## The two failure shapes it separates
 *
 * A setup failure and a runtime failure need opposite handling and the policy is
 * where the difference is written down. Setup runs **before** CameraX is told the
 * effect exists, so a failure there costs nothing: the effect is simply never
 * attached and the preview binds straight to the surface, as it does today. A
 * runtime failure has already taken the preview's surface hostage, so the detach
 * has to actually run for the preview to come back.
 */
class PreviewEffectPolicyTest {

    private fun policy(deadlineMs: Long = 2_000L) = PreviewEffectPolicy(deadlineMs)

    @Test
    fun `a fresh policy is off and attaching arms it`() {
        val p = policy()
        assertEquals(PreviewColorState.OFF, p.state)
        assertEquals(PreviewEffectDecision.Continue, p.onAttached(nowMs = 0))
        assertEquals(PreviewColorState.ATTACHING, p.state)
        assertNull(p.offReason)
    }

    @Test
    fun `a setup failure detaches without ever attaching`() {
        val p = policy()
        val decision = p.onSetupFailed()
        assertEquals(
            PreviewEffectDecision.Detach(PreviewColorOffReason.SETUP_FAILED),
            decision,
        )
        assertEquals(PreviewColorState.DETACHED, p.state)
        assertEquals(PreviewColorOffReason.SETUP_FAILED, p.offReason)
    }

    @Test
    fun `the first frame disarms the deadline for good`() {
        // The failure this pins: a deadline that stays armed after the effect is
        // demonstrably working kills a healthy preview effect the moment the camera
        // is legitimately idle (locked screen, app backgrounded, a long AF hunt).
        val p = policy(deadlineMs = 2_000L)
        p.onAttached(nowMs = 0)
        assertEquals(PreviewEffectDecision.Continue, p.onFrameDrawn())
        assertEquals(PreviewColorState.RUNNING, p.state)
        assertEquals(PreviewEffectDecision.Continue, p.onTick(nowMs = 60_000))
        assertEquals(PreviewColorState.RUNNING, p.state)
    }

    @Test
    fun `no first frame inside the deadline detaches`() {
        // The `glMapBufferRange` shape: every call returns success and no pixels
        // ever arrive. Nothing throws, so only a deadline can catch it.
        val p = policy(deadlineMs = 2_000L)
        p.onAttached(nowMs = 1_000)
        assertEquals(PreviewEffectDecision.Continue, p.onTick(nowMs = 2_500))
        assertEquals(
            PreviewEffectDecision.Detach(PreviewColorOffReason.NO_FIRST_FRAME),
            p.onTick(nowMs = 3_001),
        )
        assertEquals(PreviewColorState.DETACHED, p.state)
    }

    @Test
    fun `the deadline boundary is exclusive`() {
        // Pinned because an off-by-one here is invisible on device: it would only
        // ever show up as a preview that loses colour on a slow first frame.
        val p = policy(deadlineMs = 2_000L)
        p.onAttached(nowMs = 0)
        assertEquals(PreviewEffectDecision.Continue, p.onTick(nowMs = 2_000))
        assertEquals(
            PreviewEffectDecision.Detach(PreviewColorOffReason.NO_FIRST_FRAME),
            p.onTick(nowMs = 2_001),
        )
    }

    @Test
    fun `a tick before attaching never detaches`() {
        // The deadline is armed by attaching, not by construction. Otherwise a
        // policy built during composition and attached a second later on the GL
        // thread would detach itself before the camera had been asked for anything.
        val p = policy(deadlineMs = 2_000L)
        assertEquals(PreviewEffectDecision.Continue, p.onTick(nowMs = 999_999))
        assertEquals(PreviewColorState.OFF, p.state)
    }

    @Test
    fun `a processing error while running detaches`() {
        val p = policy()
        p.onAttached(nowMs = 0)
        p.onFrameDrawn()
        assertEquals(
            PreviewEffectDecision.Detach(PreviewColorOffReason.PROCESSING_ERROR),
            p.onProcessingError(),
        )
        assertEquals(PreviewColorState.DETACHED, p.state)
    }

    @Test
    fun `a draw failure while running detaches`() {
        val p = policy()
        p.onAttached(nowMs = 0)
        p.onFrameDrawn()
        assertEquals(
            PreviewEffectDecision.Detach(PreviewColorOffReason.DRAW_FAILED),
            p.onDrawFailed(),
        )
    }

    @Test
    fun `detach is emitted exactly once`() {
        // The caller turns Detach into one `clearEffects()` and one log line. A
        // second Detach would rebind the camera a second time — a visible black
        // flash — and print the failure twice.
        val p = policy()
        p.onAttached(nowMs = 0)
        assertEquals(
            PreviewEffectDecision.Detach(PreviewColorOffReason.PROCESSING_ERROR),
            p.onProcessingError(),
        )
        assertEquals(PreviewEffectDecision.AlreadyOff, p.onProcessingError())
        assertEquals(PreviewEffectDecision.AlreadyOff, p.onDrawFailed())
        assertEquals(PreviewEffectDecision.AlreadyOff, p.onTick(nowMs = 99_999))
    }

    @Test
    fun `nothing re-arms a detached policy`() {
        // No flapping. A driver that fails once on this hardware fails again, and a
        // preview whose colour blinks on and off is worse than one without colour.
        val p = policy()
        p.onAttached(nowMs = 0)
        p.onProcessingError()
        assertEquals(PreviewEffectDecision.AlreadyOff, p.onFrameDrawn())
        assertEquals(PreviewEffectDecision.AlreadyOff, p.onAttached(nowMs = 10))
        assertEquals(PreviewColorState.DETACHED, p.state)
        assertEquals(PreviewColorOffReason.PROCESSING_ERROR, p.offReason)
    }

    @Test
    fun `the first reason wins`() {
        // Both a ProcessingException and the deadline can fire for the same
        // underlying fault. The one that arrived first is the one worth logging.
        val p = policy(deadlineMs = 1_000L)
        p.onAttached(nowMs = 0)
        p.onProcessingError()
        p.onTick(nowMs = 5_000)
        assertEquals(PreviewColorOffReason.PROCESSING_ERROR, p.offReason)
    }

    @Test
    fun `releasing is not a failure`() {
        // Leaving the camera screen must not be reported as a GL fault, or the one
        // log line that means "this device cannot do colour" stops being rare enough
        // to notice.
        val p = policy()
        p.onAttached(nowMs = 0)
        p.onFrameDrawn()
        p.onReleased()
        assertEquals(PreviewColorState.OFF, p.state)
        assertNull(p.offReason)
    }
}
