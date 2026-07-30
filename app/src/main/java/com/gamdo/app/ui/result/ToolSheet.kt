package com.gamdo.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gamdo.app.ui.theme.Amber
import com.gamdo.app.ui.theme.GamdoType
import com.gamdo.app.ui.theme.Ink900
import com.gamdo.app.ui.theme.OnAmber
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextLow

/**
 * How long the tool sheet takes to come up or go down. **260ms**, from the design.
 *
 * One constant for both directions, deliberately: a sheet that opens and closes at
 * different speeds reads as two different objects.
 */
const val TOOL_SHEET_MILLIS = 260

/**
 * Which of the two bottom words is open — 시안 07's `필터` / `조절`.
 *
 * The label lives on the entry rather than in the composable that draws it, so the row
 * is a loop over [entries] and adding a third surface cannot mean adding a third `if`.
 */
enum class ToolTab(val label: String) {
    FILTER("필터"),
    ADJUST("조절"),
}

/**
 * 시안 07's bottom bar: two text buttons, centred, 64dp apart, in an 80dp band.
 *
 * The active one is marked with a 16×2 amber underline and nothing else — no fill, no
 * pill, no background. That is what keeps the screen inside the redesign's "at most one
 * filled amber surface", which 시안 07 spends on the 저장 pill in the header.
 *
 * @param selected null when the sheet is closed, in which case neither word is underlined.
 */
@Composable
fun ToolTabBar(
    selected: ToolTab?,
    onSelect: (ToolTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(80.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolTab.entries.forEachIndexed { index, tab ->
            if (index > 0) Box(Modifier.width(64.dp))
            val active = tab == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    // 44dp of height to press, reached with padding so the words still sit
                    // on the design's centre line.
                    .heightIn(min = 44.dp)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = tab.label,
                    color = if (active) TextHi else TextLow,
                    style = GamdoType.Cta,
                )
                Box(
                    modifier = Modifier
                        .size(width = 16.dp, height = 2.dp)
                        // Always present, only sometimes visible: drawing it
                        // conditionally moved the label 2dp on every tap.
                        .background(if (active) Amber else Color.Transparent),
                )
            }
        }
    }
}

/**
 * 시안 08's sheet: a handle, then whatever tool surface is open.
 *
 * It is an ordinary column in the screen's layout, **not** a `ModalBottomSheet` and not an
 * overlay — the design shrinks the photo to make room instead of covering it, so the
 * sheet has to take part in the same vertical layout as the photo. That also means it
 * needs no scrim: a scrim exists to say "what is behind this is out of reach", and here
 * nothing is behind it.
 *
 * @param onDismiss the design's "아래로 내리면 닫힘". Bound to the handle strip only — see
 *   below for why it cannot be bound to the whole sheet.
 */
@Composable
fun ToolSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // Far enough that a hesitant thumb resting on the handle does not close the sheet,
    // short enough to feel like a flick rather than a haul.
    val dismissThresholdPx = with(density) { 28.dp.toPx() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(Ink900)
            .padding(bottom = 10.dp),
    ) {
        // The drag lives on the handle strip and **not** on the sheet, because the sheet
        // contains the tick ruler, whose whole interaction is a drag. A vertical detector
        // wrapped around both competes with it for the gesture: a ruler push that is a few
        // degrees off horizontal would be claimed by the sheet and close it mid-adjustment.
        // The handle is the affordance the design draws for this, so it is the only thing
        // that listens.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(Unit) {
                    var travel = 0f
                    detectVerticalDragGestures(
                        onDragStart = { travel = 0f },
                        onDragEnd = { if (travel > dismissThresholdPx) onDismiss() },
                    ) { _, delta -> travel += delta }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
            )
        }
        content()
    }
}

/**
 * 시안 07's header action: `저장` as a 34dp amber pill — this screen's one filled amber
 * surface.
 *
 * Replaces a full-width [com.gamdo.app.ui.components.PrimaryPillButton] at the bottom of
 * the screen. The states it reports are the caller's and unchanged; what moved is only
 * where it sits, and the room that freed up is what lets the photo be nearly the whole
 * screen.
 */
@Composable
fun SavePill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // The pill the design draws is 34dp tall; the press target around it is 44dp,
            // added as padding so the pill itself stays the size it was drawn.
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(Amber)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, color = OnAmber, style = GamdoType.Cta)
        }
    }
}
