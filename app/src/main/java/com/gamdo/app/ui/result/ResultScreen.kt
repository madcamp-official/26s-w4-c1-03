package com.gamdo.app.ui.result

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.data.preset.StylePreset
import com.gamdo.app.edit.ResultEditController
import com.gamdo.app.edit.rememberResultEditController
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.components.SecondaryPillButton
import com.gamdo.app.ui.theme.Charcoal700
import com.gamdo.app.ui.theme.Charcoal900
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.Sage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §4-2 result screen — **host only**.
 *
 * Owns the tab structure, the comparison area, the save/share/retake bar, and the
 * two slots other agents fill:
 *
 *  - [generativeSlot] — reference-net-agent's `ui/result/GenerativeRestorePanel.kt`,
 *    the body of the 생성 복구 tab. Coordinate through [generativeState]: the tab is
 *    not rendered at all until `enabled` is set (R6), and the **host**, not the
 *    panel, shows the fallback line when `reportFallbackToBasic()` is called (R5).
 *  - [feedbackSlot] — onboarding-polish-agent's `ui/result/FeedbackSheet.kt`, shown
 *    over the screen once [feedbackState] is visible, which the host does after a
 *    successful save (§6-3).
 *
 * Neither slot file may be created or edited from this vertical.
 *
 * The pixels come from `edit/ResultEditController.kt`, which owns the pipeline, the
 * bitmap lifetimes and the resolution budget. This file decides what is on screen and
 * nothing else.
 *
 * Cut, do not reinstate: the style-strength slider (부록 A cut-line 2, lead decision).
 */
