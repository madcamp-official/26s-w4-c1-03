package com.gamdo.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.guide.OverlayProjection
import com.gamdo.app.guide.RectN
import com.gamdo.app.guide.LayoutGuideLevel
import com.gamdo.app.guide.SceneLayoutGuide
import com.gamdo.app.ui.theme.Sage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val HorizonRed = Color(0xFFE5534B)

/**
 * Overlay state for one frame — normalized (0~1, analysis-upright) boxes/points
 * plus the frame aspect used to map them onto the preview. (§2-5)
 *
 * [faces] and [personCenter] are the §2-5 coordinate-accuracy affordance and are
 * populated in debug builds only; the product overlay is [guide] plus the horizon.
 */
data class OverlayData(
    val faces: List<NormalizedBox>,
    val personCenter: Pair<Float, Float>?,
    val frameWidth: Int,
    val frameHeight: Int,
    val mirror: Boolean,
    val guide: OverlayProjection? = null,
    val layoutGuide: SceneLayoutGuide? = null,
)

/**
 * The guide overlay (§3-2) keeps the original three elements and adds the
 * scene-specific subject outline when the detector is confident:
 *
 * 1. **target bracket** — four corner marks around the composition target,
 *    translucent white until the subject is inside it, then sage,
 * 2. **silhouette** — a translucent ghost of the target area with a foot-position
 *    marker on its base edge,
 * 3. **horizon** — a line that tilts with device roll, red while tilted and
 *    straight + sage once level.
 *
 * The colour change remains the framing feedback. A short confidence prompt is
 * rendered only for the explicit scene-layout-guide flow; there is still no
 * arrow, match gauge, or auto-capture.
 *
 * Flicker damping lives upstream in `OverlayStabilizer`, not here: this composable
 * renders whatever state it is handed, so the §0.4 harness measuring the state
 * stream is measuring what the screen shows.
 *
 * Coordinates map analysis-normalized → view with the same FILL_CENTER transform
 * PreviewView uses; preview + analysis are both forced to 4:3 (same FOV) so the
 * marks land where the subject is.
 *
 * @param showDetections draws raw face boxes and the person centre dot. Debug
 *   affordance for §2-5 coordinate verification — never on in the product path.
 */
