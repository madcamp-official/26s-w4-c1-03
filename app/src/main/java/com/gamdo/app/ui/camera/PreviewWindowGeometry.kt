package com.gamdo.app.ui.camera

/**
 * Where the capture ratio's window sits inside the preview pane — pure Kotlin, no
 * `android.*`.
 *
 * ## Why this exists as one function
 *
 * Four things need this answer and they must not disagree: the aspect mask that draws
 * the bars, `resolveTapFocusPoint` (which refuses to focus on a bar), the lasso's
 * clamp, and — one layer down — `PreviewCrop.fit`, which crops the GL preview.
 *
 * Before 16:9 there were three copies of it, one of them carrying the comment "must
 * stay identical to `CameraPreviewPane`'s". They stayed identical by being watched.
 * Adding a third ratio is exactly the change that breaks that arrangement, so the
 * arithmetic moved here and the copies became calls.
 *
 * ## The bug this fixes, which was latent until 16:9
 *
 * The old rule was `windowHeight = (paneWidth / ratio).coerceAtMost(paneHeight)` —
 * it could only ever trim **height**, i.e. letterbox. That is right whenever the pane
 * is narrower than the target ratio, which 4:5 (0.8) and 1:1 (1.0) always were.
 *
 * 16:9 is 0.5625 and a phone pane is about 0.6, so the pane is *wider* than the
 * target and the binding constraint flips to height. The old rule coerced, produced
 * `barHeight = 0`, and showed a **0.635 window for a 0.5625 capture** — the preview
 * was wider than the file, which is the one thing the viewport crop exists to
 * prevent. Measured on a 1080×1700 pane: shown 0.6353 vs target 0.5625.
 *
 * So the rule is "fit inside", both axes, matching `PreviewCrop.fit` exactly. 4:5 and
 * 1:1 keep the letterbox they had; 16:9 gets a thin **pillarbox** instead.
 */
data class PreviewWindow(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /**
     * Half-open on both axes, matching the mask: a bar `Box` covers `[0, left)`, so
     * the column at `left` is the window's first.
     */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    /** The nearest point inside the window. Closed here — the boundary is reachable. */
    fun clamp(x: Float, y: Float): Pair<Float, Float> =
        x.coerceIn(left, right) to y.coerceIn(top, bottom)

    /** Position within the window, 0..1. */
    fun normalize(x: Float, y: Float): Pair<Float, Float> =
        ((x - left) / width).coerceIn(0f, 1f) to ((y - top) / height).coerceIn(0f, 1f)
}

/**
 * The largest centred [ratioWtoH] rectangle that fits in the pane.
 *
 * @return null for input no answer exists for — non-finite values, or a pane or ratio
 *   at or below zero. Callers treat that as "not measured yet" rather than guessing;
 *   it also guards the divisions below.
 */
fun previewWindowOf(paneWidth: Float, paneHeight: Float, ratioWtoH: Float): PreviewWindow? {
    if (!paneWidth.isFinite() || !paneHeight.isFinite() || !ratioWtoH.isFinite()) return null
    if (paneWidth <= 0f || paneHeight <= 0f || ratioWtoH <= 0f) return null
    val windowWidth: Float
    val windowHeight: Float
    if (paneWidth / paneHeight > ratioWtoH) {
        // Pane is wider than the target: height binds, so the width is trimmed —
        // a pillarbox. This is the branch 16:9 takes and the old code could not.
        windowHeight = paneHeight
        windowWidth = paneHeight * ratioWtoH
    } else {
        windowWidth = paneWidth
        windowHeight = paneWidth / ratioWtoH
    }
    return PreviewWindow(
        left = (paneWidth - windowWidth) / 2f,
        top = (paneHeight - windowHeight) / 2f,
        width = windowWidth,
        height = windowHeight,
    )
}
