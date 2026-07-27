package com.gamdo.app.guide

import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneProposalEngineTest {
    private val style = StyleTarget(
        subjectAnchorX = 0.5f,
        horizonPosition = 0.5f,
    )

    @Test
    fun `object with right leading space is proposed on the left`() {
        val result = SceneProposalEngine().propose(
            SceneObservation(
                subjectBox = NormalizedBox(0.2f, 0.3f, 0.45f, 0.7f),
                subjectKind = SubjectKind.OBJECT,
                subjectConfidence = 0.92f,
                leadingDirection = LeadingDirection.RIGHT,
                openSpaceRight = 0.55f,
            ),
            style,
        )

        assertEquals(1f / 3f, result.target.subjectAnchorX, 0.001f)
        assertEquals(ProposalReason.LEADING_SPACE, result.reason)
        assertFalse(result.fallback)
        assertEquals(3, result.candidates.size)
        assertTrue(result.candidates.first().score >= result.candidates.last().score)
    }

    @Test
    fun `uneven open space changes the target away from the fixed center`() {
        val result = SceneProposalEngine().propose(
            SceneObservation(
                subjectBox = NormalizedBox(0.55f, 0.25f, 0.8f, 0.65f),
                subjectKind = SubjectKind.OBJECT,
                subjectConfidence = 0.8f,
                openSpaceLeft = 0.6f,
                openSpaceRight = 0.08f,
            ),
            style,
        )

        assertEquals(2f / 3f, result.target.subjectAnchorX, 0.001f)
        assertEquals(ProposalReason.OPEN_SPACE_BALANCE, result.reason)
    }

    @Test
    fun `strong horizontal structure proposes a thirds horizon`() {
        val result = SceneProposalEngine().propose(
            SceneObservation(
                subjectBox = NormalizedBox(0.35f, 0.3f, 0.65f, 0.8f),
                subjectKind = SubjectKind.OBJECT,
                subjectConfidence = 0.8f,
                horizonPosition = 0.7f,
                dominantLineConfidence = 0.9f,
            ),
            style,
        )

        assertEquals(2f / 3f, result.target.horizonPosition, 0.001f)
        assertEquals(ProposalReason.HORIZON_BALANCE, result.reason)
    }

    @Test
    fun `small scene movement is stabilized and missing subject uses static fallback`() {
        val engine = SceneProposalEngine()
        val first = engine.propose(
            SceneObservation(
                subjectBox = NormalizedBox(0.3f, 0.3f, 0.6f, 0.7f),
                subjectKind = SubjectKind.PERSON,
                subjectConfidence = 0.9f,
                leadingDirection = LeadingDirection.RIGHT,
            ),
            style,
        )
        val stable = engine.propose(
            SceneObservation(
                subjectBox = NormalizedBox(0.31f, 0.3f, 0.61f, 0.7f),
                subjectKind = SubjectKind.PERSON,
                subjectConfidence = 0.88f,
                leadingDirection = LeadingDirection.RIGHT,
            ),
            style,
        )
        val fallback = engine.propose(SceneObservation(), style)

        assertFalse(first.fallback)
        assertTrue(stable.stabilized)
        assertTrue(fallback.fallback)
        assertEquals(ProposalReason.STATIC_FALLBACK, fallback.reason)
    }
}
