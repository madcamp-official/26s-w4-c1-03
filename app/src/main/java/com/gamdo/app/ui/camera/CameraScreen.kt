package com.gamdo.app.ui.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
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
import com.gamdo.app.camera.PreviewStats
import com.gamdo.app.camera.SceneDetectorWarmup
import com.gamdo.app.camera.ShakeMeter
import com.gamdo.app.camera.TiltSensor
import com.gamdo.app.camera.ZoomBounds
import com.gamdo.app.camera.centerCropToRatio
import com.gamdo.app.camera.brightnessSample
import com.gamdo.app.camera.croppedForObjectDetection
import com.gamdo.app.camera.sceneFrameSignals
import com.gamdo.app.camera.scaledToMaxSide
import com.gamdo.app.camera.toAnalysisBitmap
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.GuideKpiRepository
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.data.preset.StylePreset
import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.guide.toSceneObservation
import com.gamdo.app.detect.toAnalysisFrame
import com.gamdo.app.guide.StyleTarget
import com.gamdo.app.guide.toStyleTarget
import com.gamdo.app.ui.components.moodBrush
import com.gamdo.app.ui.reference.CreateReferenceThumb
import com.gamdo.app.ui.reference.MyReferenceThumb
import com.gamdo.app.ui.reference.StripEntry
import com.gamdo.app.ui.reference.buildFilterStrip
import com.gamdo.app.ui.theme.Charcoal600
import com.gamdo.app.ui.theme.Charcoal950
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.Sage
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Sage is the single accent (D11-5); no opaque chromatic constant is defined here.
// GridLine is white at 28% alpha — a translucent neutral, not a hue.
private val GridLine = Color(0x47FFFFFF)
private const val TAG = "CameraScreen"

/**
 * Cold-start attribution. One line per one-off startup cost, so a launch trace
 * can be read without inferring durations from the gaps between CameraX's logs.
 */
private const val STARTUP_TAG = "CameraStartup"

/** 52dp thumbnail + 4dp gap + label, fixed so the preview pane is laid out once. */
private val STYLE_STRIP_HEIGHT = 78.dp

enum class CaptureAspect(val label: String, val ratioWtoH: Float) {
    RATIO_4_5("4:5", 4f / 5f),
    RATIO_1_1("1:1", 1f),
}

/**
 * The camera screen's claim on the process-scoped detection stack, released when
 * this composition goes away.
 *
 * A [RememberObserver] rather than the `DisposableEffect` this file uses
 * everywhere else, because the two differ in exactly the case that matters here.
 * `remember` runs during composition and the claim is taken there — it has to be,
 * since the executor and the config are needed further down the same composable —
 * but a composition can be **abandoned** before it is ever applied, which happens
 * on a fast navigate-away during first composition. An abandoned composition runs
 * no `onDispose`, so a claim taken in `remember` and dropped in `DisposableEffect`
 * would be stranded, and a stranded claim pins ~5MB of model for the life of the
 * process (see [com.gamdo.app.camera.DetectorWarmupGate.onTrimMemory], which
 * refuses to release while a consumer is attached). [onAbandoned] is the branch
 * that closes that hole.
 */
private class SceneDetectorLease(
    val value: SceneDetectorWarmup.Lease,
) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = SceneDetectorWarmup.releaseLease()
    override fun onAbandoned() = SceneDetectorWarmup.releaseLease()
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
 * @param referenceEntry leading element of the top bar's trailing zone. Left
 *   empty by [GamdoNavHost] as of O-10 (2026-07-29): the owner moved the AI 2
 *   entry point from here into the bottom filter strip (see
 *   [onCreateReference]/[hasActiveReference] below), so this slot has no current
 *   occupant. Kept rather than removed — deleting a public parameter needs a
 *   whole-tree grep and every caller notified (TEAM.md), and a slot costing
 *   nothing when empty is cheaper than that churn for a rename.
 * @param demoControls trailing element of the top bar — the demo-mode toggle
 *   (§7-3) mounts here. Kept out of the preview area so it can never overlap
 *   the guide.
 * @param onCreateReference O-10: the bottom strip's leading `+` icon opens the
 *   photo picker (AI 2 "내 필터 만들기"). No-op by default so a caller that does
 *   not wire AI 2 yet still compiles.
 * @param hasActiveReference whether a `내 레퍼런스` slot should trail the preset
 *   strip at all — a reference is a single local slot, not a list.
 * @param activeReferenceImageUri the picked photo behind the current session's
 *   reference, for the strip thumbnail. Null falls back to a plain background
 *   rather than a fabricated image (AGENTS §7-6) — e.g. right after a relaunch,
 *   before the session has re-picked anything.
 * @param activeReferenceStyle the resolved composition/color for the active
 *   reference. Only its composition half is consumed here (as a [StyleTarget]
 *   via [toStyleTarget]) — the color half is a result-screen concern (§5-2:
 *   "촬영 구도·촬영 후 색감에 적용").
 * @param onDeleteReference the reference slot's `×` badge (삭제).
 */
