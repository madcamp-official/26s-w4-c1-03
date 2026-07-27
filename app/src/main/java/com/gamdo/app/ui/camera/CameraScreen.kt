package com.gamdo.app.ui.camera

import android.util.Log
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamdo.app.BuildConfig
import com.gamdo.app.camera.AnalysisStats
import com.gamdo.app.camera.CameraController
import com.gamdo.app.camera.FrameAnalyzer
import com.gamdo.app.camera.ShakeMeter
import com.gamdo.app.camera.TiltSensor
import com.gamdo.app.camera.centerCropToRatio
import com.gamdo.app.camera.lumaMean
import com.gamdo.app.camera.scaledToMaxSide
import com.gamdo.app.data.AppContainer
import com.gamdo.app.detect.BrightnessSample
import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.MlKitFaceDetector
import com.gamdo.app.detect.MlKitPoseDetector
import com.gamdo.app.detect.SceneDetector
import com.gamdo.app.detect.toAnalysisFrame
import com.gamdo.app.guide.GuideConfigBundle
import com.gamdo.app.guide.parseGuideConfigBundle
import com.gamdo.app.guide.toStyleTarget
import com.gamdo.app.ui.components.moodBrush
import com.gamdo.app.ui.theme.Charcoal600
import com.gamdo.app.ui.theme.Charcoal950
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.Sage
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Sage is the single accent (D11-5); no opaque chromatic constant is defined here.
// GridLine is white at 28% alpha — a translucent neutral, not a hue.
private val GridLine = Color(0x47FFFFFF)
private const val TAG = "CameraScreen"

enum class CaptureAspect(val label: String, val ratioWtoH: Float) {
    RATIO_4_5("4:5", 4f / 5f),
    RATIO_1_1("1:1", 1f),
}

/**
 * Camera = home (§1-5): real CameraX preview + capture. Shutter captures and
 * stays here (t2 flow — editing happens from the album); the shot is written to
 * the app dir, exported to the gallery, and recorded in `captures`.
 *
 * This is the **host**: it owns the camera/sensor resources and the analysis
 * wiring, keeps per-frame state in [CameraViewModel], and renders the screen out
 * of the private section composables below. Two extension slots are published
 * for other verticals; both default to empty so the host stands alone.
 *
 * @param referenceLayer drawn inside the preview box, above the camera preview
 *   and below the guide overlay — the reference translucent overlay (§5-2)
 *   mounts here. Receives [BoxScope] so it can size and align itself against the
 *   preview.
 * @param demoControls trailing element of the top status row — the demo-mode
 *   toggle (§7-3) mounts here. Kept out of the preview area so it can never
 *   overlap the guide.
 */
