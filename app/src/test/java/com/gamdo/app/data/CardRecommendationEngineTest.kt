package com.gamdo.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardRecommendationEngineTest {
    private fun card(index: Int) = CardFeature(
        id = "card_$index",
        subjectScale = (index % 4) / 3f,
        subjectPosition = (index % 3) / 2f,
        headroom = (index % 5) / 5f,
        backgroundRatio = (index % 6) / 6f,
        brightness = (index % 7) / 7f,
        lightType = "natural",
        colorTemperature = 4400f + index * 120,
        saturation = (index % 5) / 5f,
        contrast = (index % 4) / 4f,
        sharpness = (index % 5) / 5f,
        grain = (index % 3) / 3f,
        candidness = (index % 6) / 6f,
        framing = (index % 4) / 4f,
    )

    @Test fun `initial deck is bounded and never duplicates`() {
        val result = CardRecommendationEngine.nextBatch((1..40).map(::card), emptySet(), emptyList())
        assertEquals(12, result.size)
        assertEquals(result.size, result.map { it.id }.toSet().size)
    }

    @Test fun `later deck excludes seen cards and respects session maximum`() {
        val all = (1..40).map(::card)
        val seen = all.take(30).map { it.id }.toSet()
        val result = CardRecommendationEngine.nextBatch(all, seen, emptyList())
        assertEquals(2, result.size)
        assertTrue(result.none { it.id in seen })
    }
}
