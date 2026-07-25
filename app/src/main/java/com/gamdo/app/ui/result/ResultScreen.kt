package com.gamdo.app.ui.result

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.gamdo.app.edit.LocalEditParams
import com.gamdo.app.edit.LocalEditor
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

private data class GpuCandidate(
    val bitmap: Bitmap,
    val seed: Int?,
    val validation: String?,
)

private fun maskFromOffsets(start: Offset, end: Offset, width: Int, height: Int): NormalizedMask? {
    if (width <= 0 || height <= 0) return null
    val left = (minOf(start.x, end.x) / width).coerceIn(0f, 1f)
    val top = (minOf(start.y, end.y) / height).coerceIn(0f, 1f)
    val right = (maxOf(start.x, end.x) / width).coerceIn(0f, 1f)
    val bottom = (maxOf(start.y, end.y) / height).coerceIn(0f, 1f)
    return NormalizedMask(left, top, right, bottom)
}

private fun minimumMaskAt(point: Offset, width: Int, height: Int): NormalizedMask? {
    if (width <= 0 || height <= 0) return null
    val half = 0.1f
    val centerX = (point.x / width).coerceIn(half, 1f - half)
    val centerY = (point.y / height).coerceIn(half, 1f - half)
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
        // the first frame on a full-resolution gallery image.
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
    }
    var selectedFilter by remember { mutableStateOf(LocalFilter.MY_STYLE) }
    val preferredFilter by produceState(LocalFilter.MY_STYLE, container) {
        value = when (container.settingsRepository.getStylePresetId()) {
            "bright_review" -> LocalFilter.BRIGHT_REVIEW
            "candid_feed" -> LocalFilter.CAFE
            "soft_film" -> LocalFilter.SOFT_FILM
            "night_street" -> LocalFilter.NIGHT_STREET
            else -> LocalFilter.MY_STYLE
        }
    }
    LaunchedEffect(preferredFilter) { selectedFilter = preferredFilter }
    var brightness by remember { mutableStateOf(0f) }
    var warmth by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var generated by remember { mutableStateOf<Bitmap?>(null) }
    var generatedCandidates by remember { mutableStateOf<List<GpuCandidate>>(emptyList()) }
    var maskSelection by remember { mutableStateOf<NormalizedMask?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var imageAreaSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var gpuStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val edited by produceState<Bitmap?>(source, source, selectedFilter, brightness, warmth, contrast) {
        value = source?.let {
            withContext(Dispatchers.Default) {
                LocalEditor.apply(
                    it,
                    selectedFilter,
                    LocalEditParams(brightness, warmth, contrast),
                )
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
            (generated ?: edited)?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "보정 결과",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { imageAreaSize = it }
                        .pointerInput(source, generated) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    dragStart = offset
                                    dragCurrent = offset
                                    maskSelection = null
                                    gpuStatus = "지울 영역을 드래그하세요"
                                },
                                onDrag = { change, dragAmount ->
                                    val start = dragStart ?: return@detectDragGestures
                                    val current = (dragCurrent ?: start) + dragAmount
                                    dragCurrent = current
                                    maskSelection = maskFromOffsets(
                                        start,
                                        current,
                                        imageAreaSize.width,
                                        imageAreaSize.height,
                                    )
                                },
                                onDragEnd = {
                                    val start = dragStart
                                    val end = dragCurrent
                                    maskSelection = if (start != null && end != null) {
                                        maskFromOffsets(start, end, imageAreaSize.width, imageAreaSize.height)
                                            ?.takeIf { it.width >= 0.02f && it.height >= 0.02f }
                                            ?: minimumMaskAt(end, imageAreaSize.width, imageAreaSize.height)
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
                        val topLeft = Offset(mask.left * size.width, mask.top * size.height)
                        val rectSize = androidx.compose.ui.geometry.Size(
                            mask.width * size.width,
                            mask.height * size.height,
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
            } ?: run {
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

        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EditSlider("밝기", brightness, { brightness = it })
            EditSlider("따뜻함", warmth, { warmth = it })
            EditSlider("대비", contrast, { contrast = it })
        }

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
                text = if (savedPath == null) "저장" else "갤러리에 저장됨",
                onClick = {
                    val result = generated ?: edited ?: return@PrimaryPillButton
                    val captureValue = capture ?: return@PrimaryPillButton
                    scope.launch {
                        savedPath = container.captureRepository.saveEditedCapture(
                            captureId = captureValue.id,
                            bitmap = result,
                            paramsJson = "{\"filter\":\"${selectedFilter.name}\",\"brightness\":$brightness,\"warmth\":$warmth,\"contrast\":$contrast}",
                        )
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

private fun filterAsset(filter: LocalFilter): String = when (filter) {
    LocalFilter.ORIGINAL, LocalFilter.MY_STYLE -> "presets/clean_social.jpg"
    LocalFilter.CAFE -> "presets/candid_feed.jpg"
    LocalFilter.BRIGHT_REVIEW -> "presets/bright_review.jpg"
    LocalFilter.SOFT_FILM -> "presets/soft_film.jpg"
    LocalFilter.NIGHT_STREET -> "presets/night_street.jpg"
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

@Composable
private fun EditSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = OnDarkMedium, fontSize = 12.5.sp, modifier = Modifier.width(44.dp))
        Slider(value = value, onValueChange = onValueChange, valueRange = -1f..1f, modifier = Modifier.weight(1f))
        Text("%+d".format((value * 100).toInt()), color = if (value == 0f) OnDarkMuted else Sage, fontSize = 12.5.sp, modifier = Modifier.width(38.dp))
    }
}
