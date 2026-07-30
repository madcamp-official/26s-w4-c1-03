package com.gamdo.app.ui.camera

import com.gamdo.app.guide.PreviewGeometry
import kotlin.math.abs

/**
 * The lasso (연필 = 영역 선택) path, in **pane pixels** — pure Kotlin, no
 * `android.*`, for the same reason as [resolveTapFocusPoint].
 *
 * P2 owns everything downstream: `ScenePolygonRegion.fromViewPath` converts the path
 * to an analysis-normalized polygon, simplifies it, and rejects it on area
 * (2%..80%). P1 owns only the collection and the drawing, so this file deliberately
 * knows **no area threshold** — duplicating one here is how the two copies come to
 * disagree.
 *
 * ## The path is a ring, not a stroke
 *
 * `polygonArea` walks `points[(i + 1) % size]`, so the list is already treated as a
 * closed ring. "손을 떼면 경로를 자동으로 닫는다" therefore needs **no code**: the
 * closing edge exists the moment the list is read as a polygon. Appending the first
 * point again would add a zero-length edge and one more vertex for `simplify` to
 * chew on, so [appended] does not do it. This is the kind of thing worth stating
 * because the obvious implementation is wrong in a way nothing would report.
 *
 * ## Why thinning
 *
 * A finger crossing the preview produces one sample per frame for as long as the
 * drag lasts — several hundred points, all of them re-drawn every frame while the
 * user is still moving. P2's `simplify` runs at a normalized tolerance of 0.01
 * *after* the mapping, so it does not bound what P1 draws. [MIN_STEP_DP] bounds it
 * here, ahead of both, and it is a distance rather than a count so a slow careful
 * drag is thinned exactly as much as a fast one.
 */
object AreaSelectPath {

    /**
     * Minimum gap between kept samples.
     *
     * 6dp is under a third of a fingertip, so no corner a user can aim at is lost,
     * and it holds a full-screen lasso to roughly a hundred points on a 1080p phone.
     */
    const val MIN_STEP_DP: Float = 6f

    /** `fromNormalized` needs three vertices to have an area at all. */
    const val MIN_VERTICES: Int = 3

    /**
     * The path with [x], [y] appended — or unchanged when the sample is within
     * [minStepPx] of the last kept one.
     *
     * Manhattan distance rather than Euclidean, matching P2's `simplify`, so the two
     * thinning steps do not disagree about what "close" means near a diagonal.
     *
     * Non-finite samples are dropped. A NaN reaching `viewToAnalysis` would fail its
     * range check and be silently discarded by `mapNotNull`, which turns one bad
     * sample into a polygon with a hole in its outline rather than into an error.
     */
    fun appended(
        points: List<Pair<Float, Float>>,
        x: Float,
        y: Float,
        minStepPx: Float,
    ): List<Pair<Float, Float>> {
        if (!x.isFinite() || !y.isFinite()) return points
        val last = points.lastOrNull() ?: return points + (x to y)
        if (abs(x - last.first) + abs(y - last.second) < minStepPx) return points
        return points + (x to y)
    }

    /**
     * Whether the path is worth handing to P2 at all.
     *
     * Only the vertex count, on purpose. A path that fails on **area** must still be
     * submitted, because `rescanLayoutInPolygon` returning `false` is what tells the
     * screen to show the rejection — and it is P2 that owns the threshold. A path
     * with fewer than three points is different in kind: it is a tap that happened
     * while the lasso was armed, and there is nothing to reject.
     */
    fun isWorthSubmitting(points: List<Pair<Float, Float>>): Boolean =
        points.size >= MIN_VERTICES