@Composable
fun ResultScreen(
    container: AppContainer,
    captureId: String,
    onBack: () -> Unit,
    onRetake: () -> Unit = onBack,
    generativeState: GenerativeTabState = rememberGenerativeTabState(),
    feedbackState: FeedbackSheetState = rememberFeedbackSheetState(),
    generativeSlot: @Composable () -> Unit = {},
    feedbackSlot: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = rememberResultEditController()

    val capture by produceState<Captures?>(initialValue = null, captureId, container) {
        value = container.database.capturesDao().get(captureId)
    }
    val presets by produceState(initialValue = emptyList<StylePreset>(), container) {
        value = withContext(Dispatchers.IO) {
            runCatching { container.presetRepository.loadBundledPresets() }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(capture?.id) {
        val row = capture ?: return@LaunchedEffect
        controller.load(File(row.filePath), row.conditionsJson)
    }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    var selectedTab by remember { mutableStateOf(ResultTab.BASIC) }
    var busy by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val tabs = remember(generativeState.enabled) {
        ResultTab.entries.filter { it != ResultTab.GENERATIVE || generativeState.enabled }
    }
    // R6: if generation is switched off while its tab is open, fall back rather than
    // leaving the user on a tab that no longer exists. Derived, not assigned, so the
    // composition stays side-effect free.
    val activeTab = if (selectedTab in tabs) selectedTab else ResultTab.BASIC
    val styleSelected = activeTab == ResultTab.STYLE && controller.styled != null
    val ready = controller.phase == ResultEditController.Phase.READY

    /**
     * Renders at full resolution and writes a new file (D8-6), unless the current
     * selection has already been saved. Returns the saved path, or null on failure —
     * the caller must not claim a save that did not happen (AGENTS.md §7-6).
     */
    val ensureSaved: suspend () -> String? = ensure@{
        savedPath?.let { return@ensure it }
        val result = controller.save(
            repository = container.captureRepository,
            captureId = captureId,
            applyStyle = styleSelected,
        )
        savedPath = result?.filePath
        result?.filePath
    }

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
                onSelect = {
                    selectedTab = it
                    // A different tab is a different picture; the file already written
                    // is no longer what [공유] should hand out.
                    savedPath = null
                    notice = null
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                ComparisonArea(
                    controller = controller,
                    activeTab = activeTab,
                    generativeState = generativeState,
                    generativeSlot = generativeSlot,
                )
            }

            // R5: the only thing the user is ever told about a generation failure.
            if (generativeState.fallbackToBasic) {
                StatusLine("자연스러운 보정만 적용했어요")
            }
            notice?.let { StatusLine(it) }

            if (activeTab == ResultTab.STYLE) {
                StyleStrip(
                    presets = presets,
                    selectedPresetId = controller.selectedPresetId,
                    enabled = ready && !controller.styling,
                    onSelect = { preset ->
                        savedPath = null
                        notice = null
                        scope.launch {
                            controller.applyPreset(
                                preset.takeIf { it.id != controller.selectedPresetId },
                            )
                        }
                    },
                )
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryPillButton(
                    text = if (savedPath != null) "저장됨" else "저장",
                    enabled = ready && !busy && savedPath == null,
                    onClick = {
                        scope.launch {
                            busy = true
                            notice = null
                            val path = ensureSaved()
                            busy = false
                            if (path != null) {
                                feedbackState.show(captureId)
                            } else {
                                notice = "저장하지 못했어요. 다시 시도해 주세요"
                            }
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryPillButton(
                            text = "공유",
                            onClick = {
                                scope.launch {
                                    busy = true
                                    notice = null
                                    // Share the corrected photo, which means it has to
                                    // exist as a file first.
                                    val path = ensureSaved()
                                    busy = false
                                    notice = if (path == null) {
                                        "공유할 사진을 준비하지 못했어요"
                                    } else if (!sharePhoto(context, File(path))) {
                                        "공유할 수 있는 앱이 없어요"
                                    } else {
                                        null
                                    }
                                }
                            },
                        )
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

/**
 * The photo area. 원본 shows one image; 기본 보정 and 스타일 보정 compare against the
 * original with the curtain; 생성 복구 hands the area to the slot unless R5 fallback
 * has reclaimed it.
 */
@Composable
private fun ComparisonArea(
    controller: ResultEditController,
    activeTab: ResultTab,
    generativeState: GenerativeTabState,
    generativeSlot: @Composable () -> Unit,
) {
    val original = controller.original

    if (activeTab == ResultTab.GENERATIVE && !generativeState.fallbackToBasic) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) { generativeSlot() }
        return
    }

    when {
        controller.phase == ResultEditController.Phase.FAILED ->
            CenteredNote("사진을 불러오지 못했어요")

        original == null -> CenteredNote("사진을 준비하고 있어요")

        activeTab == ResultTab.ORIGINAL -> Image(
            bitmap = original,
            contentDescription = "원본 사진",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
        )

        else -> {
            // 스타일 보정 falls back to the basic result until a preset is chosen, so
            // the tab is never empty and never shows an unedited photo as "corrected".
            val after = if (activeTab == ResultTab.STYLE) {
                controller.styled ?: controller.basic
            } else {
                controller.basic
            }
            BeforeAfterSlider(
                before = original,
                after = after,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = OnDarkMuted, fontSize = 12.sp)
    }
}

/** One-line status. Everyday language, no numbers, no jargon (R7-1). */
@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        color = OnDarkMedium,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** Real preset names from the bundle; tapping the selected one clears it. */
@Composable
private fun StyleStrip(
    presets: List<StylePreset>,
    selectedPresetId: String?,
    enabled: Boolean,
    onSelect: (StylePreset) -> Unit,
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
                    .clickable(enabled = enabled) { onSelect(preset) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = preset.displayName,
                    color = when {
                        isSelected -> OnSage
                        enabled -> OnDarkMedium
                        else -> OnDarkMuted
                    },
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Box(Modifier.width(12.dp))
    }
}

/**
 * Hands [file] to the OS share sheet (§6-3: "공유: OS 공유 시트").
 *
 * The `content://` URI comes from the FileProvider the lead registered in the
 * manifest — captures live in internal storage, so a `file://` URI would throw
 * `FileUriExposedException`. `context.packageName` is the applicationId at runtime,
 * which is what `${applicationId}.fileprovider` expands to.
 *
 * @return false when nothing on the device can receive the image.
 */
private fun sharePhoto(context: Context, file: File): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "사진 공유"))
    true
}.getOrDefault(false)
