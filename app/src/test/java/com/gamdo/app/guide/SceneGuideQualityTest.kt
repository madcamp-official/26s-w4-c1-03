package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FaceObservation
import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.ObjectObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneGuideQualityTest {
    private fun unknownObject(index: Int): ObjectObservation = ObjectObservation(
        box = NormalizedBox(0.12f + index * 0.18f, 0.38f, 0.25f + index * 0.18f, 0.63f),
        trackingId = index,
        category = GuideObjectCategory.UNKNOWN,
    )

    @Test
    fun `session controller confirms person and three objects once then keeps four fixed slots`() {
        val controller = SceneGuideSessionController()
        val face = FaceObservation(NormalizedBox(0.08f, 0.20f, 0.28f, 0.52f), null, null, 0f)
        val detection = DetectionResult(
            faces = listOf(face),
            pose = null,
            objects = (0 until 3).map(::unknownObject),
            objectsFresh = true,
        )

        assertEquals(GuideLayoutState.Searching, controller.updateScene(detection.copy(objectSequenceId = 1), StyleTarget()).layoutState)
        assertEquals(GuideLayoutState.Searching, controller.updateScene(detection.copy(objectSequenceId = 2), StyleTarget()).layoutState)
        assertEquals(GuideLayoutState.Searching, controller.updateScene(detection.copy(objectSequenceId = 3), StyleTarget()).layoutState)
        assertEquals(GuideLayoutState.Searching, controller.updateScene(detection.copy(objectSequenceId = 4), StyleTarget()).layoutState)
        val fixed = controller.updateScene(detection.copy(objectSequenceId = 5), StyleTarget())

        val layout = fixed.layoutState as GuideLayoutState.Fixed
        assertEquals(LayoutSource.AUTO, layout.source)
        assertEquals(4, layout.template.slots.size)
        assertEquals(SlotRole.PERSON, layout.template.slots.first().role)

        val later = controller.updateScene(
            detection.copy(
                objectSequenceId = 4,
                objects = listOf(unknownObject(0).copy(box = NormalizedBox(0.70f, 0.15f, 0.88f, 0.55f))),
            ),
            StyleTarget(),
        )
        assertEquals(layout.template.slots.map { it.bounds }, (later.layoutState as GuideLayoutState.Fixed).template.slots.map { it.bounds })
    }

    @Test
    fun `semantic labels do not override generic layout until tracker confirms them`() {
        val unresolved = listOf(
            SlotDetection("a", GuideObjectCategory.DRINKWARE, NormalizedBox(0.1f, 0.4f, 0.3f, 0.7f), 0.9f, true, semanticConfidence = 0.95f),
            SlotDetection("b", GuideObjectCategory.DRINKWARE, NormalizedBox(0.7f, 0.4f, 0.9f, 0.7f), 0.9f, true, semanticConfidence = 0.95f),
        )
        val generic = AutoLayoutTemplateResolver().resolve(unresolved)
        assertNotNull(generic)
        assertTrue(generic!!.id.startsWith("auto_"))

        val confirmed = unresolved.map { it.copy(semanticConfirmed = true) }
        val specialised = AutoLayoutTemplateResolver().resolve(confirmed)
        assertEquals(LayoutTemplateCatalog.DRINK_PAIR, specialised!!.id)
    }

    @Test
    fun `style transforms keep every slot inside the five percent safety margin`() {
        val template = GenericLayoutSynthesizer.generic(4, Arrangement.GRID, viewportAspect = GuideViewportAspect.ONE_TO_ONE)
        val styled = GenericLayoutSynthesizer.transform(
            template,
            StyleTarget(subjectAnchorX = 0.95f, subjectAnchorY = 0.95f, subjectScaleRange = 0.55f..0.55f),
        )

        assertEquals(GuideViewportAspect.ONE_TO_ONE, styled.viewportAspect)
        assertTrue(styled.slots.all { slot ->
            slot.bounds.left >= 0.05f && slot.bounds.top >= 0.05f &&
                slot.bounds.right <= 0.95f && slot.bounds.bottom <= 0.95f
        })
    }

    @Test
    fun `automatic layouts snapshot each detected object shape instead of using equal squares`() {
        val template = GenericLayoutSynthesizer.generic(2, Arrangement.ROW)
        val tallCup = SlotDetection(
            "cup",
            GuideObjectCategory.UNKNOWN,
            NormalizedBox(0.16f, 0.24f, 0.28f, 0.72f),
            0.8f,
            isReliable = true,
        )
        val wideCake = SlotDetection(
            "cake",
            GuideObjectCategory.UNKNOWN,
            NormalizedBox(0.58f, 0.45f, 0.92f, 0.66f),
            0.8f,
            isReliable = true,
        )

        val shaped = GenericLayoutSynthesizer.snapshotObjectShapes(template, listOf(tallCup, wideCake))
        val left = shaped.slots[0]
        val right = shaped.slots[1]

        assertTrue(left.preferredAspectRatio < 0.6f)
        assertTrue(right.preferredAspectRatio > 1.5f)
        assertTrue(left.bounds.height > left.bounds.width)
        assertTrue(right.bounds.width > right.bounds.height)
        assertTrue(left.bounds.width != right.bounds.width)
        assertTrue(shaped.slots.all { it.bounds.left >= 0.05f && it.bounds.right <= 0.95f })
    }

    @Test
    fun `automatic shape snapshot remains fixed after later object size changes`() {
        val controller = SceneGuideSessionController()
        val initial = DetectionResult(
            faces = emptyList(),
            pose = null,
            objects = listOf(
                ObjectObservation(NormalizedBox(0.12f, 0.30f, 0.25f, 0.70f), trackingId = 1),
                ObjectObservation(NormalizedBox(0.62f, 0.40f, 0.92f, 0.66f), trackingId = 2),
            ),
            objectsFresh = true,
        )
        repeat(4) { index -> controller.updateScene(initial.copy(objectSequenceId = (index + 1).toLong()), StyleTarget()) }
        val fixed = controller.updateScene(initial.copy(objectSequenceId = 5), StyleTarget())
        val before = (fixed.layoutState as GuideLayoutState.Fixed).template.slots.map { it.bounds }

        val later = controller.updateScene(
            initial.copy(
                objectSequenceId = 4,
                objects = listOf(
                    ObjectObservation(NormalizedBox(0.08f, 0.10f, 0.42f, 0.90f), trackingId = 1),
                    ObjectObservation(NormalizedBox(0.70f, 0.45f, 0.82f, 0.55f), trackingId = 2),
                ),
            ),
            StyleTarget(),
        )

        assertEquals(before, (later.layoutState as GuideLayoutState.Fixed).template.slots.map { it.bounds })
    }

    @Test
    fun `manual layout replaces automatic layout and rescan clears it`() {
        val controller = SceneGuideSessionController()
        assertTrue(controller.selectManualLayout(LayoutTemplateCatalog.GENERIC_PAIR))
        assertTrue(controller.layoutState.value is GuideLayoutState.Fixed)

        controller.rescan()

        assertEquals(GuideLayoutState.Searching, controller.layoutState.value)
        assertFalse(controller.selectManualLayout("not-a-layout"))
    }
}
