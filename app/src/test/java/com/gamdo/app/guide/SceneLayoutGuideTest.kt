package com.gamdo.app.guide

import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLayoutGuideTest {
    private val style = StyleTarget(subjectAnchorX = 0.5f, horizonPosition = 0.5f)

    @Test
    fun `missing subject asks for a subject and keeps static guide`() {
        val proposal = SceneProposalEngine().propose(SceneObservation(), style)
        val guide = SceneLayoutGuideEngine().build(SceneObservation(), proposal)

        assertEquals(LayoutGuideLevel.STATIC, guide.level)
        assertEquals(LayoutGuidePrompt.FIND_SUBJECT, guide.prompt)
        assertTrue(guide.outline.isEmpty())
    }

    @Test
    fun `medium confidence shows detected object outline while waiting`() {
        val observation = SceneObservation(
            subjectBox = NormalizedBox(0.1f, 0.2f, 0.4f, 0.7f),
            subjectKind = SubjectKind.OBJECT,
            subjectConfidence = 0.5f,
        )
        val proposal = SceneProposalEngine().propose(observation, style)
        val guide = SceneLayoutGuideEngine().build(observation, proposal)

        assertEquals(LayoutGuideLevel.DETECTING, guide.level)
        assertEquals(LayoutGuidePrompt.HOLD_STEADY, guide.prompt)
        assertEquals(4, guide.outline.size)
        assertEquals(observation.subjectBox, guide.bounds)
    }

    @Test
    fun `high confidence projects subject outline into selected composition`() {
        val observation = SceneObservation(
            subjectBox = NormalizedBox(0.1f, 0.2f, 0.4f, 0.7f),
            subjectKind = SubjectKind.PERSON,
            subjectConfidence = 0.9f,
            subjectOutline = listOf(
                LayoutGuidePoint(0.22f, 0.2f),
                LayoutGuidePoint(0.1f, 0.5f),
                LayoutGuidePoint(0.28f, 0.7f),
                LayoutGuidePoint(0.4f, 0.4f),
            ),
        )
        val proposal = SceneProposalEngine().propose(observation, style)
        val guide = SceneLayoutGuideEngine().build(observation, proposal)

        assertEquals(LayoutGuideLevel.CONFIDENT, guide.level)
        assertEquals(null, guide.prompt)
        assertTrue(guide.outline.size >= 3)
        assertTrue(guide.outline.all { it.x in 0f..1f && it.y in 0f..1f })
        assertFalse(guide.bounds == observation.subjectBox)
    }

    @Test
    fun `same-level movement is eased rather than jumping`() {
        val engine = SceneLayoutGuideEngine()
        val firstObservation = SceneObservation(
            subjectBox = NormalizedBox(0.1f, 0.2f, 0.4f, 0.7f),
            subjectKind = SubjectKind.OBJECT,
            subjectConfidence = 0.9f,
        )
        val secondObservation = firstObservation.copy(
            subjectBox = NormalizedBox(0.5f, 0.2f, 0.8f, 0.7f),
        )
        val first = engine.build(firstObservation, SceneProposalEngine().propose(firstObservation, style))
        val second = engine.build(secondObservation, SceneProposalEngine().propose(secondObservation, style))

        assertFalse(first.stabilized)
        assertTrue(second.outline.first().x < 0.5f)
    }
}
