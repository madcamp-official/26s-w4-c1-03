package com.gamdo.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gamdo.app.guide.PointN
import com.gamdo.app.guide.ScenePolygonRegion

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

        repeat(4) { sequence -> assertTrue(tracker.accept(batch((sequence + 1).toLong())).isEmpty()) }
        assertEquals(1, tracker.accept(batch(5)).size)
    }

    @Test
    fun `cached batches do not count as fresh confirmations`() {
        val tracker = StableSceneTracker()
        tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), true, 1))
        repeat(3) { tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), false, 1)) }
        assertTrue(tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), true, 2)).isEmpty())
        assertTrue(tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), true, 3)).isEmpty())
        assertTrue(tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), true, 4)).isEmpty())
        assertEquals(1, tracker.accept(ObjectDetectionBatch(listOf(objectAt(0)), true, 5)).size)
    }

    @Test
    fun `five candidates are ranked down to four`() {
        val tracker = StableSceneTracker()
        repeat(5) { sequence ->
            tracker.accept(ObjectDetectionBatch((0 until 5).map(::objectAt), true, sequence.toLong()))
        }
        assertEquals(4, tracker.accept(ObjectDetectionBatch(emptyList(), false, 4)).size)
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

        repeat(5) { sequence ->
            tracker.accept(
                ObjectDetectionBatch(listOf(central, edge), isFresh = true, sequenceId = sequence.toLong()),
            )
        }

        val stable = tracker.accept(
            ObjectDetectionBatch(listOf(central, edge), isFresh = true, sequenceId = 5),
        )
        assertEquals(listOf(central.box), stable.map { it.box })
    }

    @Test
    fun `person candidate keeps portrait framing even when face is above centre`() {
        val tracker = StableSceneTracker(
            ObjectTrackerConfig(focusRegionWidth = 0.70f, focusRegionHeight = 0.68f),
        )
        val face = ObjectObservation(
            box = NormalizedBox(0.40f, 0.08f, 0.60f, 0.18f),
            category = GuideObjectCategory.PERSON,
            trackingId = 10,
        )

        repeat(5) { sequence ->
            tracker.accept(
                ObjectDetectionBatch(listOf(face), isFresh = true, sequenceId = sequence.toLong()),
            )
        }

        val stable = tracker.accept(
            ObjectDetectionBatch(listOf(face), isFresh = true, sequenceId = 5),
        )
        assertEquals(listOf(GuideObjectCategory.PERSON), stable.map { it.category })
    }

    @Test
    fun `central three-subject cluster excludes a thin cable and nested duplicate`() {
        val tracker = StableSceneTracker(
            ObjectTrackerConfig(
                subjectClusterRadius = 0.38f,
                subjectClusterMinimumRelativeArea = 0.16f,
                maximumUnknownAspectRatio = 3.5f,
            ),
        )
        val can = ObjectObservation(
            box = NormalizedBox(0.45f, 0.30f, 0.55f, 0.60f),
            trackingId = 1,
            category = GuideObjectCategory.FOOD_TABLEWARE,
        )
        val blackDevice = ObjectObservation(
            box = NormalizedBox(0.25f, 0.50f, 0.43f, 0.70f),
            trackingId = 2,
        )
        val bluePackage = ObjectObservation(
            box = NormalizedBox(0.58f, 0.52f, 0.72f, 0.72f),
            trackingId = 3,
        )
        val cable = ObjectObservation(
            box = NormalizedBox(0.20f, 0.64f, 0.62f, 0.69f),
            trackingId = 4,
        )
        val canDuplicate = ObjectObservation(
            box = NormalizedBox(0.46f, 0.31f, 0.54f, 0.58f),
            trackingId = 5,
            category = GuideObjectCategory.FOOD_TABLEWARE,
        )
        val scene = listOf(can, blackDevice, bluePackage, cable, canDuplicate)

        repeat(5) { sequence ->
            tracker.accept(ObjectDetectionBatch(scene, isFresh = true, sequenceId = sequence.toLong()))
        }

        val stable = tracker.accept(ObjectDetectionBatch(scene, isFresh = true, sequenceId = 5))
        assertEquals(setOf(1, 2, 3), stable.mapNotNull { it.trackingId }.toSet())
    }

    @Test
    fun `early single detection does not freeze before the later three-object scene`() {
        val tracker = StableSceneTracker()
        val can = ObjectObservation(
            box = NormalizedBox(0.35f, 0.28f, 0.56f, 0.54f),
            trackingId = 1,
            category = GuideObjectCategory.FOOD_TABLEWARE,
        )
        val blackDevice = ObjectObservation(
            box = NormalizedBox(0.17f, 0.51f, 0.43f, 0.69f),
            trackingId = 2,
        )
        val bluePackage = ObjectObservation(
            box = NormalizedBox(0.43f, 0.53f, 0.59f, 0.71f),
            trackingId = 3,
        )
        val clippedLaptop = ObjectObservation(
            box = NormalizedBox(0.66f, 0.66f, 1.00f, 1.00f),
            trackingId = 4,
        )
        val cable = ObjectObservation(
            box = NormalizedBox(0.10f, 0.64f, 0.70f, 0.69f),
            trackingId = 5,
        )

        assertTrue(tracker.accept(ObjectDetectionBatch(listOf(can), true, 1)).isEmpty())
        repeat(3) { sequence ->
            assertTrue(
                tracker.accept(
                    ObjectDetectionBatch(
                        listOf(can, blackDevice, bluePackage, clippedLaptop, cable),
                        true,
                        (sequence + 2).toLong(),
                    ),
                ).isEmpty(),
            )
        }
        val stable = tracker.accept(
            ObjectDetectionBatch(
                listOf(can, blackDevice, bluePackage, clippedLaptop, cable),
                true,
                5,
            ),
        )

        assertEquals(setOf(1, 2, 3), stable.mapNotNull { it.trackingId }.toSet())
    }

    @Test
    fun `tap rescan changes the interest region without changing the default region`() {
        val tracker = StableSceneTracker()
        val upper = ObjectObservation(NormalizedBox(0.58f, 0.18f, 0.72f, 0.38f), trackingId = 1)
        repeat(5) { tracker.accept(ObjectDetectionBatch(listOf(upper), true, it.toLong())) }
        assertTrue(tracker.accept(ObjectDetectionBatch(listOf(upper), true, 6)).isEmpty())

        tracker.rescanAt(0.65f, 0.28f)
        repeat(5) { tracker.accept(ObjectDetectionBatch(listOf(upper), true, (it + 7).toLong())) }
        assertEquals(1, tracker.accept(ObjectDetectionBatch(listOf(upper), true, 12)).size)
    }

    @Test
    fun `one frame companion noise is excluded while a two of five companion is retained`() {
        val tracker = StableSceneTracker()
        val anchor = objectAt(1)
        val companion = objectAt(2)
        repeat(5) { sequence ->
            val objects = if (sequence == 0) listOf(anchor, companion) else listOf(anchor)
            tracker.accept(ObjectDetectionBatch(objects, true, sequence.toLong()))
        }
        assertEquals(1, tracker.accept(ObjectDetectionBatch(listOf(anchor), true, 6)).size)

        tracker.reset()
        var stableWithCompanion = emptyList<ObjectObservation>()
        repeat(5) { sequence ->
            val objects = if (sequence < 2) listOf(anchor, companion) else listOf(anchor)
            stableWithCompanion = tracker.accept(ObjectDetectionBatch(objects, true, (sequence + 10).toLong()))
        }
        assertEquals(2, stableWithCompanion.size)
    }

    @Test
    fun `polygon search excludes stable objects outside the lasso`() {
        val tracker = StableSceneTracker()
        val inside = ObjectObservation(NormalizedBox(0.30f, 0.35f, 0.42f, 0.55f), trackingId = 1)
        val outside = ObjectObservation(NormalizedBox(0.65f, 0.35f, 0.77f, 0.55f), trackingId = 2)
        val polygon = ScenePolygonRegion.fromNormalized(listOf(
            PointN(0.20f, 0.20f), PointN(0.50f, 0.20f), PointN(0.50f, 0.70f), PointN(0.20f, 0.70f),
        ))!!
        tracker.rescanInPolygon(polygon)
        var stable = emptyList<ObjectObservation>()
        repeat(5) { sequence ->
            stable = tracker.accept(ObjectDetectionBatch(listOf(inside, outside), true, sequence.toLong()))
        }
        assertEquals(listOf(1), stable.mapNotNull { it.trackingId })
    }

    @Test
    fun `polygon search keeps separated in-scope objects instead of anchor cluster`() {
        val tracker = StableSceneTracker(
            ObjectTrackerConfig(subjectClusterRadius = 0.20f),
        )
        val left = ObjectObservation(NormalizedBox(0.12f, 0.35f, 0.24f, 0.55f), trackingId = 1)
        val middle = ObjectObservation(NormalizedBox(0.44f, 0.35f, 0.56f, 0.55f), trackingId = 2)
        val right = ObjectObservation(NormalizedBox(0.76f, 0.35f, 0.88f, 0.55f), trackingId = 3)
        val outside = ObjectObservation(NormalizedBox(0.90f, 0.05f, 0.99f, 0.18f), trackingId = 4)
        val polygon = ScenePolygonRegion.fromNormalized(listOf(
            PointN(0.05f, 0.25f), PointN(0.90f, 0.25f),
            PointN(0.90f, 0.70f), PointN(0.05f, 0.70f),
        ))!!

        tracker.rescanInPolygon(polygon)
        repeat(5) { sequence ->
            tracker.accept(
                ObjectDetectionBatch(listOf(left, middle, right, outside), true, sequence.toLong()),
            )
        }

        val stable = tracker.accept(ObjectDetectionBatch(emptyList(), false, 5))
        assertEquals(setOf(1, 2, 3), stable.mapNotNull { it.trackingId }.toSet())
    }
}
