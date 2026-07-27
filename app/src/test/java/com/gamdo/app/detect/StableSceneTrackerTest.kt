package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSceneTrackerTest {
    private fun objectAt(index: Int, category: GuideObjectCategory = GuideObjectCategory.UNKNOWN) =
        ObjectObservation(
            box = NormalizedBox(0.05f + index * 0.2f, 0.25f, 0.18f + index * 0.2f, 0.55f),
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
}
