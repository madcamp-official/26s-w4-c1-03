package com.gamdo.app.ui.camera

import com.gamdo.app.guide.FixedLayoutGuide
import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.guide.LayoutGuideLevel
import com.gamdo.app.guide.LayoutSlot
import com.gamdo.app.guide.LayoutSource
import com.gamdo.app.guide.LayoutTemplate
import com.gamdo.app.guide.OverlayProjection
import com.gamdo.app.guide.RectN
import com.gamdo.app.guide.SceneLayoutGuide
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one predicate the bracket and the shutter both turn amber on.
 *
 * The redesign gave alignment a second amber consumer. Before it, `guide.aligned` was
 * read in exactly one place — inside the `takeIf` that had already decided the bracket
 * was drawable — so the extra conditions never had to be written down. They do now,
 * because the shutter has no such enclosing gate: passing `overlay?.guide?.aligned`
 * straight to it lights it up while there is no bracket on screen to agree with.
 */
class AlignmentAmberTest {

    private val template = LayoutTemplate(
        id = "test_1",
        slots = listOf(LayoutSlot(id = "a", bounds = RectN(0.2f, 0.2f, 0.8f, 0.8f))),
    )

    private fun overlay(
        aligned: Boolean = true,
        visible: Boolean = true,
        layoutState: GuideLayoutState = GuideLayoutState.Fixed(template, LayoutSource.AUTO),
        fixedLayout: FixedLayoutGuide? = null,
        guide: OverlayProjection? = OverlayProjection(
            targetFrame = RectN(0.2f, 0.2f, 0.8f, 0.8f),
            silhouetteBounds = null,
            horizonY = 0.5f,
            visible = visible,
            aligned = aligned,
        ),
    ) = OverlayData(
        faces = emptyList(),
        personCenter = null,
        frameWidth = 480,
        frameHeight = 640,
        mirror = false,
        guide = guide,
        layoutGuide = fixedLayout?.let {
            SceneLayoutGuide(level = LayoutGuideLevel.CONFIDENT, fixedLayout = it)
        },
        layoutState = layoutState,
    )

    @Test
    fun `a matched composition with the guide on is amber`() {
        assertTrue(AlignmentAmber.isOn(overlay(), guideShown = true))
    }

    @Test
    fun `an unmatched composition is not`() {
        assertFalse(AlignmentAmber.isOn(overlay(aligned = false), guideShown = true))
    }

    /**
     * The §3-2 toggle. With the guide off there is no bracket, so an amber shutter would
     * be reporting a match against a target the user asked not to see.
     */
    @Test
    fun `the guide toggle switches it off`() {
        assertFalse(AlignmentAmber.isOn(overlay(), guideShown = false))
    }

    @Test
    fun `no overlay yet is not a match`() {
        assertFalse(AlignmentAmber.isOn(null, guideShown = true))
        assertFalse(AlignmentAmber.isOn(overlay(guide = null), guideShown = true))
    }

    /**
     * `CameraOverlay` draws no bracket while the scene is still being searched — only a
     * spinner. The shutter must not run ahead of it.
     */
    @Test
    fun `still searching is not a match`() {
        assertFalse(
            AlignmentAmber.isOn(
                overlay(layoutState = GuideLayoutState.Searching),
                guideShown = true,
            ),
        )
    }

    @Test
    fun `an invisible guide is not a match`() {
        assertFalse(AlignmentAmber.isOn(overlay(visible = false), guideShown = true))
    }

    /**
     * A manual/latched fixed layout *replaces* the preset bracket rather than colouring
     * it — `CameraOverlay` skips `drawTargetBracket` entirely in that case. So there is
     * nothing for the shutter to agree with.
     */
    @Test
    fun `a fixed layout in charge suppresses it`() {
        assertFalse(
            AlignmentAmber.isOn(
                overlay(fixedLayout = FixedLayoutGuide(template = template)),
                guideShown = true,
            ),
        )
    }

    /**
     * D2-5 bans `matchScore` from the shipped UI "in any form … colour intensity". The
     * input here is a Boolean the guide engine already decided, so there is no magnitude
     * available to encode — two outcomes, not a ramp.
     */
    @Test
    fun `there are two outcomes, not a gradient`() {
        val outcomes = listOf(true, false)
            .flatMap { a -> listOf(true, false).map { g -> AlignmentAmber.isOn(overlay(aligned = a), g) } }
            .toSet()
        assertTrue(outcomes == setOf(true, false))
    }
}
