package com.gamdo.app.ui.camera

import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.guide.OverlayProjection

/**
 * The shutter's three appearances (owner's final UI redesign, 2026-07-30), as a
 * pure decision so it can be tested on the JVM at all.
 *
 * The redesign names three states and gives each exactly one property:
 *
 *  - **기본** — White 92%
 *  - **구도 일치** — Amber, faded in over [CAMERA_ALIGN_FADE_MS]
 *  - **촬영 중** — the disc contracts to [CAPTURING_DISC_SCALE]
 *
 * ## Why colour and scale are separate answers
 *
 * Read as three exclusive states there is a fourth case with no answer: aligned
 * *and* capturing, which is the common one — the composition is what made the user
 * press the shutter. Treating "촬영 중" as its own colour would snap the disc from
 * amber back to white on the press and then back to amber on release, i.e. the one
 * visible flicker in a screen whose whole feedback vocabulary is a 200ms fade.
 *
 * So the two properties are decided independently, and the three named states are
 * what the combinations look like. [describe] exists to name them for the tests and
 * for a device report; nothing renders off it.
 *
 * ## What this must not become
 *
 * D2-5 keeps `matchScore` out of the shipped UI "in any form (numeric, percentage,
 * gauge, colour intensity)". [alignedAmber] takes a **Boolean**, not a score, and
 * that is the guard: there is no gradient available here to encode a magnitude in,
 * so a future edit that wanted one would have to change this signature.
 */
object ShutterVisual {

    /** The disc's rest size, as a fraction of the ring's inner area. */
    const val IDLE_DISC_SCALE: Float = 1f

    /** 촬영 중 — "디스크가 78%로 수축". */
    const val CAPTURING_DISC_SCALE: Float = 0.78f

    /** 기본 — White 92%. The ring uses the same value. */
    const val IDLE_ALPHA: Float = 0.92f

    /**
     * Whether the disc and ring are amber right now.
     *
     * Follows alignment and **ignores capture**, so pressing the shutter on a
     * matched composition does not flash it back to white for the length of the
     * capture. [capturing] is still in the signature: it names the input this answer
     * is deliberately independent of, the same way [DebugHudGate.initialVisible]
     * takes the build type it refuses to consult.
     */
    @Suppress("UNUSED_PARAMETER")
    fun alignedAmber(aligned: Boolean, capturing: Boolean): Boolean = aligned

    /** Disc scale. Contracts while a capture is in flight, whatever the colour. */
    fun discScale(capturing: Boolean): Float =
        if (capturing) CAPTURING_DISC_SCALE else IDLE_DISC_SCALE

    /** Which of the redesign's three names this combination reads as. */
    fun describe(aligned: Boolean, capturing: Boolean): ShutterAppearance = when {
        capturing -> ShutterAppearance.CAPTURING
        aligned -> ShutterAppearance.ALIGNED
        else -> ShutterAppearance.IDLE
    }
}

/** The redesign's three names for the shutter. Reporting only — see [ShutterVisual]. */
enum class ShutterAppearance { IDLE, ALIGNED, CAPTURING }

/**
 * The single predicate that turns things amber: **the composition matches**.
 *
 * The redesign gives alignment two amber consumers where there used to be one — the
 * target bracket, and now the shutter. They must agree exactly, and "exactly" is
 * doing work here, because the condition is not simply `guide.aligned`: the guide is
 * only drawn when it is visible, a layout has been fixed, and no manual fixed layout
 * has taken over. A shutter that went amber while the bracket was not on screen would
 * be reporting a match against a target the user cannot see.
 *
 * So the predicate lives here once and both read it, rather than the shutter
 * re-deriving a copy that drifts the first time the overlay's gate changes.
 *
 * ## Why this is not a matchScore leak
 *
 * D2-5 bans `matchScore` from the shipped UI in any form. [OverlayProjection.aligned]
 * is the alignment **decision** — a Boolean the guide engine already computed and
 * already renders as the bracket's colour (D2-3: "정렬 성공 피드백은 색 전환"). No
 * score crosses this boundary, and there is no numeric input here for one to hide in.
 */
object AlignmentAmber {

    /**
     * @param overlay the frame's overlay state, or null before the first analysis.
     * @param guideShown the §3-2 top-bar toggle. With the guide off there is no bracket
     *   to agree with, so there is no amber either — the user turned the guidance off.
     */
    fun isOn(overlay: OverlayData?, guideShown: Boolean): Boolean {
        if (!guideShown) return false
        val data = overlay ?: return false
        // Mirrors CameraOverlay's own gate for the preset guide block. A manual fixed
        // layout replaces the bracket rather than colouring it, which is why its
        // presence switches this off.
        if (data.layoutState !is GuideLayoutState.Fixed) return false
        if (data.layoutGuide?.fixedLayout != null) return false
        val guide = data.guide ?: return false
        return guide.visible && guide.aligned
    }
}
