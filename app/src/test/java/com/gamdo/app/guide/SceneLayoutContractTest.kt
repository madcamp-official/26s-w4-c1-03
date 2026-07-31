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

    // ---- a manual frame renders where it was authored --------------------------------
    //
    // `GenericLayoutSynthesizer.transform` re-centres slots on the style anchor as
    // `anchor + (slot centre − mean of centres) × spacing`. With one slot the
    // parenthesis is zero, so every single-slot manual frame collapsed onto the anchor
    // — (0.5, 0.5) without a reference — and 원경 인물 좌측/우측 rendered at the same
    // pixels while their thumbnails showed different frames. A manual frame's authored
    // coordinates are its identity, so MANUAL now bypasses the transform at selection,
    // on every re-transforming frame, and on style updates. These tests read
    // `fixedLayout` — the render input — not the catalogue summary, because the summary
    // was correct the whole time the overlay was wrong.

    private fun authoredBounds(id: String): List<RectN> =
        LayoutTemplateCatalog.resolve(id)!!.slots.map { it.bounds }

    private fun SceneGuideCoordinator.renderedBounds(): List<RectN> =
        (currentLayoutState as GuideLayoutState.Fixed).template.slots.map { it.bounds }

    @Test
    fun `a single-slot manual frame keeps its authored off-centre position`() {
        for (id in listOf(
            LayoutTemplateCatalog.PERSON_ENV_THIRDS_LEFT,
            LayoutTemplateCatalog.PERSON_FULL_OFFSET,
            LayoutTemplateCatalog.TRAVEL_LANDMARK_THIRDS,
        )) {
            val coordinator = SceneGuideCoordinator()
            assertTrue(coordinator.selectManualLayout(id))
            assertEquals(
                "$id must render its authored slots, not the style anchor",
                authoredBounds(id),
                coordinator.renderedBounds(),
            )
        }
    }

    /**
     * The per-frame path re-derives the rendered template from `fixedBaseTemplate` on
     * **every** `update`, so a selection-time fix alone would last exactly one frame.
     * The style target carries an off-centre anchor here to prove the frame ignores it.
     */
    @Test
    fun `the per-frame re-transform does not move a manual frame either`() {
        val id = LayoutTemplateCatalog.PERSON_ENV_THIRDS_LEFT
        val coordinator = SceneGuideCoordinator()
        assertTrue(coordinator.selectManualLayout(id))
        var state: SceneGuideState? = null
        repeat(3) {
            state = coordinator.update(
                detection = com.gamdo.app.detect.DetectionResult(emptyList(), null),
                styleTarget = StyleTarget(subjectAnchorX = 2f / 3f, subjectAnchorY = 0.4f),
            )
        }
        assertEquals(authoredBounds(id), state!!.fixedLayout!!.template.slots.map { it.bounds })
        assertEquals(LayoutSource.MANUAL, (state!!.layoutState as GuideLayoutState.Fixed).source)
    }

    /**
     * A reference's anchor arrives through `updateStyle` when the user toggles the
     * reference chip. The manual frame is the more explicit and more recent choice, so
     * it wins: the anchor must not drag it. (AUTO and REFERENCE layouts keep following
     * the style — only the hand-picked frame is verbatim.)
     */
    @Test
    fun `updateStyle does not move a manual frame`() {
        val id = LayoutTemplateCatalog.PERSON_ENV_THIRDS_RIGHT
        val coordinator = SceneGuideCoordinator()
        assertTrue(coordinator.selectManualLayout(id))
        coordinator.updateStyle(StyleTarget(subjectAnchorX = 1f / 3f))
        assertEquals(authoredBounds(id), coordinator.renderedBounds())
    }

    /**
     * The owner-visible symptom, pinned directly: the left and right 원경 frames must
     * render at different horizontal positions. Under the anchor collapse both rendered
     * at exactly (0.39, 0.25)-(0.61, 0.75).
     */
    @Test
    fun `the mirrored 원경 frames render at different positions`() {
        fun renderedCentreX(id: String): Float {
            val coordinator = SceneGuideCoordinator()
            assertTrue(coordinator.selectManualLayout(id))
            val bounds = coordinator.renderedBounds().single()
            return (bounds.left + bounds.right) / 2f
        }
        val left = renderedCentreX(LayoutTemplateCatalog.PERSON_ENV_THIRDS_LEFT)
        val right = renderedCentreX(LayoutTemplateCatalog.PERSON_ENV_THIRDS_RIGHT)
        assertTrue(
            "좌측 must sit left of centre and 우측 right of it, got $left / $right",
            left < 0.45f && right > 0.55f,
        )
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