    /**
     * Clamps a sample into the visible capture window.
     *
     * The `PreviewView` fills the whole pane and the aspect bars are drawn *over* it,
     * so a drag that strays into a bar is over live camera pixels the user cannot see
     * and the aspect crop will discard. `resolveTapFocusPoint` **rejects** such a
     * point, which is right for a focus tap — one tap, one answer. A lasso is a
     * stroke, and dropping its middle would splice the path straight across the
     * subject. So this rides the boundary instead.
     *
     * Clamps on **both axes**: 16:9's window is pillarboxed, so there are side bars to
     * stray into as well as top and bottom ones. The window itself comes from
     * [previewWindowOf], shared with the mask and the focus rule.
     *
     * Returns `null` only for input no clamp can rescue: non-finite coordinates, or a
     * pane that has not been measured.
     */
    fun clampToWindow(
        x: Float,
        y: Float,
        paneWidth: Float,
        paneHeight: Float,
        ratioWtoH: Float,
    ): Pair<Float, Float>? {
        if (!x.isFinite() || !y.isFinite()) return null
        val window = previewWindowOf(paneWidth, paneHeight, ratioWtoH) ?: return null
        return window.clamp(x, y)
    }
}

/**
 * What leaving area-select mode owes the guide.
 *
 * Getting this wrong is destructive rather than merely wrong, which is why it is a
 * named decision: `CameraViewModel.cancelPolygonLayoutSearch()` runs
 * `alignmentEngine.reset()`, `stabilizer.reset()` and a fresh scene search. Calling
 * it unconditionally would mean a user who armed the pencil, drew nothing and
 * disarmed it **lost the layout they already had** — the same harm §4 P2-1 forbids
 * for an invalid path ("기존 고정을 변경하지 않는다"), arriving by a different route.
 */
/**
 * Hands a finished lasso to P2, or declines to.
 *
 * Pure, with [submit] injected, so the two "do not call detection" cases are testable
 * without a ViewModel — and they are the reason this is a function rather than three
 * lines at the call site:
 *
 *  - **too few vertices.** A tap that happened while the pencil was armed. §4 P2-1:
 *    "영역이 너무 작거나 크면 서버·탐지를 호출하지 않는다".
 *  - **no analysis frame yet.** [PreviewGeometry]'s `init` *requires* positive
 *    dimensions, so passing the zeroes that stand for "the first frame has not landed"
 *    would throw `IllegalArgumentException` out of a gesture callback and take the
 *    screen with it. Returning false is the same answer as an unusable region.
 *
 * Everything else — area bounds, simplification, which objects the region accepts — is
 * P2's, reached through [submit]. P1 does not hold a second copy of the thresholds.
 *
 * @return whether a search was actually started. `false` leaves the existing fix
 *   untouched, which §4 P2-1 requires of an invalid path.
 */
fun submitLassoRegion(
    points: List<Pair<Float, Float>>,
    paneWidthPx: Float,
    paneHeightPx: Float,
    analysisWidth: Int,
    analysisHeight: Int,
    mirror: Boolean,
    submit: (List<Pair<Float, Float>>, PreviewGeometry) -> Boolean,
): Boolean {
    if (!AreaSelectPath.isWorthSubmitting(points)) return false
    if (paneWidthPx < 1f || paneHeightPx < 1f) return false
    if (analysisWidth <= 0 || analysisHeight <= 0) return false
    val geometry = PreviewGeometry(
        viewWidth = paneWidthPx.toInt(),
        viewHeight = paneHeightPx.toInt(),
        analysisWidth = analysisWidth,
        analysisHeight = analysisHeight,
        mirror = mirror,
    )
    return submit(points, geometry)
}

enum class AreaSelectExit {
    /** A polygon search is live; hand the guide back to automatic search. */
    CANCEL_POLYGON_SEARCH,

    /** Nothing was ever submitted. Leave the existing fix exactly as it is. */
    LEAVE_GUIDE_ALONE,
    ;

    companion object {
        /**
         * @param scopeIsPolygon whether `CameraViewModel.searchScope` is currently
         *   `SceneSearchScope.Polygon` — i.e. whether a lasso search was accepted.
         */
        fun forExit(scopeIsPolygon: Boolean): AreaSelectExit =
            if (scopeIsPolygon) CANCEL_POLYGON_SEARCH else LEAVE_GUIDE_ALONE
    }
}
