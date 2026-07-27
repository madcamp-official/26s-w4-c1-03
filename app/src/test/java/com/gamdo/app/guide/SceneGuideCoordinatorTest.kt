package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.ObjectObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SceneGuideCoordinatorTest {
    @Test
    fun `one update produces a scene proposal for an object`() {
        val state = SceneGuideCoordinator().update(
            detection = DetectionResult(
                faces = emptyList(),
                pose = null,
                objects = listOf(
                    ObjectObservation(
                        box = NormalizedBox(0.1f, 0.2f, 0.42f, 0.72f),
                        confidence = 0.9f,
                    ),
                ),
            ),
            styleTarget = StyleTarget(),
            signals = SceneFrameSignals(
                rowLuminance = listOf(0.2f, 0.2f, 0.72f, 0.72f),
                sideEdgeDensity = listOf(0.6f, 0.05f),
            ),
        )

        assertEquals(SubjectKind.OBJECT, state.observation.subjectKind)
        assertEquals(LeadingDirection.RIGHT, state.observation.leadingDirection)
        assertFalse(state.proposal.fallback)
        assertEquals(1f / 3f, state.proposal.target.subjectAnchorX, 0.001f)
    }
}
