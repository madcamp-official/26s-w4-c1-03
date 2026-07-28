package com.gamdo.app.ui.camera

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fix for "the first preview gesture after a cold start is lost".
 *
 * ## What the harness models
 *
 * [FakePointerDispatcher] stands in for the only part of
 * `SuspendingPointerInputModifierNodeImpl` that matters here, read off the
 * compose-ui 1.7.5 bytecode of `onPointerEvent`:
 *
 * ```
 * if (pointerInputJob == null) {
 *     launch(scope, null, CoroutineStart.UNDISPATCHED, ...)   // (1) start the handler
 * }
 * dispatchPointerEvent(pointerEvent, pass)                    // (2) deliver, same call
 * ```
 *
 * and of `awaitPointerEventScope`, which does `pointerHandlers.add(...)` and then
 * `createCoroutine(...).resumeWith(...)` — both synchronous, inside the
 * `suspendCancellableCoroutine`.
 *
 * Two consequences, and they are the whole test:
 * - a handler only receives events dispatched **after** it has registered, and
 * - (1) and (2) happen in one call, so the main dispatcher never gets a turn
 *   between starting the handler and delivering the first event.
 *
 * The load-bearing assertion is therefore **registration count at dispatch time**,
 * not what a handler has received by the next line. Whether a registered handler
 * resumes on the same stack or one loop-turn later is a dispatcher detail and
 * changes nothing: an event that arrives while a handler is unregistered is gone
 * for good, which is the drop being reproduced.
 */
class PreviewGesturesTest {

    /**
     * Registration is one-shot, like `PointerEventHandlerCoroutine`: a handler
     * re-registers by calling [awaitEvent] again, and anything dispatched while it
     * is not registered is discarded rather than queued.
     */
    private class FakePointerDispatcher {
        private val waiting = mutableListOf<CancellableContinuation<String>>()

        val registeredCount: Int get() = waiting.size

        suspend fun awaitEvent(): String = suspendCancellableCoroutine { continuation ->
            waiting += continuation
        }

        fun dispatch(event: String) {
            val current = waiting.toList()
            waiting.clear()
            current.forEach { it.resume(event) }
        }
    }

    /**
     * The one that fails without the fix.
     *
     * `bus.dispatch("DOWN")` runs on the same stack that installed the gestures, with
     * nothing in between — exactly like Compose delivering the event that started the
     * handler coroutine. With the handlers started `DISPATCHED` (the plain `launch {}`
     * this replaced) nothing is registered at that point, because both children are
     * still sitting in the dispatcher queue, and the DOWN is discarded.
     */
    @Test
    fun `the very first event reaches both handlers`() = runBlocking {
        val bus = FakePointerDispatcher()
        val tapSeen = mutableListOf<String>()
        val pinchSeen = mutableListOf<String>()

        val handler = launch(start = CoroutineStart.UNDISPATCHED) {
            installPreviewGestures(
                tap = { while (true) tapSeen += bus.awaitEvent() },
                pinch = { while (true) pinchSeen += bus.awaitEvent() },
            )
        }

        assertEquals("both handlers must be registered before any event", 2, bus.registeredCount)

        bus.dispatch("DOWN")
        yield()

        assertEquals(listOf("DOWN"), tapSeen)
        assertEquals(listOf("DOWN"), pinchSeen)

        handler.cancelAndJoin()
    }

    /**
     * Characterises the bug this replaced, so the harness above is not merely
     * asserting its own construction. This test never calls [installPreviewGestures]
     * — it starts a child the way the old code did and shows the child miss the DOWN
     * and then catch the UP, which is precisely what the on-device instrumentation
     * reported for gesture #1 after a cold start: "a Release with no Press".
     *
     * A tap needs both, so `detectTapGestures` never fired. The surviving Release also
     * explains why nothing *else* misbehaved — a lone UP matches no gesture, so the
     * failure was a silently dropped tap and never a phantom one.
     */
    @Test
    fun `a dispatched handler misses the first event and sees only the second`() = runBlocking {
        val bus = FakePointerDispatcher()
        val seen = mutableListOf<String>()

        val handler = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineScope {
                // The old code: CoroutineStart.DEFAULT, i.e. dispatched.
                launch { while (true) seen += bus.awaitEvent() }
            }
        }

        assertEquals("nothing is listening yet", 0, bus.registeredCount)
        bus.dispatch("DOWN")

        yield() // the dispatcher finally gets a turn; the child registers
        assertTrue("the first DOWN is gone, not queued", seen.isEmpty())
        assertEquals(1, bus.registeredCount)

        bus.dispatch("UP")
        yield()
        assertEquals(listOf("UP"), seen)

        handler.cancelAndJoin()
    }

    /**
     * `installPreviewGestures` must not outlive its caller: the pointer-input node
     * cancels the handler job on key change and on detach, and a gesture coroutine
     * that survived that would keep driving a `CameraController` that has been
     * unbound.
     */
    @Test
    fun `cancelling the caller cancels both handlers`() = runBlocking {
        val bus = FakePointerDispatcher()
        var tapAlive = true
        var pinchAlive = true

        val handler = launch(start = CoroutineStart.UNDISPATCHED) {
            installPreviewGestures(
                tap = { try { bus.awaitEvent() } finally { tapAlive = false } },
                pinch = { try { bus.awaitEvent() } finally { pinchAlive = false } },
            )
        }

        handler.cancelAndJoin()

        assertFalse(tapAlive)
        assertFalse(pinchAlive)
    }

    /**
     * The two gestures are siblings, not a sequence: one finishing must leave the
     * other running, so a resolved tap cannot end the pinch handler for the rest of
     * the session.
     */
    @Test
    fun `one handler completing leaves the other running`() = runBlocking {
        val bus = FakePointerDispatcher()
        var installerFinished = false

        val handler = launch(start = CoroutineStart.UNDISPATCHED) {
            installPreviewGestures(
                tap = { bus.awaitEvent() }, // resolves after one event
                pinch = { while (true) bus.awaitEvent() },
            )
            installerFinished = true
        }

        bus.dispatch("DOWN")
        yield()

        assertFalse("pinch is still live, so the installer must not have returned", installerFinished)
        assertEquals("only pinch re-registered", 1, bus.registeredCount)

        handler.cancelAndJoin()
    }
}
