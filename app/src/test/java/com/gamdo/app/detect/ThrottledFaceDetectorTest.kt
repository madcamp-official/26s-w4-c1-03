package com.gamdo.app.detect

import com.gamdo.app.guide.parseGuideConfigBundle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Face detection was the last unthrottled model in the pipeline.
 *
 * SM-G970N, 80 frames, AP ~50°C: face mean **103.0ms / median 103.4ms on every
 * frame**, against a total of mean 405.4 / median 302.6 (3.31 fps). Pose was
 * already at 1/2, segmentation at 1/12, objects at 1/1-by-design. Face was 34% of
 * the median frame and the only stage paying full price every time.
 *
 * The cadence contract is the one [ThrottledObjectSceneDetector] and
 * [ThrottledPoseDetector] already have. What is new here is that the delegate
 * returns a **list**, and the list has its own way of going stale — see
 * `an empty list on a refresh frame clears the cache` and
 * `a shrinking list replaces the cache rather than merging with it`.
 *
 * ## Why the config assertions live in this file
 *
 * `faceRefreshEveryFrames` is parsed by `guide/GuideConfigJson.kt`, but the value
 * only means anything as this class's divisor, and the two must be changed
 * together. `ObjectDetectorWiringTest` sets the precedent for a `detect` test that
 * reaches outside the package to pin a wiring property.
 */
class ThrottledFaceDetectorTest {

    private val frame = AnalysisFrame(image = null, width = 480, height = 640)

    private fun face(left: Float) = FaceObservation(
        box = NormalizedBox(left, 0.2f, left + 0.2f, 0.4f),
        leftEyeOpenProbability = null,
        rightEyeOpenProbability = null,
        headEulerAngleZ = 0f,
    )

    private class Recording(private val results: List<List<FaceObservation>>) : FaceDetector {
        var calls = 0
            private set

        override fun detect(frame: AnalysisFrame): List<FaceObservation> {
            val value = results[calls.coerceAtMost(results.lastIndex)]
            calls++
            return value
        }

        override fun close() = Unit
    }

    @Test
    fun `the model runs on the first frame and then every Nth`() {
        val delegate = Recording(listOf(listOf(face(0.4f))))
        val throttled = ThrottledFaceDetector(delegate, refreshEveryFrames = 2)

        repeat(6) { throttled.detect(frame) }

        // frames 1,2,4,6 — the first frame plus every 2nd. Without the explicit
        // first-frame case the bracket and the brightness face sample would both
        // be missing on the very first analysed frame of every camera open.
        assertEquals(4, delegate.calls)
    }

    @Test
    fun `the cached list is returned between refreshes`() {
        val delegate = Recording(listOf(listOf(face(0.4f))))
        val throttled = ThrottledFaceDetector(delegate, refreshEveryFrames = 2)

        throttled.detect(frame)               // frame 1 — runs (first-frame case)
        val fresh = throttled.detect(frame)   // frame 2 — runs (2 % 2 == 0)
        val cached = throttled.detect(frame)  // frame 3 — reuses

        assertSame("frame 3 must reuse frame 2's list", fresh, cached)
        assertEquals("frame 3 must not have run the model", 2, delegate.calls)
    }

    /**
     * The list-shaped version of the property [ThrottledPoseDetector] states for
     * `null`, and the reason `review_report` #15 is not reintroduced here.
     *
     * For a nullable result the trap is `?.let { lastResult = it }`. For a list it
     * is `takeIf { it.isNotEmpty() }` or `if (result.isNotEmpty()) lastResult = …` —
     * the same bug wearing a collection's clothes. **An empty list is a result, not
     * the absence of one:** it says "no face in this frame", which is precisely
     * what happens when the person walks out. Treating it as "no new information"
     * would leave the bracket drawn on an empty wall and keep
     * `SceneGuideSessionController` feeding a PERSON candidate into the 3/5
     * tracker forever.
     */
    @Test
    fun `an empty list on a refresh frame clears the cache`() {
        val delegate = Recording(listOf(listOf(face(0.4f)), emptyList()))
        val throttled = ThrottledFaceDetector(delegate, refreshEveryFrames = 1)

        assertEquals(1, throttled.detect(frame).size)
        assertTrue(
            "losing the face must not serve the previous list",
            throttled.detect(frame).isEmpty(),
        )
    }

