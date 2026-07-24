package com.gamdo.app.ui.camera

import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamdo.app.camera.AnalysisStats
import com.gamdo.app.camera.CameraController
import com.gamdo.app.camera.FrameAnalyzer
import com.gamdo.app.camera.ShakeMeter
import com.gamdo.app.camera.TiltSensor
import com.gamdo.app.camera.centerCropToRatio
import com.gamdo.app.data.AppContainer
import com.gamdo.app.detect.MlKitFaceDetector
import com.gamdo.app.detect.MlKitPoseDetector
import com.gamdo.app.detect.BrightnessSample
import com.gamdo.app.detect.FrameFeatureCalculator
import com.gamdo.app.detect.SceneDetector
import com.gamdo.app.detect.toAnalysisFrame
import com.gamdo.app.guide.GuideConfig
import com.gamdo.app.guide.StyleTarget
import com.gamdo.app.guide.AlignmentEngine
import com.gamdo.app.guide.parseGuideConfig
import com.gamdo.app.guide.toProjection
import com.gamdo.app.ui.components.moodBrush
import com.gamdo.app.ui.theme.Charcoal950
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.Sage
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val GuideLime = Color(0xFFCDD69A)
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
 */
@Composable
fun CameraScreen(
    container: AppContainer,
    onOpenAlbum: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val controller = remember { CameraController(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scene = remember { SceneDetector(MlKitFaceDetector(), MlKitPoseDetector()) }
    val featureCalculator = remember { FrameFeatureCalculator() }
    val alignmentEngine = remember { AlignmentEngine() }
    val guideConfig = remember {
        runCatching {
            context.assets.open("guide_config.json").bufferedReader().use { reader ->
                parseGuideConfig(reader.readText())
            }
        }.getOrDefault(GuideConfig())
    }
    val styleTarget = remember {
        StyleTarget(
            targetAspectRatio = 4f / 5f,
            subjectScaleRange = 0.35f..0.55f,
            subjectAnchorX = 0.5f,
            headroomRange = 0.05f..0.12f,
            horizonPosition = 0.5f,
            cameraPitchRange = -5f..5f,
        )
    }
    val tiltSensor = remember { TiltSensor(context) }
    val shakeMeter = remember { ShakeMeter(context) }
    val statsFlow = remember { MutableStateFlow<AnalysisStats?>(null) }
    val detectionFlow = remember { MutableStateFlow("") }
    val overlayFlow = remember { MutableStateFlow<OverlayData?>(null) }
    val stats by statsFlow.collectAsState()
    val detection by detectionFlow.collectAsState()
    val overlay by overlayFlow.collectAsState()
    val tilt by tiltSensor.reading.collectAsState()
    val shake by shakeMeter.shake.collectAsState()
    var showHud by rememberSaveable { mutableStateOf(true) }

    DisposableEffect(Unit) {
        tiltSensor.start()
        shakeMeter.start()
        onDispose {
            tiltSensor.stop()
            shakeMeter.stop()
        }
    }

    var aspect by rememberSaveable { mutableStateOf(CaptureAspect.RATIO_4_5) }
    var isFront by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    DisposableEffect(controller) {
        // Attach the analysis pipeline (§2-1) running ML Kit face + pose (§2-2).
        // FrameAnalyzer times onFrame, so the HUD reflects real detection cost.
        controller.setAnalyzer(
            analysisExecutor,
            FrameAnalyzer(
                targetFps = 12,
                onStats = { statsFlow.value = it },
                onFrame = { imageProxy ->
                    imageProxy.toAnalysisFrame()?.let { frame ->
                        val result = scene.detect(frame)
                        val faceN = result.faces.size
                        val poseN = result.pose?.landmarks?.size ?: 0
                        detectionFlow.value = "얼굴 $faceN · 포즈 $poseN"
                        val frameFeatures = featureCalculator.calculate(
                            input = com.gamdo.app.detect.FrameFeatureInput(
                                detection = result,
                                tilt = tiltSensor.reading.value,
                                brightness = BrightnessSample(frameMean = 0.5f),
                                shake = shakeMeter.shake.value,
                            ),
                        )
                        val guide = alignmentEngine
                            .align(frameFeatures, styleTarget, guideConfig)
                            .toProjection()
                        overlayFlow.value = OverlayData(
                            faces = result.faces.map { it.box },
                            personCenter = result.pose?.landmarks
                                ?.filter { it.inFrameLikelihood > 0.3f }
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { lm ->
                                    lm.map { it.x }.average().toFloat() to lm.map { it.y }.average().toFloat()
                                }
                                ?: result.faces.firstOrNull()?.box?.let { it.centerX to it.centerY },
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            mirror = controller.isFront,
                            guide = guide,
                        )
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
                },
            ),
        )
        onDispose {
            controller.clearAnalyzer()
            controller.unbind()
            scene.close()
            analysisExecutor.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Charcoal950),
    ) {
        // Status chip
        Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xE6242822))
                    .clickable { showHud = !showHud }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Sage))
                Text("내 감도 적용 중", color = OnDarkHigh, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Aspect ratio toggle
        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x66242822))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CaptureAspect.entries.forEach { option ->
                    val selected = option == aspect
                    Text(
                        text = option.label,
                        color = if (selected) Charcoal950 else OnDarkMedium,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (selected) Sage else Color.Transparent)
                            .clickable { aspect = option }
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }
        }

        // Preview + aspect mask + grid
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
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

            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().height(barHeight).background(Charcoal950))
                Box(modifier = Modifier.fillMaxWidth().height(windowHeight)) {
                    RuleOfThirds()
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZoomChip(".5", active = false) { controller.setZoom(0.6f) }
                        ZoomChip("1x", active = true) { controller.setZoom(1f) }
                        ZoomChip("2x", active = false) { controller.setZoom(2f) }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(barHeight).background(Charcoal950))
            }

            CameraOverlay(
                overlay = overlay,
                rollDeg = tilt.rollDeg,
                pitchDeg = tilt.pitchDeg,
                modifier = Modifier.fillMaxSize(),
            )

            if (showHud) {
                Column(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    stats?.let { DebugHud(stats = it) }
                    if (detection.isNotEmpty()) DetectionBadge(detection)
                    TiltBadge(rollDeg = tilt.rollDeg, pitchDeg = tilt.pitchDeg, shake = shake)
                }
            }
        }

        // Bottom bar: album / shutter / flip
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val thumb = lastThumb
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(moodBrush(2))
                        .border(1.5.dp, Color(0x40FFFFFF), RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenAlbum),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = "최근 촬영",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
                Text("앨범", color = OnDarkMedium, fontSize = 10.sp)
            }

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color(0xE6FFFFFF), CircleShape)
                    .clickable(enabled = !capturing) {
                        capturing = true
                        scope.launch {
                            try {
                                val bitmap = controller.capture(context).centerCropToRatio(aspect.ratioWtoH)
                                lastThumb = bitmap
                                container.captureRepository.saveCameraCapture(bitmap)
                            } catch (t: Throwable) {
                                Log.e(TAG, "capture failed", t)
                            } finally {
                                capturing = false
                            }
                        }
                    },
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
                    .background(Color(0xFF242822))
                    .clickable {
                        controller.toggleLens()
                        isFront = controller.isFront
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("⟲", color = if (isFront) Sage else OnDarkMedium, fontSize = 18.sp)
            }
        }
    }
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
            color = GuideLime,
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
            .then(if (active) Modifier.border(1.8.dp, GuideLime, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) GuideLime else Color(0xBFFFFFFF),
            fontSize = if (active) 11.sp else 10.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
