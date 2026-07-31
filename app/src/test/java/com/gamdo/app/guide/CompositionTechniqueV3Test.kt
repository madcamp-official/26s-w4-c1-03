package com.gamdo.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionTechniqueV3Test {
    /**
     * The manual catalogue now ships the row and grid frames the V3.1 *automatic* path
     * still refuses to synthesize (owner instruction 2026-07-31). The distinction this
     * file used to state as "no rows or grids anywhere" was really two rules, and only
     * one of them survives: the detector must never *invent* a line or a 2×2 from
     * scattered objects, but a user asking for 물체 3개 연속 by name is a composition
     * decision, not a detector guess.
     */
    @Test
    fun `manual catalog is fixed photographic frames without pose skeletons`() {
        assertEquals(23, LayoutTemplateCatalog.manualSummaries.size)
        assertTrue(LayoutTemplateCatalog.manualSummaries.all { it.poseTemplateId == null })
        assertTrue(LayoutTemplateCatalog.manualSummaries.any { it.id == LayoutTemplateCatalog.OBJECT_TRIO_ROW })
        assertTrue(LayoutTemplateCatalog.manualSummaries.any { it.id == LayoutTemplateCatalog.OBJECT_QUAD_GRID })
    }

    @Test
    fun `automatic three and four objects use triangle and diamond, never lines or grid`() {
        val three = (0 until 3).map { index ->
            SlotDetection("o$index", com.gamdo.app.detect.GuideObjectCategory.UNKNOWN,
                com.gamdo.app.detect.NormalizedBox(0.2f + index * 0.2f, 0.3f, 0.3f + index * 0.2f, 0.5f),
                confidence = 0.9f, isReliable = true)
        }
        val four = three + SlotDetection("o3", com.gamdo.app.detect.GuideObjectCategory.UNKNOWN,
            com.gamdo.app.detect.NormalizedBox(0.7f, 0.4f, 0.8f, 0.6f), confidence = 0.9f, isReliable = true)

        assertEquals(Arrangement.TRIANGLE, GenericLayoutSynthesizer.chooseArrangement(three))
        assertEquals(Arrangement.DIAMOND, GenericLayoutSynthesizer.chooseArrangement(four))
    }

    /**
     * The named row is now genuinely a row. It used to fold into TRIANGLE, which drew
     * the same frame twice under two captions — the picker offered "물체 3개 연속" and
     * delivered 물체 3개 삼각.
     */
    @Test
    fun `the manual trio row is a level line, distinct from the triangle`() {
        val row = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.OBJECT_TRIO_ROW)!!
        assertEquals(3, row.slots.size)
        assertTrue(row.slots.zipWithNext().all { (a, b) ->
            kotlin.math.abs(a.bounds.centerY() - b.bounds.centerY()) < 0.01f
        })
        val triangle = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.OBJECT_TRIO_TRIANGLE)!!
        assertFalse(
            "the row and the triangle must not be the same frame under two names",
            row.slots.map { it.bounds } == triangle.slots.map { it.bounds },
        )
    }

    @Test
    fun `legacy column and grid requests are normalized to photographic layouts`() {
        val column = GenericLayoutSynthesizer.generic(4, Arrangement.COLUMN)
        val grid = GenericLayoutSynthesizer.generic(4, Arrangement.GRID)

        assertEquals(4, column.slots.size)
        assertEquals(4, grid.slots.size)
        assertEquals(
            CompositionTechniqueCatalog.forObjects(4, Arrangement.DIAMOND).slots.map { it.bounds },
            column.slots.map { it.bounds },
        )
        assertEquals(
            CompositionTechniqueCatalog.forObjects(4, Arrangement.DIAMOND).slots.map { it.bounds },
            grid.slots.map { it.bounds },
        )
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
