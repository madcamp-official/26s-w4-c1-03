package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTemplateCatalogTest {
    @Test
    fun `public manual catalog contains twenty-three previewable layouts`() {
        // Twelve until the owner's 2026-07-31 expansion added the 원경 인물, 여행·풍경
        // and extra object/café frames. If this number changes again it should be a
        // catalogue decision, not a side effect.
        assertEquals(23, LayoutTemplateCatalog.manualSummaries.size)
        assertEquals(23, LayoutTemplateCatalog.manualSummaries.map { it.id }.distinct().size)
        assertEquals(0, LayoutTemplateCatalog.manualSummaries.count { it.poseTemplateId != null })
        assertEquals(23, LayoutTemplateCatalog.manualSummaries.count { it.slots.isNotEmpty() })
    }

    /**
     * Every manual id must survive the whole path a user's tap takes: resolve to a
     * template, stay inside the unit square, respect the four-slot contract, and carry
     * a human caption. Checked for all of them rather than the new ones only, so the
     * next expansion inherits the check for free.
     */
    @Test
    fun `every manual layout resolves previewable, in-bounds, and named`() {
        for (id in LayoutTemplateCatalog.manualIds) {
            val template = LayoutTemplateCatalog.resolve(id)
            assertTrue("$id must resolve", template != null)
            assertTrue("$id must keep at most four slots", template!!.slots.size in 1..4)
            assertEquals(
                "$id must not reuse a slot id",
                template.slots.size,
                template.slots.map { it.id }.distinct().size,
            )
            for (slot in template.slots) {
                val b = slot.bounds
                assertTrue(
                    "$id/${slot.id} must sit inside the unit square, got $b",
                    b.left >= 0f && b.top >= 0f && b.right <= 1f && b.bottom <= 1f,
                )
                assertTrue(
                    "$id/${slot.id} must have positive extent",
                    b.width > 0f && b.height > 0f,
                )
            }
        }
        for (summary in LayoutTemplateCatalog.manualSummaries) {
            assertTrue(
                "${summary.id} must carry a display name — an unnamed manual layout " +
                    "ships a captionless cell",
                summary.displayName != summary.id,
            )
        }
    }

    @Test
    fun `legacy manual ids remain resolvable`() {
        assertEquals(9, LayoutTemplateCatalog.legacyIds.count { LayoutTemplateCatalog.resolve(it) != null })
    }

    @Test
    fun `catalog exposes stable cafe and multi-slot ids`() {
        val cafe = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.CAFE_TABLE)!!
        assertEquals(3, cafe.slots.size)
        assertEquals(GuideObjectCategory.DRINKWARE, cafe.slots[0].expectedCategory)
        assertEquals(GuideObjectCategory.FOOD_TABLEWARE, cafe.slots[2].expectedCategory)
    }

    @Test
    fun `unknown layout id does not silently invent a layout`() {
        assertNull(LayoutTemplateCatalog.resolve("missing"))
    }

    @Test
    fun `semantically confirmed drinks select the specialised three-drink layout`() {
        val resolver = AutoLayoutTemplateResolver()
        val drinks = (1..3).map { index ->
            SlotDetection(
                id = "drink-$index",
                category = GuideObjectCategory.DRINKWARE,
                bounds = NormalizedBox(0.1f * index, 0.3f, 0.2f + 0.1f * index, 0.7f),
                confidence = 0.9f,
                isReliable = true,
                semanticConfidence = 0.9f,
                semanticConfirmed = true,
            )
        }

        assertEquals(LayoutTemplateCatalog.DRINK_TRIO, resolver.resolve(drinks)!!.id)
        assertEquals(LayoutTemplateCatalog.DRINK_TRIO, resolver.resolve(emptyList())!!.id)
    }

    @Test
    fun `two drinks and food automatically select cafe layout`() {
        val resolver = AutoLayoutTemplateResolver()
        val scene = listOf(
            SlotDetection("a", GuideObjectCategory.DRINKWARE, NormalizedBox(0.1f, 0.3f, 0.3f, 0.7f), 0.9f, true, semanticConfidence = 0.9f, semanticConfirmed = true),
            SlotDetection("b", GuideObjectCategory.DRINKWARE, NormalizedBox(0.7f, 0.3f, 0.9f, 0.7f), 0.9f, true, semanticConfidence = 0.9f, semanticConfirmed = true),
            SlotDetection("cake", GuideObjectCategory.FOOD_TABLEWARE, NormalizedBox(0.4f, 0.5f, 0.6f, 0.8f), 0.9f, true, semanticConfidence = 0.9f, semanticConfirmed = true),
        )
        resolver.resolve(scene)

        assertEquals(LayoutTemplateCatalog.CAFE_TABLE, resolver.resolve(emptyList())!!.id)
    }
}
