package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is left of the fixed-layout tests after the occupancy machinery was removed.
 *
 * The three tests that used to live here drove `FixedLayoutSlotMatcher` — the
 * stateful FILLED/DETECTING/EMPTY machine. D2 forbids rendering that state, the
 * matcher had lost its last production caller, and it carried three bugs, so it
 * was deleted rather than fixed. Its tests went with it: a green test on a class
 * nothing calls reads as coverage and is the opposite.
 *
 * Two things from that file were worth keeping and are kept below — the template
 * geometry assertion, and coverage of the correspondence logic that is still
 * compiled and still called every frame ([LayoutSlotAssigner]).
 */
class FixedLayoutGuideTest {

    private val template = LayoutTemplate.cafeTable()

    private fun detection(
        id: String,
        category: GuideObjectCategory,
        box: NormalizedBox,
        reliable: Boolean = true,
    ) = SlotDetection(id, category, box, confidence = 0.9f, isReliable = reliable)

    private val cafeDetections = listOf(
        detection("left-cup", GuideObjectCategory.DRINKWARE, NormalizedBox(0.12f, 0.52f, 0.35f, 0.76f)),
        detection("right-cup", GuideObjectCategory.DRINKWARE, NormalizedBox(0.66f, 0.52f, 0.88f, 0.76f)),
        detection("cake", GuideObjectCategory.FOOD_TABLEWARE, NormalizedBox(0.36f, 0.68f, 0.64f, 0.90f)),
    )

    @Test
    fun `the cafe template geometry is fixed`() {
        // Carried over from the deleted occupancy test. Slot coordinates are the
        // one thing on this path a user can actually see, since the overlay draws
        // them directly.
        assertEquals(3, template.slots.size)
        assertEquals(0.08f, template.slots.first().bounds.left, 0.001f)
        assertEquals(listOf("cup_left", "cup_right", "cake_plate"), template.slots.map { it.id })
    }

    @Test
    fun `a fixed layout carries no occupancy state`() {
        // The property the deletion establishes. If someone adds a status field
        // back, this fails and they have to read the KDoc explaining D2 first.
        val guide = FixedLayoutGuide(template)
        assertEquals(emptyList<LayoutSlotAssignment>(), guide.assignments)
    }

    @Test
    fun `each detection is assigned to at most one slot`() {
        val assignments = LayoutSlotAssigner.assign(template, cafeDetections)
        val used = assignments.mapNotNull { it.detectionId }
        assertEquals("a detection must not fill two slots", used.size, used.distinct().size)
        assertEquals(template.slots.size, assignments.size)
    }

    @Test
    fun `an unreliable detection is never assigned`() {
        val assignments = LayoutSlotAssigner.assign(
            template,
            cafeDetections.map { it.copy(isReliable = false) },
        )
        assignments.forEach { assertNull("unreliable detections must not be assigned", it.detectionId) }
    }

    @Test
    fun `a wrong-category detection does not take a typed slot`() {
        val assignments = LayoutSlotAssigner.assign(
            template,
            listOf(detection("bag", GuideObjectCategory.BAG, NormalizedBox(0.30f, 0.60f, 0.70f, 0.94f))),
        )
        assertNull(assignments.first { it.slotId == "cake_plate" }.detectionId)
    }

    /**
     * Documents a live weakness rather than asserting it is absent.
     *
     * `overlap` divides the intersection by the **slot** area, so a detection much
     * larger than the slot scores 1.0 — being entirely swallowed reads identically
     * to fitting perfectly. Today nothing consumes `assignments`, so this changes
     * nothing a user can see; the test exists so that whoever wires a consumer
     * finds the property stated instead of discovering it from a wrong result.
     */
    @Test
    fun `overlap does not penalise a detection that spills outside the slot`() {
        val wholeFrame = LayoutSlotAssigner.assign(
            template,
            listOf(detection("everything", GuideObjectCategory.DRINKWARE, NormalizedBox(0f, 0f, 1f, 1f))),
        ).first { it.detectionId == "everything" }
        assertEquals(1f, wholeFrame.overlap, 0.001f)
        assertTrue(
            "a full-frame box currently scores a perfect overlap — see this test's KDoc",
            wholeFrame.overlap >= 1f,
        )
    }
}
