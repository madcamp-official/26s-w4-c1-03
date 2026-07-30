package com.gamdo.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/**
 * The redesign's stroke icons, drawn from the mock's own SVG path data.
 *
 * ## Why the path strings are copied verbatim
 *
 * Every glyph in `감도 리디자인.dc.html` is an inline `<svg>` with a `d` attribute.
 * Re-deriving those shapes as `drawLine`/`drawArc` calls is how an icon ends up
 * *approximately* like the design — and it is not reviewable, because a reviewer
 * comparing code to the mock would have to do the trigonometry back. [PathParser] is
 * public Compose API and takes the attribute as written, so the design's shape and
 * the app's shape are the same string.
 *
 * ## Stroke widths are in viewBox units, and the scale is what makes that work
 *
 * The design gives `stroke-width` in the SVG's own coordinates (1.6, 1.7, 1.8) while
 * the rendered size is in dp (19, 20, 21). [drawStrokeIcon] scales the whole
 * `DrawScope`, and `scale` scales stroke width with it — which is exactly SVG's
 * behaviour and the reason the numbers can be copied rather than converted.
 *
 * ## Circles inherit the stroke
 *
 * `<circle fill="#0A0A0B">` inside an `<svg stroke="…" stroke-width="…">` is a
 * **stroked** circle with an opaque fill — SVG presentation attributes inherit. That
 * is what makes the 설정 glyph a slider (two rails, each with a knob riding it)
 * rather than two lines with holes punched in them. The design confirms the
 * inheritance from the other side: the 가이드 glyph's centre dot is the one circle
 * written `stroke: none`, which would not need saying if circles did not otherwise
 * inherit it. [IconDot.stroked] is that distinction, and getting it wrong turns one
 * glyph into a different picture.
 */

/** A circle from the design's SVG. Coordinates are viewBox units. */
data class IconDot(
    val cx: Float,
    val cy: Float,
    val r: Float,
    /** The `fill`. */
    val fill: Color,
    /** Whether it also inherits the `<svg>`'s stroke — see this file's KDoc. */
    val stroked: Boolean,
)

/**
 * Draws one of the design's glyphs.
 *
 * @param pathData the mock's `d` attribute, unmodified.
 * @param viewBox the mock's `viewBox` extent (square in every icon here).
 * @param strokeWidth the mock's `stroke-width`, in [viewBox] units.
 */
@Composable
fun StrokeIcon(
    viewBox: Float,
    size: Dp,
    strokeWidth: Float,
    color: Color,
    modifier: Modifier = Modifier,
    /** Blank for a glyph made only of [dots] — the 필터 lenses are the case. */
    pathData: String = "",
    dots: List<IconDot> = emptyList(),
) {
    // Parsed once per path string, not once per frame: the guide toggle recomposes on
    // every alignment change and the parser walks the whole string each time.
    val path = remember(pathData) {
        pathData.takeIf { it.isNotBlank() }?.let { PathParser().parsePathString(it).toPath() }
    }
    Canvas(modifier = modifier.size(size)) {
        drawStrokeIcon(path, viewBox, strokeWidth, color, dots)
    }
}

