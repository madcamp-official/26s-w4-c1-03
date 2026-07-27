package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedLayoutGuideTest {
    private val template = LayoutTemplate.cafeTable()
    private val detections = listOf(
        SlotDetection("left-cup", GuideObjectCategory.DRINKWARE, NormalizedBox(0.12f, 0.52f, 0.35f, 0.76f), 0.9f, true),
        SlotDetection("right-cup", GuideObjectCategory.DRINKWARE, NormalizedBox(0.66f, 0.52f, 0.88f, 0.76f), 0.9f, true),
        SlotDetection("cake", GuideObjectCategory.FOOD_TABLEWARE, NormalizedBox(0.36f, 0.68f, 0.64f, 0.90f), 0.9f, true),
    )

    @Test
    fun `fixed cafe slots fill after three matching frames`() {
        val matcher = FixedLayoutSlotMatcher()
        repeat(3) { matcher.match(template, detections) }
        val result = matcher.match(template, detections)

        assertTrue(result.allRequiredFilled)
        assertEquals(3, result.matches.count { it.status == SlotMatchStatus.FILLED })
        assertEquals(0.08f, result.template.slots.first().bounds.left, 0.001f)
    }

    @Test
    fun `wrong category never fills a slot`() {
        val matcher = FixedLayoutSlotMatcher()
        repeat(5) {
            matcher.match(
                template,
                detections.map { detection ->
                    if (detection.id == "cake") detection.copy(category = GuideObjectCategory.BAG) else detection
                },
            )
        }
        val result = matcher.match(template, detections.dropLast(1))

        assertFalse(result.allRequiredFilled)
        assertEquals(SlotMatchStatus.EMPTY, result.matches.first { it.slotId == "cake_plate" }.status)
    }

    @Test
    fun `brief occlusion keeps an already filled slot`() {
        val matcher = FixedLayoutSlotMatcher()
        repeat(3) { matcher.match(template, detections) }
        val result = matcher.match(template, detections.dropLast(1))

        assertEquals(SlotMatchStatus.FILLED, result.matches.first { it.slotId == "cake_plate" }.status)
    }
}