@Composable
fun CameraScreen(
    container: AppContainer,
    onOpenAlbum: () -> Unit,
    modifier: Modifier = Modifier,
    referenceLayer: @Composable BoxScope.() -> Unit = {},
    demoControls: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val controller = remember { CameraController(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scene = remember { SceneDetector(MlKitFaceDetector(), MlKitPoseDetector()) }
    // Thresholds come from assets only (CFG-1); the data-class defaults are the
    // fallback for a missing/!unparseable file.
    val guideConfig = remember {
        runCatching {
            context.assets.open("guide_config.json").bufferedReader().use { reader ->
                parseGuideConfigBundle(reader.readText())
            }
        }.getOrDefault(GuideConfigBundle())
    }
    val viewModel = remember {
        // The §2-4 stopwatch writes through this sink; the ViewModel itself stays
        // free of android.util.Log so the stability harness can drive it on the JVM.
        CameraViewModel(config = guideConfig, logSink = { line -> Log.d(TAG, line) })
    }

    // The guide target comes from preset data (assets/presets.json = GET /presets),
    // never from values copied into this file.
    val presets = remember {
        runCatching { container.presetRepository.loadBundledPresets() }.getOrDefault(emptyList())
    }
    var presetIndex by rememberSaveable { mutableStateOf(0) }
    val activePreset = presets.getOrNull(presetIndex)

    val tiltSensor = remember { TiltSensor(context) }
    val shakeMeter = remember { ShakeMeter(context) }

    val stats by viewModel.stats.collectAsState()
    val detection by viewModel.detectionLabel.collectAsState()
    val overlay by viewModel.overlay.collectAsState()
    val guideDebug by viewModel.guideDebug.collectAsState()
    val tilt by tiltSensor.reading.collectAsState()
    val shake by shakeMeter.shake.collectAsState()
    // The debug read-outs must stay unreachable in release. Gate the toggle *and*
    // the render: `showHud` is rememberSaveable, so a value restored from a bundle
    // written by a debug build must not be able to surface them either.
    // BuildConfig.DEBUG is a compile-time constant, so the whole HUD branch is
    // dead code in release. (§7-2's developer-gesture entry point is wave 3.)
    val hudAvailable = BuildConfig.DEBUG
    var showHud by rememberSaveable { mutableStateOf(BuildConfig.DEBUG) }

    // A preset switch invalidates the smoothing window and the last stable target
    // (handled inside setStyleTarget).
    LaunchedEffect(presetIndex) {
        presets.getOrNull(presetIndex)?.let { viewModel.setStyleTarget(it.toStyleTarget()) }
    }

    DisposableEffect(Unit) {
        tiltSensor.start()
        shakeMeter.start()
        onDispose {
            tiltSensor.stop()
            shakeMeter.stop()
        }
    }

    var aspect by rememberSaveable { mutableStateOf(CaptureAspect.RATIO_4_5) }
    var selectedZoom by rememberSaveable { mutableStateOf(1f) }
    var isFront by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    DisposableEffect(controller) {
        // Attach the analysis pipeline (§2-1) running ML Kit face + pose (§2-2).
        // FrameAnalyzer times onFrame, so the HUD reflects real detection cost.
        // Platform conversion stays here; the reduction happens in the ViewModel.
        controller.setAnalyzer(
            analysisExecutor,
            FrameAnalyzer(
                targetFps = 12,
                onStats = viewModel::onStats,
                onFrame = { imageProxy ->
                    val luma = imageProxy.lumaMean()
                    imageProxy.toAnalysisFrame()?.let { frame ->
                        val result = scene.detect(frame)
                        viewModel.onFrameAnalyzed(
                            detection = result,
                            tilt = tiltSensor.reading.value,
                            brightness = BrightnessSample(frameMean = luma),
                            shake = shakeMeter.shake.value,
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            mirror = controller.isFront,
                        )
                        if (BuildConfig.DEBUG) logDetection(result)
                    }
                },
            ),
        )
        onDispose {
            controller.clearAnalyzer()
            controller.unbind()
            scene.close()
            analysisExecutor.shutdown()
            viewModel.onAnalyzerDetached()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Charcoal950),
    ) {
        CameraStatusBar(
            hudToggleEnabled = hudAvailable,
            onToggleHud = { showHud = !showHud },
            demoControls = demoControls,
        )

        AspectSelector(selected = aspect, onSelect = { aspect = it })

        CameraPreviewPane(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            controller = controller,
            lifecycleOwner = lifecycleOwner,
            aspect = aspect,
            overlay = overlay,
            rollDeg = tilt.rollDeg,
            pitchDeg = tilt.pitchDeg,
            // §3-2: the product overlay is bracket + silhouette + horizon only.
            // Raw face boxes / centre dot ride the same toggle as the HUD.
            showDetections = hudAvailable && showHud,
            selectedZoom = selectedZoom,
            onSelectZoom = { zoom ->
                selectedZoom = zoom
                controller.setZoom(zoom)
            },
            referenceLayer = referenceLayer,
            hud = {
                if (hudAvailable && showHud) {
                    CameraHud(
                        modifier = Modifier.align(Alignment.TopStart),
                        stats = stats,
                        detection = detection,
                        rollDeg = tilt.rollDeg,
                        pitchDeg = tilt.pitchDeg,
                        shake = shake,
                        guideDebug = guideDebug,
                        presetLabel = activePreset
                            ?.let { "${it.displayName} (${presetIndex + 1}/${presets.size})" }
                            ?: "presets.json 로드 실패",
                        onCyclePreset = {
                            if (presets.isNotEmpty()) {
                                presetIndex = (presetIndex + 1) % presets.size
                            }
                        },
                    )
                }
            },
        )

        CameraBottomBar(
            lastThumb = lastThumb,
            capturing = capturing,
            isFront = isFront,
            onOpenAlbum = onOpenAlbum,
            onFlipLens = {
                controller.toggleLens()
                isFront = controller.isFront
                // Rebinding the lens resets CameraX zoom to 1x.
                selectedZoom = 1f
            },
            onShutter = {
                capturing = true
                scope.launch {
                    try {
                        // Heavy bitmap work stays off the main thread; the thumb
                        // is a downscaled copy so we never retain a
                        // full-resolution bitmap for a 44dp preview.
                        val bitmap = withContext(Dispatchers.Default) {
                            controller.capture().centerCropToRatio(aspect.ratioWtoH)
                        }
                        lastThumb = withContext(Dispatchers.Default) {
                            bitmap.scaledToMaxSide(256)
                        }
                        container.captureRepository.saveCameraCapture(bitmap)
                    } catch (t: Throwable) {
                        Log.e(TAG, "capture failed", t)
                        Toast.makeText(context, "촬영에 실패했어요", Toast.LENGTH_SHORT).show()
                    } finally {
                        capturing = false
                    }
                }
            },
        )
    }
}

/**
 * Top status row. [demoControls] is the §7-3 extension slot (trailing).
 *
 * The chip is a product status indicator; its tap exists solely as the debug-HUD
 * affordance, so when [hudToggleEnabled] is false it carries no click target at
 * all — no dead tap area and no ripple in release.
 */
@Composable
private fun CameraStatusBar(
    hudToggleEnabled: Boolean,
    onToggleHud: () -> Unit,
    demoControls: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE6242822))
                .then(
                    if (hudToggleEnabled) Modifier.clickable(onClick = onToggleHud) else Modifier,
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Sage))
            Text("내 감도 적용 중", color = OnDarkHigh, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
            demoControls()
        }
    }
}

