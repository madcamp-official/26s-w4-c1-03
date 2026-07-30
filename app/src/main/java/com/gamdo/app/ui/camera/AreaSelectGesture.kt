package com.gamdo.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

/**
 * The 연필 drag: collecting a lasso path, and drawing it.
 *
 * The pure half — thinning, closing, clamping, and what leaving the mode owes the
 * guide — is [AreaSelectPath] and is unit-tested. This file is the part that cannot be:
 * a `PointerInputScope` loop and a `Canvas`.
 *
 * ## Why a hand-written loop rather than `detectDragGestures`
 *
 * `detectDragGestures` consumes touch slop on **every** drag it sees, whether or not
 * its callbacks do anything. Installed permanently — which it must be, since keying the
 * `pointerInput` on the armed flag restarts the handler and restarting it eats a
 * gesture (measured: the first tap after every 4:5 ↔ 1:1 switch, 3/3, see
 * `installPreviewGestures`) — it would take drags away from pinch-to-zoom for the
 * entire session with the pencil off.
 *
 * So the arming check happens **at the DOWN, before anything is consumed**. Not armed
 * means return without touching the event, and the other two gestures see input
 * identical to what they saw before this existed.
 */
suspend fun PointerInputScope.detectLassoDrags(
    /** Read live, never captured: the pencil is toggled without restarting this loop. */
    armed: () -> Boolean,
    onStart: () -> Unit,
    onPoint: (Float, Float) -> Unit,
    onFinish: () -> Unit,
) {
    awaitEachGesture {
        // `requireUnconsumed = false` because this handler is registered first and so is
        // looking at a fresh event; the flag only matters to a later sibling.
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!armed()) return@awaitEachGesture
        down.consume()
        onStart()
        onPoint(down.position.x, down.position.y)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            // `IgnoreConsumed` so a sibling that consumed the UP cannot strand this
            // loop with the path still open and the mode still drawing.
            if (change.changedToUpIgnoreConsumed()) break
            onPoint(change.position.x, change.position.y)
            event.changes.forEach { it.consume() }
        }
        // "손을 떼면 경로를 자동으로 닫는다" — the closing edge needs no point appended;
        // see [AreaSelectPath]. This just hands the ring over.
        onFinish()
    }
}

/**
 * The lasso path on screen.
 *
 * **Not amber.** §4 P2-1 says the active indicator is "버튼의 앰버 하나로" — one amber,
 * on the button — so a second amber mark here would be exactly the duplication the
 * redesign removed from the top bar. White at 82% over a dark stroke reads on any scene
 * without claiming to be a state.
 *
 * [alpha] carries the settle-out. A submitted path fades rather than vanishing, which
 * is the "짧은 시각 피드백" §4 P2-1 asks for on a rejected region — and on an accepted
 * one the scene-search spinner appears as the path goes, so the two outcomes differ by
 * what follows rather than by any text (R7-1/R7-2 ban both the vocabulary and the
 * instruction).
 *
 * ## An accepted and a rejected region look nearly the same, and that is decided
 *
 * Both fade over [AREA_SELECT_SETTLE_MS]; the only difference is that an accepted one is
 * followed by the search spinner and a rejected one by nothing, with the pencil left
 * armed. That is a weak signal for "your region was too small or too large", it was
 * raised as such, and the **owner chose to keep it** (2026-07-30).
 *
 * The reason is that there is no signal left to give. Amber is reserved for *active*
 * state — it is what the pencil button itself is using while this path is on screen —
 * so an amber flash here would say "armed" twice and mean neither. Red is spoken for:
 * `HorizonRed` is the one approved chromatic exception on this screen and it means
 * horizon deviation. And copy is banned outright — §4 P2-1 says "행동 지시 문구는
 * 띠우지 않고", R7-1 bans the vocabulary and R7-2 bans the instruction.
 *
 * **So this is not a missing feature.** If you are here because the rejected path looks
 * like nothing happened: that is the decision, and the fix is not a new colour. Adding
 * one needs an owner reversal, not a judgement call — the same standing rule D2 has.
 */
@Composable
fun AreaSelectPathOverlay(
    points: List<Pair<Float, Float>>,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2 || alpha <= 0f) return
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (index in 1 until points.size) lineTo(points[index].first, points[index].second)
            // Drawn closed, because it *is* closed downstream — `polygonArea` wraps the
            // last vertex to the first. Showing an open stroke while selecting a closed
            // region would misreport what is about to be searched.
            close()
        }
        // A dark under-stroke so the line survives a white subject; the same trick the
        // horizon does not need because it is only ever one colour.
        drawPath(
            path = path,
            color = Color.Black.copy(alpha = 0.35f * alpha),
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.82f * alpha),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** The lasso's settle-out, and the fade of a rejected region. */
const val AREA_SELECT_SETTLE_MS: Int = 220

/** Where a lasso point ends up: clamped into the visible window, or dropped. */
internal fun clampedLassoPoint(
    position: Offset,
    paneWidth: Float,
    paneHeight: Float,
    ratioWtoH: Float,
): Pair<Float, Float>? = AreaSelectPath.clampToWindow(
    x = position.x,
    y = position.y,
    paneWidth = paneWidth,
    paneHeight = paneHeight,
    ratioWtoH = ratioWtoH,
)
