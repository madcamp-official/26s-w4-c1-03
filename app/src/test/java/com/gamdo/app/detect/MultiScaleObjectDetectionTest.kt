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

    @Test
    fun `scheduler runs immediately then rate limits repeated tiny-scene crops`() {
        val scheduler = MultiScaleFallbackScheduler(
            MultiScaleObjectDetectionConfig(fallbackEveryFrames = 3),
        )

        assertTrue(scheduler.shouldRun(emptyList()))
        assertFalse(scheduler.shouldRun(emptyList()))
        assertFalse(scheduler.shouldRun(emptyList()))
        assertTrue(scheduler.shouldRun(emptyList()))

        assertFalse(scheduler.shouldRun(listOf(observation(1, 0.1f, 0.1f, 0.8f, 0.8f))))
        assertTrue(scheduler.shouldRun(emptyList()))
    }

    @Test
    fun `only a broad central single box requests overlap detail inference`() {
        val config = MultiScaleObjectDetectionConfig(
            overlapDetailMinimumAreaRatio = 0.09f,
            overlapDetailMinimumSpan = 0.34f,
            overlapDetailFocusWidth = 0.50f,
        )
        val broadCentral = observation(1, 0.30f, 0.32f, 0.70f, 0.70f)
        val narrowCentral = observation(2, 0.44f, 0.40f, 0.58f, 0.66f)
        val broadEdge = observation(3, 0.00f, 0.32f, 0.38f, 0.70f)

        assertEquals(broadCentral, MultiScaleObjectDetection.overlapDetailCandidate(listOf(broadCentral), config))
        assertEquals(null, MultiScaleObjectDetection.overlapDetailCandidate(listOf(narrowCentral), config))
        assertEquals(null, MultiScaleObjectDetection.overlapDetailCandidate(listOf(broadEdge), config))
        assertEquals(
            null,
            MultiScaleObjectDetection.overlapDetailCandidate(listOf(broadCentral, narrowCentral), config),
        )
    }

    @Test
    fun `detail crop enlarges the broad group without cutting it off`() {
        val subject = observation(1, 0.40f, 0.38f, 0.60f, 0.62f)

        val crop = ObjectDetectionCrop.around(subject.box, scale = 2f)

        assertEquals(0.25f, crop.left, 0.0001f)
        assertEquals(0.25f, crop.top, 0.0001f)
        assertEquals(0.75f, crop.right, 0.0001f)
        assertEquals(0.75f, crop.bottom, 0.0001f)
    }

    @Test
    fun `detail refiner requires repeated child detections before replacing broad parent`() {
        val config = MultiScaleObjectDetectionConfig(overlapDetailCacheFrames = 4)
        val refiner = OverlapDetailRefiner(config)
        val parent = observation(1, 0.30f, 0.30f, 0.70f, 0.70f)
        val left = observation(2, 0.32f, 0.34f, 0.47f, 0.66f)
        val right = observation(3, 0.53f, 0.34f, 0.68f, 0.66f)

        val firstPass = refiner.recordDetailPass(listOf(parent), parent, listOf(left, right))
        assertEquals(listOf(1), firstPass.mapNotNull { it.trackingId })

        val confirmed = refiner.recordDetailPass(listOf(parent), parent, listOf(left, right))
        assertEquals(listOf(2, 3), confirmed.mapNotNull { it.trackingId })

        val movedParent = observation(4, 0.32f, 0.30f, 0.72f, 0.70f)
        val cached = refiner.reuseConfirmed(listOf(movedParent), movedParent)
        assertEquals(listOf(2, 3), cached?.mapNotNull { it.trackingId })
        assertEquals(0.34f, cached!![0].box.left, 0.0001f)
    }

    @Test
    fun `single child never invents a split`() {
        val refiner = OverlapDetailRefiner(MultiScaleObjectDetectionConfig())
        val parent = observation(1, 0.30f, 0.30f, 0.70f, 0.70f)
        val oneChild = observation(2, 0.33f, 0.34f, 0.50f, 0.66f)

        val result = refiner.recordDetailPass(listOf(parent), parent, listOf(oneChild))

        assertEquals(listOf(1), result.mapNotNull { it.trackingId })
    }
}
