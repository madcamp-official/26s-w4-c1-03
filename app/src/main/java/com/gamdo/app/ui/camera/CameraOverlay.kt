package com.gamdo.app.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.guide.GuideMark
import com.gamdo.app.guide.SceneGuideMarks
import com.gamdo.app.guide.SubjectWeight
import com.gamdo.app.ui.theme.Amber
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
    val layoutState: GuideLayoutState = GuideLayoutState.Searching,
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
 * Scene discovery has no user-facing text or occupancy feedback. While it is
 * searching the camera remains visually clear except for a small spinner; after
 * confirmation only the fixed composition brackets remain.
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
 * @param guideMarks 상황 우선 가이드 V2's fixed marks (요구사항 §10). When present they
 *   are drawn **instead of** the template's own slots, never alongside: P2 populates
 *   `guideMarks` only while `layoutState is Fixed`, which is the same condition the
 *   slot block below draws under, so rendering both would put two vocabularies —
 *   rounded slot rectangles and dots/rings — on the same scene at the same time.
 *   Null falls back to the slot path, which is the device-verified rendering and stays
 *   the answer whenever V2 produced nothing for this frame.
 */
@Composable
fun CameraOverlay(
    overlay: OverlayData?,
    rollDeg: Float,
    pitchDeg: Float,
    modifier: Modifier = Modifier,
    showDetections: Boolean = false,
    guideMarks: SceneGuideMarks? = null,
) {
    // Hysteresis (55° show / 65° hide) so the line doesn't flicker right at the
    // posture boundary.
    val horizonGate = remember { HorizonGate() }
    val showHorizon = horizonGate.update(pitchDeg)

    // D2-3's colour swap, now over 200ms instead of instantly (owner's redesign:
    // "브래킷도 화이트→앰버 200ms ease-out").
    //
    // Animated **here in the composable**, not inside the Canvas: a draw lambda cannot
    // host an animation, and the value has to be read where recomposition can see it.
    // `AlignmentAmber.isOn` is the shared predicate — the shutter reads the same one,
    // so the two cannot disagree about what a match is. `guideShown = true` because
    // this composable is not rendered at all when the §3-2 toggle is off.
    //
    // A hard cut used to be acceptable when the bracket was the only thing that
    // changed. With the shutter changing at the same moment, two uncoordinated hard
    // cuts read as a flicker rather than as one event.
    val alignedAmber = AlignmentAmber.isOn(overlay, guideShown = true)
    val guideAccent by animateColorAsState(
        targetValue = if (alignedAmber) Amber else Color.White.copy(alpha = 0.9f),
        animationSpec = tween(CAMERA_ALIGN_FADE_MS, easing = LinearOutSlowInEasing),
        label = "guideBracket",
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
        val vw = size.width
        val vh = size.height

        // Horizon indicator — a line through center rotated by roll. Only shown in
        // a shooting posture; near-flat (|pitch| large) has no meaningful horizon,
        // and roll there is undefined (would spin).
        if (showHorizon) {
            val horizonColor = if (isHorizonLevel(rollDeg)) Amber else HorizonRed
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

        // 상황 우선 가이드 V2 (요구사항 §10) takes precedence — see the parameter's KDoc
        // for why this is an either/or and not both.
        //
        // Marks are already fixed targets: P2 computes them once when the scene latches
        // and holds them, which is what "P1은 매 프레임 검출 위치를 그리지 않는다" asks
        // for. Nothing here re-derives a position from a detection.
        val marks = guideMarks?.marks
        if (marks != null) {
            marks.forEach { mark -> drawGuideMark(mark, data, vw, vh, Color.White.copy(alpha = 0.86f)) }
        } else {
            // Fixed-layout mode is intentionally independent from detections: the
            // slots stay on screen while the user moves the camera or the objects.
            data.layoutGuide?.fixedLayout?.let { fixed ->
                // **Every** slot the template carries, each in its own kind's style.
                //
                // Both halves are requirements (§3.3): "인물 1명과 물체가 함께 선택되면
                // 전달된 모든 슬롯을 렌더" — the `forEach` — and the per-slot style, which
                // this used to drop on the floor by drawing one shape for all five kinds.
                fixed.template.slots.forEach { slot ->
                    // Template slots are authored as screen positions, not detections.
                    val slotRect = mapRect(slot.bounds, data, vw, vh, OverlayMapping.Space.COMPOSITION)
                    val style = SlotRenderStyle.of(slot.visualKind)
                    drawRoundRect(
                        color = Color.White.copy(alpha = fixed.template.opacity * 0.32f),
                        topLeft = Offset(slotRect.left, slotRect.top),
                        size = Size(slotRect.width, slotRect.height),
                        cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                    )
                    drawSlotForStyle(slotRect, style, Color.White.copy(alpha = 0.86f))
                }
            }
        }

        // The style-preset guide: bracket + silhouette + foot marker + outline.
        //
        // This block was commented out wholesale on the AI-1 branch. 부록 A names
        // "목표 프레임·실루엣·수평선 오버레이" as one of the things this project
        // keeps to the end, and §3-2's completion criterion is exactly this
        // vocabulary, so commenting it out made that criterion unreachable. Owner
        // decision 2026-07-28: restore it, keep the fixed-layout gate below.
        //
        // The gate means the preset guide yields to a latched scene layout rather
        // than drawing over it. The way out of a latch is the 재탐색 button on the
        // preview, not a second set of marks on screen.
        data.guide
            // Searching deliberately shows only the compact loading indicator.
            // A free-floating target bracket in an empty scene looked like a false
            // recommendation despite there being no stable template to follow.
            ?.takeIf {
                it.visible && data.layoutState is GuideLayoutState.Fixed &&
                    data.layoutGuide?.fixedLayout == null
            }
            ?.let { guide ->
            // The composition target: where the subject should end up in the photo.
            val frame = mapRect(guide.targetFrame, data, vw, vh, OverlayMapping.Space.COMPOSITION)
            // D2-3: the colour swap is the entire success feedback. Mid-fade value,
            // computed above — see there for why it is not `if (guide.aligned)` here.
            val guideColor = guideAccent

            guide.silhouetteBounds?.let { silhouette ->
                val ghost = mapRect(silhouette, data, vw, vh, OverlayMapping.Space.COMPOSITION)
                drawRoundRect(
                    color = guideColor.copy(alpha = 0.22f),
                    topLeft = Offset(ghost.left, ghost.top),
                    size = Size(ghost.width, ghost.height),
                    cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawFootMarker(ghost, guideColor)
            }

            data.layoutGuide?.takeIf { it.level != LayoutGuideLevel.STATIC && it.fixedLayout == null }?.let { layout ->
                if (layout.outline.size >= 3) {
                    val points = layout.outline.map { point ->
                        // Segmentation outline — detector output.
                        mapNormalized(point.x, point.y, data, vw, vh, OverlayMapping.Space.ANALYSIS)
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

            if (data.layoutGuide?.fixedLayout == null) {
                drawTargetBracket(frame, guideColor)
            }
        }

        if (!showDetections) return@Canvas

        data.faces.forEach { box ->
            val a = mapNormalized(box.left, box.top, data, vw, vh, OverlayMapping.Space.ANALYSIS)
            val b = mapNormalized(box.right, box.bottom, data, vw, vh, OverlayMapping.Space.ANALYSIS)
            drawRoundRect(
                color = Amber,
                topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                size = Size(abs(b.x - a.x), abs(b.y - a.y)),
                cornerRadius = CornerRadius(16f, 16f),
                style = Stroke(width = 3.dp.toPx()),
            )
        }

        data.personCenter?.let { (cx, cy) ->
            drawCircle(
                color = Amber,
                radius = 5.dp.toPx(),
                center = mapNormalized(cx, cy, data, vw, vh, OverlayMapping.Space.ANALYSIS),
            )
        }
        }

        if (overlay?.layoutState is GuideLayoutState.Searching) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(18.dp),
                color = Color.White.copy(alpha = 0.72f),
                strokeWidth = 2.dp,
            )
        }
    }
}

/**
 * Draws one fixed-layout slot in the style its [SlotVisualKind] asked for.
 *
 * The three cases differ by **how much they claim to know**, which is the distinction
 * 담당 B encodes and this renderer used to erase:
 *
 *  - a silhouette states a body's shape and where the feet go;
 *  - a person bracket states a region and that a person belongs in it;
 *  - an object bracket states a region and nothing else.
 *
 * Every mark is a stroke, and there is no text, no arrow, no gauge and no occupancy
 * colour anywhere in here — a slot's appearance depends on its *kind*, never on
 * whether it has been filled (D2-1, and `CameraOverlayD2Test`'s `SlotMatchStatus` ban).
 */
/**
 * One [GuideMark] of 상황 우선 가이드 V2 (요구사항 §10), in this overlay's own stroke
 * vocabulary.
 *
 * §10 assigns each kind its shape — `SubjectDot` 점/링, `PersonSilhouette` 고정 인물
 * 실루엣, `HorizonLine` 수평선 — and forbids everything a mark could otherwise carry:
 * raw boxes, labels, confidence, track ids, alignment scores, slot fill state. None of
 * those reach this function, because [GuideMark] does not carry them; the seam is the
 * guarantee rather than a rule anyone has to remember here.
 *
 * [GuideMark.SubjectDot.weight] is deliberately **not** drawn as a different colour or
 * a number. HERO/EQUAL/SUPPORTING is authored emphasis, so it changes the ring's size
 * only — a colour would be read as occupancy feedback, which D2-1 bans outright.
 */
private fun DrawScope.drawGuideMark(
    mark: GuideMark,
    data: OverlayData,
    vw: Float,
    vh: Float,
    color: Color,
) {
    when (mark) {
        is GuideMark.SubjectDot -> {
            // Mapped as a square around the centre so the one existing coordinate
            // transform does the work; a separate point mapping could drift from the
            // slot path's by a rounding rule and put the dots somewhere the brackets
            // would not have gone.
            val box = RectN(
                left = mark.center.x - mark.radius,
                top = mark.center.y - mark.radius,
                right = mark.center.x + mark.radius,
                bottom = mark.center.y + mark.radius,
            )
            val mapped = mapRect(box, data, vw, vh, OverlayMapping.Space.COMPOSITION)
            val centre = Offset(mapped.left + mapped.width / 2f, mapped.top + mapped.height / 2f)
            // The ring is the target; the pip at its centre is what makes it read as a
            // point to put something on rather than a hole to look through.
            val ring = (mapped.width / 2f) * when (mark.weight) {
                SubjectWeight.HERO -> 1f
                SubjectWeight.EQUAL -> 0.85f
                SubjectWeight.SUPPORTING -> 0.7f
            }
            drawCircle(color = color, radius = ring, center = centre, style = Stroke(width = 1.8.dp.toPx()))
            drawCircle(color = color, radius = 2.dp.toPx(), center = centre)
        }

        is GuideMark.PersonSilhouette -> {
            // The same silhouette the slot path draws, so a person guided by V2 and a
            // person guided by a template look identical. §10 asks for 고정 인물 실루엣
            // and this overlay already has exactly one.
            val frame = mapRect(mark.bounds, data, vw, vh, OverlayMapping.Space.COMPOSITION)
            drawSlotForStyle(frame, SlotRenderStyle.PERSON_SILHOUETTE, color)
        }

        is GuideMark.HorizonLine -> {
            // A *composition* horizon — where the horizon should sit in the photograph —
            // which is a different statement from the tilt line above, and that one is
            // gated on pitch so the two are not normally on screen together. Inset to
            // the same 14%/86% the tilt line uses so they read as one vocabulary.
            val band = RectN(left = 0.14f, top = mark.y, right = 0.86f, bottom = mark.y)
            val mapped = mapRect(band, data, vw, vh, OverlayMapping.Space.COMPOSITION)
            drawLine(
                color = color,
                start = Offset(mapped.left, mapped.top),
                end = Offset(mapped.right, mapped.top),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun DrawScope.drawSlotForStyle(frame: RectN, style: SlotRenderStyle, color: Color) {
    when (style) {
        SlotRenderStyle.PERSON_SILHOUETTE -> {
            drawPersonSilhouette(frame, color)
            // The same symmetric capsule the preset guide uses — flat on purpose, so it
            // states a position and cannot be read as a direction (D2-1 방향 화살표).
            drawFootMarker(frame, color)
        }
        SlotRenderStyle.PERSON_BRACKET -> {
            drawLayoutSlotBracket(frame, color)
            drawHeadMarker(frame, color)
        }
        SlotRenderStyle.OBJECT_BRACKET -> drawLayoutSlotBracket(frame, color)
    }
}

/**
 * A body outline: head, shoulders, and a torso that runs to the slot's base.
 *
 * Deliberately crude — two arcs and two lines. It has to read as "a person stands
 * here" at a glance over live camera image, and a more detailed figure would start
 * looking like a pose to match rather than a region to stand in. It is an **outline**
 * for the same reason: a filled shape would hide the subject it is guiding.
 *
 * Proportions are fractions of the slot, not fixed dp, so the same drawing works for
 * `person_upper` (a head-and-shoulders slot) and `person_full_center` (a whole body).
 * The head is sized off the slot's **width**, because a head that scaled with height
 * would become a balloon on a tall slot.
 */
private fun DrawScope.drawPersonSilhouette(frame: RectN, color: Color) {
    val stroke = 1.5.dp.toPx()
    val headRadius = (frame.width * 0.17f).coerceAtMost(frame.height * 0.13f)
    val centerX = (frame.left + frame.right) / 2f
    val headCenterY = frame.top + headRadius * 1.25f
    drawCircle(
        color = color,
        radius = headRadius,
        center = Offset(centerX, headCenterY),
        style = Stroke(width = stroke),
    )
    // Shoulders: a shallow curve springing from just below the head out to the slot's
    // sides. Drawn as a quadratic through a control point above the ends so the line
    // bows the way a shoulder line does rather than forming a V.
    val shoulderY = headCenterY + headRadius * 1.9f
    val shoulderHalf = frame.width * 0.42f
    val torsoBottom = frame.bottom
    val body = Path().apply {
        moveTo(centerX - shoulderHalf, torsoBottom)
        lineTo(centerX - shoulderHalf, shoulderY)
        quadraticTo(centerX, shoulderY - headRadius * 1.1f, centerX + shoulderHalf, shoulderY)
        lineTo(centerX + shoulderHalf, torsoBottom)
    }
    drawPath(path = body, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

/**
 * A head-position mark for [SlotRenderStyle.PERSON_BRACKET]: an open circle at the top
 * centre of the slot.
 *
 * The whole difference between this and an object bracket, and it is enough — a circle
 * where a head goes is the least a mark can say while still saying "person". A full
 * silhouette here would contradict P2, which chose `PERSON_BRACKET` precisely for the
 * templates where it does not want to claim a body shape.
 */
private fun DrawScope.drawHeadMarker(frame: RectN, color: Color) {
    val radius = (frame.width * 0.13f).coerceAtMost(frame.height * 0.10f)
    drawCircle(
        color = color,
        radius = radius,
        center = Offset((frame.left + frame.right) / 2f, frame.top + radius * 1.6f),
        style = Stroke(width = 1.5.dp.toPx()),
    )
}

/** Thin open corners read as a composition target rather than a filled checklist box. */
private fun DrawScope.drawLayoutSlotBracket(frame: RectN, color: Color) {
    val arm = (min(frame.width, frame.height) * 0.18f)
        .coerceIn(10.dp.toPx(), 26.dp.toPx())
        .coerceAtMost(min(frame.width, frame.height) / 2f)
    val stroke = 1.5.dp.toPx()
    for (right in listOf(false, true)) {
        for (bottom in listOf(false, true)) {
            val x = if (right) frame.right else frame.left
            val y = if (bottom) frame.bottom else frame.top
            val dx = if (right) -arm else arm
            val dy = if (bottom) -arm else arm
            drawLine(color, Offset(x, y), Offset(x + dx, y), stroke, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy), stroke, StrokeCap.Round)
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

/**
 * Maps a normalized point onto the view. [space] decides whether the front-lens
 * mirror applies — see [OverlayMapping] for why that is not one answer for
 * everything.
 */
private fun mapNormalized(
    nx: Float,
    ny: Float,
    data: OverlayData,
    vw: Float,
    vh: Float,
    space: OverlayMapping.Space,
): Offset {
    val p = OverlayMapping.point(
        nx, ny, space, data.mirror, data.frameWidth, data.frameHeight, vw, vh,
    )
    return Offset(p.x, p.y)
}

private fun mapRect(
    rect: RectN,
    data: OverlayData,
    vw: Float,
    vh: Float,
    space: OverlayMapping.Space,
): RectN {
    val r = OverlayMapping.rect(
        rect.left, rect.top, rect.right, rect.bottom,
        space, data.mirror, data.frameWidth, data.frameHeight, vw, vh,
    )
    return RectN(left = r.left, top = r.top, right = r.right, bottom = r.bottom)
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
