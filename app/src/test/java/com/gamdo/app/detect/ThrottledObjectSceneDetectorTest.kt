package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class ThrottledObjectSceneDetectorTest {
    @Test
    fun `object detector is refreshed periodically and last result is reused`() {
        var calls = 0
        val delegate = object : ObjectSceneDetector {
            override fun detect(frame: AnalysisFrame): List<ObjectObservation> {
                calls++
                return listOf(
                    ObjectObservation(
                        box = NormalizedBox(calls / 10f, 0.2f, 0.5f, 0.7f),
                        confidence = 0.8f,
                    ),
                )
            }

            override fun close() = Unit
        }
        val throttled = ThrottledObjectSceneDetector(delegate, refreshEveryFrames = 3)
        val frame = AnalysisFrame(image = null, width = 100, height = 100)

        val first = throttled.detect(frame)
        val second = throttled.detect(frame)
        val third = throttled.detect(frame)
        val fourth = throttled.detect(frame)

        assertEquals(2, calls)
        assertEquals(first.single().box.left, second.single().box.left, 0.001f)
        assertEquals(0.2f, third.single().box.left, 0.001f)
        assertEquals(third.single().box.left, fourth.single().box.left, 0.001f)
    }
}
