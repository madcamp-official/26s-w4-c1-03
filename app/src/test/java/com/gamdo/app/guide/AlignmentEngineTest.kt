package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FrameFeatureCalculator
import com.gamdo.app.detect.FrameFeatureInput
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.PoseObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentEngineTest {
    private val target = StyleTarget(
        subjectScaleRange = 0.4f..0.4f,
        subjectAnchorX = 0.5f,
        headroomRange = 0.05f..0.05f,
    )

    @Test
    fun `central scene produces a clamped target frame and horizon`() {
        val state = AlignmentEngine().align(features(person = box(0.3f, 0.05f, 0.7f, 0.45f)), target)

        assertTrue(state.visible)
        assertEquals(0.5f, state.horizonY, 0.001f)
        assertTrue(state.targetFrame.left >= 0f)
        assertTrue(state.targetFrame.right <= 1f)
        assertTrue(state.targetFrame.top >= 0f)
        assertTrue(state.targetFrame.bottom <= 1f)
        assertNotNull(state.silhouette)
    }

    @Test
    fun `person entering target changes aligned state`() {
        val engine = AlignmentEngine()
        val first = engine.align(features(person = box(0f, 0f, 0.1f, 0.1f)), target)
        val alignedBox = NormalizedBox(
            first.targetFrame.left,
            first.targetFrame.top,
            first.targetFrame.right,
            first.targetFrame.bottom,
        )
        val second = engine.align(features(person = alignedBox), target)

        assertFalse(first.aligned)
        assertTrue(second.aligned)
    }

    @Test
    fun `small target movement is smoothed over five frames`() {
        val engine = AlignmentEngine()
        val config = GuideConfig(smoothingWindow = 5, recomputeMovementThreshold = 0.5f)
        val outputs = (0 until 5).map { index ->
            engine.align(
                features(person = box(0.3f + index * 0.01f, 0.05f, 0.7f + index * 0.01f, 0.45f)),
                target.copy(subjectAnchorX = 0.5f + index * 0.01f),
                config,
            ).targetFrame
        }

        val jumps = outputs.zipWithNext().map { (a, b) -> kotlin.math.abs(b.left - a.left) }
        assertTrue(jumps.maxOrNull()!! < 0.02f)
    }

    @Test
    fun `low confidence keeps last stable target then hides after reset`() {
        val engine = AlignmentEngine()
        val stable = engine.align(features(person = box(0.3f, 0.05f, 0.7f, 0.45f)), target)
        val low = engine.align(features(person = null, confidence = 0.1f), target)

        assertEquals(stable.targetFrame, low.targetFrame)
        assertTrue(low.visible)

        engine.reset()
        val firstLow = engine.align(features(person = null, confidence = 0.1f), target)
        assertFalse(firstLow.visible)
    }

    private fun features(person: NormalizedBox?, confidence: Float = 0.9f) =
        FrameFeatureCalculator().calculate(
            FrameFeatureInput(
                detection = DetectionResult(
                    faces = emptyList(),
                    pose = person?.let {
                        PoseObservation(emptyList(), confidence)
                    },
                ),
                personCandidates = listOfNotNull(person),
            ),
        )

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedBox(left, top, right, bottom)
}
