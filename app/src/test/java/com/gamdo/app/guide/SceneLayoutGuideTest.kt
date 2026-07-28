package com.gamdo.app.guide

import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `prompt` assertions that used to sit alongside each level check are gone:
 * `LayoutGuidePrompt` was removed because its only renderer drew D2-banned
 * instruction copy ("피사체를 화면에 보여주세요"). The **levels** are what the
 * overlay actually branches on, and those are still pinned here.
 */
class SceneLayoutGuideTest {
    private val style = StyleTarget(subjectAnchorX = 0.5f, horizonPosition = 0.5f)

    @Test
    fun `missing subject falls back to the static guide and draws nothing`() {
        val proposal = SceneProposalEngine().propose(SceneObservation(), style)
        val guide = SceneLayoutGuideEngine().build(SceneObservation(), proposal)

        assertEquals(LayoutGuideLevel.STATIC, guide.level)
        assertTrue(guide.outline.isEmpty())
        assertEquals(null, guide.bounds)
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
        assertEquals(4, guide.outline.size)
        assertEquals(observation.subjectBox, guide.bounds)
    }

    @Test
    fun `high confidence projects subject outline into selected composition`() {
        val observation = SceneObservation(
            subjectBox = NormalizedBox(0.1f, 0.2f, 0.4f, 0.7f),
            subjectKind = SubjectKind.PERSON,
            subjectConfidence = 0.9f,
            hasReliableOutline = true,
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
