package com.gamdo.app.detect

import org.junit.Assert.assertTrue
import org.junit.Test

class SceneDetectorSegmentationPolicyTest {
    @Test
    fun `foreground segmentation never invents an object candidate`() {
        val segmenter = object : SubjectSceneSegmenter {
            override fun detect(frame: AnalysisFrame) = SegmentationObservation(
                outline = listOf(SegmentationPoint(0.2f, 0.2f), SegmentationPoint(0.8f, 0.2f), SegmentationPoint(0.5f, 0.8f)),
                bounds = NormalizedBox(0.2f, 0.2f, 0.8f, 0.8f),
                confidence = 0.9f,
                areaRatio = 0.36f,
            )

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
        assertTrue(result.segmentation != null)
    }
}
