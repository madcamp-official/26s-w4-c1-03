package com.gamdo.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionTechniqueV3Test {
    @Test
    fun `manual catalog is twelve fixed photographic frames without pose skeletons`() {
        assertEquals(12, LayoutTemplateCatalog.manualSummaries.size)
        assertTrue(LayoutTemplateCatalog.manualSummaries.all { it.poseTemplateId == null })
        assertTrue(LayoutTemplateCatalog.manualSummaries.none { it.id == LayoutTemplateCatalog.OBJECT_TRIO_ROW })
        assertTrue(LayoutTemplateCatalog.manualSummaries.none { it.id == LayoutTemplateCatalog.OBJECT_QUAD_GRID })
    }

    @Test
    fun `three and four objects use triangle and diamond instead of lines or grid`() {
        val three = (0 until 3).map { index ->
            SlotDetection("o$index", com.gamdo.app.detect.GuideObjectCategory.UNKNOWN,
                com.gamdo.app.detect.NormalizedBox(0.2f + index * 0.2f, 0.3f, 0.3f + index * 0.2f, 0.5f),
                confidence = 0.9f, isReliable = true)
        }
        val four = three + SlotDetection("o3", com.gamdo.app.detect.GuideObjectCategory.UNKNOWN,
            com.gamdo.app.detect.NormalizedBox(0.7f, 0.4f, 0.8f, 0.6f), confidence = 0.9f, isReliable = true)

        assertEquals(Arrangement.TRIANGLE, GenericLayoutSynthesizer.chooseArrangement(three))
        assertEquals(Arrangement.DIAMOND, GenericLayoutSynthesizer.chooseArrangement(four))
        assertFalse(LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.OBJECT_TRIO_ROW)!!.slots
            .zipWithNext().all { (a, b) -> kotlin.math.abs(a.bounds.centerY() - b.bounds.centerY()) < 0.05f })
    }

    @Test
    fun `composition catalog gives one shape-preserving slot per object`() {
        val template = CompositionTechniqueCatalog.forObjects(3, Arrangement.TRIANGLE)
        assertEquals(3, template.slots.size)
        assertTrue(template.slots.all { it.preferredAspectRatio > 0f })
        assertTrue(template.slots.map { it.bounds }.distinct().size == 3)
    }

    private fun RectN.centerY() = (top + bottom) / 2f

    @Test
    fun `face only falls back to upper framing and person box selects full framing`() {
        val face = RectN(0.42f, 0.20f, 0.58f, 0.38f)
        val faceOnly = PortraitSceneClassifier.classify(face, null, 0, 0.25f, 0f)
        val full = PortraitSceneClassifier.classify(face, RectN(0.25f, 0.08f, 0.75f, 0.94f), 0, 0.35f, 0.8f)

        assertEquals(PortraitCoverage.FACE_ONLY, faceOnly.coverage)
        assertEquals(PortraitFramingCatalog.upper.id, PortraitFramingCatalog.select(faceOnly).id)
        assertEquals(PortraitCoverage.FULL_BODY, full.coverage)
        assertEquals(PortraitFramingCatalog.fullCenter.id, PortraitFramingCatalog.select(full).id)
    }
}
