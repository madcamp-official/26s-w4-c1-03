package com.gamdo.app.guide

/**
 * The composition bracket's size in normalized frame coordinates.
 *
 * ## The unit mismatch this exists to fix (review_report M1)
 *
 * `StyleTarget.targetAspectRatio` is a **pixel** ratio: `"4:5"` parses to 0.8 and
 * means the saved file is 0.8 as wide as it is tall. `subjectScaleRange` is a
 * **normalized** fraction of frame height. Two call sites computed the bracket as
 *
 *     width = height * targetAspectRatio
 *
 * which multiplies a pixel ratio into a normalized one and silently produces a
 * different shape. Normalized width is a fraction of frame *width* and normalized
 * height a fraction of frame *height*, so converting between them needs the frame's
 * own aspect:
 *
 *     width_px / height_px = (width_n * W) / (height_n * H) = targetAspectRatio
 *     ⇒ width_n = height_n * targetAspectRatio / (W / H)
 *
 * On this app's pinned 4:3 analysis stream (upright W/H = 0.75) the missing divisor
 * made every bracket **exactly 75% of its declared width** — a 4:5 target drawn as
 * roughly 3:5. The user framed themselves to a box narrower than the composition
 * they were being guided toward, on every preset, in every session.
 *
 * Both sites now call this, so they cannot drift apart again. `AlignmentEngineTest`
 * only ever asserted the bracket stayed inside 0..1, which a too-narrow box does
 * comfortably — the property that catches this is the *pixel* aspect, and that is
 * what `CompositionFrameTest` pins.
 */
object CompositionFrame {

    /**
     * The analysis stream's upright width/height.
     *
     * `CameraController` pins both preview and analysis to 4:3, so in portrait this
     * is 3/4. It is a default rather than a required argument for the same reason
     * `SubjectProjection.ANALYSIS_RATIO_W_TO_H` is: the value is fixed by the
     * capture configuration, and threading it through every guide call site would
     * add a parameter that can only ever hold one value in production. Tests
     * override it to prove the maths rather than the constant.
     */
    const val ANALYSIS_RATIO_W_TO_H: Float = 3f / 4f

    /** Normalized height of the bracket, clamped to something drawable. */
    fun height(target: StyleTarget): Float =
        ((target.subjectScaleRange.start + target.subjectScaleRange.endInclusive) / 2f)
            .coerceIn(MIN_SIDE, MAX_SIDE)

    /**
     * Normalized width for [height], such that the bracket's **pixel** aspect is
     * [StyleTarget.targetAspectRatio].
     *
     * Clamped like the height. Note the clamp can still make a very wide target
     * narrower than requested — that is a deliberate ceiling on how much of the
     * frame the guide may claim, not the unit bug above.
     */
    fun width(
        target: StyleTarget,
        height: Float = height(target),
        frameRatioWtoH: Float = ANALYSIS_RATIO_W_TO_H,
    ): Float {
        val ratio = if (frameRatioWtoH.isFinite() && frameRatioWtoH > 0f) {
            frameRatioWtoH
        } else {
            ANALYSIS_RATIO_W_TO_H
        }
        val aspect = if (target.targetAspectRatio.isFinite() && target.targetAspectRatio > 0f) {
            target.targetAspectRatio
        } else {
            1f
        }
        return (height * aspect / ratio).coerceIn(MIN_SIDE, MAX_SIDE)
    }

    private const val MIN_SIDE = 0.12f
    private const val MAX_SIDE = 0.92f
}
