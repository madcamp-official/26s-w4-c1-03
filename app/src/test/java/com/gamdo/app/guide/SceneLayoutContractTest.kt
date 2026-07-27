package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLayoutContractTest {
    @Test
    fun `unknown objects select a fixed generic layout after three fresh scenes`() {
        val resolver = AutoLayoutTemplateResolver()
        val detections = listOf(
            SlotDetection("a", GuideObjectCategory.UNKNOWN, NormalizedBox(0.10f, 0.30f, 0.30f, 0.65f), 0.7f, true),
            SlotDetection("b", GuideObjectCategory.UNKNOWN, NormalizedBox(0.65f, 0.32f, 0.85f, 0.68f), 0.7f, true),
        )

        assertEquals(null, resolver.resolve(detections, objectsFresh = true))
        assertEquals(null, resolver.resolve(detections, objectsFresh = true))
        val fixed = resolver.resolve(detections, objectsFresh = true)

        assertNotNull(fixed)
        assertEquals(2, fixed!!.slots.size)
        assertTrue(fixed.id.startsWith("auto_"))
    }

    @Test
    fun `manual selection stays fixed while style changes`() {
        val coordinator = SceneGuideCoordinator()
        assertTrue(coordinator.selectManualLayout(LayoutTemplateCatalog.GENERIC_PAIR))
        val first = coordinator.currentLayoutState as GuideLayoutState.Fixed
        val firstId = first.template.id

        val state = coordinator.update(
            detection = com.gamdo.app.detect.DetectionResult(emptyList(), null),
            styleTarget = StyleTarget(subjectAnchorX = 1f / 3f),
        )

        assertEquals(LayoutSource.MANUAL, state.layoutState.let { (it as GuideLayoutState.Fixed).source })
        assertEquals(firstId, state.fixedLayout!!.template.id)
    }

    @Test
    fun `rescan returns to searching`() {
        val coordinator = SceneGuideCoordinator()
        coordinator.selectManualLayout(LayoutTemplateCatalog.GENERIC_SINGLE)
        coordinator.rescan()
        assertEquals(GuideLayoutState.Searching, coordinator.currentLayoutState)
    }
}
