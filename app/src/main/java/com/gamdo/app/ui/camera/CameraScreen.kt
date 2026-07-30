package com.gamdo.app.ui.camera

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.gamdo.app.BuildConfig
import com.gamdo.app.camera.AnalysisPauseGate
import com.gamdo.app.camera.AnalysisStats
import com.gamdo.app.camera.CameraController
import com.gamdo.app.camera.CapturePhase
import com.gamdo.app.camera.CaptureTrace
import com.gamdo.app.camera.FrameAnalyzer
import com.gamdo.app.camera.PreviewStats
import com.gamdo.app.camera.SceneDetectorWarmup
import com.gamdo.app.camera.ShakeMeter
import com.gamdo.app.camera.TiltSensor
import com.gamdo.app.camera.ZoomBounds
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
import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.guide.LayoutPreviewSlot
import com.gamdo.app.guide.LayoutTemplateSummary
import com.gamdo.app.guide.SceneSearchScope
import com.gamdo.app.guide.StyleTarget
import com.gamdo.app.guide.toStyleTarget
import com.gamdo.app.ui.components.moodBrush
import com.gamdo.app.ui.reference.CreateReferenceThumb
import com.gamdo.app.ui.reference.MyReferenceThumb
import com.gamdo.app.ui.reference.StripEntry
import com.gamdo.app.ui.reference.buildFilterStrip
import com.gamdo.app.ui.theme.Ink700
import com.gamdo.app.ui.theme.Ink800
import com.gamdo.app.ui.theme.Ink950
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextMid
import com.gamdo.app.ui.theme.TextLow
import com.gamdo.app.ui.theme.Amber
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Amber is the single accent (D11-5); no opaque chromatic constant is defined here.
// GridLine is white at 28% alpha — a translucent neutral, not a hue.
private val GridLine = Color(0x47FFFFFF)
private const val TAG = "CameraScreen"

/**
 * Cold-start attribution. One line per one-off startup cost, so a launch trace
 * can be read without inferring durations from the gaps between CameraX's logs.
 */
private const val STARTUP_TAG = "CameraStartup"

/**
 * Shutter→saved attribution. Shared with `data/CaptureRepository`, so
 * `grep CaptureLatency` reads the synchronous breakdown and the gallery export
 * that is no longer part of it as one sequence — same convention as
 * [STARTUP_TAG], which already spans two files.
 */
private const val LATENCY_TAG = "CaptureLatency"

/** 52dp thumbnail + 4dp gap + label, fixed so the preview pane is laid out once. */
private val STYLE_STRIP_HEIGHT = 78.dp

// ---- redesign dimensions (owner's final UI redesign, 2026-07-30) ---------------
//
// Every value here is the mock's, and they are named rather than inlined so a diff
// against `감도 리디자인.dc.html` is a list of numbers in one place. The redesign is
// explicit that the horizontal margin is 20dp everywhere **except the strip**, which
// is 18 — that one-off is real and is why there are two constants.

/** `height:50px` on the top bar. */
private val TOP_BAR_HEIGHT = 50.dp

/** `padding:0 20px` — the screen's horizontal margin. */
private val SCREEN_H_PADDING = 20.dp

/** `padding:0 18px` — the filter strip only. */
private val STRIP_H_PADDING = 18.dp

/** `gap:24px` between the top bar's right-hand icons. */
private val TOP_BAR_ICON_GAP = 24.dp

/** The redesign's floor for anything pressable; the glyphs inside are 19-21dp. */
private val MIN_TOUCH_TARGET = 44.dp

/** `border-radius:20px 20px 0 0` on both bottom sheets. */
private val SHEET_CORNER = 20.dp

/**
 * `rgba(244,241,234,0.85)` — the stroke every inactive glyph uses.
 *
 * [TextHi] at 85%, not [TextLow]. The redesign has one signal for state and it is
 * amber; dimming an inactive icon as well would give it two, and a 가이드 icon that
 * is both grey *and* not-amber reads as disabled rather than off. No new colour
 * constant (D11-5) — this is a token at an alpha.
 */
private val IconInactive = TextHi.copy(alpha = 0.85f)

/**
 * `background:rgba(10,10,11,0.45)` — the redesign's rule for a control that sits **on**
 * the photo: [Ink950] at 45%, the bottom of the spec's 45-62% band. Ghost or scrim
 * only; never an amber fill.
 */
private val OnPhotoScrim = Ink950.copy(alpha = 0.45f)

/** `background:rgba(255,255,255,0.08)` — the shutter row's ghost discs. */
private val OverPhotoDisc = Color.White.copy(alpha = 0.08f)

/** `border:1px rgba(255,255,255,0.16)` — the album tile's hairline. */
private val AlbumTileBorder = Color.White.copy(alpha = 0.16f)

/**
 * `background:rgba(232,195,139,0.16)` — the filter button while its sheet is up.
 *
 * The only amber *fill* the redesign allows besides the shutter, and it is allowed
 * because 16% of an accent is not a filled surface — the icon over it is still what
 * carries the state. "한 화면에 채워진 앰버 면은 1개까지" is about the shutter-sized
 * ones.
 */
private val FilterButtonActive = Amber.copy(alpha = 0.16f)

/** `gap:14px` between the filter strip's thumbnails. */
private val STRIP_ITEM_GAP = 14.dp

/**
 * The frame sheet's cells are 4:5 rectangles, not circles.
 *
 * The filter strip is round because a colour has no shape; a frame's own proportions are
 * the first thing the cell tells you, so these match the default capture ratio. 42×54 is
 * 0.78 — 4:5 to within a rounding — and keeps the sheet the same height as the filter
 * one, so switching between them does not move the shutter row.
 */
private val FRAME_THUMB_WIDTH = 42.dp
private val FRAME_THUMB_HEIGHT = 54.dp

/** 54 thumb + 5 gap + label, so the frame sheet stands as still as the filter sheet. */
private val FRAME_STRIP_HEIGHT = 80.dp

/** `gap:16px` between the filter and lens buttons. */
private val SHUTTER_ROW_GAP = 16.dp

/** 42×42 — album, filter and lens. The shutter is 74. */
private val BOTTOM_CONTROL_SIZE = 42.dp

/** How much later than the deadline [scheduleTeardownWatchdog] pokes. See there. */
private const val TEARDOWN_WATCHDOG_SLACK_MS = 50L

/**
 * The ratios the shutter offers.
 *
 * **D9-1 said "exactly two — no 16:9", and the owner reversed it** (2026-07-30:
 * "4:5 1:1 16:9 비율을 버튼을 클릭해서 바꿀수 있게해"). Mirrored by
 * [com.gamdo.app.edit.EditAspect], which has to gain the same value or the editor
 * silently re-crops a 9:16 photo back to 4:5 when it reopens it.
 *
 * ## 16:9 is 0.5625, not 1.778
 *
 * The camera is portrait-only. 4:5 (0.8) and 1:1 (1.0) are both at-or-taller than
 * square, so the third rung continues **downward**: 16:9 here is the tall 9:16 frame,
 * the shape of the phone screen, which is what a portrait camera means by that label.
 * A 1.778 landscape frame would be the only wide option in a portrait app and would
 * not match what the preview shows.
 *
 * The declaration order is that ramp — 0.8 → 1.0 → 0.5625 is the *cycle*, and
 * `1.0 → 0.5625` is the one big step in it. See [toggled].
 */
enum class CaptureAspect(val label: String, val ratioWtoH: Float) {
    RATIO_4_5("4:5", 4f / 5f),
    RATIO_1_1("1:1", 1f),
    RATIO_16_9("16:9", 9f / 16f),
    ;

