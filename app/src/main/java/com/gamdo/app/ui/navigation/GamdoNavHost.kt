package com.gamdo.app.ui.navigation

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.ReferenceCreateController
import com.gamdo.app.data.ReferenceCreateState
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.ui.album.AlbumScreen
import com.gamdo.app.ui.camera.CameraScreen
import com.gamdo.app.ui.onboarding.OnboardingScreen
import com.gamdo.app.ui.reference.DEFAULT_REFERENCE_OVERLAY_ALPHA
import com.gamdo.app.ui.reference.ReferenceCreateSheet
import com.gamdo.app.ui.reference.ReferenceOverlayLayer
import com.gamdo.app.ui.reference.clampReferenceOverlayAlpha
import com.gamdo.app.ui.reference.shouldShowReferenceOverlay
import com.gamdo.app.ui.result.ResultScreen
import com.gamdo.app.ui.result.ResultTarget
import com.gamdo.app.ui.shoot.DelegatedShootController
import com.gamdo.app.ui.shoot.DelegatedShootScreen
import com.gamdo.app.ui.shoot.shootPolicyFor
import kotlinx.coroutines.launch

/**
 * App navigation graph (t2 flow): onboarding → camera(home) → album → result.
 * Start destination depends on whether onboarding has been completed.
 *
 * Also hosts AI 2's ("내 감도 만들기" / 레퍼런스) cross-screen state: one
 * [ReferenceCreateController] and one resolved [ResolvedStyle], shared by
 * [CameraScreen] and [ResultScreen] regardless of which of them the user
 * tapped `+` from (O-10 puts the entry point on both). See
 * `docs/AI2_레퍼런스_통합_계약_2026-07-28.md`'s "P1 연결 요구사항".
 *
 * Two [Uri]s, not one — this is load-bearing, not incidental (device bug,
 * 2026-07-29): [pickedReferenceUri] is the transient photo the *sheet* is
 * currently considering (consent/preview), and [activeReferenceImageUri] is the
 * photo behind whatever reference is *actually active*. Only the latter may
 * ever reach the camera overlay or either strip's `내 레퍼런스` thumbnail —
 * see [shouldShowReferenceOverlay]'s KDoc for the bug that collapsing them into
 * one variable caused: closing a failed/cancelled flow correctly hid the
 * `내 레퍼런스` slot (and with it, the only `×` that could have cleared
 * anything) while the picked photo kept ghosting over the live preview forever.
 */
