package com.gamdo.app.ui.result

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.detect.ImageMetricsExtractor
import com.gamdo.app.detect.Problem
import com.gamdo.app.detect.ProblemCode
import com.gamdo.app.detect.ProblemDiagnoser
import com.gamdo.app.detect.ProblemSeverity
import com.gamdo.app.edit.EditSourceLoader
import com.gamdo.app.edit.EditTool
import com.gamdo.app.edit.FilterEngine
import com.gamdo.app.edit.QuickFilterEditor
import com.gamdo.app.edit.LocalFilter
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.theme.Charcoal600
import com.gamdo.app.ui.theme.Charcoal700
import com.gamdo.app.ui.theme.Charcoal900
import com.gamdo.app.ui.theme.Charcoal950
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.OutlineDim
import com.gamdo.app.ui.theme.Sage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.gamdo.app.core.Ulid

private data class NormalizedMask(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}

private const val TAG = "ResultScreen"

private data class GpuCandidate(
    val bitmap: Bitmap,
    val seed: Int?,
    val validation: String?,
)

/**
 * Drag rectangle → mask normalized against the **image**, not its container.
 *
 * [rect] is where `ContentScale.Fit` actually put the bitmap. Dividing by the
 * container size instead — which this did — inflates the mask by
 * container/image on each axis, so the server erases a region offset from the
 * one the user drew. It never showed on screen because the overlay Canvas
 * divided by the same wrong number and so disagreed consistently.
 */
private fun maskFromOffsets(start: Offset, end: Offset, rect: FitRect?): NormalizedMask? {
    if (rect == null || rect.width <= 0f || rect.height <= 0f) return null
    val left = rect.normalizeX(minOf(start.x, end.x))
    val top = rect.normalizeY(minOf(start.y, end.y))
    val right = rect.normalizeX(maxOf(start.x, end.x))
    val bottom = rect.normalizeY(maxOf(start.y, end.y))
    return NormalizedMask(left, top, right, bottom)
}