    /**
     * The next ratio in the cycle — the redesign's top bar is a single label that
     * advances rather than the two-cell segmented control it replaced.
     *
     * `entries` arithmetic rather than a hand-written chain, so this survived the
     * two-to-three change without an edit and will survive the next one. It was
     * written this way while there were only two values and the reversal arrived a
     * day later; that is the argument for the general form, not a lucky guess.
     *
     * `CaptureAspectTest` pins the membership and the order, so adding a fourth is a
     * failing test rather than a silently different control.
     */
    fun toggled(): CaptureAspect = entries[(ordinal + 1) % entries.size]
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
 * @param onOpenReferenceDetail the reference slot's `⋯` badge — opens 내 감도 상세,
 *   where 취향 정교화 and 삭제 both live. It no longer deletes: see [MyReferenceThumb].
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
    onOpenReferenceDetail: () -> Unit = {},
    /**
     * P2 §5 나 찍어줘 — hand the layout on screen to a friend's browser.
     *
     * Nullable and defaulted to null so the control is *absent*, not disabled, in a
     * build that has not wired it: P2's own §5 says an unconnected QR feature is left
     * off the product surface rather than shown greyed out. The parameter takes the
     * layout rather than reading it from a shared holder because
     * `GuideLayoutState.Fixed` is what the QR screen turns into a `ShootPolicyV2`, and
     * handing over the value the user is actually looking at is the whole contract.
     */
    onOpenDelegatedShoot: ((GuideLayoutState) -> Unit)? = null,
) {
    val context = LocalContext.current
    // **The Activity's lifecycle, not this destination's.** Inside a
    // Navigation-Compose `composable { }`, `LocalLifecycleOwner` is the
    // NavBackStackEntry, and binding CameraX to it made navigating to the album
    // detach the use cases and abort the in-flight capture from underneath us —
    // `ImageCapture.onStateDetached`, 7ms after the tap, before any code of ours
    // ran. See [rememberCameraBindingOwner]; this file must not read
    // `LocalLifecycleOwner` again, and `CameraBindingOwnerTest` enforces that.
    val cameraLifecycleOwner = rememberCameraBindingOwner()
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
    // Change (1): the detection stack stands down for the length of a capture.
    // Remembered here rather than inside the analyzer's DisposableEffect so the
    // shutter and the analyzer hold the same one; a fresh composition gets a fresh
    // gate, which is RUNNING, so no pause can survive a trip to the album.
    val analysisPauseGate = remember { AnalysisPauseGate() }
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
    // The debug read-outs must stay unreachable in release, and must not be the
    // state a debug build *starts* in either (P1-C1). Both answers come from
    // [DebugHudGate] rather than from a boolean written here, because a decision
    // inside a @Composable cannot be unit-tested on the JVM and this one has
    // already regressed once: the initial value used to be `BuildConfig.DEBUG`, so
    // clearing app data left the HUD on screen before anyone asked for it.
    // (§7-2's developer-gesture entry point is wave 3; the top-bar chip is the
    // explicit act for now.)
    val hudAvailable = DebugHudGate.availableIn(BuildConfig.DEBUG)
    var showHud by rememberSaveable { mutableStateOf(DebugHudGate.initialVisible(BuildConfig.DEBUG)) }

    // Which sheet is up / whether the lasso is armed — exactly one of them, by
    // construction. See [CameraPanels]: every open/close rule the redesign states
    // lives there as a pure function, because a decision written inside a
    // @Composable cannot be tested on the JVM and "picking a filter closes the
    // sheet" is a one-character regression.
    //
    // Read back through `resolve` rather than trusted as stored: this survives
    // process death, so a bundle written by a debug build must not raise the
    // debug-only 설정 sheet in a build that has none.
    var storedMode by rememberSaveable { mutableStateOf(CameraOverlayMode.NONE) }
    val overlayMode = CameraPanels.resolve(storedMode, BuildConfig.DEBUG)

    // ---- 연필 (영역 선택) ------------------------------------------------------
    //
    // Pane-pixel points, deliberately **not** `rememberSaveable`: a half-drawn lasso is
    // not worth restoring across process death, and the pane it was measured against
    // may come back a different size.
    var lassoPath by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    // True from the finger lifting until the path has faded away. Both outcomes fade:
    // an accepted region hands over to the scene-search spinner, a rejected one leaves
    // the pencil armed with nothing started — §4 P2-1 forbids saying more than that
    // ("행동 지시 문구는 띄우지 않고").
    var lassoSettling by remember { mutableStateOf(false) }
    val lassoAlpha by animateFloatAsState(
        targetValue = if (lassoSettling) 0f else 1f,
        animationSpec = tween(AREA_SELECT_SETTLE_MS),
        // The path is cleared by the animation *finishing*, not by a timer. A `delay()`
        // here would be the only wait on this screen, and `CameraRedesignGuardTest` bans
        // one outright so that a capture countdown (D2-1) cannot arrive disguised as
        // something else.
        finishedListener = { value ->
            if (value == 0f) {
                lassoPath = emptyList()
                lassoSettling = false
            }
        },
        label = "lassoSettle",
    )
    val searchScope by viewModel.searchScope.collectAsState()

    // §3.1. Observed, never stored — see [ManualFrameSelection] for why the absence of
    // local state is what satisfies both "실패를 성공으로 표시하지 않는다" and "세션을
    // 나갔다 오면 자동 탐색으로 복귀한다".
    val layoutState by viewModel.layoutState.collectAsState()

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
                                // Three capture ratios, two guide viewports — and
                                // `GuideViewportAspect` is 담당 B's, so it does not grow
                                // to match (widening it would change every layout
                                // template's authored coordinates).
                                //
                                // 16:9 (0.5625) approximates to FOUR_TO_FIVE, and the
                                // arithmetic says so rather than the naming: |0.5625 −
                                // 0.8| = 0.2375 against |0.5625 − 1.0| = 0.4375, so 4:5
                                // is the nearer of the two by almost half. It is also
                                // the right *direction* — both are taller than square,
                                // and the layouts differ mainly in how much vertical
                                // room a slot may claim.
                                //
                                // The `!=` form is deliberate: the two non-square ratios
                                // share a branch, so a fourth ratio lands on the tall
                                // approximation by default rather than silently reading
                                // as square.
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
                pauseGate = analysisPauseGate,
            ),
        )
        onDispose {
            controller.clearAnalyzer()
            // **This line used to be the bug, and it is no longer unconditional.**
            //
            // `LifecycleCameraController.unbind()` is
            // `ProcessCameraProvider.unbindAll()`, and unbinding an `ImageCapture`
            // runs `abortImageCaptureRequests()` → `TakePictureManager.abortRequests()`,
            // which fails every request *still in flight* with
            // `ImageCaptureException(ERROR_CAMERA_CLOSED, "Camera is closed.")`. So
            // leaving the camera screen mid-capture destroyed the photo inside
            // CameraX, before the shutter coroutine could reach any code that saves
            // it — which is why making that coroutine uncancellable was necessary
            // and not sufficient. Confirmed on SM-G970N 2026-07-30 from both ends:
            // the camera-core 1.4.1 disassembly, and the device's own
            // `ImageCaptureException: Camera is closed. at
            // TakePictureManager.abortRequests(TakePictureManager.java:159)` with
            // zero `CaptureLatency` lines.
            //
            // With no capture in flight this runs right here, exactly as before.
            // With one, the release is handed to the shutter's `finally`. See
            // [CameraTeardownGate] for the two things that makes safe — a handed-off
            // release must not tear down the *next* screen's camera, and it must not
            // fail to happen at all.
            releaseCamera("dispose", cameraTeardownGate.screenDisposed { controller.unbind() })
            if (cameraTeardownGate.hasDeferredTeardown) scheduleTeardownWatchdog()
            // Resume guarantee 2 of 3 (see AnalysisPauseGate): the shutter's
            // `finally` cannot run if its coroutine was cancelled by this very
            // disposal, so the pause is released unconditionally here as well.
            analysisPauseGate.resumeAll()
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
            .background(Ink950),
    ) {
        CameraTopBar(
            guideVisible = guideVisible,
            onToggleGuide = { guideVisible = !guideVisible },
            aspect = aspect,
            onToggleAspect = { aspect = aspect.toggled() },
            // 나 찍어줘. Null when the host has not wired the hand-off, which is what
            // keeps the control out of the bar entirely rather than in it and inert.
            onOpenDelegatedShoot = onOpenDelegatedShoot?.let { open ->
                {
                    // "자동 레이아웃이 없으면 QR을 조용히 생성하지 않는다" (P2 §5). The
                    // policy is built *from* the fixed template, so handing over a
                    // Searching state would either send a wrong framing or land the QR
                    // screen on its 넘길 구도가 없어요 dead end for a reason the user
                    // cannot see from the camera.
                    //
                    // So the no-layout branch opens the frame sheet instead. That is
                    // P2's "기본 프레임을 먼저 고르게 하거나 취소할 수 있어야 한다" with
                    // the control that already exists for exactly this — 12 manual
                    // frames, and dismissing the sheet is the cancel. Once a frame is
                    // fixed the same button hands it over.
                    when (val layout = layoutState) {
                        is GuideLayoutState.Fixed -> open(layout)
                        GuideLayoutState.Searching ->
                            storedMode = CameraPanels.toggled(overlayMode, CameraOverlayMode.FRAME_SHEET)
                    }
                }
            },
            settingsAvailable = CameraPanels.settingsSheetAvailable(BuildConfig.DEBUG),
            onToggleSettings = {
                storedMode = CameraPanels.toggled(overlayMode, CameraOverlayMode.SETTINGS_SHEET)
            },
            areaSelectArmed = CameraPanels.areaSelectArmed(overlayMode),
            onToggleAreaSelect = {
                val next = CameraPanels.toggled(overlayMode, CameraOverlayMode.AREA_SELECT)
                // Leaving the mode by re-tapping the button is the screen's one cancel
                // gesture (see CameraPanels). What it owes the guide depends on whether a
                // lasso search was ever accepted: `cancelPolygonLayoutSearch` resets the
                // alignment engine and the stabilizer, so calling it unconditionally
                // would destroy the layout of a user who armed the pencil, drew nothing
                // and changed their mind.
                if (next != CameraOverlayMode.AREA_SELECT) {
                    val exit = AreaSelectExit.forExit(searchScope is SceneSearchScope.Polygon)
                    if (exit == AreaSelectExit.CANCEL_POLYGON_SEARCH) {
                        viewModel.cancelPolygonLayoutSearch()
                    }
                    lassoPath = emptyList()
                    lassoSettling = false
                }
                storedMode = next
            },
            referenceEntry = referenceEntry,
            demoControls = demoControls,
        )

        CameraPreviewPane(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            controller = controller,
            cameraLifecycleOwner = cameraLifecycleOwner,
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
            showDetections = DebugHudGate.visible(BuildConfig.DEBUG, showHud),
            // Pinch-to-zoom lives inside the pane and drives CameraX directly;
            // this is the read-back of CameraX's actual ZoomState, not a request.
            zoomRatio = actualZoom,
            zoomBounds = zoomBounds,
            onSelectZoom = { controller.setZoom(it) },
            onRescan = { viewModel.rescanLayout() },
            // What the guide engine is holding, not what the sheet asked for. See
            // [ManualFrameSelection]: `selectManualLayout` returning true means "the id
            // resolves", and the state change lands on the analysis thread afterwards —
            // so the only honest source for "a manual frame is active" is `layoutState`.
            frameSheetActive = ManualFrameSelection.frameButtonActive(layoutState),
            onToggleFrameSheet = {
                storedMode = CameraPanels.toggled(overlayMode, CameraOverlayMode.FRAME_SHEET)
            },
            onRescanAt = { anchorX, anchorY -> viewModel.rescanLayoutAt(anchorX, anchorY) },
            onPaneRatio = { paneRatioWtoH = it },
            onPreviewFrameNs = viewModel::onPreviewFrame,
            onPreviewFpsAvailability = viewModel::onPreviewFpsAvailability,
            referenceLayer = referenceLayer,
            // "버튼 바깥 탭 또는 버튼 재탭으로 닫는다". The layer that receives that
            // tap only exists while a sheet is up — see the parameter's KDoc for why
            // that condition is load-bearing rather than tidiness.
            dismissSheetOnTap = CameraPanels.sheetVisible(overlayMode),
            onDismissSheet = { storedMode = CameraPanels.scrimTapped(overlayMode) },
            areaSelectArmed = CameraPanels.areaSelectArmed(overlayMode),
            lassoPath = lassoPath,
            lassoAlpha = lassoAlpha,
            onLassoStart = {
                // A new stroke replaces the previous one outright — "다시 그리기는 …
                // 새 경로를 그리는 방식"이고, 두 경로를 동시에 들고 있을 이유가 없다.
                lassoSettling = false
                lassoPath = emptyList()
            },
            onLassoPoint = { x, y, minStepPx ->
                lassoPath = AreaSelectPath.appended(lassoPath, x, y, minStepPx)
            },
            onLassoFinish = { paneWidthPx, paneHeightPx ->
                // The analysis frame's dimensions come from the overlay state, which is
                // the only place they are published. Zero until the first frame lands,
                // and `submitLassoRegion` treats that as "nothing to search" rather than
                // building a PreviewGeometry whose `require` would throw.
                val accepted = submitLassoRegion(
                    points = lassoPath,
                    paneWidthPx = paneWidthPx,
                    paneHeightPx = paneHeightPx,
                    analysisWidth = overlay?.frameWidth ?: 0,
                    analysisHeight = overlay?.frameHeight ?: 0,
                    mirror = isFront,
                    submit = viewModel::rescanLayoutInPolygon,
                )
                // Both outcomes fade the path. Accepted also leaves the mode, because
                // §4 P2-1 puts redrawing behind re-arming the button ("다시 그리기는
                // 영역 선택 버튼을 다시 활성화한 뒤"); rejected keeps the pencil armed
                // so the next attempt costs no extra tap.
                lassoSettling = true
                if (accepted) storedMode = CameraOverlayMode.NONE
            },
            hud = {
                if (DebugHudGate.visible(BuildConfig.DEBUG, showHud)) {
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

        // The sheet slot. A **sibling** of the shutter row in this Column, never a
        // layer over it — that is why "시트가 열린 상태에서도 셔터는 계속 쓸 수 있다"
        // holds structurally instead of depending on where the sheet's top edge
        // happens to land. The preview (`weight(1f)`) is what gives up the space.
        CameraSheetSlot(visible = overlayMode == CameraOverlayMode.SETTINGS_SHEET) {
            CameraSettingsSheet(
                hudVisible = DebugHudGate.visible(BuildConfig.DEBUG, showHud),
                onToggleHud = { showHud = !showHud },
            )
        }

        // The strip is **in the sheet now**, not on screen. O-13 made a preset a
        // colour, and six colour swatches permanently occupying the bottom of a camera
        // is the "기능을 모두 펼쳐놓는" arrangement the redesign undoes: the preview is
        // what the screen is for.
        //
        // Picking one does not close it — see [CameraPanels.filterPicked]. That is why
        // choosing between two looks still costs one tap each rather than three.
        // §3.1. The list is read from the ViewModel, which reads it from
        // `LayoutTemplateCatalog` — no template id is written down in this file.
        CameraSheetSlot(visible = overlayMode == CameraOverlayMode.FRAME_SHEET) {
            CameraFrameSheet(
                layouts = viewModel.availableManualLayouts,
                activeLayoutId = ManualFrameSelection.activeManualLayoutId(layoutState),
                onSelectLayout = { id ->
                    // The return value is deliberately not stored. It means "the id
                    // resolves", and the sheet already only offers ids that came from the
                    // catalogue — so what it can actually report is a bug on our side, not
                    // a user-visible outcome. The *rendered* state comes from
                    // `layoutState` on the next frame either way.
                    viewModel.selectManualLayout(id)
                },
                // §3.1's "자동으로 돌아가기" — the same call the 재탐색 button makes,
                // because it is the same act.
                onSelectAuto = { viewModel.rescanLayout() },
            )
        }

        CameraSheetSlot(visible = overlayMode == CameraOverlayMode.FILTER_SHEET) {
            CameraFilterSheet(
                presets = presets,
                selectedIndex = styleIndex,
                // Session-only: this never reaches SettingsRepository (TEAM.md §8) —
                // that key is the D4 personalisation profile, so a relaunch returns to
                // the onboarding style.
                onSelect = { index ->
                    sessionStyleId = presetIds.getOrNull(index)
                    referenceSelected = false
                    storedMode = CameraPanels.filterPicked(overlayMode)
                },
                onCreateReference = onCreateReference,
                hasActiveReference = hasActiveReference,
                referenceSelected = referenceSelected,
                activeReferenceImageUri = activeReferenceImageUri,
                onSelectReference = {
                    referenceSelected = true
                    storedMode = CameraPanels.filterPicked(overlayMode)
                },
                onOpenReferenceDetail = {
                    referenceSelected = false
                    onOpenReferenceDetail()
                },
            )
        }

        CameraBottomBar(
            lastThumb = lastThumb,
            capturing = capturing,
            // The shutter turns amber on exactly the condition the bracket does —
            // one predicate, two consumers. Passing `overlay?.guide?.aligned` straight
            // through would light the shutter while the bracket was off screen (guide
            // toggled off, still searching, or a manual layout in charge).
            aligned = AlignmentAmber.isOn(overlay, guideShown = guideVisible),
            isFront = isFront,
            filterSheetOpen = overlayMode == CameraOverlayMode.FILTER_SHEET,
            // "적용 중", so the *active* style being a reference — not the mere existence
            // of one. Hidden while the sheet is open because the button is already amber
            // then (시안 05 draws no dot).
            showMoodDot = referenceSelected && overlayMode != CameraOverlayMode.FILTER_SHEET,
            onOpenAlbum = onOpenAlbum,
            onToggleFilterSheet = {
                storedMode = CameraPanels.toggled(overlayMode, CameraOverlayMode.FILTER_SHEET)
            },
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
                    // One line per capture, DEBUG only. Null in release, which is
                    // what keeps `CaptureTrace` out of the shipped shutter path.
                    val trace = if (BuildConfig.DEBUG) CaptureTrace() else null
                    // Two claims, released together in the same `finally`. This one
                    // is first so that `pause()` keeps the position its KDoc pins;
                    // neither call can throw, so nothing can be lost between them.
                    //
                    // What it claims: for as long as this capture runs, the camera
                    // is not released even if the screen goes away. Held here rather
                    // than around `capture()` alone because the abort window is the
                    // whole CameraX request, and the request has already been issued
                    // by the time `capture()` suspends.
                    val teardownToken = cameraTeardownGate.captureStarted()
                    // The statement immediately before `try`, with nothing between
                    // them, so its `finally` is unconditionally paired with it:
                    // `finally` runs on success, on throw and on cancellation
                    // alike. Inside the coroutine rather than beside `launch` for
                    // the same reason — a body that never starts cannot pause.
                    val pauseToken = analysisPauseGate.pause()
                    try {
                        // §3-3: read the analysis state *before* awaiting the
                        // capture. takePicture() takes a few hundred ms, during
                        // which the analyzer keeps publishing — awaiting first
                        // would record the frame the shutter produced rather than
                        // the one the user was looking at when they pressed it.
                        //
                        // Outside the uncancellable region below because it cannot
                        // block: it is a `.value` read of a StateFlow.
                        val frame = viewModel.lastFrame.value

                        // ── The photo's life. Nothing in here may be cancelled. ──
                        //
                        // `scope` is `rememberCoroutineScope()`, so leaving the
                        // camera screen cancels this coroutine. Pressing the shutter
                        // and then tapping 앨범 0.3s later therefore threw the photo
                        // away at whichever suspension point it happened to be
                        // sitting on — usually inside `capture()`, since
                        // `CapturePhase.CAMERA_X` measures 290-1613ms. A user who
                        // pressed the shutter asked for a photo; not waiting for it
                        // is not a reason to discard it.
                        //
                        // The region ends where the photo stops depending on this
                        // screen. `saveCameraCapture` writes the private file
                        // (`CapturePhase.APP_FILE`, "the photo is safe") and the
                        // `captures` row in one call, and the row is inside the
                        // region rather than after it on purpose: a file with no row
                        // is invisible to the album and the editor, which is not a
                        // saved photo in any sense the user would recognise.
                        //
                        // Everything else in here is between those two points and
                        // must not be reordered out — the thumbnail because moving
                        // it would print `CapturePhase.CROP` after ENCODE/ROW and
                        // silently corrupt every latency breakdown, and the two log
                        // lines because they are the only evidence that any of this
                        // ran. In particular the `capture ...` line is what the
                        // on-device check for this fix reads.
                        //
                        // `NonCancellable` replaces the Job, not the dispatcher, so
                        // this still runs on Main — which `capture()` requires:
                        // it reaches CameraX's takePicture(), which asserts the main
                        // thread outright (Threads.checkMainThread). It is already
                        // off-main where it matters, since the decode/rotate runs on
                        // the callback executor it passes in. Wrapping the call in
                        // withContext(Default) bought nothing and threw
                        // IllegalStateException on every shutter press, losing the
                        // shot; three presses, three "촬영에 실패했어요" toasts,
                        // verified on SM-G970N.
                        val score = withContext(NonCancellable) {
                            // The aspect crop is part of the capture's single
                            // transform now, not a fifth full-resolution copy
                            // afterwards — see `captureGeometryFor`. `capture()`
                            // already runs its work on Dispatchers.Default inside
                            // CameraX's callback.
                            val captured = controller.capture(trace, aspect.ratioWtoH)
                            val bitmap = captured.bitmap
                            // The thumb stays a separate downscale: it is a different
                            // size from the photo, so it cannot share the same pass.
                            // It is small and it means no full-resolution bitmap is
                            // retained for a 44dp preview.
                            lastThumb = withContext(Dispatchers.Default) {
                                bitmap.scaledToMaxSide(256)
                            }
                            trace?.mark(CapturePhase.CROP)

                            // Geometry evidence line, DEBUG only. **Measurement, not
                            // behaviour** — nothing reads this back.
                            //
                            // `saved.width` against `pane` says whether CameraX's
                            // viewport is cropping the width. **It has answered both
                            // ways on the same device**, 2026-07-30 on SM-G970N, at
                            // 4:5:
                            //
                            //   before the redesign merges  3024×3780  full sensor
                            //   after them (5 shots)        2610×3263  = 4032 × 0.6475
                            //
                            // 2610 is the pane's aspect exactly, so the preview mask
                            // being opaque bars over a pane-filling PreviewView is what
                            // decides it. Do not treat either number as settled without
                            // re-reading this line on the build in hand.
                            //
                            // That instability is what retired `SubjectProjection`'s
                            // two-ratio inference: it was exact in the second state and
                            // out by up to 0.084 of the frame in the first, with no test
                            // able to say which was live. It reads `captured.geometry`
                            // now — the crop that ran — so `pane` below is measurement
                            // only and nothing reads it back.
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    LATENCY_TAG,
                                    "geometry pane=%.4f target=%.4f saved=%dx%d (%.4f)".format(
                                        paneRatioWtoH,
                                        aspect.ratioWtoH,
                                        bitmap.width,
                                        bitmap.height,
                                        bitmap.width.toFloat() / bitmap.height.toFloat(),
                                    ),
                                )
                            }

                            val score = frame?.let { viewModel.matchScoreOf(it) }
                            container.captureRepository.saveCameraCapture(
                                bitmap,
                                buildCaptureSnapshot(
                                    frame = frame,
                                    matchScore = score,
                                    sessionId = sessionId,
                                    geometry = captured.geometry,
                                    bufferWidth = captured.bufferWidth,
                                    bufferHeight = captured.bufferHeight,
                                    tiltRecorded = tiltSensor.hasReading,
                                ),
                                trace = trace,
                            )
                            // Logged here rather than in `finally`: this is the point
                            // the user's photo is safe and the shutter's job is done.
                            // The gallery copy is still running and reports itself on
                            // the same tag when it finishes.
                            trace?.let {
                                Log.d(
                                    LATENCY_TAG,
                                    "capture ${it.format()} pause=" +
                                        (if (analysisPauseGate.isEnabled) "on" else "off") +
                                        " watchdogTrips=${analysisPauseGate.watchdogTrips}",
                                )
                            }
                            score
                        }

                        // Outside the region: the photo is already saved, and this is
                        // a session aggregate rather than the shot's own record. The
                        // score itself is not at risk — `buildCaptureSnapshot` wrote
                        // it into the `captures` row above — so what a navigate-away
                        // costs here is `sessions.final_match_score` for that session,
                        // not any part of the photo.
                        //
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
                    } catch (t: CancellationException) {
                        // The screen was left. Everything the user asked for already
                        // happened inside the region above; what was cancelled is the
                        // KPI tail. Rethrown rather than swallowed so this coroutine
                        // still completes as cancelled, and — the visible half —
                        // ahead of the generic catch so it cannot reach the toast.
                        //
                        // It used to. `catch (t: Throwable)` catches
                        // CancellationException too, so walking away from the camera
                        // mid-capture reported "촬영에 실패했어요" for a capture that
                        // had not failed. Only the two clauses together are correct:
                        // a real failure must still say so (W2-2 — silence about a
                        // failure is its own defect), and a cancellation must not.
                        throw t
                    } catch (t: Throwable) {
                        Log.e(TAG, "capture failed", t)
                        Toast.makeText(context, "촬영에 실패했어요", Toast.LENGTH_SHORT).show()
                    } finally {
                        // Resume guarantee 1 of 3. Ahead of `capturing = false` so
                        // that no ordering of the two can leave the shutter usable
                        // again while the guide is still stood down.
                        analysisPauseGate.resume(pauseToken)
                        // The screen may have been disposed while this capture was
                        // in flight, in which case `onDispose` left the camera to
                        // us. Null in the ordinary case — and null too when someone
                        // else already spent the release, which is what keeps a late
                        // arrival from unbinding a camera that is back on screen.
                        releaseCamera("shutter", cameraTeardownGate.captureFinished(teardownToken))
                        capturing = false
                    }
                }
            },
        )
    }
}