@Composable
fun GamdoNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val startDestination by produceState<String?>(initialValue = null, container) {
        value = if (container.settingsRepository.isOnboardingDone()) Routes.CAMERA else Routes.ONBOARDING
    }

    // ---- AI 2 reference state, shared across camera/result ----

    val referenceController = remember(container) {
        ReferenceCreateController(container.referenceRepository, container.settingsRepository, scope)
    }
    val referenceState by referenceController.state.collectAsState()

    // The photo the *sheet* is currently considering — set the moment the
    // picker returns, read by ConsentSection/PreviewSection only. Cleared on
    // every path that ends the flow without applying, so nothing here can
    // outlive the attempt that picked it (see this function's KDoc and
    // [shouldShowReferenceOverlay]).
    var pickedReferenceUri by remember { mutableStateOf<Uri?>(null) }

    // The photo behind whatever reference is *actually active* — the only Uri
    // the camera overlay or either strip's thumbnail may ever read. Set once,
    // at the moment `apply()` succeeds (below), from whatever `pickedReferenceUri`
    // held at that instant; never written from anywhere else. Null on a fresh
    // launch even when a reference is already active from a previous session —
    // `ReferenceRepository` does not keep the original bytes past the upload
    // (`resolveBytes`'s `tempFile.delete()`) — so the thumbnail/overlay
    // correctly show nothing until this session re-picks (appearance decision
    // #3 from the integration report; unaffected by this fix).
    var activeReferenceImageUri by remember { mutableStateOf<Uri?>(null) }

    // The active reference's resolved composition+color, or null. Loaded once on
    // start from whatever was already active (Room-persisted via
    // `SettingsRepository`/`ReferenceRepository`), then replaced the moment a new
    // `apply()` lands (see the LaunchedEffect below) or cleared on delete.
    var activeReferenceStyle by remember { mutableStateOf<ResolvedStyle?>(null) }
    LaunchedEffect(container) {
        // Do not let a slower Room restore overwrite an Apply that completed
        // while this effect was reading. This race made the reference slot and
        // downstream filter state disappear immediately after a successful apply.
        val restored = runCatching { loadActiveReferenceStyle(container) }.getOrNull()
        if (activeReferenceStyle == null) activeReferenceStyle = restored
    }
    LaunchedEffect(referenceState) {
        val applied = referenceState as? ReferenceCreateState.Applied ?: return@LaunchedEffect
        activeReferenceStyle = applied.style
        activeReferenceImageUri = pickedReferenceUri
    }

    var overlayAlpha by rememberSaveable { mutableStateOf(DEFAULT_REFERENCE_OVERLAY_ALPHA) }

    // ---- P2 §5 "나 찍어줘" hand-off state ----

    // The layout the QR screen will turn into a ShootPolicyV2, handed over in memory
    // because it is an object graph and [Routes.SHOOT] takes no arguments (see that
    // constant's KDoc). Deliberately *not* rememberSaveable: a GuideLayoutState is not
    // parcelable, and a stale layout restored into a new camera session would describe
    // a scene that is no longer in front of the lens. Losing it to process death lands
    // the screen on its 넘길 구도가 없어요 state, which is correct.
    var pendingShootLayout by remember { mutableStateOf<GuideLayoutState?>(null) }

    /**
     * The camera's entry point into the hand-off — the seam, ready and unoccupied.
     *
     * `CameraScreen` would take this as
     * `onOpenDelegatedShoot: ((GuideLayoutState) -> Unit)? = null` and invoke it from a
     * tap with `layoutState.value`, never from a `LaunchedEffect`. It is not passed
     * below, by owner decision (2026-07-30): the delegated web page is not deployed —
     * `gamdo-web/dist` is absent and the server answers `/shoot/{token}` with
     * 503 `web_not_built` — so a button would be a control that cannot work, and P2's
     * own §5 says an unconnected QR feature is to be left off the product surface and
     * marked 미구현 rather than shown. Wiring it is one argument at the `CameraScreen`
     * call below plus one button in that file.
     */
    @Suppress("UNUSED_VARIABLE")
    val onOpenDelegatedShoot: (GuideLayoutState) -> Unit = { layout ->
        pendingShootLayout = layout
        navController.navigate(Routes.SHOOT)
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            pickedReferenceUri = uri
            referenceController.select(uri)
        }
    }
    val onCreateReference: () -> Unit = {
        pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val onDeleteReference: () -> Unit = {
        referenceController.clearActive()
        pickedReferenceUri = null
        activeReferenceImageUri = null
        activeReferenceStyle = null
    }
    // Every path that ends the flow *without* applying funnels through here —
    // the scrim tap, 취소 on consent/preview, 닫기 on a non-retryable error, and
    // 닫기 on Applied (harmless there: `activeReferenceImageUri` was already
    // captured by the LaunchedEffect above before this can run). Clearing
    // `pickedReferenceUri` on every one of them is the actual fix: a photo the
    // sheet is no longer considering can no longer be mistaken for one that is.
    val onDismissReferenceSheet: () -> Unit = {
        referenceController.cancel()
        pickedReferenceUri = null
    }

    val start = startDestination
    if (start == null) {
        // Brief decision gate while we read the onboarding flag.
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {}
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    container = container,
                    onFinished = {
                        scope.launch { container.settingsRepository.setOnboardingDone() }
                        navController.navigate(Routes.CAMERA) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.CAMERA) {
                CameraScreen(
                    container = container,
                    onOpenAlbum = { navController.navigate(Routes.ALBUM) },
                    // §5-2: the *active* reference's photo, translucent, over the
                    // live preview — never the sheet's transient pick (device
                    // bug, 2026-07-29; see this function's KDoc). Gated on
                    // `shouldShowReferenceOverlay` explicitly rather than trusting
                    // `activeReferenceImageUri == null` alone — the two are kept
                    // in sync by construction, but the gate is the tested,
                    // enforced invariant and the null-check is only a consequence
                    // of it.
                    referenceLayer = {
                        ReferenceOverlayLayer(
                            imageUri = activeReferenceImageUri.takeIf {
                                shouldShowReferenceOverlay(referenceState, activeReferenceStyle != null)
                            },
                            alpha = overlayAlpha,
                            onAlphaChange = { overlayAlpha = clampReferenceOverlayAlpha(it) },
                        )
                    },
                    // O-10 moved the AI 2 entry point into the bottom filter strip
                    // (onCreateReference below); this top-bar slot has no occupant
                    // now — see CameraScreen's KDoc on the parameter.
                    onCreateReference = onCreateReference,
                    hasActiveReference = activeReferenceStyle != null,
                    activeReferenceImageUri = activeReferenceImageUri,
                    activeReferenceStyle = activeReferenceStyle,
                    onDeleteReference = onDeleteReference,
                )
            }

            composable(Routes.ALBUM) {
                AlbumScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenPhoto = { captureId -> navController.navigate(Routes.result(captureId)) },
                    // W3.5-6. The tap opens the same 보정 screen on a `MediaStore`
                    // photo — **without** minting a `captures` row for it. Importing
                    // one would put the same photo in the grid twice, once as an app
                    // capture and once as the device original, which is the exact
                    // duplication W3.5-2's dedup removed.
                    onOpenDevicePhoto = { tap ->
                        navController.navigate(Routes.devicePhoto(tap.mediaStoreId))
                    },
                )
            }

            composable(
                route = Routes.RESULT,
                arguments = listOf(navArgument(Routes.ARG_CAPTURE_ID) { type = NavType.StringType }),
            ) { entry ->
                val captureId = entry.arguments?.getString(Routes.ARG_CAPTURE_ID).orEmpty()
                ResultScreen(
                    container = container,
                    target = ResultTarget.AppCapture(captureId),
                    onBack = { navController.popBackStack() },
                    activeReferenceStyle = activeReferenceStyle,
                    activeReferenceImageUri = activeReferenceImageUri,
                    onCreateReference = onCreateReference,
                    onDeleteReference = onDeleteReference,
                    // AI 3's slot (O-10) — deliberately wired to nothing here; see
                    // the integration task's explicit instruction not to
                    // implement AI 3 behaviour.
                )
            }

            composable(Routes.SHOOT) {
                // **This destination's own scope, not the nav host's `scope`.** The
                // difference is load-bearing: the nav host's scope lives as long as the
                // app, so a 사진 받기 download launched into it would survive the user
                // leaving — and `receiveAndClaim` *deletes the session server-side* when
                // it finishes, so it would complete, claim, and then find no screen left
                // to hand the files to. The photos would be gone with no way back to
                // them. Cancelled with the screen instead, the download stops **before**
                // the claim, the session stays alive on the server, and coming back finds
                // the photos still waiting.
                val shootScope = rememberCoroutineScope()
                // One controller per visit. Keyed on the layout that was handed over, so
                // re-entering with a different 구도 builds a different policy — and on the
                // container, matching every other screen here.
                val controller = remember(container, pendingShootLayout) {
                    DelegatedShootController(
                        repository = container.shootSessionRepository,
                        // The entire coupling to `CaptureRepository`. A received photo
                        // becomes an ordinary capture — app-private copy, EXIF/GPS dropped
                        // by the decode-and-re-encode (D8), `captures` row — so it appears
                        // in the album and opens through the same route as any other photo.
                        //
                        // `conditionsJson`/`analysisJson` stay at their `{}` defaults, and
                        // that is the honest answer rather than an omission: a friend's
                        // phone recorded no shutter facts, so there is no tilt, no subject
                        // rectangle and no session. Inventing neutral values would claim a
                        // measurement that was never taken.
                        importer = { file ->
                            container.captureRepository
                                .importReceivedShootPhoto(file)
                                .id
                        },
                        scope = shootScope,
                        policyDecision = shootPolicyFor(pendingShootLayout),
                    )
                }
                DelegatedShootScreen(
                    controller = controller,
                    onClose = { navController.popBackStack() },
                    // The friend's photo opens through `Routes.result` — byte for byte the
                    // route an album tap uses, because after the import it *is* an album
                    // photo. The dedicated file-path route this screen used before is gone
                    // with it.
                    //
                    // Only the first is opened, by owner decision: five result screens is
                    // not an outcome, and opening none leaves the user with no idea what
                    // arrived. The rest are in the album.
                    onOpenCapture = { captureId ->
                        navController.navigate(Routes.result(captureId))
                    },
                    // P2 asks for 기본 프레임 선택 또는 취소; the manual frame picker does
                    // not exist yet, so only 취소 is offered. This is the slot it plugs
                    // into when it lands.
                    onPickFrame = null,
                )
            }

            composable(
                route = Routes.DEVICE_PHOTO,
                arguments = listOf(navArgument(Routes.ARG_MEDIA_STORE_ID) { type = NavType.LongType }),
            ) { entry ->
                // Rebuilt from the id rather than passed as an encoded Uri — the
                // album built the Uri it handed over the same way, so this is the
                // same value with no escaping to get wrong. A missing argument is
                // not a thing this route can produce (`LongType` is required and
                // `Routes.devicePhoto` is the only writer), but if it somehow were,
                // -1 resolves to a Uri that fails to open and the screen says
                // 사진을 열지 못했어요 rather than sitting blank.
                val mediaStoreId = entry.arguments?.getLong(Routes.ARG_MEDIA_STORE_ID) ?: -1L
                val uri = remember(mediaStoreId) {
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        mediaStoreId,
                    )
                }
                ResultScreen(
                    container = container,
                    // O-12 rides on this: `DevicePhoto` means no geometry and no
                    // optical pass until the user picks a look. See
                    // `ui/result/ResultFlowDecisions.kt`.
                    target = ResultTarget.DevicePhoto(uri),
                    onBack = { navController.popBackStack() },
                    activeReferenceStyle = activeReferenceStyle,
                    activeReferenceImageUri = activeReferenceImageUri,
                    onCreateReference = onCreateReference,
                    onDeleteReference = onDeleteReference,
                )
            }
        }

        // Hosted once, above the NavHost, so it overlays whichever screen the
        // flow was started from — both camera and result only need the `+` entry
        // point and the `내 레퍼런스` slot.
        ReferenceCreateSheet(
            state = referenceState,
            previewImageUri = pickedReferenceUri,
            onDismiss = onDismissReferenceSheet,
            onConfirmUpload = { referenceController.confirmUpload(context) },
            onRetry = { referenceController.confirmUpload(context) },
            onApply = { pickedScope -> referenceController.apply(pickedScope) },
        )
    }
}

/**
 * Reconstructs the active reference's [ResolvedStyle] from whatever
 * [ReferenceRepository.active] + the [AppContainer.settingsRepository] scope/
 * strength say is currently active — i.e. what was persisted by a previous
 * `apply()`, possibly in an earlier app session. Returns null on any failure or
 * absence, which the caller treats the same as "no reference" rather than
 * crashing the nav host.
 */
private suspend fun loadActiveReferenceStyle(container: AppContainer): ResolvedStyle? {
    val settings = container.settingsRepository
    val resolution = container.referenceRepository.active(settings) ?: return null
    val scope = runCatching {
        ResolvedStyle.ReferenceScope.valueOf(settings.getActiveReferenceScope().uppercase())
    }.getOrDefault(ResolvedStyle.ReferenceScope.BOTH)
    return ResolvedStyle.fromReference(
        hash = resolution.contentHash,
        target = resolution.targetComposition,
        colorTarget = resolution.colorTarget,
        scope = scope,
        strength = settings.getActiveReferenceStrength(),
    )
}
