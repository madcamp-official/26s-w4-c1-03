package com.gamdo.app.guide

import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneStructureAnalyzerTest {
    @Test
    fun `strong row transition becomes a horizon and open right side becomes leading space`() {
        val observation = SceneStructureAnalyzer().analyze(
            SceneStructureInput(
                rowLuminance = listOf(0.2f, 0.2f, 0.2f, 0.72f, 0.72f, 0.72f),
                sideEdgeDensity = listOf(0.65f, 0.05f),
                subjectBox = NormalizedBox(0.1f, 0.25f, 0.42f, 0.72f),
                subjectKind = SubjectKind.OBJECT,
                subjectConfidence = 0.9f,
            ),
        )

        assertEquals(0.5f, observation.horizonPosition!!, 0.01f)
        assertEquals(LeadingDirection.RIGHT, observation.leadingDirection)
        assertTrue(observation.openSpaceRight > observation.openSpaceLeft)
        assertTrue(observation.dominantLineConfidence > 0.8f)
    }

    @Test
    fun `flat scene does not invent a horizon`() {
        val observation = SceneStructureAnalyzer().analyze(
            SceneStructureInput(
                rowLuminance = listOf(0.4f, 0.41f, 0.4f, 0.41f),
                sideEdgeDensity = listOf(0.4f, 0.4f),
                subjectBox = null,
            ),
        )

        assertEquals(null, observation.horizonPosition)
        assertEquals(LeadingDirection.NONE, observation.leadingDirection)
    }
}