/**
 * Runs a camera release [CameraTeardownGate] handed back, or does nothing when it
 * handed back nothing.
 *
 * ## Why every release goes through one function
 *
 * The camera is bound to the **Activity** (see [rememberCameraBindingOwner]), so
 * leaving the camera screen no longer detaches it. That was previously a safety
 * net underneath every mistake in this file: whatever we forgot, navigating away
 * cleaned it up. It is gone, and what replaces it is an argument that has to hold
 * link by link:
 *
 *  1. `bind()` is called from the `AndroidView` factory and nowhere else, so a
 *     binding exists only for a composition that was **applied** — an abandoned
 *     one never creates the node. (`CameraBindingOwnerTest` pins both halves.)
 *  2. An applied composition always runs its `DisposableEffect`'s `onDispose`.
 *  3. `onDispose` always asks the gate, never unbinds directly
 *     (`CameraTeardownGateTest`).
 *  4. The gate either hands the release back immediately or records a deferral —
 *     never neither, and a recorded deferral is always visible as
 *     [CameraTeardownGate.hasDeferredTeardown].
 *  5. A deferral always gets [scheduleTeardownWatchdog], which fires on the main
 *     looper — which runs while the Activity is stopped, so backgrounding cannot
 *     postpone it.
 *  6. And any deferral still outstanding is spent by the next
 *     [CameraTeardownGate.releaseBeforeBind].
 *
 * So every binding is released within about four seconds of its screen going
 * away. This function closes the one silent gap left in that chain: a release
 * that throws. Logged rather than rethrown because the alternative is crashing
 * the app during a navigation, and step 6 still recovers — the next time the user
 * opens the camera, the stale binding is torn down before the new one is made.
 */
