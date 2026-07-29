package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneDetectorSegmentationPolicyTest {
    @Test
    fun `foreground segmentation waits for an existing foreground seed`() {
        var calls = 0
        val segmenter = object : SubjectSceneSegmenter {
            override fun detect(frame: AnalysisFrame): SegmentationObservation {
                calls++
                return SegmentationObservation(
                    outline = listOf(SegmentationPoint(0.2f, 0.2f), SegmentationPoint(0.8f, 0.2f), SegmentationPoint(0.5f, 0.8f)),
                    bounds = NormalizedBox(0.2f, 0.2f, 0.8f, 0.8f),
                    confidence = 0.9f,
                    areaRatio = 0.36f,
                )
            }

            override fun close() = Unit
        }
        val noObjects = object : ObjectSceneDetector {
            override fun detect(frame: AnalysisFrame): List<ObjectObservation> = emptyList()
            override fun close() = Unit
        }
        val noFaces = object : FaceDetector {
            override fun detect(frame: AnalysisFrame): List<FaceObservation> = emptyList()
            override fun close() = Unit
        }
        val noPose = object : PoseDetector {
            override fun detect(frame: AnalysisFrame): PoseObservation? = null
            override fun close() = Unit
        }

        val result = SceneDetector(noFaces, noPose, noObjects, segmenter)
            .detect(AnalysisFrame(null, 640, 480))

        assertTrue(result.objects.isEmpty())
        assertNull(result.segmentation)
        assertEquals("an empty scene must not spend a segmentation pass", 0, calls)
    }
}
