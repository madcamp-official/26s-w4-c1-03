package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Test

class FixedLayoutGuideResetTest {
    @Test
    fun `reset clears filled history before a new layout session`() {
        val template = LayoutTemplate(
            id = "cup",
            slots = listOf(LayoutSlot("cup", GuideObjectCategory.DRINKWARE, RectN(0.2f, 0.2f, 0.8f, 0.8f))),
        )
        val detection = SlotDetection(
            id = "cup-1",
            category = GuideObjectCategory.DRINKWARE,
            bounds = NormalizedBox(0.3f, 0.3f, 0.7f, 0.7f),
            confidence = 0.9f,
            isReliable = true,
        )
        val matcher = FixedLayoutSlotMatcher()
        repeat(3) { matcher.match(template, listOf(detection)) }
        assertEquals(SlotMatchStatus.FILLED, matcher.match(template, listOf(detection)).matches.single().status)

        matcher.reset()

        assertEquals(SlotMatchStatus.DETECTING, matcher.match(template, listOf(detection)).matches.single().status)
    }
}