private fun releaseCamera(where: String, release: (() -> Unit)?) {
    if (release == null) return
    runCatching(release).onFailure {
        Log.e(TAG, "camera release failed at '$where'; camera stays open until the next bind", it)
    }
}

/**
 * Pokes a deferred camera release once its deadline has passed.
 *
 * `AnalysisPauseGate` gets its expiry for free — the analysis thread asks
 * `isPaused()` on every frame, so a stuck pause is noticed by the next one. This
 * gate has no such visitor: `clearAnalyzer()` has already run by the time anything
 * is deferred, so there is no frame loop left to ask. Hence one delayed post,
 * which is the only timer in the arrangement.
 *
 * Idempotent and cheap to be wrong about: if the capture finished normally the
 * poke finds nothing and does nothing. Logged at `w` rather than behind
 * `BuildConfig.DEBUG` because a non-zero count is a defect — a capture that
 * neither succeeded nor failed — and the same reasoning as
 * `AnalysisPauseGate.watchdogTrips` applies: it should be visible in whatever
 * build it happens in, not inferred later from a camera indicator that stayed on.
 */
private fun scheduleTeardownWatchdog() {
    Handler(Looper.getMainLooper()).postDelayed(
        {
            val expired = cameraTeardownGate.releaseIfExpired()
            if (expired != null) {
                Log.w(
                    TAG,
                    "camera teardown waited ${cameraTeardownGate.maxDeferMs}ms for a capture " +
                        "that never finished; releasing anyway " +
                        "(expiredDefers=${cameraTeardownGate.expiredDefers})",
                )
            }
            releaseCamera("watchdog", expired)
        },
        // The gate's deadline is nanoTime-based and this post is uptimeMillis-based.
        // Landing a hair *early* would find the deferral unexpired and never come
        // back, so the poke is deliberately late by a margin larger than the two
        // clocks can disagree by.
        cameraTeardownGate.maxDeferMs + TEARDOWN_WATCHDOG_SLACK_MS,
    )
}