    /** And the cleared cache stays cleared on the frames in between. */
    @Test
    fun `a cleared cache stays cleared between refreshes`() {
        val delegate = Recording(listOf(listOf(face(0.4f)), emptyList()))
        val throttled = ThrottledFaceDetector(delegate, refreshEveryFrames = 2)

        assertEquals("frame 1 runs the model", 1, throttled.detect(frame).size)
        assertTrue("frame 2 runs the model and it found nothing", throttled.detect(frame).isEmpty())
        assertTrue("frame 3 serves the cache, which is now empty", throttled.detect(frame).isEmpty())
        assertEquals("frame 3 must not have run the model", 2, delegate.calls)
    }

    /**
     * A list can go stale in a second way a nullable cannot: in its **count**.
     *
     * Two people in frame, one leaves. The refresh is non-empty either way, so an
     * emptiness check would not catch this — only a whole-value replacement does.
     * The count is load-bearing: `CameraViewModel` logs `detection.faces.size` and
     * both `SceneGuideSessionController` and `SceneProposalEngine` pick the
     * *largest* face, so a retained second entry can win the selection and anchor
     * the guide on someone who has gone.
     */
    @Test
    fun `a shrinking list replaces the cache rather than merging with it`() {
        val delegate = Recording(
            listOf(
                listOf(face(0.1f), face(0.6f)),
                listOf(face(0.1f)),
            ),
        )
        val throttled = ThrottledFaceDetector(delegate, refreshEveryFrames = 1)

        assertEquals(2, throttled.detect(frame).size)
        assertEquals("the departed face must not survive in the cache", 1, throttled.detect(frame).size)
    }

    @Test
    fun `reset returns it to the first-frame state`() {
        val delegate = Recording(listOf(listOf(face(0.4f))))
        val throttled = ThrottledFaceDetector(delegate, refreshEveryFrames = 3)

        repeat(3) { throttled.detect(frame) }
        val before = delegate.calls
        throttled.reset()
        throttled.detect(frame)

        assertEquals("the frame after a reset must run the model", before + 1, delegate.calls)
        assertTrue("reset must also drop the cached faces", throttled.detect(frame).isNotEmpty())
    }

    @Test
    fun `a divisor below one is rejected rather than silently disabling the throttle`() {
        for (bad in listOf(0, -1)) {
            runCatching { ThrottledFaceDetector(Recording(listOf(emptyList())), refreshEveryFrames = bad) }
                .onSuccess { throw AssertionError("refreshEveryFrames=$bad should not be accepted") }
        }
    }

    /**
     * CFG-1: the divisor is an asset value, not a code default, exactly like
     * `objectRefreshEveryFrames` and `segmentationRefreshEveryFrames`. A typo in
     * the asset must fail here rather than quietly fall back to the code default
     * on a device.
     */
    @Test
    fun `the shipped asset supplies the face divisor`() {
        val bundle = parseGuideConfigBundle(readAsset("guide_config.json"))

        assertEquals(
            "faceRefreshEveryFrames must come from the asset. 103.0ms mean per frame " +
                "against a 302.6ms median total; 1/2 buys ~51ms and costs one frame of " +
                "notice latency, and the next halving only buys ~17ms more.",
            2,
            bundle.objectGuide.faceRefreshEveryFrames,
        )
    }

    /**
     * The end-to-end shape of the line the camera host has to write. If the asset
     * value stops reaching the constructor, the throttle silently reverts to its
     * code default and the 103ms would come back without anything failing.
     */
    @Test
    fun `the asset divisor drives the cadence when wired through`() {
        val bundle = parseGuideConfigBundle(
            readAsset("guide_config.json").replace("\"faceRefreshEveryFrames\": 2", "\"faceRefreshEveryFrames\": 4"),
        )
        val delegate = Recording(listOf(listOf(face(0.4f))))
        val throttled = ThrottledFaceDetector(
            delegate,
            refreshEveryFrames = bundle.objectGuide.faceRefreshEveryFrames,
        )

        repeat(8) { throttled.detect(frame) }

        // frames 1, 4, 8 with a divisor of 4 — not the 5 calls a divisor of 2 gives.
        assertEquals("the asset value, not the code default, must set the cadence", 3, delegate.calls)
    }

    @Test
    fun `an asset divisor below one is rejected at parse time`() {
        val broken = readAsset("guide_config.json")
            .replace("\"faceRefreshEveryFrames\": 2", "\"faceRefreshEveryFrames\": 0")

        runCatching { parseGuideConfigBundle(broken) }
            .onSuccess { throw AssertionError("faceRefreshEveryFrames=0 should not parse") }
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
