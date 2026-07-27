package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectSceneDetectorTest {
    @Test
    fun `scene detector forwards optional object candidates`() {
        val face = object : FaceDetector {
            override fun detect(frame: AnalysisFrame) = emptyList<FaceObservation>()
            override fun close() = Unit
        }
        val pose = object : PoseDetector {
            override fun detect(frame: AnalysisFrame) = null
            override fun close() = Unit
        }
        val objectDetector = object : ObjectSceneDetector {
            override fun detect(frame: AnalysisFrame) = listOf(
                ObjectObservation(
                    box = NormalizedBox(0.2f, 0.2f, 0.7f, 0.8f),
                    confidence = 0.9f,
                    trackingId = 4,
                    labels = listOf("Home goods"),
                ),
            )
            override fun close() = Unit
        }

        val detector = SceneDetector(face, pose, objectDetector)
        repeat(3) { detector.detect(AnalysisFrame(image = null, width = 100, height = 100)) }
        val result = detector.detect(AnalysisFrame(image = null, width = 100, height = 100))

        assertEquals(1, result.objects.size)
        assertEquals(4, result.objects.single().trackingId)
        assertEquals("Home goods", result.objects.single().labels.single())
    }
}