/** 4:5 / 1:1 only (D9). */
@Composable
private fun AspectSelector(selected: CaptureAspect, onSelect: (CaptureAspect) -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x66242822))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CaptureAspect.entries.forEach { option ->
                val isSelected = option == selected
                Text(
                    text = option.label,
                    color = if (isSelected) Charcoal950 else OnDarkMedium,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isSelected) Sage else Color.Transparent)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * Preview + guide overlay + aspect mask + zoom chips.
 *
 * Layer order is load-bearing: camera preview → [referenceLayer] → guide overlay
 * → aspect mask → [hud]. The guide must stay readable on top of any reference
 * image, and the mask must clip both so nothing spills onto the letterbox bars.
 */
@Composable
private fun CameraPreviewPane(
    modifier: Modifier,
    controller: CameraController,
    lifecycleOwner: LifecycleOwner,
    aspect: CaptureAspect,
    overlay: OverlayData?,
    rollDeg: Float,
    pitchDeg: Float,
    showDetections: Boolean,
    selectedZoom: Float,
    onSelectZoom: (Float) -> Unit,
    referenceLayer: @Composable BoxScope.() -> Unit,
    hud: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val windowHeight = (maxWidth / aspect.ratioWtoH).coerceAtMost(maxHeight)
        val barHeight = (maxHeight - windowHeight) / 2

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller.camera
                    controller.bind(lifecycleOwner)
                }
            },
        )

        referenceLayer()

        // Drawn under the aspect mask so boxes/guides never spill onto the bars.
        CameraOverlay(
            overlay = overlay,
            rollDeg = rollDeg,
            pitchDeg = pitchDeg,
            modifier = Modifier.fillMaxSize(),
            showDetections = showDetections,
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(barHeight).background(Charcoal950))
            Box(modifier = Modifier.fillMaxWidth().height(windowHeight)) {
                RuleOfThirds()
                ZoomChipRow(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    selectedZoom = selectedZoom,
                    onSelectZoom = onSelectZoom,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(barHeight).background(Charcoal950))
        }

        hud()
    }
}

@Composable
private fun ZoomChipRow(
    modifier: Modifier,
    selectedZoom: Float,
    onSelectZoom: (Float) -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoomChip("0.6x", active = isZoomSelected(selectedZoom, 0.6f)) { onSelectZoom(0.6f) }
        ZoomChip("1x", active = isZoomSelected(selectedZoom, 1f)) { onSelectZoom(1f) }
        ZoomChip("2x", active = isZoomSelected(selectedZoom, 2f)) { onSelectZoom(2f) }
    }
}

/** Bottom bar: album / shutter / flip. */
@Composable
private fun CameraBottomBar(
    lastThumb: android.graphics.Bitmap?,
    capturing: Boolean,
    isFront: Boolean,
    onOpenAlbum: () -> Unit,
    onFlipLens: () -> Unit,
    onShutter: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(moodBrush(2))
                    .border(1.5.dp, Color(0x40FFFFFF), RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenAlbum),
                contentAlignment = Alignment.Center,
            ) {
                if (lastThumb != null) {
                    Image(
                        bitmap = lastThumb.asImageBitmap(),
                        contentDescription = "최근 촬영",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            Text("앨범", color = OnDarkMedium, fontSize = 10.sp)
        }

        // D2: the shutter is manual only — capture is reachable from this
        // clickable lambda and nowhere else.
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(4.dp, Color(0xE6FFFFFF), CircleShape)
                .clickable(enabled = !capturing, onClick = onShutter),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (capturing) OnDarkMuted else OnDarkHigh),
            )
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Charcoal600)
                .clickable(onClick = onFlipLens),
            contentAlignment = Alignment.Center,
        ) {
            Text("⟲", color = if (isFront) Sage else OnDarkMedium, fontSize = 18.sp)
        }
    }
}

