package com.gamdo.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCandidateStabilizerTest {
    private fun candidate(x: Float, id: Int = 7) = ObjectObservation(
        box = NormalizedBox(x, 0.2f, x + 0.2f, 0.7f),
        confidence = 0.8f,
        trackingId = id,
        category = GuideObjectCategory.BAG,
        isGuideEligible = true,
    )

    @Test
    fun `three matching observations promote candidate`() {
        val stabilizer = GuideCandidateStabilizer()
        assertFalse(stabilizer.accept(candidate(0.2f))!!.isGuideEligible)
        assertFalse(stabilizer.accept(candidate(0.2f))!!.isGuideEligible)
        assertTrue(stabilizer.accept(candidate(0.2f))!!.isGuideEligible)
    }

    @Test
    fun `new tracking id clears previous confirmation`() {
        val stabilizer = GuideCandidateStabilizer()
        repeat(3) { stabilizer.accept(candidate(0.2f)) }
        assertFalse(stabilizer.accept(candidate(0.4f, id = 8))!!.isGuideEligible)
    }
}
