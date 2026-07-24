package com.gamdo.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.ui.theme.Sage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val HorizonRed = Color(0xFFE5534B)

/**
 * Overlay state for one frame — normalized (0~1, analysis-upright) boxes/points
 * plus the frame aspect used to map them onto the preview. (§2-5)
 */
data class OverlayData(
    val faces: List<NormalizedBox>,
    val personCenter: Pair<Float, Float>?,
    val frameWidth: Int,
    val frameHeight: Int,
    val mirror: Boolean,
)

/**
 * Overlay v1 (§2-5): face boxes (rounded rect), person center point, and a
 * horizon indicator that tilts with device roll (red when tilted, sage when level).
 *
 * Coordinates map analysis-normalized → view with the same FILL_CENTER transform
 * PreviewView uses; preview + analysis are both forced to 4:3 (same FOV) so the
 * box lands on the face.
 */
@Composable
fun CameraOverlay(
    overlay: OverlayData?,
    rollDeg: Float,
    pitchDeg: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val vw = size.width
        val vh = size.height

        // Horizon indicator — a line through center rotated by roll. Only shown in
        // a shooting posture; near-flat (|pitch| large) has no meaningful horizon,
        // and roll there is undefined (would spin).
        if (abs(pitchDeg) < 60f) {
            val level = abs(rollDeg) <= 1.5f
            val horizonColor = if (level) Sage else HorizonRed
            rotate(degrees = -rollDeg, pivot = Offset(vw / 2f, vh / 2f)) {
                drawLine(
                    color = horizonColor,
                    start = Offset(vw * 0.14f, vh / 2f),
                    end = Offset(vw * 0.86f, vh / 2f),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }

        val data = overlay ?: return@Canvas

        data.faces.forEach { box ->
            val a = mapNormalized(box.left, box.top, data, vw, vh)
            val b = mapNormalized(box.right, box.bottom, data, vw, vh)
            val left = min(a.x, b.x)
            val top = min(a.y, b.y)
            drawRoundRect(
                color = Sage,
                topLeft = Offset(left, top),
                size = Size(abs(b.x - a.x), abs(b.y - a.y)),
                cornerRadius = CornerRadius(16f, 16f),
                style = Stroke(width = 3.dp.toPx()),
            )
        }

        data.personCenter?.let { (cx, cy) ->
            val p = mapNormalized(cx, cy, data, vw, vh)
            drawCircle(color = Sage, radius = 5.dp.toPx(), center = p)
        }
    }
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

// Kept internal-visible for potential future use.
@Suppress("unused")
private fun clamp01(v: Float) = max(0f, min(1f, v))