/**
 * Top bar, per the owner's final redesign (시안 03): **four ghost icons and nothing
 * else** — 설정 on the left, 비율 · 직접 지정 · 가이드 grouped on the right.
 *
 * ```
 * height 50dp · padding horizontal 20dp · space-between · right group gap 24dp
 * ```
 *
 * Two things left, and the design says where each went:
 *
 *  - the `내 감도 적용 중` **pill is gone**. Mood state is now the dot on the filter
 *    button (see [CameraBottomBar]) — a pill saying the app is doing something on
 *    your behalf costs a quarter of the bar to say what a 6px dot says.
 *  - the `HUD` chip **moved into the 설정 sheet**. It is the sheet's only content,
 *    which is why [CameraPanels.settingsSheetAvailable] delegates to the HUD's own
 *    gate: in a demo build the sheet has nothing to hold, so the bar drops to three
 *    icons rather than opening an empty sheet.
 *
 * **No element here has a background.** The bar's controls are ghost — stroke only.
 * The `White 8%` discs belong to the shutter row, which sits over the photo; this bar
 * does not. Every glyph is [StrokeIcon] over the mock's own path data.
 *
 * Amber is used for exactly one thing: **active**. `#E8C38B` stroke on 가이드 when
 * the guide is on, `TextHi` at 85% for everything else including the ratio label —
 * the design does not highlight the selected ratio, so neither does this.
 *
 * @param referenceEntry kept, and empty at every call site since O-10 — see
 *   [CameraScreen]'s KDoc. An occupant would make this bar five icons, which the
 *   redesign does not draw; it stays only because deleting a public parameter costs
 *   a whole-tree grep.
 * @param demoControls §7-3's demo toggle, deliberately outside the design.
 */
@Composable
private fun CameraTopBar(
    guideVisible: Boolean,
    onToggleGuide: () -> Unit,
    aspect: CaptureAspect,
    onToggleAspect: () -> Unit,
    settingsAvailable: Boolean,
    onToggleSettings: () -> Unit,
    areaSelectArmed: Boolean,
    onToggleAreaSelect: () -> Unit,
    /** 나 찍어줘, or null to leave the control out of the bar. */
    onOpenDelegatedShoot: (() -> Unit)?,
    referenceEntry: @Composable () -> Unit,
    demoControls: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = SCREEN_H_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Double-gated like CameraHud: BuildConfig.DEBUG is a compile-time constant,
        // so this control does not exist in a release build whatever the caller says.
        if (BuildConfig.DEBUG && settingsAvailable) {
            BarIconButton(onClick = onToggleSettings, contentDescription = "설정") {
                StrokeIcon(
                    pathData = CameraIconPaths.SETTINGS,
                    viewBox = 22f,
                    size = 21.dp,
                    strokeWidth = 1.6f,
                    color = IconInactive,
                    // The knobs are filled with the surface behind them, which is what
                    // makes each rail read as a slider track passing under its knob.
                    dots = CameraIconPaths.settingsKnobs(background = Ink950),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TOP_BAR_ICON_GAP),
        ) {
            // 4:5 / 1:1 only (D9-1). One label that toggles, not a segmented pair;
            // the design draws no selected/unselected distinction on it at all.
            Box(
                modifier = Modifier
                    .size(MIN_TOUCH_TARGET)
                    .clickable(onClick = onToggleAspect),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = aspect.label,
                    color = IconInactive,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.03.em,
                )
            }

            // 직접 지정 — arms the lasso. Amber stroke while armed and **no
            // background**: the top bar has no element with one, and adding a fill for
            // this one control would be the second filled amber surface on the screen.
            BarIconButton(onClick = onToggleAreaSelect, contentDescription = "직접 지정") {
                StrokeIcon(
                    pathData = CameraIconPaths.PENCIL,
                    viewBox = 24f,
                    size = 19.dp,
                    strokeWidth = 1.7f,
                    color = if (areaSelectArmed) Amber else IconInactive,
                )
            }

            BarIconButton(onClick = onToggleGuide, contentDescription = "가이드") {
                StrokeIcon(
                    pathData = CameraIconPaths.GUIDE,
                    viewBox = 22f,
                    size = 21.dp,
                    strokeWidth = 1.7f,
                    color = if (guideVisible) Amber else IconInactive,
                    dots = CameraIconPaths.guideCentreDot(
                        color = if (guideVisible) Amber else IconInactive,
                    ),
                )
            }

            // 나 찍어줘 — an auxiliary action, which is where P2 §5 asks for it
            // ("셔터 주변의 항상 노출된 주 조작으로 만들 필요는 없다"). Never amber:
            // it opens another screen rather than arming a mode, and amber in this bar
            // means "this is on".
            if (onOpenDelegatedShoot != null) {
                BarIconButton(onClick = onOpenDelegatedShoot, contentDescription = "나 찍어줘") {
                    StrokeIcon(
                        pathData = CameraIconPaths.SHARE,
                        viewBox = 24f,
                        size = 19.dp,
                        strokeWidth = 1.8f,
                        color = IconInactive,
                        dots = CameraIconPaths.SHARE_NODES,
                    )
                }
            }

            // 재탐색 stays where the owner put it (2026-07-28) — the preview's
            // bottom-right. It is not in this bar.
            referenceEntry()
            demoControls()
        }
    }
}

/**
 * Shared hit target for the bar's ghost icons: 44dp of touch around a 19-21dp glyph.
 *
 * The padding *is* the touch target — the design's icons are too small to press
 * reliably and it gives no button box for them, so the box exists only for the
 * finger and never draws.
 */
@Composable
private fun BarIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(MIN_TOUCH_TARGET)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 설정 — debug only, and its whole content is the HUD toggle.
 *
 * A sheet holding one row is the honest shape of it. The alternative considered was
 * filling it out with preview-colour and analysis-rate switches, which do not exist
 * as product features; inventing controls to justify a container is a mistake this
 * project has made before.
 */
@Composable
private fun CameraSettingsSheet(
    hudVisible: Boolean,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
            .background(Ink800)
            .padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SheetHandle()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = STRIP_H_PADDING)
                .clickable(onClick = onToggleHud)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("디버그 HUD", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (hudVisible) Amber.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f))
                    .padding(3.dp),
                contentAlignment = if (hudVisible) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (hudVisible) Amber else TextLow),
                )
            }
        }
    }
}

/**
 * Slides a bottom sheet in and out over [CAMERA_SHEET_ANIM_MS].
 *
 * `AnimatedVisibility` in a Column animates the *height* as well as the offset, so
 * the preview above it gives up its space over the same 260ms instead of jumping by
 * the sheet's height on the first frame. `slideInVertically` alone would slide the
 * sheet up out of a gap that had already appeared.
 */
@Composable
private fun CameraSheetSlot(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(CAMERA_SHEET_ANIM_MS)),
        exit = shrinkVertically(animationSpec = tween(CAMERA_SHEET_ANIM_MS)),
    ) {
        content()
    }
}

/** The 36×4 grab handle both sheets carry. */
@Composable
private fun SheetHandle() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.22f)),
        )
    }
}

/**
 * 시안 05 — the filter sheet: `Ink800`, 20dp top corners, a grab handle, and the strip.
 *
 * ```
 * background #1A1A1D · radius 20 20 0 0 · padding 8 0 12 · gap 11
 * handle 36×4 r2 White 22%    strip gap 14 · padding 0 18 · thumb 52 circle
 * ```
 *
 * **Opaque `Ink800`, and no scrim over the preview.** Both are the design's, and both
 * follow from what the sheet is for: a preset is a colour (O-13), so choosing one means
 * comparing it against the live scene. A translucent sheet would tint its own swatches
 * and a scrim would tint the scene, and either makes the comparison the sheet exists
 * for impossible.
 *
 * A **sibling** of the shutter row rather than a layer over it (see the call site),
 * which is how "시트가 열린 상태에서도 셔터는 계속 쓸 수 있다" holds structurally.
 *
 * Round thumbnails showing the presets' own bundled images, so the strip shows what
 * each look *is* rather than only what it is called — which matters for
 * `자연스러운 피드` and `밤거리`, whose names are the only thing separating them.
 *
 * O-10's AI 2 entry points ride the same row: a leading `+` and, once a reference is
 * active, a trailing `내 레퍼런스` slot — ordered by [buildFilterStrip] and drawn with
 * the shared thumbs in `ui/reference/ReferenceStrip.kt` so this sheet and the result
 * screen stay pixel-consistent. 시안 05 shows the strip mid-scroll with the `+` pushed
 * off the left edge; that is a scroll position, not a different order.
 */
