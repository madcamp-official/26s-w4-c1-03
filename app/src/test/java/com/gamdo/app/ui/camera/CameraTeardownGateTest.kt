package com.gamdo.app.ui.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera release, and the two ways deferring it could be worse than the bug.
 *
 * `CameraScreen`'s `onDispose` used to unbind unconditionally, which aborts an
 * in-flight capture inside CameraX (see [CameraTeardownGate]'s KDoc). Waiting for
 * the capture fixes that and introduces two failures of its own, both invisible
 * from a screenshot and neither reachable from `testDebugUnitTest` through the
 * screen itself:
 *
 *  - a release arriving **after the next camera has bound**, which — because
 *    `unbind()` is process-wide `unbindAll()` — tears down the camera that is
 *    currently on screen. A dead preview is worse than a lost photo.
 *  - a release that **never arrives**, leaving the camera and Android 12's
 *    camera-in-use indicator on after the user has left the screen.
 *
 * Every test below drives the clock and the ordering by hand, which is the whole
 * reason the decision was extracted from the `@Composable` in the first place.
 */
class CameraTeardownGateTest {

    /** Records that a teardown ran, so ordering assertions have something to read. */
    private class Camera(val name: String) {
        var released = 0
            private set

        val unbind: () -> Unit = { released += 1 }
    }

    private fun gate() = CameraTeardownGate(maxDeferMs = 4_000L)

    private val t0 = 1_000_000_000L
    private fun msAfter(ms: Long) = t0 + ms * 1_000_000L

    // ---- the ordinary paths ---------------------------------------------

    @Test
    fun `with no capture in flight the screen releases its own camera`() {
        val gate = gate()
        val camera = Camera("only")

        val release = gate.screenDisposed(nowNs = t0, teardown = camera.unbind)

        assertNotNull("nothing is waiting, so nothing should be deferred", release)
        assertFalse(gate.hasDeferredTeardown)
        release!!()
        assertEquals(1, camera.released)
    }

    @Test
    fun `a capture in flight defers the release until it finishes`() {
        val gate = gate()
        val camera = Camera("only")

        val token = gate.captureStarted()
        assertNull(
            "the camera must stay bound while CameraX still owes us an image",
            gate.screenDisposed(nowNs = t0, teardown = camera.unbind),
        )
        assertTrue(gate.hasDeferredTeardown)
        assertEquals(0, camera.released)

        gate.captureFinished(token)!!()
        assertEquals(1, camera.released)
        assertFalse(gate.hasDeferredTeardown)
    }

    @Test
    fun `the release waits for the last of several captures`() {
        val gate = gate()
        val camera = Camera("only")

        val first = gate.captureStarted()
        val second = gate.captureStarted()
        gate.screenDisposed(nowNs = t0, teardown = camera.unbind)

        assertNull("one of two finished is not all of them", gate.captureFinished(first))
        assertEquals(0, camera.released)

        gate.captureFinished(second)!!()
        assertEquals(1, camera.released)
    }

    /**
     * The invariant the caller's watchdog scheduling rests on.
     *
     * `onDispose` reads `if (hasDeferredTeardown) scheduleTeardownWatchdog()`. If
     * [screenDisposed] could ever return null *without* leaving the deferral
     * visible, that branch would not fire and the release would have no timer at
     * all — and since the camera is bound to the Activity now, nothing else would
     * ever come for it. So: exactly one of "handed back" or "visibly deferred", on
     * every path.
     */
    @Test
    fun `disposing always either releases or leaves a visible deferral`() {
        for (inFlight in 0..3) {
            val gate = gate()
            val camera = Camera("only")
            repeat(inFlight) { gate.captureStarted() }

            val release = gate.screenDisposed(nowNs = t0, teardown = camera.unbind)

            assertEquals(
                "with $inFlight capture(s) in flight, exactly one of {handed back, " +
                    "deferred} must be true — never neither, which is a camera nobody " +
                    "will release, and never both.",
                release == null,
                gate.hasDeferredTeardown,
            )
        }
    }

    @Test
    fun `every claim path clears the deferral it spent`() {
        val byCapture = gate()
        val token = byCapture.captureStarted()
        byCapture.screenDisposed(nowNs = t0, teardown = Camera("a").unbind)
        byCapture.captureFinished(token)
        assertFalse("captureFinished must clear it", byCapture.hasDeferredTeardown)

        val byBind = gate()
        byBind.captureStarted()
        byBind.screenDisposed(nowNs = t0, teardown = Camera("b").unbind)
        byBind.releaseBeforeBind()
        assertFalse("releaseBeforeBind must clear it", byBind.hasDeferredTeardown)

        val byWatchdog = gate()
        byWatchdog.captureStarted()
        byWatchdog.screenDisposed(nowNs = t0, teardown = Camera("c").unbind)
        byWatchdog.releaseIfExpired(msAfter(4_000))
        assertFalse("the watchdog must clear it", byWatchdog.hasDeferredTeardown)
    }

    // ---- a late release must not touch a live camera --------------------

    /**
     * The scenario: shutter, 앨범, straight back to the camera before the capture
     * finished.
     *
     * The new screen spends the old release before binding, so by the time the old
     * capture finishes there is nothing left for it to release. Without this the
     * finishing capture would call `unbindAll()` on a camera that is on screen.
     */
    @Test
    fun `a capture finishing after the next bind releases nothing`() {
        val gate = gate()
        val old = Camera("old")
        val new = Camera("new")

        val token = gate.captureStarted()
        gate.screenDisposed(nowNs = t0, teardown = old.unbind)

        // The next camera screen composes and spends the deferral before binding.
        gate.releaseBeforeBind()!!()
        assertEquals("the old camera is released before the new one binds", 1, old.released)

        // …and only now does the capture that was holding it up come back.
        assertNull(
            "a release arriving after the next bind must be a no-op — it is " +
                "unbindAll(), so running it would tear down the camera on screen",
            gate.captureFinished(token),
        )
        assertEquals("the camera that is on screen must not be touched", 0, new.released)
        assertEquals(1, old.released)
    }

    /**
     * The same hazard one generation further out, which a simple boolean would get
     * wrong: an *older* screen's capture must not release a *newer* screen's
     * deferral. This is the shape `AnalysisPauseGate.resume(token)` solves with an
     * epoch, and the stamp on the deferral is what solves it here.
     */
    @Test
    fun `an older screen's capture cannot release a newer screen's camera`() {
        val gate = gate()
        val first = Camera("first")
        val second = Camera("second")

        // Screen 1 leaves with a capture still running.
        val staleToken = gate.captureStarted()
        gate.screenDisposed(nowNs = t0, teardown = first.unbind)

        // Screen 2 binds — which spends screen 1's release — takes its own photo,
        // and leaves while that one is still running.
        gate.releaseBeforeBind()!!()
        val liveToken = gate.captureStarted()
        gate.screenDisposed(nowNs = msAfter(100), teardown = second.unbind)
        assertTrue(gate.hasDeferredTeardown)

        // Screen 1's forgotten capture finally comes back.
        assertNull(
            "a token from a retired screen must not release the deferral belonging " +
                "to a later one — screen 2's capture is still in flight",
            gate.captureFinished(staleToken),
        )
        assertEquals(0, second.released)

        // Screen 2's own capture is the only thing that may release it.
        gate.captureFinished(liveToken)!!()
        assertEquals(1, second.released)
        assertEquals("screen 1's camera was released exactly once, at the bind", 1, first.released)
    }

    @Test
    fun `a deferral is claimed once, however many claimants there are`() {
        val gate = gate()
        val camera = Camera("only")

        val token = gate.captureStarted()
        gate.screenDisposed(nowNs = t0, teardown = camera.unbind)

        assertNotNull(gate.releaseBeforeBind())
        assertNull("second claim finds nothing", gate.releaseBeforeBind())
        assertNull("the watchdog finds nothing either", gate.releaseIfExpired(msAfter(9_999)))
        assertNull("and neither does the capture", gate.captureFinished(token))
    }

    @Test
    fun `a token nobody handed out releases nothing`() {
        val gate = gate()
        val camera = Camera("only")

        val token = gate.captureStarted()
        gate.screenDisposed(nowNs = t0, teardown = camera.unbind)

        assertNull(gate.captureFinished(token + 999))
        assertNull(gate.captureFinished(CameraTeardownGate.NO_GENERATION))
        assertEquals("only the real token may release", 0, camera.released)

        gate.captureFinished(token)!!()
        assertEquals(1, camera.released)
    }

    // ---- a release that never arrives -----------------------------------

    @Test
    fun `a deferral before its deadline is still a deferral`() {
        val gate = gate()
        val camera = Camera("only")

        gate.captureStarted()
        gate.screenDisposed(nowNs = t0, teardown = camera.unbind)

        assertNull(gate.releaseIfExpired(msAfter(3_999)))
        assertEquals(0, camera.released)
        assertEquals(0, gate.expiredDefers)
    }

    @Test
    fun `a deferral whose capture never finishes expires on its own`() {
        val gate = gate()
        val camera = Camera("only")

        gate.captureStarted()
        gate.screenDisposed(nowNs = t0, teardown = camera.unbind)

        gate.releaseIfExpired(msAfter(4_000))!!()

        assertEquals(
            "the camera — and Android 12's in-use indicator — must not stay on " +
                "because a capture wedged",
            1,
            camera.released,
        )
        assertEquals("an expiry is a defect and has to be countable", 1, gate.expiredDefers)
        assertFalse(gate.hasDeferredTeardown)
    }

    @Test
    fun `an expiry is counted once, not once per poke`() {
        val gate = gate()
        val camera = Camera("only")

        gate.captureStarted()
        gate.screenDisposed(nowNs = t0, teardown = camera.unbind)

        gate.releaseIfExpired(msAfter(4_000))!!()
        assertNull(gate.releaseIfExpired(msAfter(5_000)))
        assertNull(gate.releaseIfExpired(msAfter(9_000)))

        assertEquals(1, gate.expiredDefers)
        assertEquals("and the camera is released once, not three times", 1, camera.released)
    }

    @Test
    fun `nothing expires when nothing was deferred`() {
        val gate = gate()
        gate.screenDisposed(nowNs = t0, teardown = Camera("only").unbind)

        assertNull(gate.releaseIfExpired(msAfter(60_000)))
        assertEquals(0, gate.expiredDefers)
    }

    /**
     * The expired capture eventually comes back. It owns nothing by then, and
     * running the release a second time would `unbindAll()` whatever is bound —
     * the same hazard as the late-arrival case, reached the other way.
     */
    @Test
    fun `a capture that finishes after its deferral expired releases nothing`() {
        val gate = gate()
        val camera = Camera("only")

        val token = gate.captureStarted()
        gate.screenDisposed(nowNs = t0, teardown = camera.unbind)
        gate.releaseIfExpired(msAfter(4_000))!!()

        assertNull(gate.captureFinished(token))
        assertEquals(1, camera.released)
    }

    // ---- construction ---------------------------------------------------

    @Test
    fun `the default deadline matches the pause gate's, and for the same reason`() {
        assertEquals(
            "sized against the measured 2247ms shutter→saved, and deliberately not " +
                "tighter: an expiry here throws away a real photo.",
            com.gamdo.app.camera.AnalysisPauseGate.DEFAULT_MAX_PAUSE_MS,
            CameraTeardownGate.DEFAULT_MAX_DEFER_MS,
        )
        assertEquals(CameraTeardownGate.DEFAULT_MAX_DEFER_MS, CameraTeardownGate().maxDeferMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive deadline is refused`() {
        CameraTeardownGate(maxDeferMs = 0L)
    }

    @Test
    fun `no generation can collide with the empty stamp`() {
        assertTrue(
            "captureStarted() hands out generations from FIRST_GENERATION upwards; " +
                "if NO_GENERATION were reachable, a token could release a deferral " +
                "that does not exist.",
            CameraTeardownGate.FIRST_GENERATION > CameraTeardownGate.NO_GENERATION,
        )
        assertEquals(CameraTeardownGate.FIRST_GENERATION, gate().captureStarted())
    }

    // ---- wiring ----------------------------------------------------------
    //
    // A correct gate nobody calls is the same screen as no gate, and `CameraScreen`
    // is a `@Composable` — no androidTest source set, no Robolectric, so it cannot
    // be composed here. Same approach as `DebugHudGateTest`.

    private val screenSource = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")

    private fun code(): List<String> = KotlinSourceProbe.codeLines(screenSource)

    @Test
    fun `the screen never unbinds without asking the gate`() {
        val offenders = code().withIndex()
            .filter { (_, line) -> line.contains("controller.unbind()") }
            .filterNot { (_, line) ->
                // The two sanctioned forms: handed to the gate at dispose, and the
                // lambda the gate hands back. Both go through `cameraTeardownGate`.
                line.contains("cameraTeardownGate.screenDisposed")
            }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
        assertEquals(
            "`unbind()` aborts any in-flight capture inside CameraX. Every call has " +
                "to go through CameraTeardownGate, which is what knows whether one " +
                "is running.\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Ordering, and the reason this is a source assertion rather than a comment:
     * `releaseBeforeBind()` *after* `bind()` would silently unbind the camera it
     * was supposed to protect, and the symptom — an occasional black preview after
     * a fast album round trip — would not point back here.
     */
    @Test
    fun `the deferred release is spent before the new camera binds`() {
        val lines = code()
        val release = lines.indexOfFirst { it.contains("cameraTeardownGate.releaseBeforeBind()") }
        val bind = lines.indexOfFirst { it.contains("controller.bind(") }
        assertTrue("releaseBeforeBind() call disappeared — repoint this test.", release >= 0)
        assertTrue("controller.bind() call disappeared — repoint this test.", bind >= 0)
        assertTrue(
            "releaseBeforeBind() is at line ${release + 1}, after bind() at line " +
                "${bind + 1}. unbind() is process-wide unbindAll(), so in that order " +
                "the old screen's deferral tears down the new screen's camera.",
            release < bind,
        )
    }

    @Test
    fun `every capture claims and releases the teardown`() {
        val lines = code()
        assertTrue(
            "the shutter must claim the camera for the length of the capture",
            lines.any { it.contains("cameraTeardownGate.captureStarted()") },
        )
        val finish = lines.indexOfFirst { it.contains("cameraTeardownGate.captureFinished(") }
        assertTrue("captureFinished() call disappeared — repoint this test.", finish >= 0)
        val finallyAt = lines.indexOfLast { it.indexOf("} finally {") >= 0 && it.trim() == "} finally {" }
        assertTrue("the shutter's `finally` disappeared — repoint this test.", finallyAt >= 0)
        assertTrue(
            "captureFinished() is at line ${finish + 1}, outside the shutter's " +
                "`finally` at line ${finallyAt + 1}. A claim released only on the " +
                "success path is a camera that stays bound after a failed capture.",
            finish > finallyAt,
        )
    }

    @Test
    fun `a deferred release is always given a watchdog`() {
        val lines = code()
        val defer = lines.indexOfFirst { it.contains("cameraTeardownGate.screenDisposed") }
        val watchdog = lines.indexOfFirst { it.contains("scheduleTeardownWatchdog()") }
        assertTrue("screenDisposed() call disappeared — repoint this test.", defer >= 0)
        assertTrue(
            "a deferral with no timer is a camera that can stay open for the rest " +
                "of the session — scheduleTeardownWatchdog() must be called at the " +
                "point the release is deferred.",
            watchdog > defer,
        )
    }
}
