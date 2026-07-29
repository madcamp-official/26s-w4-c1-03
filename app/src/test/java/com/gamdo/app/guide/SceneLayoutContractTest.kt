package com.gamdo.app.guide

import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLayoutContractTest {
    @Test
    fun `already-stable unknown objects select a generic layout without a second confirmation window`() {
        val resolver = AutoLayoutTemplateResolver()
        val detections = listOf(
            SlotDetection("a", GuideObjectCategory.UNKNOWN, NormalizedBox(0.10f, 0.30f, 0.30f, 0.65f), 0.7f, true),
            SlotDetection("b", GuideObjectCategory.UNKNOWN, NormalizedBox(0.65f, 0.32f, 0.85f, 0.68f), 0.7f, true),
        )

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

    private val twoObjectReference = StyleTarget(
        referenceSlots = listOf(
            ReferenceTargetSlot(SlotRole.OBJECT, SlotVisualKind.GENERIC_OBJECT, RectN(0.10f, 0.20f, 0.40f, 0.60f)),
            ReferenceTargetSlot(SlotRole.OBJECT, SlotVisualKind.PLATE, RectN(0.55f, 0.50f, 0.80f, 0.70f)),
        ),
    )

    /**
     * **This contract was reversed by O-13 (2), 2026-07-29.**
     *
     * It used to read `reference slots fix immediately and do not wait for live
     * object detection`, and it was accurate: `referenceTemplate` sat in the
     * `explicitTemplate` chain and latched on the first frame, before
     * `autoLayoutResolver` was consulted at all. The owner ruled that a reference's
     * composition is a **candidate**, not a command — so the scene analyser is heard
     * first, for `referenceGraceFrames` frames.
     */
    @Test
    fun `reference slots wait for the scene analyser rather than latching on frame one`() {
        val coordinator = SceneGuideCoordinator()
        val state = coordinator.update(
            detection = com.gamdo.app.detect.DetectionResult(emptyList(), null),
            styleTarget = twoObjectReference,
        )
        assertEquals(GuideLayoutState.Searching, state.layoutState)
    }

    @Test
    fun `reference slots fix once the scene analyser has had its grace window`() {
        val grace = 8
        val coordinator = SceneGuideCoordinator(referenceGraceFrames = grace)
        var state = coordinator.update(
            detection = com.gamdo.app.detect.DetectionResult(emptyList(), null),
            styleTarget = twoObjectReference,
        )
        repeat(grace) {
            state = coordinator.update(
                detection = com.gamdo.app.detect.DetectionResult(emptyList(), null),
                styleTarget = twoObjectReference,
            )
        }

        assertEquals(LayoutSource.REFERENCE, (state.layoutState as GuideLayoutState.Fixed).source)
        assertEquals(2, state.fixedLayout!!.template.slots.size)
        // The shared style transformer is allowed to apply its documented
        // size/spacing micro-adjustment. The reference slot must remain a
        // fixed, safe-area-clamped slot rather than preserving raw pixels.
        assertTrue(state.fixedLayout!!.template.slots.first().bounds.left in 0.05f..0.95f)
    }

    @Test
    fun `a zero grace window restores the pre-O13 immediate latch`() {
        // The knob is real, and this is what turning it off looks like — kept so the
        // owner can reject the grace window without a code change.
        val coordinator = SceneGuideCoordinator(referenceGraceFrames = 0)
        val state = coordinator.update(
            detection = com.gamdo.app.detect.DetectionResult(emptyList(), null),
            styleTarget = twoObjectReference,
        )
        assertEquals(LayoutSource.REFERENCE, (state.layoutState as GuideLayoutState.Fixed).source)
    }
}
