package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThrottledSubjectSceneSegmenterTest {

    private val frame = AnalysisFrame(null, 10, 10)

    private fun observation(left: Float = 0.2f) = SegmentationObservation(
        outline = listOf(SegmentationPoint(left, 0.2f)),
        bounds = NormalizedBox(left, 0.2f, left + 0.2f, 0.4f),
        confidence = 0.9f,
        areaRatio = 0.1f,
    )

    /** Replays a fixed script, then repeats its last entry forever. */
    private class Scripted(private val script: List<SegmentationObservation?>) : SubjectSceneSegmenter {
        var calls = 0
            private set

        override fun detect(frame: AnalysisFrame): SegmentationObservation? {
            val value = script[calls.coerceAtMost(script.lastIndex)]
            calls++
            return value
        }

        override fun close() = Unit
    }

    @Test
    fun `segmentation is refreshed sparsely and last result is reused`() {
        val result = observation()
        val delegate = Scripted(listOf(result))
        val throttled = ThrottledSubjectSceneSegmenter(delegate, refreshEveryFrames = 3)

        repeat(5) { assertEquals(result, throttled.detect(frame)) }

        assertEquals(2, delegate.calls)
    }

    /**
     * review_report #15.
     *
     * The cache used to be written as `delegate.detect(frame)?.let { lastResult = it }`,
     * so a null never cleared it. Null is not an exotic case here — it is what
     * `SegmentationMaskReducer` returns when the foreground occupies too few cells,
     * i.e. **literally the subject leaving the frame** — and it is also every
     * timeout and every not-yet-downloaded-model frame.
     *
     * The visible consequence: point the camera at a person for a second, then pan
     * to a blank wall, and the outline keeps being drawn over nothing while the
     * proposal engine keeps reporting a confident subject. The stale mask even
     * outranks live detection, because `subjectBox` prefers `segmented?.bounds`.
     */
    @Test
    fun `a null on a refresh frame clears the cache`() {
        val delegate = Scripted(listOf(observation(), null))
        val throttled = ThrottledSubjectSceneSegmenter(delegate, refreshEveryFrames = 1)

        assertNotNull(throttled.detect(frame))
        assertNull(
            "the subject left the frame — the previous mask must not be served",
            throttled.detect(frame),
        )
    }

    @Test
    fun `a cleared cache stays cleared between refreshes`() {
        val delegate = Scripted(listOf(observation(), null))
        val throttled = ThrottledSubjectSceneSegmenter(delegate, refreshEveryFrames = 2)

        assertNotNull("frame 1 runs the model", throttled.detect(frame))
        assertNull("frame 2 runs the model and it returned null", throttled.detect(frame))
        assertNull("frame 3 serves the cache, which is now empty", throttled.detect(frame))
        assertEquals("frame 3 must not have run the model", 2, delegate.calls)
    }

    /**
     * The counterpart the fix must not break: between refreshes the last decision
     * is still reused. "Clear on null" is about the *refresh* frame, not about
     * dropping the cache on every frame.
     */
    @Test
    fun `a non-null result is still reused between refreshes`() {
        val first = observation(0.2f)
        val delegate = Scripted(listOf(first))
        val throttled = ThrottledSubjectSceneSegmenter(delegate, refreshEveryFrames = 4)

        throttled.detect(frame)
        repeat(2) { assertEquals(first, throttled.detect(frame)) }
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `reset clears the cache`() {
        val delegate = Scripted(listOf(observation()))
        val throttled = ThrottledSubjectSceneSegmenter(delegate, refreshEveryFrames = 4)

        assertNotNull(throttled.detect(frame))
        throttled.reset()
        // After a reset the next frame is frame 1 again, so it runs the model.
        assertEquals(1, delegate.calls)
        assertNotNull(throttled.detect(frame))
        assertEquals(2, delegate.calls)
    }
}