@Composable
fun CameraScreen(
    container: AppContainer,
    onOpenAlbum: () -> Unit,
    modifier: Modifier = Modifier,
    referenceLayer: @Composable BoxScope.() -> Unit = {},
    referenceEntry: @Composable () -> Unit = {},
    demoControls: @Composable () -> Unit = {},
    onCreateReference: () -> Unit = {},
    hasActiveReference: Boolean = false,
    activeReferenceImageUri: Uri? = null,
    activeReferenceStyle: ResolvedStyle? = null,
    onDeleteReference: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val controller = remember { CameraController(context) }
    // Neither built nor owned here — claimed.
    //
    // This block used to construct the detector stack inline, which put a 4.5MB
    // TFLite model load on the composition thread and left the preview black for
    // 6.4s. Moving it to the analysis executor fixed the preview but not the
    // guide: the build still did not *start* until this composable first ran, so
    // on SM-G970N the first detection landed 9.4s after launch. See
    // [SceneDetectorWarmup] for where the load starts now, how much of those 9.4s
    // that can actually recover (about a second — the model itself is unchanged),
    // and why the executor is adopted along with the resource rather than
    // separately.
    val lease = remember { SceneDetectorLease(SceneDetectorWarmup.lease(context)) }
    val analysisExecutor = lease.value.executor
    // Thresholds come from assets only (CFG-1); the data-class defaults are the
    // fallback for a missing/unparseable file. Parsed on the analysis thread when
    // the warm-up ran, on this thread when it did not.
    val guideConfig = lease.value.guideConfig
    val scene = lease.value.detector
    val viewModel = remember {
        // The §2-4 stopwatch writes through this sink; the ViewModel itself stays
        // free of android.util.Log so the stability harness can drive it on the JVM.
        CameraViewModel(config = guideConfig, logSink = { line -> Log.d(TAG, line) })
    }

    // The guide target comes from preset data (assets/presets.json = GET /presets),
    // never from values copied into this file.
    val catalogue = remember {
        traceColdStart("presets") {
            runCatching { container.presetRepository.loadBundledPresets() }.getOrDefault(emptyList())
        }
    }
    // §6-2 onboarding → camera: the style picked during onboarding selects the
    // initial preset. A failed read degrades to "nothing stored" rather than to
    // "still reading" — the latter would leave the guide target unpublished for
    // the rest of the session (see resolveStyleIndex's `loaded`).
    val onboardingStyle by produceState<OnboardingStyle?>(initialValue = null, container) {
        value = OnboardingStyle(
            runCatching { container.settingsRepository.getStylePresetId() }.getOrNull(),
            runCatching { container.settingsRepository.getRecommendedPresetIds() }.getOrDefault(emptyList()),
        )
    }
    // §6-2: the strip is ordered by the profile's recommendation rank.
    //
    // `style_preset_id` remains the first-run selection. The companion rank list
    // carries the rest of the profile recommendation without adding a Room column;
    // old installs simply return an empty list and keep catalogue order.
    val rankedPresetIds = remember(onboardingStyle) {
        onboardingStyle?.recommendedPresetIds.orEmpty()
    }
    val presets = remember(catalogue, rankedPresetIds) {
        orderByRank(catalogue, rankedPresetIds) { it.id }
    }
    val presetIds = remember(presets) { presets.map { it.id } }
    // §3-2 top bar. The in-session pick is deliberately *not* written back to
    // app_settings (TEAM.md §8): that key is the D4 personalisation profile, so
    // a relaunch must return to the onboarding style. It is held as an id, not
    // an index, because §6-2 reorders `presets` by recommendation rank — a stored
    // index would come to point at a style the user never chose.
    var sessionStyleId by rememberSaveable { mutableStateOf<String?>(null) }
    var stylePickerOpen by rememberSaveable { mutableStateOf(false) }
    var guideVisible by rememberSaveable { mutableStateOf(true) }
    // §5-2: the `내 레퍼런스` strip slot and a preset are mutually exclusive — one
    // active style at a time, same as the strip has always been. Selecting a
    // preset below clears this; it is not itself in `presetIds`/`sessionStyleId`
    // because a reference is not a StylePreset.
    var referenceSelected by rememberSaveable { mutableStateOf(false) }
    // Both id lists below are derived from the *reordered* `presets`, which is what
    // keeps the selection on the same style across a reorder. See
    // `StyleSelectionTest.재정렬해도 선택된 스타일은 그대로 선택되어 있다`.
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
    val previewStats by viewModel.previewStats.collectAsState()
    val previewFpsAvailability by viewModel.previewFpsAvailability.collectAsState()
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

    // O-13 (1): **a preset is colour. It does not reach the guide.**
    //
    // This effect used to read `activePreset` and publish
    // `activePreset.toStyleTarget()`. `StylePreset.toStyleTarget()` maps
    // `composition.subjectPosition` onto `subjectAnchorX` — `third_left` → 1/3,
    // `third_right` → 2/3 — and `GenericLayoutSynthesizer.transform` then re-centres
    // *every slot of the layout the AI had just chosen* on that anchor and rescales
    // it by `subjectScaleRange`. With `presets.json` as shipped, tapping 부드러운
    // 필름 threw the brackets into the right third and 밤거리 threw them into the
    // left third. That is the backwards behaviour the owner reported: the colour
    // control was the composition control, and the colour control was not a colour
    // control at all.
    //
    // A reference still publishes composition, because O-13 (2) says a reference
    // *may* carry one — as a candidate. Which candidate the overlay draws is
    // decided per frame by `GuideCompositionChoice`, not here.
    //
    // Keyed without `activePreset` on purpose: a preset switch must no longer reset
    // the alignment smoothing window or the display stabilizer, so the bracket now
    // stays exactly where it is while the strip scrolls under it.
    LaunchedEffect(referenceSelected, activeReferenceStyle) {
        val referenceTarget = activeReferenceStyle?.takeIf { referenceSelected }
        // Dropping a reference returns the guide to the neutral target rather than
        // leaving the last reference's anchor latched in — `StyleTarget()`'s
        // defaults are centre/0.35..0.55, which is what the scene analyser assumes.
        viewModel.setStyleTarget(referenceTarget?.toStyleTarget() ?: StyleTarget())
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

    // O-13 (1) / O-14: the preset's colour, on the preview, from the recipe the
    // editor renders the saved file with. Detaches itself if this device's GL
    // cannot run it, leaving the preview alive and uncoloured.
    PreviewColorBinding(
        controller = controller,
        presetId = activePreset?.id,
        aspect = aspect,
    )

    val actualZoom by controller.zoomRatio.collectAsState()
    // Which stops the lens can actually reach — 2c's `.5` is absent, not
    // disabled, on a device without an ultra-wide.
    val zoomBounds by controller.zoomBounds.collectAsState()
    var isFront by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // §3-3. The preview pane's aspect is the viewport crop the saved pixels go
    // through; SubjectProjection needs it to place the subject box in file space.
    var paneRatioWtoH by remember { mutableFloatStateOf(0f) }

    // §3-3: one session per (camera visit × style), opened as soon as a style is
    // active rather than lazily at the first shutter. Overlay events happen while
    // the user is still framing, so a session created at capture time would drop
    // every guide event that led up to the photo — which is exactly the sequence
    // 담당 B's metric script is trying to see.
    var sessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activePreset?.id) {
        val preset = activePreset ?: return@LaunchedEffect
        sessionId?.let { container.guideKpiRepository.endSession(it) }
        sessionId = container.guideKpiRepository.startSession(
            stylePresetId = preset.id,
            resolvedStyleJson = "{}",
        )
    }

    // §3-3 `session_guides`: overlay show/hide transitions.
    //
    // Collected in a coroutine rather than through collectAsState on purpose —
    // `lastFrame` updates at the analysis rate, and observing it as Compose state
    // would recompose this whole screen 12 times a second to write a row that only
    // changes when the overlay appears or disappears. `distinctUntilChanged` on the
    // flag is what keeps this to two rows per framing attempt instead of ~700 a minute.
    LaunchedEffect(sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        viewModel.lastFrame
            .map { it?.visible }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { visible ->
                container.guideKpiRepository.recordGuideShown(
                    sessionId = sid,
                    guideType = if (visible) {
                        GuideKpiRepository.GUIDE_TARGET_FRAME
                    } else {
                        GuideKpiRepository.GUIDE_HIDDEN
                    },
                    message = activePreset?.id.orEmpty(),
                )
            }
    }

    DisposableEffect(controller) {
        // Attach the analysis pipeline (§2-1) running ML Kit face + pose (§2-2).
        // FrameAnalyzer times onFrame, so the HUD reflects real detection cost.
        // Platform conversion stays here; the reduction happens in the ViewModel.
        controller.setAnalyzer(
            analysisExecutor,
            FrameAnalyzer(
                // W3-1 (CFG-1): the cadence is the asset's, not a literal's. See
                // `FeaturesConfigJson.analysisTargetFps` for why 12 is a ceiling
                // this device does not reach.
                targetFps = guideConfig.features.analysisTargetFps,
                onStats = viewModel::onStats,
                onFrame = onFrame@{ imageProxy ->
                    // Read before any conversion work. The detector is built on
                    // this very executor, so FIFO ordering has it ready before the
                    // first frame lands here — except during the build itself
                    // (frames now start arriving several seconds before the models
                    // finish loading) and if the build failed outright. Both mean
                    // "no analysis this frame", which costs one null check;
                    // FrameAnalyzer still closes the ImageProxy in its `finally`.
                    val detector = scene.get() ?: return@onFrame
                    viewModel.attachDetector(detector)
                    imageProxy.toAnalysisFrame { crop ->
                        // Called synchronously by MlKitObjectDetector before this
                        // analyzer returns and FrameAnalyzer closes imageProxy.
                        val bitmap = imageProxy.toAnalysisBitmap()
                        try {
                            bitmap.croppedForObjectDetection(crop)
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }?.let { frame ->
                        val result = detector.detect(frame)
                        val subjectBox = result.toSceneObservation().subjectBox
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
                            sceneSignals = imageProxy.sceneFrameSignals(subjectBox).copy(
                                viewportAspect = if (aspect == CaptureAspect.RATIO_1_1) {
                                    com.gamdo.app.guide.GuideViewportAspect.ONE_TO_ONE
                                } else {
                                    com.gamdo.app.guide.GuideViewportAspect.FOUR_TO_FIVE
                                },
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
            // The detector is **not** released here, and neither is the thread.
            // Leaving for the album and coming back is one tap each way, and this
            // used to charge the full 7.6s model load on the way back in. The
            // claim is dropped by [SceneDetectorLease] when this composable is
            // forgotten; the memory goes back on a trim, which — unlike onDispose
            // — knows whether the app is still on screen.
            viewModel.onAnalyzerDetached()
        }
    }

    // 2c has no floating picker and no separate aspect row: one top bar, the
    // preview, a persistent style strip, the shutter. Because the strip is always
    // present the preview pane never changes height, which also retires the
    // PreviewView surface-bleed workaround the picker needed.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Charcoal950),
    ) {
        CameraTopBar(
            guideVisible = guideVisible,
            onToggleGuide = { guideVisible = !guideVisible },
            aspect = aspect,
            onSelectAspect = { aspect = it },
            hudToggleEnabled = hudAvailable,
            onToggleHud = { showHud = !showHud },
            referenceEntry = referenceEntry,
            demoControls = demoControls,
        )

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
            zoomBounds = zoomBounds,
            onSelectZoom = { controller.setZoom(it) },
            onRescan = { viewModel.rescanLayout() },
            onRescanAt = { anchorX, anchorY -> viewModel.rescanLayoutAt(anchorX, anchorY) },
            onPaneRatio = { paneRatioWtoH = it },
            onPreviewFrameNs = viewModel::onPreviewFrame,
            onPreviewFpsAvailability = viewModel::onPreviewFpsAvailability,
            referenceLayer = referenceLayer,
            hud = {
                if (hudAvailable && showHud) {
                    CameraHud(
                        modifier = Modifier.align(Alignment.TopStart),
                        stats = stats,
                        previewStats = previewStats,
                        previewFpsAvailability = previewFpsAvailability,
                        detection = detection,
                        rollDeg = tilt.rollDeg,
                        pitchDeg = tilt.pitchDeg,
                        shake = shake,
                        guideDebug = guideDebug,
                    )
                }
            },
        )

        // 2c: the style strip is a permanent row between the preview and the
        // shutter, not something you open. Choosing a look is the screen's primary
        // job, so it costs no taps and its state is always readable.
        CameraStyleStrip(
            presets = presets,
            selectedIndex = styleIndex,
            // Session-only: this never reaches SettingsRepository (TEAM.md §8) —
            // that key is the D4 personalisation profile, so a relaunch returns to
            // the onboarding style.
            onSelect = { index ->
                sessionStyleId = presetIds.getOrNull(index)
                referenceSelected = false
            },
            onCreateReference = onCreateReference,
            hasActiveReference = hasActiveReference,
            referenceSelected = referenceSelected,
            activeReferenceImageUri = activeReferenceImageUri,
            onSelectReference = { referenceSelected = true },
            onDeleteReference = {
                referenceSelected = false
                onDeleteReference()
            },
            modifier = Modifier.padding(top = 12.dp),
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
                        // §3-3: read the analysis state *before* awaiting the
                        // capture. takePicture() takes a few hundred ms, during
                        // which the analyzer keeps publishing — awaiting first
                        // would record the frame the shutter produced rather than
                        // the one the user was looking at when they pressed it.
                        val frame = viewModel.lastFrame.value

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

                        val score = frame?.let { viewModel.matchScoreOf(it) }
                        container.captureRepository.saveCameraCapture(
                            bitmap,
                            buildCaptureSnapshot(
                                frame = frame,
                                matchScore = score,
                                sessionId = sessionId,
                                paneRatioWtoH = paneRatioWtoH,
                                targetRatioWtoH = aspect.ratioWtoH,
                                mirror = isFront,
                                tiltRecorded = tiltSensor.hasReading,
                            ),
                        )

                        // KPI, and never a reason to fail a capture — the repository
                        // swallows its own errors. `ended_at` is refreshed with every
                        // press so it tracks the last shot of the session; the screen
                        // can be left without warning, so there is no other moment
                        // that reliably closes one.
                        val sid = sessionId
                        if (sid != null && score != null) {
                            container.guideKpiRepository.recordFinalScore(sid, score)
                            container.guideKpiRepository.endSession(sid)
                        }
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
 * Top bar, per **2c** of `감도 화면 디자인.dc.html`: one centred pill saying the
 * personalisation is on, and nothing else competing with it.
 *
 * The design's bar is *only* that pill — style selection lives in the permanent
 * strip above the shutter ([CameraStyleStrip]), so the top of the screen has no job
 * beyond telling you the app is doing something on your behalf.
 *
 * Two controls the design does not draw are kept, in its own chip language, on
 * owner instruction: the guide on/off toggle (§3-2 requires it) and the 4:5 / 1:1
 * selector (D9 allows exactly those two ratios and §1-5 requires the choice).
 * Dropping them would make plan items unreachable rather than merely unstyled.
 *
 * Zones are a [Row] with the centre weighted, never a [Box] with alignments: a Box
 * lets the centre chip overlap the sides, and that overlap steals input as well as
 * being silent — with `부드러운 필름` (6 glyphs) the old style chip covered the
 * debug HUD chip on SM-G970N and ate its taps, while `밝은 리뷰` (4) left it alone.
 */
@Composable
private fun CameraTopBar(
    guideVisible: Boolean,
    onToggleGuide: () -> Unit,
    aspect: CaptureAspect,
    onSelectAspect: (CaptureAspect) -> Unit,
    hudToggleEnabled: Boolean,
    onToggleHud: () -> Unit,
    referenceEntry: @Composable () -> Unit,
    demoControls: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
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
            modifier = Modifier.weight(1f).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Charcoal600.copy(alpha = 0.9f))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Sage))
                Text(
                    text = "내 감도 적용 중",
                    color = OnDarkHigh,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AspectChip(selected = aspect, onSelect = onSelectAspect)
            // 재탐색은 프리뷰 우측 하단 버튼 하나로 일원화했다(오너 지정 위치,
            // 2026-07-28). 같은 동작을 상단 드롭다운에도 두면 두 곳이 갈라진다.
            //
            // ⚠️ 그 드롭다운이 D13의 **수동 레이아웃 선택**도 담고 있었으므로
            // 지금은 그 기능이 없다. `CameraViewModel.selectManualLayout`과
            // `availableManualLayouts`는 완성된 채 호출자 0으로 남아 있다 —
            // D13 미충족 상태이며 remain_plan 부록 C에 기록한다.
            referenceEntry()
            demoControls()
        }
    }
}

/** 4:5 / 1:1 only (D9), as a segmented chip in the top bar's language. */
@Composable
private fun AspectChip(selected: CaptureAspect, onSelect: (CaptureAspect) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Charcoal600.copy(alpha = 0.9f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CaptureAspect.entries.forEach { option ->
            val isSelected = option == selected
            Text(
                text = option.label,
                color = if (isSelected) OnSage else OnDarkMedium,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) Sage else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The permanent style strip, per 2c: a circular thumbnail per preset with its name
 * underneath, the active one ringed in sage.
 *
 * Round rather than square, and always on screen rather than behind a picker,
 * because choosing a look is the job this screen exists for. The thumbnails are the
 * presets’ own bundled images, so the strip shows what each look *is* rather than
 * only what it is called — which matters for `자연스러운 피드` and `밤거리`, whose
 * bracket geometry is identical and whose names are the only thing separating them.
 *
 * O-10 (2026-07-29) wraps this same row with the AI 2 entry point: a leading `+`
 * and, once a reference is active, a trailing `내 레퍼런스` slot — built by
 * [buildFilterStrip] and rendered with the shared thumb composables in
 * `ui/reference/ReferenceStrip.kt` so this screen and the result screen stay
 * pixel-consistent without duplicating the thumb shape twice.
 */
@Composable
private fun CameraStyleStrip(
    presets: List<StylePreset>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onCreateReference: () -> Unit,
    hasActiveReference: Boolean,
    referenceSelected: Boolean,
    activeReferenceImageUri: Uri?,
    onSelectReference: () -> Unit,
    onDeleteReference: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reserve the row even with nothing to put in it: `presets` arrives from disk a
    // frame or two after first composition, and a strip that appears late makes the
    // preview visibly jump. (It no longer *breaks* anything — the preview spill that
    // used to follow a pane resize was an unclipped interop view, fixed at the
    // AndroidView — but a fixed height is still the right shape for a row whose
    // contents are async.) The `+`/`내 레퍼런스` slots wait for the same frame as
    // the presets rather than appearing first and shifting everything right.
    if (presets.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().height(STYLE_STRIP_HEIGHT))
        return
    }
    // Presets carry their catalogue index through the wrapper so the click
    // handler and the selection highlight keep working against `presetIds`
    // exactly as before — `buildFilterStrip` itself does not know about indices.
    val strip = remember(presets, hasActiveReference) {
        buildFilterStrip(
            presets = presets.mapIndexed { index, preset -> index to preset },
            includeAiRestore = false,
            hasActiveReference = hasActiveReference,
        )
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex, referenceSelected, strip) {
        // Offset by one for the leading `+`; the reference slot, when present, is
        // always last (buildFilterStrip's contract).
        val target = when {
            referenceSelected && strip.lastOrNull() is StripEntry.MyReference -> strip.lastIndex
            selectedIndex >= 0 -> selectedIndex + 1
            else -> null
        }
        if (target != null) listState.animateScrollToItem(target)
    }
    LazyRow(
        modifier = modifier.fillMaxWidth().height(STYLE_STRIP_HEIGHT),
        state = listState,
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(strip) { _, entry ->
            when (entry) {
                is StripEntry.CreateReference -> CreateReferenceThumb(
                    shape = CircleShape,
                    size = 52.dp,
                    onClick = onCreateReference,
                )
                is StripEntry.AiRestore -> Unit // camera strip never includes this (O-10)
                is StripEntry.Preset -> {
                    val (index, preset) = entry.value
                    val isSelected = !referenceSelected && index == selectedIndex
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { onSelect(index) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.dp, Sage, CircleShape).padding(2.dp)
                                    } else {
                                        Modifier.padding(4.dp)
                                    },
                                )
                                .clip(CircleShape)
                                .background(Charcoal600),
                        ) {
                            AsyncImage(
                                model = "file:///android_asset/" + (preset.thumbnail ?: "presets/${preset.id}.jpg"),
                                contentDescription = preset.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            text = preset.displayName,
                            color = if (isSelected) Sage else OnDarkMedium,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
                is StripEntry.MyReference -> MyReferenceThumb(
                    shape = CircleShape,
                    size = 52.dp,
                    imageUri = activeReferenceImageUri,
                    selected = referenceSelected,
                    onSelect = onSelectReference,
                    onDelete = onDeleteReference,
                )
            }
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
    zoomBounds: ZoomBounds,
    onSelectZoom: (Float) -> Unit,
    onRescan: () -> Unit,
    onRescanAt: (Float, Float) -> Unit,
    onPaneRatio: (Float) -> Unit,
    /** One tick per delivered preview frame (§7-1). Main thread. */
    onPreviewFrameNs: (Long) -> Unit,
    onPreviewFpsAvailability: (PreviewFpsAvailability) -> Unit,
    referenceLayer: @Composable BoxScope.() -> Unit,
    hud: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        // Mask geometry. `resolveTapFocusPoint` recomputes this from px and must
        // stay in step with it — the bars are also the no-focus zone.
        val windowHeight = (maxWidth / aspect.ratioWtoH).coerceAtMost(maxHeight)
        val barHeight = (maxHeight - windowHeight) / 2

        // §3-3 needs the viewport aspect, and this is where it is exact. CameraX
        // attaches the PreviewView's viewport as the capture's cropRect, the
        // PreviewView fills this box, so the box's aspect *is* the first of the two
        // centre crops the saved pixels go through (see SubjectProjection). Read off
        // an outer `onSizeChanged` it would include this pane's padding and be wrong
        // by a few pixels — enough to misplace a subject box near an edge.
        val paneRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 0f
        LaunchedEffect(paneRatio) { onPaneRatio(paneRatio) }

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
            // clipToBounds, and it is load-bearing.
            //
            // FILL_CENTER scales PreviewView's content to *cover* the pane, so a
            // 4:3 sensor feed in a 4:5 window produces a child taller than its
            // parent — and Compose does not clip an interop view's overflow. The
            // spill showed up as a band of live camera image above and below the
            // preview, over the top bar's gap and over the style strip: 36px each
            // side here, 70px each side under the layout this replaced.
            //
            // It was first read as PreviewView's SurfaceView failing to follow a
            // resize, because the spill did change with the pane's height and was
            // always symmetric. It is not: switching to
            // ImplementationMode.COMPATIBLE (TextureView) changed nothing, and this
            // one modifier fixed it with the SurfaceView default left alone. The
            // earlier layout gymnastics to stop the pane ever resizing were
            // treating the symptom.
            modifier = Modifier.fillMaxSize().clipToBounds(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // Implementation mode is left at CameraX's PERFORMANCE default,
                    // i.e. a SurfaceView.
                    //
                    // A comment here used to claim the opposite — that the app runs
                    // COMPATIBLE (TextureView) to stop live camera image bleeding
                    // over the rows above and below the pane. It never did:
                    // `git log -S "implementationMode ="` over this package is
                    // empty. The comment arrived in 609f67b beside the real fix,
                    // `clipToBounds()` on this AndroidView, in the same commit whose
                    // neighbouring comment records that COMPATIBLE "changed nothing"
                    // for that bug. The claim mattered because W3-2's preview-rate
                    // measurement is only available in COMPATIBLE mode, so anyone
                    // reading it would have concluded the measurement was already
                    // reachable. See [MEASURE_PREVIEW_FPS].
                    if (MEASURE_PREVIEW_FPS) {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller.camera
                    controller.bind(lifecycleOwner)
                    previewView = this
                    // §7-1: the preview rate, measured rather than inferred from the
                    // analysis rate. Reports why it could not attach when it cannot.
                    onPreviewFpsAvailability(attachPreviewFrameProbe(onPreviewFrameNs))
                }
            },
            onRelease = {
                previewView = null
                onPreviewFpsAvailability(PreviewFpsAvailability.NOT_ATTACHED)
            },
        )

        // The single pointer surface (see this function's KDoc). Both gestures are
        // hosted by one pointerInput node and run as sibling coroutines inside it;
        // a second Box for the tap would sit above this one and swallow the pinch.
        // How those siblings are started is load-bearing — see installPreviewGestures.
        //
        // Keyed on `controller` only — see `currentAspect` above for why `aspect`
        // must not be a key.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(controller) {
                    // Both gestures are installed *undispatched*, and that is the whole
                    // fix for "the first preview gesture after a cold start is lost".
                    // Compose already starts this handler undispatched and only then
                    // delivers the event that started it; the two `launch`es that used
                    // to be here were dispatched, so neither had reached
                    // `awaitPointerEventScope` in time and the first DOWN was dropped —
                    // which is why gesture #1 was instrumented as a Release with no
                    // Press. See installPreviewGestures for the bytecode this is read
                    // off. Nothing is replayed: a lost tap beats a phantom one.
                    installPreviewGestures(
                        tap = {
                            detectTapGestures { offset ->
                                val factory = previewView?.meteringPointFactory
                                val point = resolveTapFocusPoint(
                                    tapX = offset.x,
                                    tapY = offset.y,
                                    paneWidth = size.width.toFloat(),
                                    paneHeight = size.height.toFloat(),
                                    ratioWtoH = currentAspect.ratioWtoH,
                                )
                                val sceneAnchor = resolveTapSceneAnchor(
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
                                if (factory == null || point == null || sceneAnchor == null) return@detectTapGestures
                                controller.focusAt(factory, point.x, point.y)
                                // A focus tap says where the photographer's subject
                                // is. It does not force-select one object; it restarts
                                // the same automatic group search inside that area.
                                onRescanAt(sceneAnchor.x, sceneAnchor.y)
                            }
                        },
                        // Keep zoom interaction on the preview itself, like the
                        // stock Galaxy camera. CameraX receives the continuous
                        // gesture value, while the controller rounds the applied
                        // ratio to 0.1x and clamps to the lens bounds. The readout
                        // below observes CameraX's actual ZoomState.
                        pinch = {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                                    controller.setZoom(controller.zoomRatio.value * zoomChange)
                                }
                            }
                        },
                    )
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
                ZoomStops(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    zoomRatio = zoomRatio,
                    bounds = zoomBounds,
                    onSelect = onSelectZoom,
                )
                RescanButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 12.dp, end = 18.dp),
                    onClick = onRescan,
                )
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
    previewStats: PreviewStats?,
    previewFpsAvailability: PreviewFpsAvailability,
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
        PreviewFpsHud(stats = previewStats, availability = previewFpsAvailability)
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

/**
 * Debug-only stopwatch for work that still runs on the composition thread.
 *
 * `presets.json` (4.5KB) is the main-thread asset read left in the cold-start
 * path once the detector build moved off it — `guide_config.json` followed the
 * build into [SceneDetectorWarmup]. It is almost certainly small, but "almost
 * certainly" is how the 6.4s went unattributed for a week, and it has no log line
 * of its own to be read off logcat timestamps.
 */
private inline fun <T> traceColdStart(label: String, block: () -> T): T {
    if (!BuildConfig.DEBUG) return block()
    val startNs = System.nanoTime()
    val result = block()
    Log.d(
        STARTUP_TAG,
        "%s %.1fms (%s)".format(
            label,
            (System.nanoTime() - startNs) / 1_000_000.0,
            Thread.currentThread().name,
        ),
    )
    return result
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

/**
 * Analysis cost and rate. **The `fps` here is the detection stack's**, which on
 * SM-G970N is 3-5 while the preview runs at 30 — the badge said only "fps" and was
 * read as the §7-1 preview target failing. Both numbers now carry their subject.
 */
@Composable
private fun DebugHud(stats: AnalysisStats, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "분석 %.1fms · %dfps · drop %d%%".format(
                stats.processMs,
                stats.fps,
                stats.dropRatePercent,
            ),
            color = Sage,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Preview rate (§7-1) — measured, or explicitly not.
 *
 * Never renders a number it did not measure. When no per-frame source could be
 * attached it says so and names the reason, because a 0 here would be
 * indistinguishable from a frozen preview.
 */
@Composable
private fun PreviewFpsHud(
    stats: PreviewStats?,
    availability: PreviewFpsAvailability,
    modifier: Modifier = Modifier,
) {
    val measured = availability == PreviewFpsAvailability.MEASURING && stats != null
    val text = when {
        measured -> "프리뷰 %dfps (%d프레임 / %.0fms)".format(
            stats!!.fps,
            stats.frames,
            stats.windowMs,
        )
        availability == PreviewFpsAvailability.MEASURING -> "프리뷰 측정 중"
        availability == PreviewFpsAvailability.UNAVAILABLE_PERFORMANCE_MODE ->
            "프리뷰 미측정 · SurfaceView(PERFORMANCE)"
        availability == PreviewFpsAvailability.UNAVAILABLE_ERROR -> "프리뷰 미측정 · 부착 실패"
        else -> "프리뷰 미측정 · 미부착"
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = if (measured) Sage else OnDarkMedium,
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
                text = "aligned=%s visible=%s · IoU %.2f · match %.2f · fixed=%s".format(
                    debug.aligned, debug.visible, debug.iou, debug.matchScore,
                    debug.fixedLayoutId ?: "none",
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

/**
 * The `.5 / 1x / 2x` stops from 2c, sitting at the bottom of the preview window.
 *
 * Stops the lens cannot reach are **absent, not disabled**: `.5` needs an
 * ultra-wide, and offering a control that silently clamps to 1x is worse than not
 * offering it. Which stop reads as active is decided by proximity to the live
 * `ZoomState`, so pinching between stops still highlights the nearest one instead
 * of leaving the row looking inert.
 */
@Composable
private fun ZoomStops(
    modifier: Modifier,
    zoomRatio: Float,
    bounds: ZoomBounds,
    onSelect: (Float) -> Unit,
) {
    val stops = remember(bounds) {
        listOf(0.5f, 1f, 2f).filter { it >= bounds.min - 1e-3f && it <= bounds.max + 1e-3f }
    }
    if (stops.isEmpty()) return
    val active = stops.minByOrNull { kotlin.math.abs(it - zoomRatio) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stops.forEach { stop ->
            val isActive = stop == active
            Box(
                modifier = Modifier
                    .size(if (isActive) 34.dp else 30.dp)
                    .clip(CircleShape)
                    .background(Color(0x99141614))
                    .then(if (isActive) Modifier.border(1.8.dp, Sage, CircleShape) else Modifier)
                    .clickable { onSelect(stop) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatZoomStop(stop),
                    color = if (isActive) Sage else OnDarkMedium,
                    fontSize = if (isActive) 11.sp else 10.5.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** `.5` / `1x` / `2x` — the design's labels, not a formatted ratio. */
private fun formatZoomStop(stop: Float): String =
    if (stop < 1f) ".5" else "${stop.toInt()}x"

/**
 * 재탐색 — asks the guide to look at the scene again (owner decision, 2026-07-28).
 *
 * Not in the 2c design. It is here because the auto layout resolver latches a
 * template within a few frames and never un-latches inside a session: on device
 * that shows up as the same `auto_2_row` slots hanging over every scene until the
 * user changes style. remain_plan O-3 had ruled a layout control out; the owner
 * reversed that after seeing the symptom.
 *
 * Placed on the zoom row at the preview's trailing edge, per the owner. The zoom
 * stops stay centred, so this reads as a sibling affordance rather than a fourth
 * stop — sharing a row with them but never sitting between them.
 *
 * The glyph is a miniature of the app's own target bracket, drawn rather than
 * typed. A refresh arrow would be the conventional choice, but D2 bans direction
 * arrows from this screen and the four corner marks say "composition" in the same
 * vocabulary the overlay already uses. Drawing it also means it cannot fail to
 * render on a device whose font lacks the codepoint.
 */
@Composable
private fun RescanButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0x99141614))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(15.dp)) {
            val stroke = 1.6.dp.toPx()
            val arm = size.minDimension * 0.34f
            for (right in listOf(false, true)) {
                for (bottom in listOf(false, true)) {
                    val x = if (right) size.width else 0f
                    val y = if (bottom) size.height else 0f
                    val dx = if (right) -arm else arm
                    val dy = if (bottom) -arm else arm
                    drawLine(OnDarkMedium, Offset(x, y), Offset(x + dx, y), stroke, StrokeCap.Round)
                    drawLine(OnDarkMedium, Offset(x, y), Offset(x, y + dy), stroke, StrokeCap.Round)
                }
            }
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
