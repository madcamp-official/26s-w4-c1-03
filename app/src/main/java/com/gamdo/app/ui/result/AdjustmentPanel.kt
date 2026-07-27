package com.gamdo.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WbIncandescent
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamdo.app.edit.EditTool
import com.gamdo.app.edit.FilterEngine
import com.gamdo.app.ui.theme.Charcoal700
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.Sage
import kotlin.math.roundToInt

/**
 * The manual adjustment surface: one slider for the selected tool, and a
 * horizontally scrolling strip of tools under it.
 *
 * ## Why a strip and not a list
 *
 * The version this replaces stacked one labelled slider per adjustment. That reads
 * fine at three rows and collapses at fourteen — the photo would be pushed off the
 * screen by its own controls, on a screen whose entire premise (§3-2, D11) is that
 * the picture is the subject. One slider at a time keeps the panel a fixed height
 * no matter how many adjustments exist, which is also why adding a fifteenth costs
 * nothing here.
 *
 * ## What the strip has to communicate
 *
 * Two things at once, and they are different: **which tool you are holding**
 * (filled sage) and **which tools are doing something** (a dot). Without the
 * second, a user who set 대비 four tools ago has no way to find it again except by
 * visiting all fourteen, and no way to know whether the photo in front of them is
 * the filter's doing or their own.
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
    val value = selected.get(adjustments)
    val edited = selected.isEdited(adjustments, baseline)
    val listState = rememberLazyListState()
    // Keep the held tool on screen. Selection can move without a tap — resetting
    // everything, or arriving from a filter change — and a highlighted control
    // scrolled out of view reads as no selection at all.
    LaunchedEffect(selected) {
        listState.animateScrollToItem(EditTool.entries.indexOf(selected))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected.label,
                color = OnDarkMedium,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Box(Modifier.width(8.dp))
            Text(
                text = formatValue(selected, value),
                color = if (value != 0) Sage else OnDarkMuted,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
            )
            Box(Modifier.weight(1f))
            // Reset goes back to the filter's value, not to zero. Zero would be
            // "remove the filter from this one control", which is a different and
            // much less useful thing to want than "undo what I just did".
            if (edited) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "${selected.label}을 필터 값으로 되돌리기",
                    tint = OnDarkMedium,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable {
                            onChange(selected.set(adjustments, selected.get(baseline)))
                        },
                )
            }
        }

        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(selected.set(adjustments, it.roundToInt())) },
            valueRange = selected.range.first.toFloat()..selected.range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Sage,
                activeTrackColor = Sage,
                inactiveTrackColor = Charcoal700,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            itemsIndexed(EditTool.entries) { _, tool ->
                ToolButton(
                    tool = tool,
                    selected = tool == selected,
                    set = tool.get(adjustments) != 0,
                    edited = tool.isEdited(adjustments, baseline),
                    onClick = { onSelect(tool) },
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    tool: EditTool,
    selected: Boolean,
    set: Boolean,
    edited: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(if (selected) Sage else Charcoal700, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = iconFor(tool),
                contentDescription = tool.label,
                tint = if (selected) OnSage else if (set) Sage else OnDarkMedium,
                modifier = Modifier.size(20.dp),
            )
        }
        // The dot means "you moved this", not "this is non-zero" — after picking a
        // filter almost every control is non-zero, and a strip of thirteen dots
        // says nothing. Non-zero is carried by the icon tint instead, so the two
        // facts stay separable: what the filter set, and what you changed.
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(4.dp)
                .background(if (edited) Sage else Color.Transparent, CircleShape),
        )
        Text(
            text = tool.label,
            color = if (selected) Sage else OnDarkMuted,
            fontSize = 9.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 2.dp),
        )
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

/**
 * Icons are chosen here rather than on [EditTool] so the domain enum stays free of
 * Compose — it is also read by the JSON writer and by tests.
 */
private fun iconFor(tool: EditTool): ImageVector = when (tool) {
    EditTool.EXPOSURE -> Icons.Outlined.Exposure
    EditTool.HIGHLIGHTS -> Icons.Outlined.WbSunny
    EditTool.SHADOWS -> Icons.Outlined.Bedtime
    EditTool.CONTRAST -> Icons.Outlined.Contrast
    // An empty ring and a solid disc: whites and blacks are the two ends of the
    // same control, and the pair reads as that. Brightness3/Brightness7 were both
    // crescents on this device and 검정 계열 was indistinguishable from 어두운 영역.
    EditTool.WHITES -> Icons.Outlined.Circle
    EditTool.BLACKS -> Icons.Filled.Circle
    EditTool.SATURATION -> Icons.Outlined.Opacity
    EditTool.VIBRANCE -> Icons.Outlined.AutoAwesome
    EditTool.WARMTH -> Icons.Outlined.WbIncandescent
    EditTool.TINT -> Icons.Outlined.Palette
    EditTool.FADE -> Icons.Outlined.Gradient
    EditTool.GRAIN -> Icons.Outlined.Grain
    EditTool.VIGNETTE -> Icons.Outlined.Vignette
}