@Composable
private fun CameraFilterSheet(
    presets: List<StylePreset>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onCreateReference: () -> Unit,
    hasActiveReference: Boolean,
    referenceSelected: Boolean,
    activeReferenceImageUri: Uri?,
    onSelectReference: () -> Unit,
    onOpenReferenceDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
            .background(Ink800)
            .padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SheetHandle()
        CameraStyleStrip(
            presets = presets,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            onCreateReference = onCreateReference,
            hasActiveReference = hasActiveReference,
            referenceSelected = referenceSelected,
            activeReferenceImageUri = activeReferenceImageUri,
            onSelectReference = onSelectReference,
            onOpenReferenceDetail = onOpenReferenceDetail,
        )
    }
}

/**
 * The manual frame sheet (§3.1) — the 12 composition templates, plus `자동`.
 *
 * Same container vocabulary as [CameraFilterSheet] (Ink800, 20dp top corners, handle,
 * 260ms) because it is the same kind of thing: a picker raised from a button. Its
 * *contents* differ, and have to — a frame is a shape, so each cell draws the template's
 * own slot rectangles rather than a colour swatch.
 *
 * **The list comes from [availableManualLayouts] and is never written down here.** §3.1
 * requires it, and `CameraFramePickerTest` asserts that no template id literal appears
 * anywhere under `ui/camera/`. That is not bureaucracy: the catalogue keeps old ids as
 * compatibility aliases (`person_object` → `person_object_v2`), so a hardcoded list would
 * be a second, silently diverging copy of names 담당 B owns.
 *
 * The leading cell is `자동` and calls `rescanLayout()` — §3.1's "자동으로 돌아가기" exit,
 * in the same position and with the same grammar as the filter sheet's leading `+`.
 *
 * @param activeLayoutId from [ManualFrameSelection.activeManualLayoutId], i.e. what the
 *   guide engine is actually holding — never what this sheet last asked for. See that
 *   object for why the distinction is the requirement.
 */
@Composable
private fun CameraFrameSheet(
    layouts: List<LayoutTemplateSummary>,
    activeLayoutId: String?,
    onSelectLayout: (String) -> Unit,
    onSelectAuto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
            .background(Ink800)
            .padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SheetHandle()
        val listState = rememberLazyListState()
        LaunchedEffect(activeLayoutId, layouts) {
            // Offset by one for the leading `자동` cell.
            val index = layouts.indexOfFirst { it.id == activeLayoutId }
            if (index >= 0) listState.animateScrollToItem(index + 1)
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(FRAME_STRIP_HEIGHT),
            state = listState,
            contentPadding = PaddingValues(horizontal = STRIP_H_PADDING),
            horizontalArrangement = Arrangement.spacedBy(STRIP_ITEM_GAP),
            verticalAlignment = Alignment.Top,
        ) {
            item {
                // `자동` — selected exactly when no manual frame is in charge, which is
                // the same fact the frame button reads. One source, two consumers.
                FrameThumb(
                    label = "자동",
                    selected = activeLayoutId == null,
                    onClick = onSelectAuto,
                ) { color ->
                    // The scene-search glyph, not a template: four corner marks, the
                    // overlay's own word for "composition", with nothing inside because
                    // automatic means the app has not committed to a shape yet.
                    drawFrameThumbBracket(color)
                }
            }
            items(layouts, key = { it.id }) { summary ->
                FrameThumb(
                    label = ManualFrameSelection.label(summary),
                    selected = summary.id == activeLayoutId,
                    onClick = { onSelectLayout(summary.id) },
                ) { color ->
                    drawFrameThumbSlots(summary.slots, color)
                }
            }
        }
    }
}

/**
 * One cell of the frame sheet: a 4:5 miniature with an optional caption.
 *
 * A rounded rectangle rather than the filter strip's circle, and 4:5 rather than square,
 * because the thing being chosen **is** a frame — the cell's own shape is the first
 * information it gives. The selection ring is the strip's (2dp amber + 2dp gap), so
 * "selected" looks identical in both sheets.
 *
 * @param label null renders no caption. See [ManualFrameSelection.label] — one shipped
 *   layout has no display name, and printing its raw id would be worse than a gap.
 */
@Composable
private fun FrameThumb(
    label: String?,
    selected: Boolean,
    onClick: () -> Unit,
    content: DrawScope.(Color) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(width = FRAME_THUMB_WIDTH, height = FRAME_THUMB_HEIGHT)
                .then(
                    if (selected) {
                        Modifier
                            .border(2.dp, Amber, RoundedCornerShape(8.dp))
                            .padding(2.dp)
                    } else {
                        Modifier.padding(2.dp)
                    },
                )
                .clip(RoundedCornerShape(6.dp))
                .background(Ink700),
        ) {
            val color = if (selected) Amber else IconInactive
            Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) { content(color) }
        }
        if (label != null) {
            Text(
                text = label,
                color = if (selected) Amber else TextMid,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/**
 * A template's slots as a miniature, **in their own kinds' styles**.
 *
 * Shares [SlotRenderStyle] with the live overlay, so a person slot is marked as a person
 * in the picker and on the preview. Choosing "인물과 소품" and then seeing two identical
 * boxes was the §3.3 defect; showing two identical boxes in the picker that chooses it
 * would be the same defect one step earlier.
 *
 * Simplified rather than reproduced: a head circle for a person, a plain outline for an
 * object. At 42×54dp a silhouette's shoulders are a few pixels and would read as noise.
 */
private fun DrawScope.drawFrameThumbSlots(slots: List<LayoutPreviewSlot>, color: Color) {
    val stroke = 1.2.dp.toPx()
    slots.forEach { slot ->
        val left = slot.bounds.left * size.width
        val top = slot.bounds.top * size.height
        val width = (slot.bounds.right - slot.bounds.left) * size.width
        val height = (slot.bounds.bottom - slot.bounds.top) * size.height
        drawRoundRect(
            color = color.copy(alpha = 0.85f),
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
            style = Stroke(width = stroke),
        )
        if (SlotRenderStyle.of(slot.visualKind).isPerson) {
            val radius = (width * 0.24f).coerceAtMost(height * 0.16f)
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(left + width / 2f, top + radius * 1.5f),
                style = Stroke(width = stroke),
            )
        }
    }
}

/** The `자동` cell's glyph: the overlay's four corner marks, at thumbnail scale. */
private fun DrawScope.drawFrameThumbBracket(color: Color) {
    val stroke = 1.2.dp.toPx()
    val arm = size.minDimension * 0.26f
    for (right in listOf(false, true)) {
        for (bottom in listOf(false, true)) {
            val x = if (right) size.width else 0f
            val y = if (bottom) size.height else 0f
            val dx = if (right) -arm else arm
            val dy = if (bottom) -arm else arm
            drawLine(color, Offset(x, y), Offset(x + dx, y), stroke, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy), stroke, StrokeCap.Round)
        }
    }
}

