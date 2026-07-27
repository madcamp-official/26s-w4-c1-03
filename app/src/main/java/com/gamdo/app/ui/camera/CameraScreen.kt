package com.gamdo.app.ui.camera

import android.util.Log
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.gamdo.app.camera.brightnessSample
import com.gamdo.app.camera.scaledToMaxSide
import com.gamdo.app.data.AppContainer
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
import kotlinx.coroutines.coroutineScope
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
 * @param referenceEntry leading element of the top bar's trailing zone — the
 *   §5-1 reference entry point mounts here. Empty by default; the top bar lays
 *   out correctly with the zone empty, so nothing has to move when it lands.
 * @param demoControls trailing element of the top bar — the demo-mode toggle
 *   (§7-3) mounts here. Kept out of the preview area so it can never overlap
 *   the guide.
 */
@Composable
fun CameraScreen(
    container: AppContainer,
    onOpenAlbum: () -> Unit,
    modifier: Modifier = Modifier,
    referenceLayer: @Composable BoxScope.() -> Unit = {},
    referenceEntry: @Composable () -> Unit = {},
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
    val presetIds = remember(presets) { presets.map { it.id } }
    val presetNames = remember(presets) { presets.map { it.displayName } }
    // §6-2 onboarding → camera: the style picked during onboarding selects the
    // initial preset. A failed read degrades to "nothing stored" rather than to
    // "still reading" — the latter would leave the guide target unpublished for
    // the rest of the session (see resolveStyleIndex's `loaded`).
    val onboardingStyle by produceState<OnboardingStyle?>(initialValue = null, container) {
        value = OnboardingStyle(
            runCatching { container.settingsRepository.getStylePresetId() }.getOrNull(),
        )
    }
    // §3-2 top bar. The in-session pick is deliberately *not* written back to
    // app_settings (TEAM.md §8): that key is the D4 personalisation profile, so
    // a relaunch must return to the onboarding style. It is held as an id, not
    // an index, because §6-2 will reorder `presets` by recommendation rank.
    var sessionStyleId by rememberSaveable { mutableStateOf<String?>(null) }
    var stylePickerOpen by rememberSaveable { mutableStateOf(false) }
    var guideVisible by rememberSaveable { mutableStateOf(true) }
    val styleIndex = resolveStyleIndex(
        presetIds = presetIds,
        onboardingId = onboardingStyle?.id,
        sessionId = sessionStyleId,
        loaded = onboardingStyle != null,
    )
    val activePreset = presets.getOrNull(styleIndex)

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
    // (handled inside setStyleTarget), so this is keyed on the preset *value*:
    // a recomposition that resolves to the same style must not reset the guide.
    // `styleIndex = -1` (still reading / no presets) leaves the target
    // unpublished rather than publishing preset 0 and swapping it a frame later.
    LaunchedEffect(activePreset) {
        activePreset?.let { viewModel.setStyleTarget(it.toStyleTarget()) }
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
    val actualZoom by controller.zoomRatio.collectAsState()
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
                    imageProxy.toAnalysisFrame()?.let { frame ->
                        val result = scene.detect(frame)
                        viewModel.onFrameAnalyzed(
                            detection = result,
                            tilt = tiltSensor.reading.value,
                            // Sample the frame *and* the largest face region. A
                            // frame mean on its own leaves backlightFlag stuck at
                            // false, because backlight is defined by the subject
                            // being darker than the surround.
                            brightness = imageProxy.brightnessSample(
                                faceBox = result.faces.maxByOrNull {
                                    it.box.width * it.box.height
                                }?.box,
                            ),
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

    // Measured, not hard-coded: the floating style strip has to start exactly at
    // the preview's top edge, and a dp constant would drift the moment a chip's
    // padding or font size changes. It covers the *preview*, never the controls
    // above it — offsetting by the top bar alone put the strip's scrim over the
    // aspect toggle and made 4:5 / 1:1 unreachable while the picker was open.
    var topControlsHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Charcoal950),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.onSizeChanged { topControlsHeightPx = it.height }) {
        CameraStatusBar(
            styleName = activePreset?.displayName,
            pickerOpen = stylePickerOpen,
            onTogglePicker = { stylePickerOpen = !stylePickerOpen },
            guideVisible = guideVisible,
            onToggleGuide = { guideVisible = !guideVisible },
            hudToggleEnabled = hudAvailable,
            onToggleHud = { showHud = !showHud },
            referenceEntry = referenceEntry,
            demoControls = demoControls,
        )

        AspectSelector(selected = aspect, onSelect = { aspect = it })
    }

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
            // §3-2 top-bar toggle. Display-side only: the analyzer keeps running
            // so §3-3's shutter snapshot and the §2-4 budget log stay alive with
            // the guide hidden.
            showGuide = guideVisible,
            // §3-2: the product overlay is bracket + silhouette + horizon only.
            // Raw face boxes / centre dot ride the same toggle as the HUD.
            showDetections = hudAvailable && showHud,
            // Pinch-to-zoom lives inside the pane and drives CameraX directly;
            // this is the read-back of CameraX's actual ZoomState, not a request.
            zoomRatio = actualZoom,
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
                // Rebinding the lens resets CameraX zoom to 1x. Nothing to reset
                // here: CameraController's ZoomState observer pushes the new ratio
                // into `zoomRatio`, so the readout follows the rebind on its own.
            },
            onShutter = {
                capturing = true
                scope.launch {
                    try {
                        // capture() must be called on the main thread: it reaches
                        // CameraX's takePicture(), which asserts it outright
                        // (Threads.checkMainThread). It is already off-main where it
                        // matters — the decode/rotate runs on the callback executor
                        // it passes in. Wrapping the call in withContext(Default)
                        // therefore bought nothing and threw IllegalStateException on
                        // every shutter press, losing the shot; three presses, three
                        // "촬영에 실패했어요" toasts, verified on SM-G970N.
                        val captured = controller.capture()
                        // Heavy bitmap work stays off the main thread; the thumb
                        // is a downscaled copy so we never retain a
                        // full-resolution bitmap for a 44dp preview.
                        val bitmap = withContext(Dispatchers.Default) {
                            captured.centerCropToRatio(aspect.ratioWtoH)
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

        // Floats over the preview instead of taking a row in the [Column].
        //
        // In the Column it changed the preview pane's height, and PreviewView's
        // SurfaceView does not follow that resize. Measured on SM-G970N: opening
        // the picker shrank the pane by the strip's 140px, the surface kept its
        // old height, and it bled 70px above and below the pane — covering the
        // aspect toggle and the top of the bottom bar with live camera image.
        // Floating the strip keeps the pane's size constant, so the surface is
        // never asked to resize, and the preview no longer jumps open/closed.
        if (stylePickerOpen && presetNames.isNotEmpty()) {
            StyleStrip(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, topControlsHeightPx) },
                styleNames = presetNames,
                selectedIndex = styleIndex,
                // Session-only: this never reaches SettingsRepository.
                onSelect = { index -> sessionStyleId = presetIds.getOrNull(index) },
            )
        }
    }
}

/**
 * Top bar (§3-2), in three zones: **start** = guide on/off, **center** = the
 * active style name and its change button, **end** = [referenceEntry] then
 * [demoControls].
 *
 * Zones are laid out in a [Row] with the centre zone weighted, not aligned inside
 * a [Box]. A Box centres the style chip against the screen but lets it overlap the
 * side zones, and the overlap is silent *and* input-stealing: with `부드러운 필름`
 * (6 glyphs) the chip covered the debug HUD chip on SM-G970N and ate its taps,
 * while `밝은 리뷰` (4) left it alone — a bug whose existence depended on the
 * preset name. The weighted centre can never overlap; the name ellipsizes instead,
 * and `변경` keeps its width so the chip never loses its verb.
 *
 * D2 line: everything here is chrome — a control label and a style name. No
 * instruction banner, no direction arrow, no match gauge, no auto-capture.
 * Sage is the only accent (D11-5). §3-2's "프리뷰가 주인공" is why the whole bar
 * is chips on charcoal rather than a surface.
 *
 * The style chip is absent, not disabled, when there is no style to name
 * (presets.json failed to parse): a chip that opens an empty list would be dead
 * UI, and the only honest label for that state would be the asset name — which
 * is exactly the jargon R7-1 forbids.
 */
@Composable
private fun CameraStatusBar(
    modifier: Modifier = Modifier,
    styleName: String?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    guideVisible: Boolean,
    onToggleGuide: () -> Unit,
    hudToggleEnabled: Boolean,
    onToggleHud: () -> Unit,
    referenceEntry: @Composable () -> Unit,
    demoControls: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BarChip(onClick = onToggleGuide) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (guideVisible) Sage else OnDarkMuted),
                )
                Text(
                    text = "가이드",
                    color = if (guideVisible) OnDarkHigh else OnDarkMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Double-gated like CameraHud: BuildConfig.DEBUG is a compile-time
            // constant, so this chip does not exist in a release build no matter
            // what the caller passes.
            if (BuildConfig.DEBUG && hudToggleEnabled) {
                BarChip(onClick = onToggleHud) {
                    Text("HUD", color = OnDarkMedium, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Box(
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (styleName != null) {
                BarChip(onClick = onTogglePicker) {
                    Text(
                        text = styleName,
                        color = OnDarkHigh,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // fill = false so a short name still hugs its text; the
                        // weight only matters when the name would otherwise push
                        // `변경` out of the chip.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // §3-2 asks for "스타일 이름 + 변경 버튼" literally. A word beats a
                    // caret here: it says what the tap does without a glyph that
                    // could be read as one of D2-1's direction arrows.
                    Text(
                        text = if (pickerOpen) "닫기" else "변경",
                        color = Sage,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            referenceEntry()
            demoControls()
        }
    }
}

/** Shared shape/padding for the top bar's controls. */
@Composable
private fun BarChip(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Charcoal600.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * The style list, opened from the top bar's change button.
 *
 * Laid out in the chrome column (between the bar and the aspect selector), not
 * over the preview. Two reasons, both structural rather than aesthetic:
 * `CameraPreviewPane`'s touch surface must stay the only pointer-input node over
 * the preview (see its KDoc — a sibling above it silently kills pinch *and*
 * tap-to-focus together), and a list drawn over the preview invites being read
 * as a fourth overlay element against §3-2's "브래킷+실루엣+수평선 셋만".
 *
 * It stays open after a pick. Switching styles moves the guide bracket, and the
 * point of the control is to see that happen — closing on every tap would make
 * comparing two styles a four-tap operation.
 *
 * [styleNames] arrives in display order and is consumed as given: §6-2 orders it
 * by recommendation rank, and re-sorting it here would discard that.
 */
@Composable
private fun StyleStrip(
    modifier: Modifier = Modifier,
    styleNames: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // The active style can be off-screen once the list is recommendation-ordered.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
    }
    LazyRow(
        // The scrim is load-bearing now that the strip floats over live preview:
        // the unselected chips are Charcoal600 at 60% alpha, which is legible on
        // charcoal but not against a bright frame.
        modifier = modifier
            .fillMaxWidth()
            .background(Charcoal950.copy(alpha = 0.88f))
            .padding(vertical = 8.dp),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(styleNames) { index, name ->
            val isSelected = index == selectedIndex
            Text(
                text = name,
                color = if (isSelected) Charcoal950 else OnDarkMedium,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) Sage else Charcoal600.copy(alpha = 0.6f))
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
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
                .background(Charcoal600.copy(alpha = 0.4f))
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
 * Preview + touch surface + guide overlay + aspect mask + zoom readout.
 *
 * Layer order is load-bearing: camera preview → touch surface → [referenceLayer]
 * → guide overlay → aspect mask → [hud]. The guide must stay readable on top of
 * any reference image, and the mask must clip both so nothing spills onto the
 * letterbox bars.
 *
 * **The touch surface is the *only* pointer-input node over the preview, and every
 * preview gesture has to be implemented inside it.** Compose hit-tests siblings in
 * reverse z-order and stops at the first pointer-input node, since
 * `sharePointerInputWithSiblings()` is false by default. So a `Modifier.pointerInput`
 * or `clickable` added to *any* layer above it does not merely take priority — it
 * takes the DOWN and kills pinch and tap-to-focus **together**, silently. That is
 * exactly how tap-to-focus was lost: `PreviewView.onTouchEvent` never ran, so no
 * amount of [CameraController] configuration could have brought it back. Adding a
 * second `Box` for the tap would have reproduced the same bug.
 *
 * @param zoomRatio CameraX's *observed* zoom ratio, rendered as the fixed
 *   readout. Zoom requests go straight to [controller] from the pinch gesture, so
 *   this value is a read-back and never a request.
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
    showGuide: Boolean,
    showDetections: Boolean,
    zoomRatio: Float,
    referenceLayer: @Composable BoxScope.() -> Unit,
    hud: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        // Mask geometry. `resolveTapFocusPoint` recomputes this from px and must
        // stay in step with it — the bars are also the no-focus zone.
        val windowHeight = (maxWidth / aspect.ratioWtoH).coerceAtMost(maxHeight)
        val barHeight = (maxHeight - windowHeight) / 2

        // Tap-to-focus needs the PreviewView's own metering point factory, so the
        // instance has to escape the factory lambda. Cleared in onRelease: holding
        // a detached View past its AndroidView leaks the whole view hierarchy.
        var previewView by remember { mutableStateOf<PreviewView?>(null) }

        // Read through an updated state instead of keying the gesture on `aspect`.
        // The bar boundary still has to follow the 4:5 / 1:1 toggle, but a key
        // change restarts the pointer-input handler and the restart eats one
        // gesture: measured on SM-G970N, the first preview tap after every aspect
        // switch was dropped, 3/3 in both directions, while the steady state was
        // 3/3 fine. This keeps the value fresh without restarting anything.
        val currentAspect by rememberUpdatedState(aspect)

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller.camera
                    controller.bind(lifecycleOwner)
                    previewView = this
                }
            },
            onRelease = { previewView = null },
        )

        // The single pointer surface (see this function's KDoc). Both gestures are
        // hosted by one pointerInput node and run as sibling coroutines inside it;
        // a second Box for the tap would sit above this one and swallow the pinch.
        //
        // Keyed on `controller` only — see `currentAspect` above for why `aspect`
        // must not be a key.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(controller) {
                    coroutineScope {
                        // KNOWN GAP — the first preview gesture after a cold start is
                        // lost, every launch, on SM-G970N. Instrumented: on gesture #1
                        // this node sees a Release with no Press; on #2 it sees both.
                        // Compose starts a pointerInput coroutine lazily on the first
                        // event, so the DOWN that starts it is never observed and
                        // `awaitFirstDown` below never completes. Costs one tap per
                        // app launch; a fix means changing how PreviewView is bound
                        // (surfaceProvider instead of a CameraController), which is
                        // its own piece of work — not an improvised patch here.
                        launch {
                            detectTapGestures { offset ->
                                val factory = previewView?.meteringPointFactory
                                val point = resolveTapFocusPoint(
                                    tapX = offset.x,
                                    tapY = offset.y,
                                    paneWidth = size.width.toFloat(),
                                    paneHeight = size.height.toFloat(),
                                    ratioWtoH = currentAspect.ratioWtoH,
                                )
                                // On-device verification needs a signal of its own:
                                // a successful focus request logs nothing, and
                                // CameraX's own capture-request lines also fire for
                                // AE/AWB, so they cannot tell accept from reject.
                                // The three outcomes are logged apart because they
                                // fail for unrelated reasons — a letterbox tap is
                                // correct behaviour, a missing view is not.
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        TAG,
                                        "tapFocus (${offset.x.toInt()}, ${offset.y.toInt()}) " +
                                            "pane=${size.width}x${size.height} -> " +
                                            when {
                                                factory == null -> "NO_PREVIEW_VIEW"
                                                point == null -> "REJECTED"
                                                else -> "${point.x}, ${point.y}"
                                            },
                                    )
                                }
                                if (factory == null || point == null) return@detectTapGestures
                                controller.focusAt(factory, point.x, point.y)
                            }
                        }
                        // Keep zoom interaction on the preview itself, like the
                        // stock Galaxy camera. CameraX receives the continuous
                        // gesture value, while the controller rounds the applied
                        // ratio to 0.1x and clamps to the lens bounds. The readout
                        // below observes CameraX's actual ZoomState.
                        launch {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                                    controller.setZoom(controller.zoomRatio.value * zoomChange)
                                }
                            }
                        }
                    }
                },
        )

        referenceLayer()

        // Drawn under the aspect mask so boxes/guides never spill onto the bars.
        //
        // The §3-2 toggle is this branch and nothing else: the analysis chain
        // upstream is untouched, so hiding the guide costs a Canvas and changes
        // no measurement. `HorizonGate`'s hysteresis is remembered inside
        // CameraOverlay and so restarts when the guide comes back — the gate
        // just re-reads the current pitch, which is the right answer anyway.
        //
        // Scope note: this hides the guide overlay (bracket / silhouette /
        // horizon). The rule-of-thirds grid below is the framing grid every
        // stock camera carries independently of style guidance, so it stays.
        if (showGuide) {
            CameraOverlay(
                overlay = overlay,
                rollDeg = rollDeg,
                pitchDeg = pitchDeg,
                modifier = Modifier.fillMaxSize(),
                showDetections = showDetections,
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(barHeight).background(Charcoal950))
            Box(modifier = Modifier.fillMaxWidth().height(windowHeight)) {
                RuleOfThirds()
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Galaxy Camera-style readout: pinch on the preview to zoom;
                    // keep one fixed indicator instead of adding a second slider.
                    Text(
                        text = formatZoom(zoomRatio),
                        color = Sage,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(barHeight).background(Charcoal950))
        }

        hud()
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
) {
    Column(
        modifier = modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stats?.let { DebugHud(stats = it) }
        if (detection.isNotEmpty()) DetectionBadge(detection)
        TiltBadge(rollDeg = rollDeg, pitchDeg = pitchDeg, shake = shake)
        // The debug preset-cycling badge lived here until the §3-2 style chip
        // gave the same control a product path. Two ways to switch styles means
        // two things to keep in step, and the badge's label leaked the raw
        // index and the asset filename.
        if (BuildConfig.DEBUG) {
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
private fun formatZoom(value: Float): String =
    "%.1fx".format(value.coerceAtLeast(0f))
