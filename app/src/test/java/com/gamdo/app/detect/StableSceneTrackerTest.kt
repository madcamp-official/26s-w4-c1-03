package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSceneTrackerTest {
    private fun objectAt(index: Int, category: GuideObjectCategory = GuideObjectCategory.UNKNOWN) =
        ObjectObservation(
            // Keep default candidates inside the viewfinder's central focus
            // region; edge behaviour is covered by its own focused tests.
            box = NormalizedBox(0.18f + index * 0.12f, 0.25f, 0.31f + index * 0.12f, 0.55f),
            trackingId = index,
            category = category,
        )

    @Test
    fun `unclassified box without mask becomes stable generic subject`() {
        val tracker = StableSceneTracker()
        val batch = { sequence: Long ->
            ObjectDetectionBatch(listOf(objectAt(0)), isFresh = true, sequenceId = sequence)
        }

        assertTrue(tracker.accept(batch(1)).isEmpty())
        assertTrue(tracker.accept(batch(2)).isEmpty())
        assertEquals(1, tracker.accept(batch(3)).size)
    }

    @Test
    fun `cached batches do not count as fresh confirmations`() {
        val tracker = StableSceneTracker()
        tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), true, 1))
        repeat(3) { tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), false, 1)) }
        assertTrue(tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), true, 2)).isEmpty())
        assertEquals(1, tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), true, 3)).size)
    }

    @Test
    fun `five candidates are ranked down to four`() {
        val tracker = StableSceneTracker()
        repeat(3) { sequence ->
            tracker.accept(ObjectDetectionBatch((0 until 5).map(::objectAt), true, sequence.toLong()))
        }
        assertEquals(4, tracker.accept(ObjectDetectionBatch(emptyList(), false, 3)).size)
    }

    @Test
    fun `edge object is excluded even when it remains stable`() {
        val tracker = StableSceneTracker(
            ObjectTrackerConfig(focusRegionWidth = 0.70f, focusRegionHeight = 0.68f),
        )
        val edgeObject = ObjectObservation(
            box = NormalizedBox(0.01f, 0.30f, 0.13f, 0.54f),
            trackingId = 7,
        )

        repeat(5) { sequence ->
            assertTrue(
                tracker.accept(
                    ObjectDetectionBatch(listOf(edgeObject), isFresh = true, sequenceId = sequence.toLong()),
                ).isEmpty(),
            )
        }
    }

    @Test
    fun `central object remains a candidate while edge clutter is excluded`() {
        val tracker = StableSceneTracker(
            ObjectTrackerConfig(focusRegionWidth = 0.70f, focusRegionHeight = 0.68f),
        )
        val central = ObjectObservation(
            box = NormalizedBox(0.41f, 0.34f, 0.58f, 0.68f),
            trackingId = 1,
        )
        val edge = ObjectObservation(
            box = NormalizedBox(0.84f, 0.28f, 0.98f, 0.56f),
            trackingId = 2,
        )

        repeat(3) { sequence ->
            tracker.accept(
                ObjectDetectionBatch(listOf(central, edge), isFresh = true, sequenceId = sequence.toLong()),
            )
        }

        val stable = tracker.accept(
            ObjectDetectionBatch(listOf(central, edge), isFresh = true, sequenceId = 3),
        )
        assertEquals(listOf(central.box), stable.map { it.box })
    }
}