private fun DrawScope.drawStrokeIcon(
    path: Path?,
    viewBox: Float,
    strokeWidth: Float,
    color: Color,
    dots: List<IconDot>,
) {
    val factor = size.minDimension / viewBox
    scale(scale = factor, pivot = Offset.Zero) {
        if (path != null) {
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
        dots.forEach { dot ->
            drawCircle(color = dot.fill, radius = dot.r, center = Offset(dot.cx, dot.cy))
            if (dot.stroked) {
                drawCircle(
                    color = color,
                    radius = dot.r,
                    center = Offset(dot.cx, dot.cy),
                    style = Stroke(width = strokeWidth),
                )
            }
        }
    }
}

/**
 * The path data, verbatim from the mock. Grouped here so a diff against the design
 * is a string comparison and nothing else.
 */
object CameraIconPaths {

    /** 설정 — two rails; the knobs are [settingsKnobs]. viewBox 22, stroke 1.6, 21dp. */
    const val SETTINGS: String = "M3 6.5h16M3 15.5h16"

    /** 직접 지정 — Feather `edit-2`. viewBox 24, stroke 1.7, 19dp. */
    const val PENCIL: String = "M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"

    /**
     * 가이드 — four corner brackets. viewBox 22, stroke 1.7, 21dp. The centre dot is
     * [guideCentreDot] and is not optional: it is what separates this glyph from the
     * 재탐색 button's older bracket miniature.
     */
    const val GUIDE: String =
        "M3 8V5.5A2.5 2.5 0 0 1 5.5 3H8M14 3h2.5A2.5 2.5 0 0 1 19 5.5V8" +
            "M19 14v2.5a2.5 2.5 0 0 1-2.5 2.5H14M8 19H5.5A2.5 2.5 0 0 1 3 16.5V14"

    /**
     * 재탐색 — a circular (refresh) arrow. viewBox 15, stroke 1.5, 15dp.
     *
     * This replaces a hand-drawn corner-bracket miniature that was chosen because
     * "D2 bans direction arrows from this screen". The ban is on **direction**
     * arrows — a mark telling the user which way to move — and a refresh arrow is
     * not one. Owner's redesign draws this shape, and the design is final.
     */
    const val RESCAN: String = "M13.5 8a5.5 5.5 0 1 1-1.6-3.9M13.5 1.8v2.8h-2.8"

    // 필터 has no `d` at all in the mock — it is two `<circle>` elements. See
    // [filterLenses]; [StrokeIcon]'s `pathData` defaults to blank for exactly this.

    /** 렌즈 전환 — Feather `refresh-cw`. viewBox 24, stroke 1.8, 19dp. */
    const val FLIP_LENS: String =
        "M23 4v6h-6M1 20v-6h6" +
            "M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"

    /** 내 레퍼런스 삭제 badge. viewBox 10, stroke 1.5, 8dp. */
    const val CLOSE: String = "M1.5 1.5l7 7M8.5 1.5l-7 7"

    /**
     * 프레임 — a rounded outer frame holding two inner rectangles, i.e. "a layout".
     * viewBox 16, stroke 1.4, 16dp.
     *
     * Not from the mock: the final design has no frame button, so 담당 B's handoff leaves
     * the glyph to P1 (§1). Drawn in the same idiom as the mock's own icons and chosen to
     * collide with neither neighbour — 가이드 is four corner brackets, 재탐색 is a
     * circular arrow, and this is the only one that encloses smaller shapes.
     */
    const val FRAME: String =
        "M2 3.2a1.2 1.2 0 0 1 1.2-1.2h9.6A1.2 1.2 0 0 1 14 3.2v9.6a1.2 1.2 0 0 1-1.2 1.2" +
            "H3.2A1.2 1.2 0 0 1 2 12.8Z" +
            "M4.4 5.6h3.2v4.8H4.4ZM9.4 5.6h2.2v2.6H9.4Z"

    /** The 설정 glyph's two knobs, riding the rails at 6.5 and 15.5. */
    fun settingsKnobs(background: Color): List<IconDot> = listOf(
        IconDot(cx = 8.5f, cy = 6.5f, r = 2.4f, fill = background, stroked = true),
        IconDot(cx = 14f, cy = 15.5f, r = 2.4f, fill = background, stroked = true),
    )

    /** The 가이드 glyph's centre dot — filled, and the one circle with `stroke: none`. */
    fun guideCentreDot(color: Color): List<IconDot> = listOf(
        IconDot(cx = 11f, cy = 11f, r = 1.7f, fill = color, stroked = false),
    )

    /** The 필터 glyph's two lenses. Stroked, unfilled — hence no `fill` in the mock. */
    fun filterLenses(): List<IconDot> = listOf(
        IconDot(cx = 8.5f, cy = 11f, r = 5.5f, fill = Color.Transparent, stroked = true),
        IconDot(cx = 13.5f, cy = 11f, r = 5.5f, fill = Color.Transparent, stroked = true),
    )
}
