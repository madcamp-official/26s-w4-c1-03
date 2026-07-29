package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Face and pose are both throttled to 1/2, and until this test they ran on the
 * *same* frames.
 *
 * Both wrappers count from the same starting point and both refresh on
 * `frameCount % 2 == 0`, so their costs landed together. Measured on SM-G970N
 * after the face throttle was wired, the frame time split in two:
 *
 * ```
 * heavy (face + pose + object)  median 376ms
 * light (object only)           median 118ms
 * ```
 *
 * Total work per two frames is identical either way — this changes nothing about
 * throughput, and the FPS number does not move. What it changes is the **worst
 * case**. Under `STRATEGY_KEEP_ONLY_LATEST` a 376ms frame is 376ms in which every
 * arriving frame is dropped, so the guide updates in a lurch-and-wait rhythm
 * rather than a steady one. Splitting the pair across alternate frames turns
 * 376/118 into two frames of roughly 230ms each.
 *
 * The property below is the whole point and is worth stating plainly: **after the
 * first frame, no frame runs both models.** Frame 1 is the deliberate exception —
 * both wrappers have an explicit first-frame case so the guide is not missing a
 * silhouette or a bracket on the very first analysed frame of a camera open, and
 * paying one heavy frame once at startup is the right trade for that.
 */
class DetectorPhaseInterleaveTest {

    private val frame = AnalysisFrame(image = null, width = 480, height = 640)

    private class CountingFace : FaceDetector {
        var calls = 0
            private set
        val refreshedOn = mutableListOf<Int>()

        override fun detect(frame: AnalysisFrame): List<FaceObservation> {
            calls++
            return emptyList()
        }

        override fun close() = Unit
    }

    private class CountingPose : PoseDetector {
        var calls = 0
            private set

        override fun detect(frame: AnalysisFrame): PoseObservation? {
            calls++
            return null
        }

        override fun close() = Unit
    }

    /** Runs both throttles over [frames] frames and returns the 1-based frame numbers each refreshed on. */
    private fun run(
        frames: Int,
        faceDivisor: Int = 2,
        poseDivisor: Int = 2,
        posePhase: Int = 1,
    ): Pair<List<Int>, List<Int>> {
        val faceDelegate = CountingFace()
        val poseDelegate = CountingPose()
        val face = ThrottledFaceDetector(faceDelegate, refreshEveryFrames = faceDivisor)
        val pose = ThrottledPoseDetector(
            poseDelegate,
            refreshEveryFrames = poseDivisor,
            phaseOffset = posePhase,
        )
        val faceFrames = mutableListOf<Int>()
        val poseFrames = mutableListOf<Int>()
        var facePrev = 0
        var posePrev = 0
        for (n in 1..frames) {
            face.detect(frame)
            pose.detect(frame)
            if (faceDelegate.calls > facePrev) faceFrames += n
            if (poseDelegate.calls > posePrev) poseFrames += n
            facePrev = faceDelegate.calls
            posePrev = poseDelegate.calls
        }
        return faceFrames to poseFrames
    }

    @Test
    fun `after the first frame the two models never refresh together`() {
        val (faceFrames, poseFrames) = run(frames = 20)

        val collisions = faceFrames.intersect(poseFrames.toSet()) - 1
        assertEquals(
            "face refreshed on $faceFrames, pose on $poseFrames — these must not " +
                "overlap after frame 1, or the two costs land on the same frame and " +
                "produce the 376ms/118ms split this offset exists to remove.",
            emptySet<Int>(),
            collisions,
        )
    }

    /** Frame 1 is the documented exception, not an accident. */
    @Test
    fun `both models still run on the very first frame`() {
        val (faceFrames, poseFrames) = run(frames = 4)

        assertTrue("face must run on frame 1", 1 in faceFrames)
        assertTrue("pose must run on frame 1", 1 in poseFrames)
    }

    /**
     * The offset must not become a second throttle. Over a long window pose still
     * runs about every other frame — if this drifted it would be a cadence change
     * wearing a phase change's clothes, and the latency argument for the divisor
     * (documented on `poseRefreshEveryFrames`) would no longer hold.
     */
    @Test
    fun `the offset shifts which frames run without changing how many`() {
        val (_, shifted) = run(frames = 100, posePhase = 1)
        val (_, unshifted) = run(frames = 100, posePhase = 0)

        assertTrue(
            "pose refreshed ${shifted.size} times with the offset vs ${unshifted.size} " +
                "without; the offset must move the frames, not thin them out",
            kotlin.math.abs(shifted.size - unshifted.size) <= 1,
        )
        assertTrue("pose must still run about every other frame", shifted.size in 48..52)
    }

    /**
     * Pins the behaviour this change replaced, so a later reader can see that the
     * collision was real and not a theoretical concern.
     */
    @Test
    fun `without the offset every pose frame collides with a face frame`() {
        val (faceFrames, poseFrames) = run(frames = 20, posePhase = 0)

        assertEquals(
            "with phaseOffset = 0 the two wrappers are identical and every refresh collides",
            faceFrames,
            poseFrames,
        )
    }

    @Test
    fun `a negative offset is rejected`() {
        runCatching { ThrottledPoseDetector(CountingPose(), refreshEveryFrames = 2, phaseOffset = -1) }
            .onSuccess { throw AssertionError("phaseOffset = -1 should not be accepted") }
    }

    /**
     * A reset restarts the counter, so the first frame after it is the first-frame
     * case again for both — one heavy frame, then the interleave resumes.
     */
    @Test
    fun `the interleave resumes after a reset`() {
        val faceDelegate = CountingFace()
        val poseDelegate = CountingPose()
        val face = ThrottledFaceDetector(faceDelegate, refreshEveryFrames = 2)
        val pose = ThrottledPoseDetector(poseDelegate, refreshEveryFrames = 2, phaseOffset = 1)

        repeat(5) { face.detect(frame); pose.detect(frame) }
        face.reset()
        pose.reset()

        val faceBefore = faceDelegate.calls
        val poseBefore = poseDelegate.calls
        face.detect(frame)
        pose.detect(frame)
        assertEquals("frame 1 after reset runs face", faceBefore + 1, faceDelegate.calls)
        assertEquals("frame 1 after reset runs pose", poseBefore + 1, poseDelegate.calls)

        face.detect(frame)
        pose.detect(frame)
        assertEquals("frame 2 after reset runs face only", faceBefore + 2, faceDelegate.calls)
        assertEquals("frame 2 after reset must not run pose", poseBefore + 1, poseDelegate.calls)
    }
}
