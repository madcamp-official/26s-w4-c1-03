package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies detectors sit behind interfaces and can be swapped for fakes (§2-2
 * DoD: "단위 테스트에서 mock 교체 동작"). Runs on the JVM — no ML Kit/Android.
 */
class SceneDetectorTest {

    private val frame = AnalysisFrame(image = null, width = 100, height = 100)

    @Test
    fun legacy_pose_injection_is_ignored_by_the_v31_pipeline() {
        val fakeFace = object : FaceDetector {
            override fun detect(frame: AnalysisFrame) = listOf(
                FaceObservation(NormalizedBox(0.1f, 0.1f, 0.5f, 0.6f), 0.9f, 0.8f, 3f),
            )
            override fun close() {}
        }
        val fakePose = object : PoseDetector {
            override fun detect(frame: AnalysisFrame) = PoseObservation(
                landmarks = listOf(PoseLandmarkPoint(0, 0.5f, 0.5f, 0.95f)),
                averageInFrameLikelihood = 0.95f,
            )
            override fun close() {}
        }

        val result = SceneDetector(fakeFace, fakePose).detect(frame)

        assertEquals(1, result.faces.size)
        assertEquals(0.9f, result.faces[0].leftEyeOpenProbability!!, 1e-4f)
        assertEquals(0.3f, result.faces[0].box.centerX, 1e-4f) // (0.1 + 0.5) / 2
        assertNull(result.pose)
    }

    @Test
    fun emptyDetectorsYieldEmptyResult() {
        val noFace = object : FaceDetector {
            override fun detect(frame: AnalysisFrame): List<FaceObservation> = emptyList()
            override fun close() {}
        }
        val noPose = object : PoseDetector {
            override fun detect(frame: AnalysisFrame): PoseObservation? = null
            override fun close() {}
        }

        val result = SceneDetector(noFace, noPose).detect(frame)

        assertEquals(0, result.faces.size)
        assertNull(result.pose)
    }
}
