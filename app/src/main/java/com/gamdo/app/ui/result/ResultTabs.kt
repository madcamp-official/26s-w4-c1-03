package com.gamdo.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamdo.app.ui.theme.Charcoal700
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.Sage

/**
 * §4-2 result-screen tab structure and the two slot contracts other agents plug
 * into. `ResultScreen.kt` is the host; this file holds everything the host shares
 * with its collaborators, so neither side has to reach into the other.
 *
 * Note on §4-2: the style-strength slider listed in the plan is **cut** (lead
 * decision, 부록 A cut-line 2). Do not add one.
 */

/**
 * The four result views. Labels are everyday language per R7-1 — no 채도/대비/EV,
 * no numbers, no `ProblemCode` text.
 */
enum class ResultTab(val label: String) {
    ORIGINAL("원본"),
    BASIC("기본 보정"),
    STYLE("스타일 보정"),
    GENERATIVE("생성 복구"),
}

/**
 * Shared state for the 생성 복구 tab — the seam between the host and
 * reference-net-agent's `ui/result/GenerativeRestorePanel.kt`.
 *
 * The panel is passed to the host as an opaque `@Composable () -> Unit`, so this
 * object carries the two signals that must cross the boundary:
 *
 *  - [enabled] — R6. Defaults to **false**, meaning the tab is not rendered at all.
 *    Generation must prove itself available before the tab appears; a hidden tab is
 *    the required behaviour when the feature is unstable, not a degraded one.
 *  - [fallbackToBasic] — R5. The panel sets this when a job fails or validation
 *    rejects the result. The host then shows the fixed reassurance line and keeps
 *    the user on the basic-correction result. The panel must **never** surface a
 *    server `fail_reason` or exception text itself.
 */
@Stable
class GenerativeTabState {
    var enabled by mutableStateOf(false)
    var fallbackToBasic by mutableStateOf(false)
        private set

    /** Call when generation cannot be shown. Reason stays internal (R5). */
    fun reportFallbackToBasic() {
        fallbackToBasic = true
    }

    /** Call when a fresh attempt starts, to clear a previous notice. */
    fun clearFallback() {
        fallbackToBasic = false
    }
}

@Composable
fun rememberGenerativeTabState(): GenerativeTabState = remember { GenerativeTabState() }

/**
 * Shared state for the post-save feedback sheet — the seam between the host and
 * onboarding-polish-agent's `ui/result/FeedbackSheet.kt` (§6-3).
 *
 * The host owns *when* the sheet appears: it calls [show] after a successful save.
 * The sheet owns what it looks like and calls [dismiss] when done. [captureId] is
 * the row the feedback should be attached to.
 */
@Stable
class FeedbackSheetState {
    var visible by mutableStateOf(false)
        private set
    var captureId by mutableStateOf<String?>(null)
        private set

    fun show(captureId: String) {
        this.captureId = captureId
        visible = true
    }

    fun dismiss() {
        visible = false
    }
}

@Composable
fun rememberFeedbackSheetState(): FeedbackSheetState = remember { FeedbackSheetState() }

/**
 * Segmented tab row. Only [tabs] are drawn, so hiding 생성 복구 (R6) is a matter of
 * leaving it out of the list rather than disabling it visually.
 */
@Composable
fun ResultTabRow(
    tabs: List<ResultTab>,
    selected: ResultTab,
    onSelect: (ResultTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Charcoal700)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Sage else Charcoal700)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    color = if (isSelected) OnSage else OnDarkMedium,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
