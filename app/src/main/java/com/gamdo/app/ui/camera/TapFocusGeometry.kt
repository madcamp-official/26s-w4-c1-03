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
 * - non-finite coordinates — a NaN handed to `createPoint` becomes a poisoned
 *   metering rectangle inside CameraX, where it stops being our bug;
 * - an unmeasured or degenerate pane, which [previewWindowOf] answers `null` for;
 * - taps outside the capture window, **on either axis**. That region is invisible
 *   *and* the aspect crop discards it at save time, so focusing there racks the lens
 *   onto something the user will never receive.
 *
 * In-window taps pass through unchanged — see [FocusPoint].
 *
 * The window comes from [previewWindowOf], which `CameraPreviewPane`'s mask also
 * calls. This file used to carry its own copy of that arithmetic under a comment
 * saying it "must stay identical to `CameraPreviewPane`'s"; adding 16:9 is precisely
 * the change that breaks an arrangement held together by watching, so there is one
 * copy now. The two still differ by the sub-pixel rounding of `Modifier.width/height`
 * — far below a fingertip, and why the tests straddle the boundary rather than
 * sitting on it.
 */
fun resolveTapFocusPoint(
    tapX: Float,
    tapY: Float,
    paneWidth: Float,
    paneHeight: Float,
    ratioWtoH: Float,
): FocusPoint? {
    if (!tapX.isFinite() || !tapY.isFinite()) return null
    val window = previewWindowOf(paneWidth, paneHeight, ratioWtoH) ?: return null
    if (!window.contains(tapX, tapY)) return null
    return FocusPoint(tapX, tapY)
}

/**
 * The same tap as a normalized point **within the capture window**.
 *
 * Normalized against the window rather than the pane, on both axes. The x term used
 * to divide by `paneWidth`, which was correct only while the window was always the
 * pane's full width — true for 4:5 and 1:1, false for 16:9, whose pillarbox makes the
 * window narrower than the pane. Left as it was, a tap at the window's right edge
 * would have reported ~0.94 and the scene search would have restarted off-centre.
 */
fun resolveTapSceneAnchor(
    tapX: Float,
    tapY: Float,
    paneWidth: Float,
    paneHeight: Float,
    ratioWtoH: Float,
): SceneAnchorPoint? {
    if (!tapX.isFinite() || !tapY.isFinite()) return null
    val window = previewWindowOf(paneWidth, paneHeight, ratioWtoH) ?: return null
    if (!window.contains(tapX, tapY)) return null
    val (nx, ny) = window.normalize(tapX, tapY)
    return SceneAnchorPoint(x = nx, y = ny)
}