private fun minimumMaskAt(point: Offset, rect: FitRect?): NormalizedMask? {
    if (rect == null || rect.width <= 0f || rect.height <= 0f) return null
    val half = 0.1f
    val centerX = rect.normalizeX(point.x).coerceIn(half, 1f - half)
    val centerY = rect.normalizeY(point.y).coerceIn(half, 1f - half)
    return NormalizedMask(centerX - half, centerY - half, centerX + half, centerY + half)
}

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
    val source by produceState<Bitmap?>(initialValue = null, capture?.filePath) {
        val path = capture?.filePath ?: return@produceState
        // Decode off the composition thread so entering the editor never blocks
        // the first frame, and decode *small*.
        //
        // The filter is a per-pixel pass and it re-runs on every filter tap and on
        // every frame of a slider drag. Decoding at full resolution meant each of
        // those re-rendered 2904x3630 = 10.5M pixels for a preview being displayed
        // at roughly 940 wide — about twelve times more work than the screen can
        // show, on the interaction path. Saving still uses the full file; see the
        // save button below.
        value = withContext(Dispatchers.IO) {
            EditSourceLoader.decode(File(path), PREVIEW_MAX_SIDE)
        }
    }
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
    var savedPath by remember { mutableStateOf<String?>(null) }
    // Saving re-renders the full-resolution file, which takes seconds. Without a
    // state for it the button looks inert and invites a second tap.
    var saving by remember { mutableStateOf(false) }
    var generated by remember { mutableStateOf<Bitmap?>(null) }
    var generatedCandidates by remember { mutableStateOf<List<GpuCandidate>>(emptyList()) }
    var maskSelection by remember { mutableStateOf<NormalizedMask?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var imageAreaSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var gpuStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val edited by produceState<Bitmap?>(source, source, selectedFilter, adjustments) {
        value = source?.let {
            withContext(Dispatchers.Default) {
                QuickFilterEditor.apply(it, selectedFilter, adjustments)
            }
        }
    }
    val diagnosedProblems by produceState<List<Problem>>(emptyList(), source) {
        value = source?.let { bitmap ->
            withContext(Dispatchers.Default) {
                ProblemDiagnoser().diagnose(ImageMetricsExtractor.extract(bitmap))
            }
        }.orEmpty()
    }

    Column(modifier = Modifier.fillMaxSize().background(Charcoal900)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("‹", color = OnDarkMedium, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onBack))
            Text("보정", color = OnDarkHigh, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (savedPath == null) "완료" else "저장됨",
                color = Sage,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Box(
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(16.dp)).background(Charcoal950),
        ) {
            val displayBitmap = generated ?: edited ?: source
            // Where Fit actually put those pixels. Every mask coordinate below is
            // relative to this and never to `imageAreaSize`, which includes the
            // letterbox bars.
            val fitRect = displayBitmap?.let {
                fitImageRect(
                    containerW = imageAreaSize.width.toFloat(),
                    containerH = imageAreaSize.height.toFloat(),
                    imageW = it.width,
                    imageH = it.height,
                )
            }
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
                        .onSizeChanged { imageAreaSize = it }
                        .pointerInput(source, generated) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    // A drag that starts on a letterbox bar is not
                                    // on the photo, so there is nothing to erase.
                                    if (fitRect?.contains(offset.x, offset.y) != true) {
                                        return@detectDragGestures
                                    }
                                    dragStart = offset
                                    dragCurrent = offset
                                    maskSelection = null
                                    gpuStatus = "지울 영역을 드래그하세요"
                                },
                                onDrag = { change, dragAmount ->
                                    val start = dragStart ?: return@detectDragGestures
                                    val current = (dragCurrent ?: start) + dragAmount
                                    dragCurrent = current
                                    maskSelection = maskFromOffsets(start, current, fitRect)
                                },
                                onDragEnd = {
                                    val start = dragStart
                                    val end = dragCurrent
                                    maskSelection = if (start != null && end != null) {
                                        maskFromOffsets(start, end, fitRect)
                                            ?.takeIf { it.width >= 0.02f && it.height >= 0.02f }
                                            ?: minimumMaskAt(end, fitRect)
                                    } else {
                                        null
                                    }
                                    dragStart = null
                                    dragCurrent = null
                                    if (maskSelection != null) gpuStatus = "선택한 영역을 지울 수 있어요"
                                },
                                onDragCancel = {
                                    dragStart = null
                                    dragCurrent = null
                                    maskSelection = null
                                },
                            )
                        },
                )
                maskSelection?.let { mask ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Drawn through the same rect the mask was normalized
                        // against. Using `size` here would put the overlay back on
                        // the container and hide the very mismatch this fixes —
                        // the old code was wrong in both places and so looked right.
                        val r = fitRect ?: return@Canvas
                        val topLeft = Offset(r.toContainerX(mask.left), r.toContainerY(mask.top))
                        val rectSize = androidx.compose.ui.geometry.Size(
                            mask.width * r.width,
                            mask.height * r.height,
                        )
                        drawRect(
                            color = Sage.copy(alpha = 0.22f),
                            topLeft = topLeft,
                            size = rectSize,
                        )
                        drawRect(
                            color = Sage,
                            topLeft = topLeft,
                            size = rectSize,
                            style = Stroke(width = 4f),
                        )
                    }
                }
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

        if (diagnosedProblems.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                diagnosedProblems.forEach { problem ->
                    DiagnosticChip(problem)
                }
            }
        }

        if (generatedCandidates.size > 1) {
            Text(
                "생성 결과를 골라보세요",
                color = OnDarkMedium,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, top = 10.dp),
            )
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                generatedCandidates.forEachIndexed { index, candidate ->
                    Image(
                        bitmap = candidate.bitmap.asImageBitmap(),
                        contentDescription = "AI 결과 ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (generated == candidate.bitmap) Modifier.border(2.dp, Sage, RoundedCornerShape(10.dp))
                                else Modifier.border(1.dp, OutlineDim, RoundedCornerShape(10.dp)),
                            )
                            .clickable {
                                generated = candidate.bitmap
                                gpuStatus = "AI 결과 ${index + 1}을 선택했어요"
                            },
                    )
                }
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
            if (maskSelection != null) {
                PrimaryPillButton(
                    text = gpuStatus ?: "사진 살리기",
                    onClick = {
                        val selected = capture
                        if (selected == null) return@PrimaryPillButton
                        val mask = maskSelection ?: return@PrimaryPillButton
                        scope.launch {
                            gpuStatus = "방해 요소를 지우는 중…"
                            val jobId = "job_" + Ulid.generate()
                            val operations = buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "remove_objects")
                                    put("maskAreaRatio", mask.area)
                                    put("masks", buildJsonArray {
                                        add(buildJsonObject {
                                            put("rect", buildJsonObject {
                                                put("x", mask.left)
                                                put("y", mask.top)
                                                put("width", mask.width)
                                                put("height", mask.height)
                                            })
                                        })
                                    })
                                })
                            }
                            runCatching {
                                container.apiClient.createEditJob(
                                    jobId = jobId,
                                    captureRef = selected.id,
                                    operations = operations,
                                    image = File(selected.filePath),
                                )
                                var final = container.apiClient.getEditJob(jobId)
                                for (attempt in 0 until 180) {
                                    if (final.status in setOf("done", "fallback", "failed")) break
                                    delay(1_000)
                                    final = container.apiClient.getEditJob(jobId)
                                }
                                if (final.status == "done" && final.results.isNotEmpty()) {
                                    val databasePath = container.database.openHelper.writableDatabase.path
                                        ?: error("app database path is unavailable")
                                    val downloaded = final.results.mapIndexed { index, result ->
                                        val output = File(databasePath.substringBeforeLast('/'), "gamdo-edit-$jobId-$index.png")
                                        container.apiClient.downloadResult(result.url, output)
                                        container.captureRepository.recordDownloadedEditResult(
                                            captureId = selected.id,
                                            jobId = jobId,
                                            filePath = output.absolutePath,
                                            rank = index,
                                            seed = result.seed,
                                            validationJson = "{\"status\":\"${result.validation}\"}",
                                            operationsJson = operations.toString(),
                                        )
                                        GpuCandidate(
                                            bitmap = BitmapFactory.decodeFile(output.absolutePath)
                                                ?: error("AI result $index is not decodable"),
                                            seed = result.seed,
                                            validation = result.validation,
                                        )
                                    }
                                    generatedCandidates = downloaded
                                    generated = downloaded.firstOrNull()?.bitmap
                                    gpuStatus = "AI 보정 결과를 적용했어요"
                                } else {
                                    gpuStatus = "자연스러운 보정만 적용했어요"
                                }
                            }.onFailure {
                                gpuStatus = "자연스러운 보정만 적용했어요"
                            }
                        }
                    },
                )
            }
            PrimaryPillButton(
                text = when {
                    savedPath != null -> "갤러리에 저장됨"
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
                            // photo to the gallery. An AI result is used as-is
                            // because it did not come from this pipeline.
                            val result = generated ?: withContext(Dispatchers.Default) {
                                EditSourceLoader.decode(File(captureValue.filePath), SAVE_MAX_SIDE)
                                    ?.let { QuickFilterEditor.apply(it, selectedFilter, adjustments) }
                            } ?: edited ?: source ?: return@launch
                            savedPath = container.captureRepository.saveEditedCapture(
                                captureId = captureValue.id,
                                bitmap = result,
                                // §4-1 비파괴: the record has to hold every control,
                                // not the three the old panel happened to show, or a
                                // saved edit cannot be reopened as what it was.
                                paramsJson = "{\"filter\":\"${selectedFilter.name}\"," +
                                    "\"adjustments\":${EditTool.toJson(adjustments)}}",
                            )
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
                            gpuStatus = "저장하지 못했어요. 저장 공간을 확인해 주세요"
                        } finally {
                            saving = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DiagnosticChip(problem: Problem) {
    val label = when (problem.code) {
        ProblemCode.UNDEREXPOSED -> "조금 어두워요"
        ProblemCode.OVEREXPOSED -> "빛이 강해요"
        ProblemCode.BLUR_SUSPECT -> "선명도를 확인해보세요"
        ProblemCode.TILT -> "기울기를 확인해보세요"
        ProblemCode.EXCESS_MARGIN -> "여백이 넓어요"
        ProblemCode.BACKLIGHT -> "뒤에서 빛이 들어와요"
    }
    val color = if (problem.severity == ProblemSeverity.HIGH) OnDarkHigh else OnDarkMedium
    Box(
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(Charcoal700)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = color, fontSize = 11.sp)
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
