package com.gamdo.app.detect

import com.gamdo.app.guide.SceneFrameSignals
import com.gamdo.app.guide.StyleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SceneAwareFrameProcessorTest {
    @Test
    fun `processor turns detector output into an object proposal`() {
        val face = object : FaceDetector {
            override fun detect(frame: AnalysisFrame) = emptyList<FaceObservation>()
            override fun close() = Unit
        }
        val pose = object : PoseDetector {
            override fun detect(frame: AnalysisFrame) = null
            override fun close() = Unit
        }
        val objects = object : ObjectSceneDetector {
            override fun detect(frame: AnalysisFrame) = listOf(
                ObjectObservation(
                    box = NormalizedBox(0.1f, 0.2f, 0.42f, 0.72f),
                    confidence = 0.9f,
                ),
            )
            override fun close() = Unit
        }
        val processor = SceneAwareFrameProcessor(SceneDetector(face, pose, objects))

        val result = repeat(3) {
            processor.process(
                frame = AnalysisFrame(null, 100, 100),
                styleTarget = StyleTarget(),
                signals = SceneFrameSignals(
                    rowLuminance = listOf(0.2f, 0.2f, 0.72f, 0.72f),
                    sideEdgeDensity = listOf(0.6f, 0.05f),
                ),
            )
        }.let { processor.process(AnalysisFrame(null, 100, 100), StyleTarget()) }

        assertEquals(0.9f, result.observation.subjectConfidence, 0.001f)
        assertFalse(result.proposal.fallback)
    }
}