/** Debug read-outs. Assembled by the host and mounted inside the preview box. */
@Composable
private fun CameraHud(
    modifier: Modifier,
    stats: AnalysisStats?,
    detection: String,
    rollDeg: Float,
    pitchDeg: Float,
    shake: Float,
    guideDebug: GuideDebug?,
    presetLabel: String,
    onCyclePreset: () -> Unit,
) {
    Column(
        modifier = modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stats?.let { DebugHud(stats = it) }
        if (detection.isNotEmpty()) DetectionBadge(detection)
        TiltBadge(rollDeg = rollDeg, pitchDeg = pitchDeg, shake = shake)
        if (BuildConfig.DEBUG) {
            // Debug-only: cycles the 6 presets so the guide target can be
            // checked against every preset before the style strip exists.
            PresetBadge(label = presetLabel, onClick = onCyclePreset)
            guideDebug?.let { GuideDebugBadge(it) }
        }
    }
}

private fun logDetection(result: DetectionResult) {
    val faceN = result.faces.size
    val poseN = result.pose?.landmarks?.size ?: 0
    val f = result.faces.firstOrNull()
    Log.d(
        TAG,
        "faces=$faceN pose=$poseN " +
            "poseConf=%.2f ".format(result.pose?.averageInFrameLikelihood ?: 0f) +
            if (f != null) {
                "face0 box=(%.2f,%.2f,%.2f,%.2f) eyeL=%s eyeR=%s rollZ=%.1f".format(
                    f.box.left, f.box.top, f.box.right, f.box.bottom,
                    f.leftEyeOpenProbability?.let { "%.2f".format(it) } ?: "?",
                    f.rightEyeOpenProbability?.let { "%.2f".format(it) } ?: "?",
                    f.headEulerAngleZ,
                )
            } else {
                "no-face"
            },
    )
}

@Composable
private fun RuleOfThirds() {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth())
        Box(Modifier.height(1.dp).fillMaxWidth().background(GridLine))
        Box(Modifier.weight(1f).fillMaxWidth())
        Box(Modifier.height(1.dp).fillMaxWidth().background(GridLine))
        Box(Modifier.weight(1f).fillMaxWidth())
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(GridLine))
        Box(Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(GridLine))
        Box(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun DebugHud(stats: AnalysisStats, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "%.1fms · %dfps · drop %d%%".format(stats.processMs, stats.fps, stats.dropRatePercent),
            color = Sage,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TiltBadge(rollDeg: Float, pitchDeg: Float, shake: Float) {
    val level = abs(rollDeg) <= 1f
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "수평 %.1f° · 기울기 %.1f° · 흔들림 %.3f".format(rollDeg, pitchDeg, shake),
            color = if (level) Sage else OnDarkMedium,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Debug-only read-out of the P2 guide chain: measured frame features, the
 * alignment decision, and both scores. `matchScore` stays out of the product UI
 * by contract (D2) — this badge is compiled into debug builds only.
 */
@Composable
private fun GuideDebugBadge(debug: GuideDebug) {
    val f = debug.features
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "aligned=%s visible=%s · IoU %.2f · match %.2f".format(
                    debug.aligned, debug.visible, debug.iou, debug.matchScore,
                ),
                color = if (debug.aligned) Sage else OnDarkHigh,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "area %.2f · headroom %.2f · margins %.2f/%.2f".format(
                    f.personAreaRatio, f.headroom, f.sideMargins.left, f.sideMargins.right,
                ),
                color = OnDarkMedium,
                fontSize = 10.sp,
            )
            Text(
                text = "luma %.2f · poseConf %.2f · backlight=%s lowLight=%s".format(
                    f.brightnessMean, f.poseConfidence, f.backlightFlag, f.lowLightFlag,
                ),
                color = OnDarkMedium,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun PresetBadge(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = "preset: $label ▸", color = Sage, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DetectionBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = Sage, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ZoomChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (active) 34.dp else 30.dp)
            .clip(CircleShape)
            .background(Color(0x99141614))
            .then(if (active) Modifier.border(1.8.dp, Sage, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Sage else Color(0xBFFFFFFF),
            fontSize = if (active) 11.sp else 10.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun isZoomSelected(current: Float, target: Float): Boolean =
    kotlin.math.abs(current - target) < 0.05f
