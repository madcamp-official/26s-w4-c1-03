package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiScaleObjectDetectionTest {
    private fun observation(
        id: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = ObjectObservation(
        box = NormalizedBox(left, top, right, bottom),
        trackingId = id,
    )

    @Test
    fun `fallback runs for an empty or tiny primary scene only`() {
        val config = MultiScaleObjectDetectionConfig(smallObjectAreaRatio = 0.05f)

        assertTrue(MultiScaleObjectDetection.shouldRunFallback(emptyList(), config))
        assertTrue(
            MultiScaleObjectDetection.shouldRunFallback(
                listOf(observation(1, 0.45f, 0.45f, 0.60f, 0.65f)),
                config,
            ),
        )
        assertFalse(
            MultiScaleObjectDetection.shouldRunFallback(
                listOf(observation(1, 0.20f, 0.20f, 0.70f, 0.70f)),
                config,
            ),
        )
    }

    @Test
    fun `crop local detection maps back to the original frame`() {
        val crop = ObjectDetectionCrop.centered(2f)
        val local = observation(1, 0.20f, 0.10f, 0.80f, 0.70f)

        val remapped = MultiScaleObjectDetection.remapToFrame(listOf(local), crop).single().box

        assertEquals(0.35f, remapped.left, 0.0001f)
        assertEquals(0.30f, remapped.top, 0.0001f)
        assertEquals(0.65f, remapped.right, 0.0001f)
        assertEquals(0.60f, remapped.bottom, 0.0001f)
    }

    @Test
    fun `crop duplicate is removed while a second subject is retained`() {
        val primary = listOf(observation(1, 0.30f, 0.30f, 0.55f, 0.65f))
        val duplicate = observation(2, 0.32f, 0.32f, 0.56f, 0.66f)
        val distinct = observation(3, 0.62f, 0.40f, 0.78f, 0.66f)

        val merged = MultiScaleObjectDetection.mergeDistinct(primary, listOf(duplicate, distinct), duplicateIou = 0.55f)

        assertEquals(listOf(1, 3), merged.mapNotNull { it.trackingId })
    }
}