/** The strip itself. Its container is [CameraFilterSheet]. */
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
    onOpenReferenceDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reserve the row even with nothing to put in it. `presets` arrives from disk, and a
    // sheet that opens at one height and grows to another 60ms later is a visible
    // stutter on top of the 260ms slide — the two animations would fight. Empty is also
    // the honest state when the read failed (AGENTS §7-6), which is why this is a blank
    // row rather than a placeholder thumbnail.
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
        contentPadding = PaddingValues(horizontal = STRIP_H_PADDING),
        horizontalArrangement = Arrangement.spacedBy(STRIP_ITEM_GAP),
        // `align-items:flex-start` — the labels hang below their thumbnails and a
        // two-line label must not push its thumbnail up out of line with the others.
        verticalAlignment = Alignment.Top,
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
                                        Modifier.border(2.dp, Amber, CircleShape).padding(2.dp)
                                    } else {
                                        Modifier.padding(4.dp)
                                    },
                                )
                                .clip(CircleShape)
                                .background(Ink700),
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
                            color = if (isSelected) Amber else TextMid,
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
                    onOpenDetail = onOpenReferenceDetail,
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
    /**
     * What CameraX is bound to. Must come from [rememberCameraBindingOwner] — the
     * Activity — and never from `LocalLifecycleOwner`, which inside a
     * Navigation-Compose destination is that destination's back-stack entry and
     * detaches the camera the moment the user leaves the screen.
     */
    cameraLifecycleOwner: LifecycleOwner,
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
    /**
     * Whether a **manual frame is in charge** — from
     * [ManualFrameSelection.frameButtonActive], never from whether the sheet is open.
     */
    frameSheetActive: Boolean,
    onToggleFrameSheet: () -> Unit,
    onRescanAt: (Float, Float) -> Unit,
    onPaneRatio: (Float) -> Unit,
    /** One tick per delivered preview frame (§7-1). Main thread. */
    onPreviewFrameNs: (Long) -> Unit,
    onPreviewFpsAvailability: (PreviewFpsAvailability) -> Unit,
    referenceLayer: @Composable BoxScope.() -> Unit,
    /**
     * Whether a tap anywhere on the preview should dismiss an open sheet.
     *
     * **Must be false when no sheet is open**, and the reason is this function's own
     * KDoc: the dismiss layer is a `clickable` above the gesture surface, so while it
     * exists it takes the DOWN and pinch-to-zoom and tap-to-focus stop working
     * *together, silently*. That is correct while a sheet is up — the tap means
     * "close it" — and it is the bug the KDoc describes at any other time. Hence a
     * condition rather than a permanently-mounted transparent Box.
     */
    dismissSheetOnTap: Boolean,
    onDismissSheet: () -> Unit,
    /** 연필 armed. While true the preview collects a path instead of framing gestures. */
    areaSelectArmed: Boolean,
    /** The path so far, in pane pixels, already clamped and thinned. */
    lassoPath: List<Pair<Float, Float>>,
    /** 0..1 settle-out for [lassoPath]; 1 while drawing. */
    lassoAlpha: Float,
    onLassoStart: () -> Unit,
    onLassoPoint: (Float, Float, Float) -> Unit,
    /** Finger lifted. Receives the pane's pixel size, which only this scope knows. */
    onLassoFinish: (Float, Float) -> Unit,
    hud: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        // Mask geometry, from the one function `resolveTapFocusPoint` and the lasso
        // clamp also call — the bars are the no-focus, no-draw zone, so all three have
        // to mean the same rectangle. Computed here in Dp off `BoxWithConstraints` and
        // there in px off `PointerInputScope.size`; the formula is scale-invariant so
        // the fraction agrees.
        //
        // **Both axes.** 16:9 (0.5625) is narrower than a phone pane, so its window is
        // pillarboxed rather than letterboxed. The old `coerceAtMost` form could only
        // trim height and silently showed a 0.635 window for a 0.5625 capture.
        val window = previewWindowOf(
            paneWidth = maxWidth.value,
            paneHeight = maxHeight.value,
            ratioWtoH = aspect.ratioWtoH,
        )
        val windowWidth = window?.width?.dp ?: maxWidth
        val windowHeight = window?.height?.dp ?: maxHeight
        val barHeight = window?.top?.dp ?: 0.dp
        val barWidth = window?.left?.dp ?: 0.dp

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
        // Same reason as `currentAspect`: toggling the pencil must not restart the
        // gesture handler, because a restart drops the gesture that follows it.
        val currentAreaSelectArmed by rememberUpdatedState(areaSelectArmed)

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
                // Before anything of ours is bound, and never after.
                //
                // A release deferred by the *previous* camera screen is
                // `ProcessCameraProvider.unbindAll()` — it does not know which
                // controller asked for it. Landing after the bind below, it would
                // tear down this preview instead of the old one, leaving a black
                // screen with no error anywhere. Spending it here means the two can
                // never overlap: the old camera is always released while the new one
                // does not yet exist. The capture it was waiting for is lost, which
                // is the right trade — a photo is worth less than a working camera,
                // and two cameras cannot have the hardware at once.
                releaseCamera("rebind", cameraTeardownGate.releaseBeforeBind())
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
                    controller.bind(cameraLifecycleOwner)
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
                        // 연필. Reads `armed` through the updated state rather than being
                        // keyed on it — a key change restarts this whole handler and the
                        // restart eats one gesture, which here would be the first stroke
                        // of every lasso.
                        lasso = {
                            val minStepPx = AreaSelectPath.MIN_STEP_DP.dp.toPx()
                            detectLassoDrags(
                                armed = { currentAreaSelectArmed },
                                onStart = onLassoStart,
                                onPoint = { x, y ->
                                    // Clamped, not rejected: a stroke that strays into the
                                    // letterbox must ride the boundary, because dropping
                                    // its middle would splice the path across the subject.
                                    clampedLassoPoint(
                                        position = Offset(x, y),
                                        paneWidth = size.width.toFloat(),
                                        paneHeight = size.height.toFloat(),
                                        ratioWtoH = currentAspect.ratioWtoH,
                                    )?.let { (cx, cy) -> onLassoPoint(cx, cy, minStepPx) }
                                },
                                onFinish = {
                                    onLassoFinish(size.width.toFloat(), size.height.toFloat())
                                },
                            )
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

        // Above the guide, below the aspect mask — the path is the user's own mark and
        // must not be hidden by the bracket, but it must still be clipped off the
        // letterbox like everything else in this pane.
        AreaSelectPathOverlay(
            points = lassoPath,
            alpha = lassoAlpha,
            modifier = Modifier.fillMaxSize(),
        )

        // The mask, and the window's own contents.
        //
        // Four bars now, not two: 16:9 pillarboxes rather than letterboxes, so a
        // `Column` of two full-width bars cannot express it. The Row nested inside the
        // Column's middle band is what makes both axes available while keeping the
        // window a single Box that `RuleOfThirds`, `ZoomStops` and `RescanButton`
        // continue to sit inside unchanged — the grid still spans exactly the frame
        // that will be saved, which is the only thing it is for.
        //
        // Every bar is `Ink950`, i.e. the same opaque surface as the screen behind the
        // pane, so a zero-width or zero-height bar is invisible rather than a hairline
        // (4:5 and 1:1 have `barWidth == 0`).
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(barHeight).background(Ink950))
            Row(modifier = Modifier.fillMaxWidth().height(windowHeight)) {
                Box(modifier = Modifier.width(barWidth).fillMaxHeight().background(Ink950))
                Box(modifier = Modifier.width(windowWidth).fillMaxHeight()) {
                    RuleOfThirds()
                    ZoomStops(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        zoomRatio = zoomRatio,
                        bounds = zoomBounds,
                        onSelect = onSelectZoom,
                    )
                    // The two directions of one decision, stacked.
                    //
                    // 재탐색 says "look at the scene again" (automatic composition); the
                    // frame button says "no, use this one". §3.1 makes that literal —
                    // the documented way back from a manual frame is `rescanLayout()`,
                    // i.e. the button directly below. Two controls that are each other's
                    // exit belong next to each other.
                    //
                    // Vertical rather than side by side because the zoom stops are
                    // centred in this row and 16:9 pillarboxes the window narrower than
                    // the pane (938px measured against a 1080px pane). A horizontal pair
                    // clears the stops today but only by relying on that margin.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 12.dp, end = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FrameButton(active = frameSheetActive, onClick = onToggleFrameSheet)
                        RescanButton(onClick = onRescan)
                    }
                }
                Box(modifier = Modifier.width(barWidth).fillMaxHeight().background(Ink950))
            }
            Box(modifier = Modifier.fillMaxWidth().height(barHeight).background(Ink950))
        }

        hud()

        // Topmost, and **transparent**. The redesign gives the filter sheet no scrim,
        // which is not an omission: a filter is a colour (O-13), so choosing one means
        // seeing it on the live scene, and darkening the preview to signal modality
        // would defeat the only thing the sheet is for. So this catches the tap and
        // tints nothing.
        if (dismissSheetOnTap) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        // No ripple and no role: this is not a button, it is the
                        // absence of one. A ripple here would flash over the photo.
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissSheet,
                    ),
            )
        }
    }
}

/**
 * Shutter row, per the redesign: `album | shutter | filter · lens`.
 *
 * ```
 * grid-template-columns:1fr auto 1fr · padding:0 34px 18px
 * album 42 r13 border White 16% (justify-self:start)
 * shutter 74 ring 3 · disc 58          filter/lens 42 circle, gap 16 (justify-self:end)
 * ```
 *
 * The `1fr auto 1fr` is what centres the shutter regardless of how wide the two side
 * groups are, and it is a real requirement rather than CSS trivia: the right group has
 * two controls and the left has one, so `SpaceBetween` would put the shutter visibly
 * off-centre. Hence the two weighted boxes.
 *
 * The **filter button is the mood indicator**. The `내 감도 적용 중` pill that used to
 * say this in the top bar is gone; a 6dp amber dot on this button's corner says it
 * instead. See [showMoodDot] for what "mood" means here and why the dot goes away
 * while the sheet is open.
 */
