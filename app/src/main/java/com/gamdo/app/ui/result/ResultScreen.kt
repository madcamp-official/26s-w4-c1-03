package com.gamdo.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.data.preset.StylePreset
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.components.SecondaryPillButton
import com.gamdo.app.ui.components.moodBrush
import com.gamdo.app.ui.theme.Charcoal700
import com.gamdo.app.ui.theme.Charcoal900
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.OutlineDim
import com.gamdo.app.ui.theme.Sage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §4-2 result screen — **host only**.
 *
 * Owns the tab structure, the photo area, the save/share/retake bar, and the two
 * slots other agents fill:
 *
 *  - [generativeSlot] — reference-net-agent's `ui/result/GenerativeRestorePanel.kt`,
 *    rendered as the body of the 생성 복구 tab. Coordinate through [generativeState]:
 *    the tab is hidden entirely until `enabled` is set (R6), and the host — not the
 *    panel — renders the fallback line when `reportFallbackToBasic()` is called (R5).
 *  - [feedbackSlot] — onboarding-polish-agent's `ui/result/FeedbackSheet.kt`,
 *    rendered over the screen once [feedbackState] is shown, which the host does
 *    after a successful save (§6-3).
 *
 * Neither slot file may be created or edited from this vertical.
 *
 * Wave 0 scope: seams only. The local edit pipeline (`edit/LocalEditor.kt`) is not
 * wired yet, so the 기본/스타일 tabs show the untouched photo with an explicit
 * "not corrected yet" line. AGENTS.md §7-6 forbids passing an unprocessed image off
 * as a corrected one, so this stays until the pipeline lands.
 *
 * Cut, do not reinstate: the style-strength slider (부록 A cut-line 2).
 */
@Composable
fun ResultScreen(
    container: AppContainer,
    captureId: String,
    onBack: () -> Unit,
    onRetake: () -> Unit = onBack,
    onShare: (() -> Unit)? = null,
    generativeState: GenerativeTabState = rememberGenerativeTabState(),
    feedbackState: FeedbackSheetState = rememberFeedbackSheetState(),
    generativeSlot: @Composable () -> Unit = {},
    feedbackSlot: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    val capture by produceState<Captures?>(initialValue = null, captureId, container) {
        value = container.database.capturesDao().get(captureId)
    }
    val presets by produceState(initialValue = emptyList<StylePreset>(), container) {
        value = withContext(Dispatchers.IO) {
            runCatching { container.presetRepository.loadBundledPresets() }.getOrDefault(emptyList())
        }
    }

    var selectedTab by remember { mutableStateOf(ResultTab.BASIC) }
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val tabs = remember(generativeState.enabled) {
        ResultTab.entries.filter { it != ResultTab.GENERATIVE || generativeState.enabled }
    }
    // R6: if generation is switched off while its tab is open, fall back rather than
    // leaving the user on a tab that no longer exists. Derived, not assigned, so the
    // composition stays side-effect free.
    val activeTab = if (selectedTab in tabs) selectedTab else ResultTab.BASIC

    Box(modifier = Modifier.fillMaxSize().background(Charcoal900)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "‹",
                    color = OnDarkMedium,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Text("보정", color = OnDarkHigh, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "완료",
                    color = Sage,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onBack),
                )
            }

            ResultTabRow(
                tabs = tabs,
                selected = activeTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(moodBrush(0)),
            ) {
                // R5: on fallback the host reclaims the photo area and shows the
                // basic-correction result itself. The generative panel is never asked
                // to render someone else's result, and the slot is simply not called.
                if (activeTab == ResultTab.GENERATIVE && !generativeState.fallbackToBasic) {
                    generativeSlot()
                } else {
                    CapturePhoto(capture)
                }
            }

            // R5: the only thing the user is ever told about a generation failure.
            if (generativeState.fallbackToBasic) {
                Text(
                    text = "자연스러운 보정만 적용했어요",
                    color = OnDarkMedium,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            when (activeTab) {
                ResultTab.BASIC -> PendingPipelineNote()
                ResultTab.STYLE -> {
                    StyleStrip(
                        presets = presets,
                        selectedPresetId = selectedPresetId,
                        onSelect = { selectedPresetId = it },
                    )
                    PendingPipelineNote()
                }
                else -> Unit
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryPillButton(
                    text = if (saved) "저장됨" else "저장",
                    enabled = capture != null && !saving && !saved,
                    onClick = {
                        scope.launch {
                            saving = true
                            // §4-2: saving records saved_to_gallery = 1. Once the
                            // pipeline lands this becomes saveEditedResult(), which
                            // writes a new file and the capture_edit_stack rows.
                            runCatching { container.captureRepository.markSavedToGallery(captureId) }
                            saving = false
                            saved = true
                            feedbackState.show(captureId)
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (onShare != null) {
                            SecondaryPillButton(text = "공유", onClick = onShare)
                        } else {
                            // No FileProvider is declared yet, so a share intent would
                            // throw FileUriExposedException. Shown inert rather than
                            // wired to something that crashes.
                            DisabledPill(text = "공유")
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryPillButton(text = "다시 찍기", onClick = onRetake)
                    }
                }
            }
        }

        if (feedbackState.visible) {
            feedbackSlot()
        }
    }
}

@Composable
private fun CapturePhoto(capture: Captures?) {
    if (capture == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("사진을 불러오는 중이에요", color = OnDarkMuted, fontSize = 12.sp)
        }
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = File(capture.filePath),
            contentDescription = "선택한 사진",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .padding(12.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Sage)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text("내 감도", color = OnSage, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Honest placeholder while the edit pipeline is unwired. Everyday language, no
 * numbers, no jargon (R7-1).
 */
@Composable
private fun PendingPipelineNote() {
    Text(
        text = "아직 보정 전이에요",
        color = OnDarkMuted,
        fontSize = 11.5.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** Real preset names from the bundle; the chosen id is what the pipeline will consume. */
@Composable
private fun StyleStrip(
    presets: List<StylePreset>,
    selectedPresetId: String?,
    onSelect: (String) -> Unit,
) {
    if (presets.isEmpty()) return
    Row(
        modifier = Modifier
            .padding(start = 20.dp, top = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            val isSelected = preset.id == selectedPresetId
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) Sage else Charcoal700)
                    .clickable { onSelect(preset.id) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = preset.displayName,
                    color = if (isSelected) OnSage else OnDarkMedium,
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Box(Modifier.width(12.dp))
    }
}

/** Outlined pill that reads as unavailable — same tokens as [SecondaryPillButton]. */
@Composable
private fun DisabledPill(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(27.dp))
            .border(1.5.dp, OutlineDim, RoundedCornerShape(27.dp))
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = OnDarkMuted, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
