package com.gamdo.app.detect

import com.gamdo.app.guide.parseGuideConfigBundle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pose ran on every analysed frame and cost 89.8ms of a measured 263ms budget on
 * SM-G970N — the second largest cost in the pipeline, and it does not get cheaper
 * when nobody is in frame. Halving its cadence is the cheapest real saving
 * available (owner decision, 2026-07-28).
 *
 * The cadence contract is the same one [ThrottledObjectSceneDetector] already has.
 * What is deliberately **not** copied is `ThrottledSubjectSceneSegmenter`'s cache
 * behaviour — see `a null on a refresh frame clears the cache` below.
 */
class ThrottledPoseDetectorTest {

    private val frame = AnalysisFrame(image = null, width = 480, height = 640)

    private fun pose(likelihood: Float) = PoseObservation(
        landmarks = listOf(PoseLandmarkPoint(0, 0.5f, 0.5f, likelihood)),
        averageInFrameLikelihood = likelihood,
    )

    private class Recording(private val results: List<PoseObservation?>) : PoseDetector {
        var calls = 0
            private set

        override fun detect(frame: AnalysisFrame): PoseObservation? {
            val value = results[calls.coerceAtMost(results.lastIndex)]
            calls++
            return value
        }

        override fun close() = Unit
    }

    @Test
    fun `the model runs on the first frame and then every Nth`() {
        val delegate = Recording(listOf(pose(0.9f)))
        val throttled = ThrottledPoseDetector(delegate, refreshEveryFrames = 2)

        repeat(6) { throttled.detect(frame) }

        // frames 1,2,4,6 → the first frame plus every 2nd. Without the explicit
        // first-frame case the guide would have no pose at all until frame 2.
        assertEquals(4, delegate.calls)
    }

    /**
     * Note which frames actually reuse. With `refreshEveryFrames = 2` the run
     * frames are 1, 2, 4, 6 — frame 1 is a special case and frame 2 satisfies the
     * divisor, so the *first* reused frame is the third one, not the second. The
     * one-off first frame is why the long-run rate converges to 1/2 rather than
     * being exactly 1/2 from the start. This matches [ThrottledObjectSceneDetector]
     * exactly; the two wrappers must not drift.
     */
    @Test
    fun `the cached result is returned between refreshes`() {
        val delegate = Recording(listOf(pose(0.9f)))
        val throttled = ThrottledPoseDetector(delegate, refreshEveryFrames = 2)

        throttled.detect(frame)               // frame 1 — runs (first-frame case)
        val fresh = throttled.detect(frame)   // frame 2 — runs (2 % 2 == 0)
        val cached = throttled.detect(frame)  // frame 3 — reuses

        assertSame("frame 3 must reuse frame 2's result", fresh, cached)
        assertEquals("frame 3 must not have run the model", 2, delegate.calls)
    }

    /**
     * The property that keeps `review_report` #15 from being duplicated here.
     *
     * `ThrottledSubjectSceneSegmenter` writes its cache as
     * `delegate.detect(frame)?.let { lastResult = it }`, so a null never clears it
     * — once a mask has succeeded, a stale one is served for the rest of the
     * session even after the subject walks out of frame. A pose cache with that
     * behaviour would keep a person silhouette on an empty wall.
     *
     * Here the refresh frame assigns unconditionally: a null result means "no pose
     * now", and that is what gets served.
     */
    @Test
    fun `a null on a refresh frame clears the cache`() {
        val delegate = Recording(listOf(pose(0.9f), null))
        val throttled = ThrottledPoseDetector(delegate, refreshEveryFrames = 1)

        assertNotNull(throttled.detect(frame))
        assertNull("losing the subject must not serve the previous pose", throttled.detect(frame))
    }

    /**
     * And the cleared cache stays cleared on the frames in between, rather than
     * the previous non-null result reappearing.
     */
    @Test
    fun `a cleared cache stays cleared between refreshes`() {
        val delegate = Recording(listOf(pose(0.9f), null))
        val throttled = ThrottledPoseDetector(delegate, refreshEveryFrames = 2)

        assertNotNull("frame 1 runs the model", throttled.detect(frame))
        assertNull("frame 2 runs the model and it returned null", throttled.detect(frame))
        assertNull("frame 3 serves the cache, which is now empty", throttled.detect(frame))
        assertEquals("frame 3 must not have run the model", 2, delegate.calls)
    }

    @Test
    fun `reset returns it to the first-frame state`() {
        val delegate = Recording(listOf(pose(0.9f)))
        val throttled = ThrottledPoseDetector(delegate, refreshEveryFrames = 3)

        repeat(3) { throttled.detect(frame) }
        val before = delegate.calls
        throttled.reset()
        throttled.detect(frame)

        assertEquals("the frame after a reset must run the model", before + 1, delegate.calls)
    }

    @Test
    fun `a divisor below one is rejected rather than silently disabling the throttle`() {
        for (bad in listOf(0, -1)) {
            runCatching { ThrottledPoseDetector(Recording(listOf(null)), refreshEveryFrames = bad) }
                .onSuccess { throw AssertionError("refreshEveryFrames=$bad should not be accepted") }
        }
    }

    /**
     * The end-to-end shape of the line the camera host has to write, mirroring
     * `the asset divisor drives the cadence when wired through` in
     * [ThrottledFaceDetectorTest].
     *
     * Pose was the last divisor in the pipeline still compiled in, so this is the
     * test that would have caught its absence: if the asset value stops reaching the
     * constructor the throttle silently reverts to its code default, and the 89.8ms
     * comes back with nothing failing. Parsing the shipped asset rather than a
     * literal also pins `poseRefreshEveryFrames` as a real key — a typo in
     * `guide_config.json` fails here instead of on a device.
     */
    @Test
    fun `the asset divisor drives the cadence when wired through`() {
        val bundle = parseGuideConfigBundle(
            readAsset("guide_config.json").replace("\"poseRefreshEveryFrames\": 2", "\"poseRefreshEveryFrames\": 4"),
        )
        val delegate = Recording(listOf(pose(0.9f)))
        val throttled = ThrottledPoseDetector(
            delegate,
            refreshEveryFrames = bundle.objectGuide.poseRefreshEveryFrames,
        )

        repeat(8) { throttled.detect(frame) }

        // frames 1, 4, 8 with a divisor of 4 — not the 5 calls a divisor of 2 gives.
        assertEquals("the asset value, not the code default, must set the cadence", 3, delegate.calls)
    }

    private fun readAsset(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf("src/main/assets/$name", "app/src/main/assets/$name")) {
                val file = File(dir, candidate)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        error("assets/$name not found from ${System.getProperty("user.dir")}")
    }
}