@Composable
fun CameraOverlay(
    overlay: OverlayData?,
    rollDeg: Float,
    pitchDeg: Float,
    modifier: Modifier = Modifier,
    showDetections: Boolean = false,
) {
    // Hysteresis (55° show / 65° hide) so the line doesn't flicker right at the
    // posture boundary.
    val horizonGate = remember { HorizonGate() }
    val showHorizon = horizonGate.update(pitchDeg)

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
        val vw = size.width
        val vh = size.height

        // Horizon indicator — a line through center rotated by roll. Only shown in
        // a shooting posture; near-flat (|pitch| large) has no meaningful horizon,
        // and roll there is undefined (would spin).
        if (showHorizon) {
            val horizonColor = if (isHorizonLevel(rollDeg)) Sage else HorizonRed
            // Angle decision lives in HorizonGeometry.kt so it is JVM-testable;
            // this Canvas only draws what it is handed.
            rotate(degrees = horizonIndicatorRotationDeg(rollDeg), pivot = Offset(vw / 2f, vh / 2f)) {
                drawLine(
                    color = horizonColor,
                    start = Offset(vw * 0.14f, vh / 2f),
                    end = Offset(vw * 0.86f, vh / 2f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        val data = overlay ?: return@Canvas

        data.guide?.takeIf { it.visible }?.let { guide ->
            val frame = mapRect(guide.targetFrame, data, vw, vh)
            // D2-3: the colour swap is the entire success feedback.
            val guideColor = if (guide.aligned) Sage else Color.White.copy(alpha = 0.9f)

            guide.silhouetteBounds?.let { silhouette ->
                val ghost = mapRect(silhouette, data, vw, vh)
                drawRoundRect(
                    color = guideColor.copy(alpha = 0.22f),
                    topLeft = Offset(ghost.left, ghost.top),
                    size = Size(ghost.width, ghost.height),
                    cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawFootMarker(ghost, guideColor)
            }

            data.layoutGuide?.takeIf { it.level != LayoutGuideLevel.STATIC }?.let { layout ->
                if (layout.outline.size >= 3) {
                    val points = layout.outline.map { point ->
                        mapNormalized(point.x, point.y, data, vw, vh)
                    }
                    val outlineColor = when (layout.level) {
                        LayoutGuideLevel.CONFIDENT -> guideColor.copy(alpha = 0.72f)
                        LayoutGuideLevel.DETECTING -> Color.White.copy(alpha = 0.45f)
                        LayoutGuideLevel.STATIC -> Color.Transparent
                    }
                    for (index in points.indices) {
                        val next = points[(index + 1) % points.size]
                        drawLine(
                            color = outlineColor,
                            start = points[index],
                            end = next,
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }

            drawTargetBracket(frame, guideColor)
        }

        if (!showDetections) return@Canvas

        data.faces.forEach { box ->
            val a = mapNormalized(box.left, box.top, data, vw, vh)
            val b = mapNormalized(box.right, box.bottom, data, vw, vh)
            drawRoundRect(
                color = Sage,
                topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                size = Size(abs(b.x - a.x), abs(b.y - a.y)),
                cornerRadius = CornerRadius(16f, 16f),
                style = Stroke(width = 3.dp.toPx()),
            )
        }

        data.personCenter?.let { (cx, cy) ->
            drawCircle(
                color = Sage,
                radius = 5.dp.toPx(),
                center = mapNormalized(cx, cy, data, vw, vh),
            )
        }
        }

        overlay?.layoutGuide?.prompt?.let { prompt ->
            val message = when (prompt) {
                com.gamdo.app.guide.LayoutGuidePrompt.FIND_SUBJECT -> "피사체를 화면에 보여주세요"
                com.gamdo.app.guide.LayoutGuidePrompt.HOLD_STEADY -> "피사체를 잠시 유지해주세요"
            }
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp),
            )
        }
    }
}

/**
 * Four corner marks instead of a closed rectangle: it reads as a framing bracket,
 * and leaving the edges open keeps the preview the subject of the screen rather
 * than boxing it in.
 */
private fun DrawScope.drawTargetBracket(frame: RectN, color: Color) {
    val arm = (min(frame.width, frame.height) * 0.22f)
        .coerceIn(14.dp.toPx(), 44.dp.toPx())
        // Never let two arms meet in the middle of a very small bracket.
        .coerceAtMost(min(frame.width, frame.height) / 2f)
    val stroke = 3.dp.toPx()
    for (right in listOf(false, true)) {
        for (bottom in listOf(false, true)) {
            val x = if (right) frame.right else frame.left
            val y = if (bottom) frame.bottom else frame.top
            val dx = if (right) -arm else arm
            val dy = if (bottom) -arm else arm
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x + dx, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x, y + dy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * "Stand here" mark on the base edge of the silhouette (§3-2 발 위치 마커).
 *
 * Deliberately a flat, symmetric capsule: it states a position and cannot be read
 * as a direction, which a chevron or a tapered mark would be (D2-1 방향 화살표).
 */
private fun DrawScope.drawFootMarker(silhouette: RectN, color: Color) {
    val halfWidth = silhouette.width * 0.17f
    val centerX = (silhouette.left + silhouette.right) / 2f
    drawLine(
        color = color,
        start = Offset(centerX - halfWidth, silhouette.bottom),
        end = Offset(centerX + halfWidth, silhouette.bottom),
        strokeWidth = 5.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/** Maps a normalized (0~1) analysis point to view pixels via FILL_CENTER. */
private fun mapNormalized(nx: Float, ny: Float, data: OverlayData, vw: Float, vh: Float): Offset {
    val arAnalysis = data.frameWidth.toFloat() / data.frameHeight.toFloat()
    val arView = vw / vh
    val contentW: Float
    val contentH: Float
    val offX: Float
    val offY: Float
    if (arView > arAnalysis) {
        // fill width, crop height
        contentW = vw
        contentH = vw / arAnalysis
        offX = 0f
        offY = (vh - contentH) / 2f
    } else {
        // fill height, crop width
        contentH = vh
        contentW = vh * arAnalysis
        offX = (vw - contentW) / 2f
        offY = 0f
    }
    val fx = if (data.mirror) 1f - nx else nx
    return Offset(offX + fx * contentW, offY + ny * contentH)
}

private fun mapRect(rect: RectN, data: OverlayData, vw: Float, vh: Float): RectN {
    val topLeft = mapNormalized(rect.left, rect.top, data, vw, vh)
    val bottomRight = mapNormalized(rect.right, rect.bottom, data, vw, vh)
    return RectN(
        left = min(topLeft.x, bottomRight.x),
        top = min(topLeft.y, bottomRight.y),
        right = max(topLeft.x, bottomRight.x),
        bottom = max(topLeft.y, bottomRight.y),
    )
}

/**
 * Show/hide gate for the horizon with hysteresis around the posture boundary.
 *
 * `hideAboveDeg` is [MAX_MEANINGFUL_PITCH_DEG] — the same boundary §3-3 uses to
 * decide whether the roll is worth recording. Moving one without the other would
 * leave the app drawing a horizon it refuses to store, or storing one it refuses
 * to draw. The lower `showBelowDeg` is display-only hysteresis so the indicator
 * does not flicker on and off at the boundary; a one-shot record has nothing to
 * flicker.
 */
private class HorizonGate(
    private val showBelowDeg: Float = 55f,
    private val hideAboveDeg: Float = MAX_MEANINGFUL_PITCH_DEG,
) {
    private var visible = true

    fun update(pitchDeg: Float): Boolean {
        val p = abs(pitchDeg)
        visible = if (visible) p < hideAboveDeg else p < showBelowDeg
        return visible
    }
}
