package com.gamdo.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamdo.app.edit.EditTool
import com.gamdo.app.edit.FilterEngine
import com.gamdo.app.ui.theme.Ink700
import com.gamdo.app.ui.theme.Ink900
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextMid
import com.gamdo.app.ui.theme.TextLow
import com.gamdo.app.ui.theme.Outline
import com.gamdo.app.ui.theme.Amber
import kotlin.math.abs

/**
 * The manual adjustment surface, per **2f** of `감도 화면 디자인.dc.html`: a row of
 * value dials, and one tick ruler you push sideways to move the held control.
 *
 * ## Why dials rather than icons with a value line
 *
 * The design puts the number *inside* the control it belongs to. That removes the
 * separate label/value row entirely, and with it the question of which control a
 * readout is describing — a real risk at thirteen tools, where the strip scrolls
 * independently of anything above it. Each dial then carries two facts at once,
 * and they are deliberately given different channels:
 *
 *  - **ring and number** — whether this control has a value (sage) or sits at zero
 *  - **label weight** — whether this is the control you are currently holding
 *
 * Collapsing those into one signal is what made an earlier version unreadable:
 * after choosing a filter almost every control is non-zero, so "has a value" and
 * "is selected" have to look different or the strip says nothing.
 *
 * ## Why a ruler rather than a slider
 *
 * A slider encodes the value by thumb *position*, so at thirteen controls the thumb
 * jumps on every tool switch and the eye has to re-find it. The ruler's indicator
 * never moves — the scale slides under it — so switching tools changes the numbers
 * and nothing else. It also gives single-step control anywhere in the range, which
 * a 320dp slider spanning 200 units cannot resolve.
 */
@Composable
fun AdjustmentPanel(
    adjustments: FilterEngine.Adjustments,
    baseline: FilterEngine.Adjustments,
    selected: EditTool,
    onSelect: (EditTool) -> Unit,
    onChange: (FilterEngine.Adjustments) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Keep the held tool on screen. Selection can move without a tap — choosing a
    // filter reseeds everything — and a highlighted control scrolled out of view
    // reads as no selection at all.
    LaunchedEffect(selected) {
        listState.animateScrollToItem(EditTool.entries.indexOf(selected))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(EditTool.entries) { tool ->
                ToolDial(
                    tool = tool,
                    value = tool.get(adjustments),
                    selected = tool == selected,
                    onClick = { onSelect(tool) },
                )
            }
        }

        TickRuler(
            value = selected.get(adjustments),
            range = selected.range,
            onValueChange = { onChange(selected.set(adjustments, it)) },
            // Double-tap goes back to what the filter set. Reset lives on the ruler
            // rather than on a separate button because the ruler is the surface the
            // finger is already on, and the design leaves no room for a third row.
            onReset = { onChange(selected.set(adjustments, selected.get(baseline))) },
            modifier = Modifier.padding(top = 16.dp),
        )

        Text(
            text = "좌우로 밀어서 ${selected.label} 조절",
            color = TextLow,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/**
 * One adjustment as a filled arc with its value in the middle.
 *
 * The arc sweeps clockwise from 12 o'clock across the control's whole range, so a
 * bipolar control at zero reads as a half-filled dial and a one-sided control at
 * zero reads as empty — which is what each of those means.
 */
@Composable
private fun ToolDial(
    tool: EditTool,
    value: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val active = value != 0
    val fraction = tool.fraction(value)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(46.dp)) {
                drawArc(color = Ink700, startAngle = -90f, sweepAngle = 360f, useCenter = true)
                drawArc(
                    color = if (active) Amber else Outline,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = true,
                )
            }
            Box(
                modifier = Modifier.size(38.dp).background(Ink900, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatValue(tool, value),
                    color = if (active) Amber else TextLow,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
                )
            }
        }
        Text(
            text = tool.label,
            color = if (selected) TextHi else TextMid,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * A ruler that slides under a fixed centre indicator.
 *
 * Ticks are placed in the *value* domain and projected onto the screen, so the
 * marks stay locked to the numbers rather than to the widget. Contrast falls off
 * toward the edges, which pulls the eye to the value under the indicator instead
 * of to the ends of the range.
 */
@Composable
private fun TickRuler(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxPerUnit = with(density) { 2.4.dp.toPx() }
    // rememberUpdatedState rather than pointerInput keys: re-keying on every value
    // restarts the gesture mid-drag and drops the rest of the stroke — the same
    // failure that ate the first preview tap after an aspect change.
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val reset by rememberUpdatedState(onReset)
    // Sub-unit motion has to accumulate, or a slow drag is swallowed by rounding
    // and the ruler feels stuck.
    var residual by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(range) {
                detectHorizontalDragGestures(onDragStart = { residual = 0f }) { evt, dragAmount ->
                    evt.consume()
                    residual -= dragAmount / pxPerUnit
                    val steps = residual.toInt()
                    if (steps != 0) {
                        residual -= steps
                        change((currentValue + steps).coerceIn(range.first, range.last))
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { reset() })
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
            val centreX = size.width / 2f
            val midY = size.height / 2f
            val minorHalf = 8.dp.toPx()
            val majorHalf = 11.dp.toPx()
            val tickWidth = 2.dp.toPx()

            var tick = range.first
            while (tick <= range.last) {
                val x = centreX + (tick - currentValue) * pxPerUnit
                if (x >= -tickWidth && x <= size.width + tickWidth) {
                    val half = if (tick % 25 == 0) majorHalf else minorHalf
                    val near = 1f - (abs(x - centreX) / (size.width / 2f)).coerceIn(0f, 1f)
                    drawLine(
                        color = if (near > 0.45f) TextLow else Outline,
                        start = Offset(x, midY - half),
                        end = Offset(x, midY + half),
                        strokeWidth = tickWidth,
                    )
                }
                tick += 5
            }

            val indicatorHalf = 18.dp.toPx()
            val indicatorWidth = 3.dp.toPx()
            // The design asks for a box-shadow glow, which a Canvas line has no
            // equivalent for; a wider faint pass underneath reads the same.
            drawLine(
                color = Amber.copy(alpha = 0.30f),
                start = Offset(centreX, midY - indicatorHalf),
                end = Offset(centreX, midY + indicatorHalf),
                strokeWidth = indicatorWidth * 3f,
            )
            drawLine(
                color = Amber,
                start = Offset(centreX, midY - indicatorHalf),
                end = Offset(centreX, midY + indicatorHalf),
                strokeWidth = indicatorWidth,
            )
        }
    }
}

/**
 * A signed readout for the bipolar controls and a plain one for the rest.
 *
 * `+0` on a control that only goes up would be noise, and `32` on 대비 would hide
 * whether the user added or removed it.
 */
private fun formatValue(tool: EditTool, value: Int): String =
    if (tool.range.first < 0) "%+d".format(value) else "$value"
