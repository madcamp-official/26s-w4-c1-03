package com.gamdo.app.edit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * §4-2: what a drag costs.
 *
 * The adjustment ruler emits a value per drag tick and the screen re-filtered the
 * whole preview bitmap for each one, so a one-second drag started sixty full-frame
 * passes — most of them already superseded before they finished, all of them
 * allocating. These tests pin the rule that replaces that: **one render at a time,
 * and the ticks that pile up behind it collapse to the newest**.
 *
 * ## Why the handshakes instead of `delay`
 *
 * `kotlinx-coroutines-test` is not on this module's test classpath, so there is no
 * virtual clock and a timing-based version of this test would be a flake generator.
 * Channels give the same thing deterministically: the render announces that it
 * started and then blocks until the test releases it, so "requests that arrive
 * while a render is running" is a fact the test establishes rather than one it
 * hopes for. `withTimeout` is there so a regression that deadlocks fails in ten
 * seconds instead of hanging the build.
 */
class PreviewRenderLoopTest {

    @Test
    fun `sixty ticks arriving during one render cost two renders, not sixty`() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val renderStarted = Channel<Int>(Channel.UNLIMITED)
            // Rendezvous, so the test cannot release a render before one is waiting.
            val letRenderFinish = Channel<Unit>(Channel.RENDEZVOUS)
            val firstRenderBegan = CompletableDeferred<Unit>()
            val published = mutableListOf<Int>()
            var renders = 0

            val ticks = flow {
                emit(0)
                // Hold until the render of tick 0 is underway. Everything after this
                // point genuinely arrives *during* a render, which is the situation a
                // finger on the ruler puts the screen in.
                firstRenderBegan.await()
                for (tick in 1..60) emit(tick)
            }

            val loop = launch {
                renderLatest(
                    requests = ticks,
                    render = { tick ->
                        renders++
                        renderStarted.send(tick)
                        firstRenderBegan.complete(Unit)
                        letRenderFinish.receive()
                        tick
                    },
                    publish = { _, tick -> published += tick },
                )
            }

            assertEquals("the first tick renders immediately", 0, renderStarted.receive())
            letRenderFinish.send(Unit)

            // The whole fix in one assertion. Ticks 1..59 were superseded before the
            // renderer was free, so they are never rendered at all; tick 60 is where
            // the finger actually is.
            assertEquals("the next render is the newest tick, not the next one", 60, renderStarted.receive())
            letRenderFinish.send(Unit)

            loop.join()
            assertEquals(listOf(0, 60), published)
            assertEquals(2, renders)
        }
    }

    /**
     * A ruler pushed against the end of its range keeps reporting the same number.
     * Re-filtering for those is work whose output cannot differ by a single pixel.
     */
    @Test
    fun `a value the ruler repeats costs nothing`() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val renderStarted = Channel<Int>(Channel.UNLIMITED)
            val letRenderFinish = Channel<Unit>(Channel.RENDEZVOUS)
            val firstRenderBegan = CompletableDeferred<Unit>()
            val published = mutableListOf<Int>()
            var renders = 0

            val ticks = flow {
                emit(100)
                firstRenderBegan.await()
                repeat(20) { emit(100) }
            }

            val loop = launch {
                renderLatest(
                    requests = ticks,
                    render = { tick ->
                        renders++
                        renderStarted.send(tick)
                        firstRenderBegan.complete(Unit)
                        letRenderFinish.receive()
                        tick
                    },
                    publish = { _, tick -> published += tick },
                )
            }

            assertEquals(100, renderStarted.receive())
            letRenderFinish.send(Unit)

            loop.join()
            assertEquals(listOf(100), published)
            assertEquals(1, renders)
        }
    }

    /**
     * Conflation must never eat the *last* request: the picture the finger stops on
     * is the picture that has to end up on screen. Here the burst arrives with no
     * render in flight at all, so the loop is free to run — and the one thing that
     * cannot happen is finishing on anything but 60.
     */
    @Test
    fun `whatever else is dropped, the last request is always the one published`() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val published = mutableListOf<Int>()
            renderLatest(
                requests = flow { for (tick in 0..60) emit(tick) },
                render = { it },
                publish = { _, tick -> published += tick },
            )
            assertEquals(60, published.last())
        }
    }

    @Test
    fun `a buffer of the right size is handed back rather than reallocated`() {
        val first = pixelBuffer(null, 40, 50)
        assertEquals(2000, first.size)
        assertSame("same frame size, same buffer", first, pixelBuffer(first, 40, 50))
        // Same pixel count, different shape: still the right length, so still reusable.
        assertSame(first, pixelBuffer(first, 50, 40))
    }

    @Test
    fun `a buffer of the wrong size is replaced, never handed back short`() {
        val small = pixelBuffer(null, 10, 10)
        val bigger = pixelBuffer(small, 40, 50)
        assertNotSame(small, bigger)
        assertEquals(2000, bigger.size)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
