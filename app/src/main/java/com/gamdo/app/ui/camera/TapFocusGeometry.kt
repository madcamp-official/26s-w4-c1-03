package com.gamdo.app.ui.camera

/**
 * Tap-to-focus geometry (§1-5) — pure Kotlin, no `android.*`.
 *
 * Lives outside the composable so the "which taps are allowed to move focus"
 * decision is reachable from a JVM test. See `TapFocusGeometryTest`.
 *
 * ## Why this exists rather than CameraX's built-in
 *
 * `androidx.camera.view.CameraController.isTapToFocusEnabled` defaults to **true**,
 * so a tap-to-focus rule has always been compiled into the app. It was merely
 * *unreachable*: the pinch `Box` in `CameraPreviewPane` is the only pointer-input
 * sibling over the preview, and Compose stops hit-testing at the first pointer-input
 * node it hits, so `PreviewView.onTouchEvent` never ran.
 *
 * The built-in rule was extracted here first and put under test, to check whether
 * simply making it reachable would have been the fix. It would not have been — it
 * forwards *every* touch it receives, because it maps against the `PreviewView` and
 * knows nothing about what Compose draws on top of it. It focuses on the letterbox
 * bars, and it forwards NaN. Both failures are now pinned as tests.
 *
 * We therefore keep the built-in disabled (in `camera/CameraController.kt`) and
 * drive focus ourselves from the pinch surface, through this rule.
 */

/**
 * A point in **pane pixels**, which is also `PreviewView` view-pixel space (the
 * `AndroidView` fills the pane), ready for `MeteringPointFactory.createPoint`.
 *
 * Only `PreviewView.meteringPointFactory` may consume this. It owns the
 * FILL_CENTER crop and the sensor orientation; a `SurfaceOrientedMeteringPointFactory`
 * built from the Compose size does not, and would be off by exactly that crop.
 */
data class FocusPoint(val x: Float, val y: Float)

/** Normalized point consumed by the scene-interest ROI after a focus tap. */
data class SceneAnchorPoint(val x: Float, val y: Float)

/**
 * Resolves a tap on the preview pane to the point focus should be driven to, or
 * `null` if the tap must not move focus.
 *
 * Rejects, in order:
 * - non-finite coordinates or pane metrics — a NaN handed to `createPoint` becomes
 *   a poisoned metering rectangle inside CameraX, where it stops being our bug;
 * - an unmeasured or degenerate pane (any dimension or the ratio at or below zero),
 *   which also guards the divisor below;
 * - taps outside the pane;
 * - taps on the letterbox bars. That region is invisible *and* `centerCropToRatio`
 *   discards it at save time, so focusing there racks the lens onto something the
 *   user will never receive.
 *
 * In-window taps pass through unchanged — see [FocusPoint].
 *
 * The mask arithmetic below **must stay identical to `CameraPreviewPane`'s**, which
 * computes it in `Dp` off `BoxWithConstraints` while this runs in px off
 * `PointerInputScope.size`. The formula is scale-invariant so the fraction agrees;
 * the two can differ by the sub-pixel rounding of `Modifier.height(barHeight)`,
 * which is far below a fingertip and is why the tests straddle the boundary instead
 * of sitting on it.
 */
fun resolveTapFocusPoint(
    tapX: Float,
    tapY: Float,
    paneWidth: Float,
    paneHeight: Float,
    ratioWtoH: Float,
): FocusPoint? {
    if (!tapX.isFinite() || !tapY.isFinite()) return null
    if (!paneWidth.isFinite() || !paneHeight.isFinite() || !ratioWtoH.isFinite()) return null
    if (paneWidth <= 0f || paneHeight <= 0f || ratioWtoH <= 0f) return null
    if (tapX < 0f || tapX >= paneWidth) return null

    val windowHeight = (paneWidth / ratioWtoH).coerceAtMost(paneHeight)
    val barHeight = (paneHeight - windowHeight) / 2f
    // Half-open, matching the mask: the bar Box covers [0, barHeight), so the row
    // at barHeight is the window's first. When the pane is too short for the ratio
    // the coercion collapses barHeight to 0 and the mask draws no bars — do not
    // invent bars the user cannot see.
    if (tapY < barHeight || tapY >= barHeight + windowHeight) return null

    return FocusPoint(tapX, tapY)
}

fun resolveTapSceneAnchor(
    tapX: Float,
    tapY: Float,
    paneWidth: Float,
    paneHeight: Float,
    ratioWtoH: Float,
): SceneAnchorPoint? {
    val point = resolveTapPointInPreview(tapX, tapY, paneWidth, paneHeight, ratioWtoH) ?: return null
    return SceneAnchorPoint(
        x = (point.x / paneWidth).coerceIn(0f, 1f),
        y = (point.y / point.windowHeight).coerceIn(0f, 1f),
    )
}

private data class PreviewPoint(val x: Float, val y: Float, val windowHeight: Float)

private fun resolveTapPointInPreview(
    tapX: Float,
    tapY: Float,
    paneWidth: Float,
    paneHeight: Float,
    ratioWtoH: Float,
): PreviewPoint? {
    if (!tapX.isFinite() || !tapY.isFinite() || paneWidth <= 0f || paneHeight <= 0f || ratioWtoH <= 0f) return null
    val windowHeight = (paneWidth / ratioWtoH).coerceAtMost(paneHeight)
    val barHeight = (paneHeight - windowHeight) / 2f
    if (tapX !in 0f..paneWidth || tapY < barHeight || tapY >= barHeight + windowHeight) return null
    return PreviewPoint(tapX, tapY - barHeight, windowHeight)
}
