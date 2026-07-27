package com.gamdo.app.guide

import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.ObjectObservation
import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.detect.SegmentationObservation
import com.gamdo.app.detect.SegmentationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneGuideCoordinatorTest {
    @Test
    fun `one update produces a scene proposal for an object`() {
        val state = SceneGuideCoordinator().update(
            detection = DetectionResult(
                faces = emptyList(),
                pose = null,
                objects = listOf(
                    ObjectObservation(
                        box = NormalizedBox(0.1f, 0.2f, 0.42f, 0.72f),
                        confidence = 0.9f,
                    ),
                ),
            ),
            styleTarget = StyleTarget(),
            signals = SceneFrameSignals(
                rowLuminance = listOf(0.2f, 0.2f, 0.72f, 0.72f),
                sideEdgeDensity = listOf(0.6f, 0.05f),
            ),
        )

        assertEquals(SubjectKind.OBJECT, state.observation.subjectKind)
        assertEquals(LeadingDirection.RIGHT, state.observation.leadingDirection)
        assertFalse(state.proposal.fallback)
        assertEquals(1f / 3f, state.proposal.target.subjectAnchorX, 0.001f)
    }

    @Test
    fun `fixed layout is exposed without moving its slots`() {
        val template = LayoutTemplate(
            id = "single-cup",
            slots = listOf(
                LayoutSlot("cup", GuideObjectCategory.DRINKWARE, RectN(0.2f, 0.3f, 0.6f, 0.7f)),
            ),
        )
        val detection = DetectionResult(
            faces = emptyList(),
            pose = null,
            objects = listOf(
                ObjectObservation(
                    box = NormalizedBox(0.25f, 0.35f, 0.55f, 0.65f),
                    confidence = 0.9f,
                    category = GuideObjectCategory.DRINKWARE,
                    mask = SegmentationObservation(
                        outline = listOf(
                            SegmentationPoint(0.25f, 0.35f),
                            SegmentationPoint(0.55f, 0.35f),
                            SegmentationPoint(0.55f, 0.65f),
                        ),
                        bounds = NormalizedBox(0.25f, 0.35f, 0.55f, 0.65f),
                        confidence = 0.9f,
                        areaRatio = 0.09f,
                    ),
                    isGuideEligible = true,
                ),
            ),
        )
        val coordinator = SceneGuideCoordinator()
        repeat(3) {
            coordinator.update(detection, StyleTarget(), SceneFrameSignals(layoutTemplate = template))
        }
        val state = coordinator.update(detection, StyleTarget(), SceneFrameSignals(layoutTemplate = template))

        assertTrue(state.layoutState is GuideLayoutState.Fixed)
        assertTrue(state.fixedLayout!!.matches.isEmpty())
        val fixedBounds = state.layoutGuide.fixedLayout!!.template.slots.single().bounds
        assertTrue(fixedBounds.left in 0f..1f && fixedBounds.right in 0f..1f)
    }
}
