package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class ThrottledSubjectSceneSegmenterTest {
    @Test
    fun `segmentation is refreshed sparsely and last result is reused`() {
        var calls = 0
        val result = SegmentationObservation(
            outline = listOf(SegmentationPoint(0.2f, 0.2f)),
            bounds = NormalizedBox(0.2f, 0.2f, 0.4f, 0.4f),
            confidence = 0.9f,
            areaRatio = 0.1f,
        )
        val delegate = object : SubjectSceneSegmenter {
            override fun detect(frame: AnalysisFrame): SegmentationObservation {
                calls++
                return result
            }

            override fun close() = Unit
        }
        val throttled = ThrottledSubjectSceneSegmenter(delegate, refreshEveryFrames = 3)

        repeat(5) { assertEquals(result, throttled.detect(AnalysisFrame(null, 10, 10))) }

        assertEquals(2, calls)
    }
}
