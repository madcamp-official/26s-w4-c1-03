package com.gamdo.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneRecognitionPolicyTest {
    private val validMask = SegmentationObservation(
        outline = listOf(SegmentationPoint(0.2f, 0.2f), SegmentationPoint(0.6f, 0.2f), SegmentationPoint(0.4f, 0.7f)),
        bounds = NormalizedBox(0.2f, 0.2f, 0.6f, 0.7f),
        confidence = 0.9f,
        areaRatio = 0.2f,
    )

    @Test
    fun `supported label maps to a guide category`() {
        assertTrue(SceneRecognitionPolicy.categoryFor(listOf("Cup")) == GuideObjectCategory.DRINKWARE)
        assertTrue(SceneRecognitionPolicy.categoryFor(listOf("Potted plant")) == GuideObjectCategory.PLANT)
    }

    @Test
    fun `unknown or box only candidate is not guide eligible`() {
        assertFalse(SceneRecognitionPolicy.isGuideEligible(GuideObjectCategory.UNKNOWN, 0.99f, validMask))
        assertFalse(SceneRecognitionPolicy.isGuideEligible(GuideObjectCategory.BAG, 0.99f, null))
    }

    @Test
    fun `eligible candidate requires class confidence and a valid mask`() {
        assertTrue(SceneRecognitionPolicy.isGuideEligible(GuideObjectCategory.BAG, 0.8f, validMask))
        assertFalse(SceneRecognitionPolicy.isGuideEligible(GuideObjectCategory.BAG, 0.5f, validMask))
    }
}
