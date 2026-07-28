package com.gamdo.app.ui.result

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.data.SavedEdit
import com.gamdo.app.edit.CaptureConditions
import com.gamdo.app.edit.EditPlan
import com.gamdo.app.edit.EditSourceLoader
import com.gamdo.app.edit.EditTool
import com.gamdo.app.edit.FilterEngine
import com.gamdo.app.edit.QuickFilterEditor
import com.gamdo.app.edit.LocalEditor
import com.gamdo.app.edit.LocalFilter
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.theme.Charcoal700
import com.gamdo.app.ui.theme.Charcoal900
import com.gamdo.app.ui.theme.Charcoal950
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.Sage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ResultScreen"

/**
 * The opened photo after §4-1 levelling + auto exposure, plus the plan that made
 * it so the save path can re-apply the identical correction at full resolution.
 *
 * [plan] is null when auto-correction could not run; [bitmap] is then the
 * untouched decode.
 */
private data class AutoCorrected(val bitmap: Bitmap, val plan: EditPlan?)

@Composable
fun ResultScreen(
    container: AppContainer,
    captureId: String,
    onBack: () -> Unit,
) {
    val capture by produceState<Captures?>(initialValue = null, captureId, container) {
        value = withContext(Dispatchers.IO) {
            container.database.capturesDao().get(captureId)
        }
    }
    // §4-1 기하·광학 자동 보정 (O-2): levelling rotation + auto exposure, applied
    // when the photo opens, with **no visible control**.
    //
    // Until now `captures.conditions_json` was written on every shutter and read by
    // nobody: the only production reader was `rememberResultEditController()`, which
    // had zero call sites, so the shutter-time tilt was recorded and discarded
    // (review_report #6). This is that reader.
    //
    // `plan` is kept in state and handed to the save path unchanged. Re-deriving it
    // there from a larger decode would risk preview and save disagreeing about the
    // crop; `EditPlan.withProcessingMaxSide` exists for exactly this and
    // `EditPlanTest` pins the property.
    val corrected by produceState<AutoCorrected?>(initialValue = null, capture?.filePath) {
        val captureValue = capture ?: return@produceState
        // Decode off the composition thread so entering the editor never blocks
        // the first frame, and decode *small*.
        //
        // The filter is a per-pixel pass and it re-runs on every filter tap and on
        // every frame of a slider drag. Decoding at full resolution meant each of
        // those re-rendered 2904x3630 = 10.5M pixels for a preview being displayed
        // at roughly 940 wide — about twelve times more work than the screen can
        // show, on the interaction path. Saving still uses the full file; see the
        // save button below.
        value = withContext(Dispatchers.Default) {
            val file = File(captureValue.filePath)
            val preview = EditSourceLoader.decode(file, PREVIEW_MAX_SIDE)
                ?: return@withContext null
            // Every failure below falls back to the untouched decode. An
            // auto-correction the user never asked for must never be the reason a
            // photo will not open.
            runCatching {
                val fullSize = EditSourceLoader.readSize(file) ?: (preview.width to preview.height)
                val conditions = CaptureConditions.parse(captureValue.conditionsJson)
                val editor = LocalEditor()
                val sample = editor.sample(
                    bitmap = preview,
                    tiltDeg = conditions.tiltDegOrZero,
                    subject = conditions.subject,
                    sourceWidth = fullSize.first,
                    sourceHeight = fullSize.second,
                )
                // preset = null → geometry + optical only. The style stage stays an
                // identity, because auto-applying a look the user did not pick is a
                // different feature and not the one O-2 approved.
                val plan = editor.plan(
                    sample = sample,
                    preset = null,
                    subject = conditions.subject,
                    requestedMaxSide = PREVIEW_MAX_SIDE,
                )
                AutoCorrected(editor.render(preview, plan).bitmap, plan)
            }.getOrElse {
                Log.w(TAG, "auto-correction failed for ${captureValue.id}; showing the original", it)
                AutoCorrected(preview, plan = null)
            }
        }
    }
    val source: Bitmap? = corrected?.bitmap
    var selectedFilter by remember { mutableStateOf(LocalFilter.ORIGINAL) }
    // Every style in presets.json now has a filter of its own, so this is a lookup
    // rather than a when-chain with a fallback. The chain listed four of the six
    // and sent `clean_social` and `casual_portrait` to a different style's look.
    val preferredFilter by produceState(LocalFilter.ORIGINAL, container) {
        value = LocalFilter.forPresetId(container.settingsRepository.getStylePresetId())
    }
    LaunchedEffect(preferredFilter) { selectedFilter = preferredFilter }
    // One luminance pass per photo, kept so seeding a filter does not re-measure.
    // It is only needed to cap a preset's exposure against this frame's headroom.
    val measure by produceState<FilterEngine.Measure?>(null, source) {
        val bitmap = source ?: return@produceState
        value = withContext(Dispatchers.Default) { QuickFilterEditor.measure(bitmap) }
    }
    var adjustments by remember { mutableStateOf(FilterEngine.Adjustments.NEUTRAL) }
    // Where the chosen filter puts every slider. Reset targets it, and the strip
    // marks controls that differ from it.
    var baseline by remember { mutableStateOf(FilterEngine.Adjustments.NEUTRAL) }
    var selectedTool by remember { mutableStateOf(EditTool.EXPOSURE) }

    // Choosing a filter *sets the sliders*, the way applying a preset does in any
    // editor — so what a slider reads is what the renderer uses, with no second
    // hidden contribution. Keyed on the measurement too because exposure is seeded
    // from it, and it arrives one frame after the bitmap.
    LaunchedEffect(selectedFilter, measure) {
        val m = measure ?: return@LaunchedEffect
        val seeded = FilterEngine.seedFrom(selectedFilter.filter, m)
        baseline = seeded
        adjustments = seeded
    }
    var saved by remember { mutableStateOf<SavedEdit?>(null) }
    // Saving re-renders the full-resolution file, which takes seconds. Without a
    // state for it the button looks inert and invites a second tap.
    var saving by remember { mutableStateOf(false) }
    // A save can fail — full disk, refused MediaStore insert — and the user has to
    // hear about it. 2f has no status line, so this stays empty except on failure.
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val edited by produceState<Bitmap?>(source, source, selectedFilter, adjustments) {
        value = source?.let {
            withContext(Dispatchers.Default) {
                QuickFilterEditor.apply(it, selectedFilter, adjustments)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Charcoal900)) {
        // 2f header: `‹` (18sp, OnDarkMedium) · `보정` (15sp bold) · `완료`
        // (13.5sp bold, Sage), space-between at 20dp / 14dp top.
        //
        // The one thing added to the drawing is that both ends are real 44dp touch
        // targets, reached with padding so the glyphs still land where the design
        // puts them. `완료` in particular was Sage + Bold with no click at all — the
        // design draws it as the way out of the screen, so it has to be one.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = OnDarkMedium, fontSize = 18.sp)
            }
            Text("보정", color = OnDarkHigh, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("완료", color = Sage, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            }
        }

        Box(
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(16.dp)).background(Charcoal950),
        ) {
            val displayBitmap = edited ?: source
            // Show the untouched source immediately while the full-resolution
            // local edit is still being computed. Waiting for `edited` here
            // made a valid gallery photo look like a placeholder on device.
            if (displayBitmap != null) {
                Image(
                    bitmap = displayBitmap.asImageBitmap(),
                    contentDescription = "보정 결과",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                )
            }
            if (displayBitmap == null) {
                Text("사진을 불러오는 중이에요", color = OnDarkMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            }

            Box(
                modifier = Modifier.padding(12.dp).clip(RoundedCornerShape(5.dp))
                    .background(Sage).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(selectedFilter.label, color = OnSage, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(
            modifier = Modifier.padding(start = 20.dp, top = 14.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocalFilter.entries.forEach { filter ->
                FilterThumb(
                    label = filter.label,
                    asset = filterAsset(filter),
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                )
            }
            Box(Modifier.width(12.dp))
        }

        AdjustmentPanel(
            adjustments = adjustments,
            baseline = baseline,
            selected = selectedTool,
            onSelect = { selectedTool = it },
            onChange = { adjustments = it },
            modifier = Modifier.padding(top = 10.dp),
        )

        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 18.dp)) {
            saveError?.let { status ->
                Text(
                    text = status,
                    color = OnDarkMedium,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // 2f's bottom is one button and nothing else. `[저장] [공유] [다시 찍기]`
            // comes from the plan's §4-2 checklist, which predates this design — the
            // owner never drew 공유 or 다시 찍기, so they are not built.
            PrimaryPillButton(
                text = when {
                    // Three states, not two. A save whose MediaStore insert was
                    // refused still wrote the app's own copy, and telling the user
                    // it is "갤러리에 저장됨" when it is not there is the kind of
                    // claim AGENTS.md §7-6 rules out.
                    saved?.savedToGallery == true -> "갤러리에 저장됨"
                    saved != null -> "앱에 저장됨"
                    saving -> "저장 중…"
                    else -> "저장"
                },
                onClick = {
                    if (saving) return@PrimaryPillButton
                    val captureValue = capture ?: return@PrimaryPillButton
                    saving = true
                    scope.launch {
                        try {
                            // Re-render at full resolution rather than saving the
                            // preview: `edited` is a downsampled bitmap now, and
                            // writing it out would quietly ship a low-resolution
                            // photo to the gallery.
                            val plan = corrected?.plan
                            val result = withContext(Dispatchers.Default) {
                                EditSourceLoader.decode(File(captureValue.filePath), SAVE_MAX_SIDE)
                                    ?.let { full ->
                                        // The **same** plan the preview used, only at
                                        // save resolution. Re-planning here from a
                                        // larger decode would let the saved crop drift
                                        // from the one the user approved.
                                        val levelled = plan?.let { p ->
                                            runCatching {
                                                LocalEditor().render(full, p.withProcessingMaxSide(SAVE_MAX_SIDE)).bitmap
                                            }.getOrElse {
                                                Log.w(TAG, "save-time auto-correction failed; saving uncorrected", it)
                                                full
                                            }
                                        } ?: full
                                        QuickFilterEditor.apply(levelled, selectedFilter, adjustments)
                                    }
                            } ?: edited ?: source ?: return@launch
                            val written = container.captureRepository.saveEditedCapture(
                                captureId = captureValue.id,
                                bitmap = result,
                                // §4-1 비파괴: the record has to hold every control,
                                // not the three the old panel happened to show, or a
                                // saved edit cannot be reopened as what it was.
                                paramsJson = "{\"filter\":\"${selectedFilter.name}\"," +
                                    "\"adjustments\":${EditTool.toJson(adjustments)}}",
                            )
                            saved = written
                            if (!written.savedToGallery) {
                                saveError = "갤러리에 못 넣었어요. 앱에는 저장했습니다"
                            }
                        } catch (t: Throwable) {
                            // Without this the exception left `scope`'s Job and took
                            // the process with it: a full disk or a revoked
                            // MediaStore insert crashed the app on the save tap,
                            // while the camera's shutter one screen away had caught
                            // the same class of failure since Day 1. §6-1's "크래시
                            // 0건" is the standard, and silence is not the fix
                            // either — the button snapping back from "저장 중…" with
                            // nothing saved and nothing said is what the user saw.
                            Log.e(TAG, "save failed", t)
                            saveError = "저장하지 못했어요. 저장 공간을 확인해 주세요"
                        } finally {
                            saving = false
                        }
                    }
                },
            )
        }
    }
}

/** Thumbnail asset, which is the style's own preset image — the ids match. */
private fun filterAsset(filter: LocalFilter): String = when (filter) {
    LocalFilter.ORIGINAL -> "presets/clean_social.jpg"
    else -> "presets/${filter.filter.id}.jpg"
}

@Composable
private fun FilterThumb(label: String, asset: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(11.dp)).background(Charcoal700)
                .then(if (selected) Modifier.border(2.dp, Sage, RoundedCornerShape(11.dp)) else Modifier),
        ) {
            AsyncImage(
                model = "file:///android_asset/$asset",
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(label, color = if (selected) Sage else OnDarkMedium, fontSize = 10.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
    }
}


/**
 * Longest edge of the bitmap the editor previews and re-filters interactively.
 *
 * 1440 is above the 1080 the panel can show, so the preview is not the thing that
 * limits quality on screen, and it is roughly 1/7 the pixels of a capture — which
 * is what the filter re-render costs on every tap and every slider frame.
 */
private const val PREVIEW_MAX_SIDE = 1440

/**
 * Longest edge the save pass renders at. §4-1's target is 4000px; captures come
 * off this camera at 3630, so in practice this is "the file, unchanged".
 */
private const val SAVE_MAX_SIDE = 4000