@Composable
private fun CameraBottomBar(
    lastThumb: android.graphics.Bitmap?,
    capturing: Boolean,
    /**
     * Whether the composition matches — from [AlignmentAmber.isOn], the same predicate
     * the target bracket turns amber on. Never a score (D2-5).
     */
    aligned: Boolean,
    isFront: Boolean,
    filterSheetOpen: Boolean,
    /**
     * Whether a reference (`내 감도`) is the active style **right now** — not merely
     * whether one exists. A preset is always selected, so a dot meaning "something is
     * selected" would never go out and would say nothing.
     *
     * False while [filterSheetOpen], because then the whole button is amber and the dot
     * would be saying it twice. 시안 03 has the dot; 시안 05 does not.
     */
    showMoodDot: Boolean,
    onOpenAlbum: () -> Unit,
    onToggleFilterSheet: () -> Unit,
    onFlipLens: () -> Unit,
    onShutter: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp, end = 34.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Box(
                modifier = Modifier
                    .size(BOTTOM_CONTROL_SIZE)
                    .clip(RoundedCornerShape(13.dp))
                    // Kept from before the redesign, deliberately. The mock's album tile
                    // contains a photo, so it does not say what an empty one looks like
                    // — and on first launch there is no photo. Without a fill this is a
                    // 1px hairline on near-black, i.e. an invisible control. Preserving
                    // the existing answer to a question the design does not address is
                    // not the same as inventing one.
                    .background(moodBrush(2))
                    .border(1.dp, AlbumTileBorder, RoundedCornerShape(13.dp))
                    .semantics { contentDescription = "앨범" }
                    .clickable(onClick = onOpenAlbum),
            ) {
                if (lastThumb != null) {
                    Image(
                        bitmap = lastThumb.asImageBitmap(),
                        contentDescription = "최근 촬영",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp)),
                    )
                }
            }
        }

        // D2-4: the shutter is manual only — `takePicture` is reachable from this
        // clickable lambda and nowhere else. No countdown, no auto-capture.
        CameraShutter(capturing = capturing, aligned = aligned, onShutter = onShutter)

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SHUTTER_ROW_GAP),
            ) {
                FilterButton(
                    open = filterSheetOpen,
                    showMoodDot = showMoodDot,
                    onClick = onToggleFilterSheet,
                )
                Box(
                    modifier = Modifier
                        .size(BOTTOM_CONTROL_SIZE)
                        .clip(CircleShape)
                        .background(OverPhotoDisc)
                        .semantics { contentDescription = "렌즈 전환" }
                        .clickable(onClick = onFlipLens),
                    contentAlignment = Alignment.Center,
                ) {
                    StrokeIcon(
                        pathData = CameraIconPaths.FLIP_LENS,
                        viewBox = 24f,
                        size = 19.dp,
                        strokeWidth = 1.8f,
                        // The front lens is a state, and amber means active — the same
                        // rule the top bar's 가이드 icon follows.
                        color = if (isFront) Amber else IconInactive,
                    )
                }
            }
        }
    }
}

/**
 * The shutter and its three appearances (redesign 시안 03 / 04).
 *
 * ```
 * 74 · ring 3px White 92% · disc 58 #F4F1EA
 * 구도 일치 → Amber, faded over 200ms      촬영 중 → disc contracts to 78%
 * ```
 *
 * Which appearance applies is [ShutterVisual]'s answer, not this composable's: colour
 * follows alignment and scale follows capture, **independently**, so pressing the
 * shutter on a matched composition does not flash the disc back to white for the
 * length of the capture. See that file for why the redesign's three-state wording
 * needs two decisions rather than one.
 *
 * Everything here is animated and nothing blinks. D2-3 allows exactly one success
 * signal — a colour change — so there is no haptic, no sound, no toast and no
 * scale pulse on alignment; the only scale change in the whole control belongs to the
 * capture.
 *
 * `LinearOutSlowInEasing` is CSS `ease-out`: fast at the start, settling at the end.
 * `FastOutSlowInEasing` would be `ease-in-out` and is the wrong curve for a state that
 * should announce itself immediately and then stop moving.
 */
@Composable
private fun CameraShutter(capturing: Boolean, aligned: Boolean, onShutter: () -> Unit) {
    val amber = ShutterVisual.alignedAmber(aligned = aligned, capturing = capturing)
    val target = if (amber) Amber else Color.White.copy(alpha = ShutterVisual.IDLE_ALPHA)
    val ringColor by animateColorAsState(
        targetValue = target,
        animationSpec = tween(CAMERA_ALIGN_FADE_MS, easing = LinearOutSlowInEasing),
        label = "shutterRing",
    )
    val discColor by animateColorAsState(
        // The disc is opaque TextHi at rest, not White 92% — that alpha is the ring's.
        targetValue = if (amber) Amber else TextHi,
        animationSpec = tween(CAMERA_ALIGN_FADE_MS, easing = LinearOutSlowInEasing),
        label = "shutterDisc",
    )
    val discScale by animateFloatAsState(
        targetValue = ShutterVisual.discScale(capturing),
        animationSpec = tween(CAMERA_ALIGN_FADE_MS, easing = LinearOutSlowInEasing),
        label = "shutterDiscScale",
    )
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .border(3.dp, ringColor, CircleShape)
            .semantics { contentDescription = "촬영" }
            .clickable(enabled = !capturing, onClick = onShutter),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // Scaled rather than resized: `Modifier.size(58.dp * scale)` would
                // re-measure the disc every animation frame and, because the parent
                // centres it, nudge the layout. `graphicsLayer` moves it on the render
                // thread and leaves the measured tree alone.
                .size(58.dp)
                .graphicsLayer {
                    scaleX = discScale
                    scaleY = discScale
                }
                .clip(CircleShape)
                .background(discColor),
        )
    }
}

/**
 * 필터 — raises the sheet, and carries the mood dot.
 *
 * Amber at 16% while open, `White 8%` while closed. Both are the design's, and the
 * asymmetry is the point: this is the one control whose own appearance says whether its
 * sheet is up, because the sheet has no scrim to say it.
 */
@Composable
private fun FilterButton(open: Boolean, showMoodDot: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.size(BOTTOM_CONTROL_SIZE)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (open) FilterButtonActive else OverPhotoDisc)
                .semantics { contentDescription = "필터" }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            StrokeIcon(
                viewBox = 22f,
                size = 20.dp,
                strokeWidth = 1.6f,
                color = if (open) Amber else IconInactive,
                dots = CameraIconPaths.filterLenses(),
            )
        }
        if (showMoodDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Amber),
            )
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
    val f = result.faces.firstOrNull()
    Log.d(
        TAG,
        "faces=$faceN " +
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
            color = Amber,
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
            color = if (measured) Amber else TextMid,
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
            color = if (level) Amber else TextMid,
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
                color = if (debug.aligned) Amber else TextHi,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "area %.2f · headroom %.2f · margins %.2f/%.2f".format(
                    f.personAreaRatio, f.headroom, f.sideMargins.left, f.sideMargins.right,
                ),
                color = TextMid,
                fontSize = 10.sp,
            )
            Text(
                text = "luma %.2f · poseConf %.2f · backlight=%s lowLight=%s".format(
                    f.brightnessMean, f.poseConfidence, f.backlightFlag, f.lowLightFlag,
                ),
                color = TextMid,
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
                    // Same over-photo rule as [RescanButton]; same stale green-tinted
                    // charcoal replaced. These two sit on the same row, so leaving one
                    // tinted next to a corrected one would be more visible than either.
                    .background(OnPhotoScrim)
                    .then(if (isActive) Modifier.border(1.8.dp, Amber, CircleShape) else Modifier)
                    .clickable { onSelect(stop) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatZoomStop(stop),
                    color = if (isActive) Amber else TextMid,
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
 * 프레임 — raises the manual composition sheet (§3.1).
 *
 * Sits directly above [RescanButton] and shares its whole vocabulary: 34dp circle,
 * [OnPhotoScrim], a stroke glyph. Not in the top bar (the owner fixed that at four icons)
 * and not in the shutter row (also four) — and it belongs here anyway, because this is
 * where the *composition* controls live while colour lives on the shutter row. That split
 * is O-13's two axes showing up as two places.
 *
 * The glyph is a rounded outer frame with two inner rectangles — "a layout" — chosen so it
 * collides with neither of its neighbours' meanings: the 가이드 icon is four corner
 * brackets and 재탐색 is a circular arrow.
 *
 * Amber stroke while a manual frame is in charge. Note **what decides that**: it is
 * [ManualFrameSelection.frameButtonActive] reading the guide's own `layoutState`, not
 * whether the sheet is open and not what the sheet last requested. A frame the engine
 * refused leaves this button dark, which is §3.1's "선택 실패를 고정 성공으로 표시하지
 * 않는다" holding by construction.
 */
@Composable
private fun FrameButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(OnPhotoScrim)
            .semantics { contentDescription = "프레임" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StrokeIcon(
            pathData = CameraIconPaths.FRAME,
            viewBox = 16f,
            size = 16.dp,
            strokeWidth = 1.4f,
            color = if (active) Amber else IconInactive,
        )
    }
}

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
 * The glyph **is** the circular arrow now (owner's redesign, 2026-07-30). It used to be
 * a miniature of the app's own target bracket, on the reasoning that "D2 bans direction
 * arrows from this screen"; the ban is on arrows that tell the user which way to *move*,
 * and a refresh arrow is not one. The design draws this shape and the design is final.
 *
 * It also stops competing with the 가이드 icon, which is now four corner brackets — two
 * controls with the same glyph and different jobs was the worse problem.
 */
@Composable
private fun RescanButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            // The redesign's rule for a control sitting **on** the photo: Ink950 at
            // 45%, the bottom of the spec's 45-62% band. Was `Color(0x99141614)`, a
            // green-tinted charcoal from before the token replacement — the one hue
            // besides the accent that the new palette is supposed to have removed.
            .background(OnPhotoScrim)
            .semantics { contentDescription = "재탐색" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StrokeIcon(
            pathData = CameraIconPaths.RESCAN,
            viewBox = 15f,
            size = 15.dp,
            strokeWidth = 1.5f,
            color = IconInactive,
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
        Text(text = text, color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun formatZoom(value: Float): String =
    "%.1fx".format(value.coerceAtLeast(0f))
