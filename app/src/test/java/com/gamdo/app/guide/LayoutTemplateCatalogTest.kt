package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutTemplateCatalogTest {
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
