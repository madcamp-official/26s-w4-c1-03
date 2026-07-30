package com.gamdo.app.ui.result

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.local.entity.Captures
import com.gamdo.app.data.ReferenceRepository
import com.gamdo.app.data.ResultFilterKind
import com.gamdo.app.data.ResultFilterStateHolder
import com.gamdo.app.core.ExifSanitizer
import com.gamdo.app.data.network.RescueAnalysisResponse
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.data.rescue.RescueState
import com.gamdo.app.edit.CaptureConditions
import com.gamdo.app.edit.EditImageSource
import com.gamdo.app.edit.EditPlan
import com.gamdo.app.edit.EditTool
import com.gamdo.app.edit.FileEditImageSource
import com.gamdo.app.edit.FilterEngine
import com.gamdo.app.edit.QuickFilterEditor
import com.gamdo.app.edit.LocalEditor
import com.gamdo.app.edit.LocalFilter
import com.gamdo.app.edit.SaveRender
import com.gamdo.app.edit.UriEditImageSource
import com.gamdo.app.edit.pixelBuffer
import com.gamdo.app.edit.renderForSave
import com.gamdo.app.edit.renderLatest
import com.gamdo.app.ui.reference.AiRestoreThumb
import com.gamdo.app.ui.reference.CreateReferenceThumb
import com.gamdo.app.ui.reference.MyReferenceThumb
import com.gamdo.app.ui.reference.StripEntry
import com.gamdo.app.ui.reference.buildFilterStrip
import com.gamdo.app.ui.rescue.GenerativeBadge
import com.gamdo.app.ui.rescue.RescueCandidate
import com.gamdo.app.ui.rescue.RescueLocalResult
import com.gamdo.app.ui.rescue.RescueSheet
import com.gamdo.app.ui.rescue.canSubmit
import com.gamdo.app.ui.rescue.referenceCompositionJson
import com.gamdo.app.ui.rescue.reopensOnOutcome
import com.gamdo.app.ui.rescue.rescueCandidates
import com.gamdo.app.ui.rescue.retainedCandidateId
import com.gamdo.app.ui.rescue.retainedRecommendations
import com.gamdo.app.ui.rescue.retainedRunningOperation
import com.gamdo.app.ui.rescue.styleParamsJson
import com.gamdo.app.ui.theme.GamdoType
import com.gamdo.app.ui.theme.Ink800
import com.gamdo.app.ui.theme.Ink900
import com.gamdo.app.ui.theme.Ink950
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextMid
import com.gamdo.app.ui.theme.TextLow
import com.gamdo.app.ui.theme.Amber
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val TAG = "ResultScreen"

private data class RescueInput(val file: File, val captureRef: String)

/**
 * What the screen has managed to open, as one value.
 *
 * Three states, not two, and the third one is the point: `null` used to mean both
 * "still decoding" and "this will never decode", so a photo that could not be read
 * left the screen on 사진을 불러오는 중이에요 forever. A device photo can be deleted
 * between the album listing it and the user tapping it, which makes that a reachable
 * state rather than a theoretical one (W3.5-6).
 */
private sealed interface OpenedPhoto {

    /** The decode is in flight. */
    data object Loading : OpenedPhoto

    /**
     * The photo, after whatever §4-1 passes [com.gamdo.app.ui.result.correctionPassesFor]
     * allowed, plus the [plan] that produced it so the save path can re-apply the
     * identical correction at full resolution.
     *
     * [plan] is null when no pass ran — either O-12 held them all back, or the
     * correction failed and this is the untouched decode. Both mean the same thing
     * downstream: there is nothing for the save path to re-apply.
     */
    data class Ready(val bitmap: Bitmap, val plan: EditPlan?) : OpenedPhoto

    /** The source could not be read at all. Terminal — nothing else is coming. */
    data object Unavailable : OpenedPhoto
}

/**
 * Everything the preview render depends on, in one value so the render loop has a
 * single thing to watch and a single thing to compare.
 *
 * Equality is what decides whether a render happens at all: [FilterEngine.Adjustments]
 * is a data class, so a ruler pushed against the end of its range produces an equal
 * request and no work, and [Bitmap] compares by identity, which is what "a different
 * photo" means here.
 */
private data class PreviewRequest(
    val source: Bitmap?,
    val filter: LocalFilter,
    val adjustments: FilterEngine.Adjustments,
)

/**
 * Where a strip selection puts every slider, for this photograph.
 *
 * The `내 감도` slot seeds to NEUTRAL rather than to a recipe: a reference is not a
 * [LocalFilter] — that enum is a closed, hand-authored set of
 * [com.gamdo.app.edit.PhotoFilter] recipes, while a reference's colour target is
 * arbitrary analysis output — and its colour is already rendered into the base
 * bitmap (see [ResultScreen]'s `corrected`). So the sliders start at zero and only
 * add a further adjustment on top, exactly as 원본 does.
 */
private fun seedFor(
    filterId: String,
    measure: FilterEngine.Measure,
): FilterEngine.Adjustments = when (filterId) {
    ResultFilterStateHolder.REFERENCE_FILTER_ID -> FilterEngine.Adjustments.NEUTRAL
    else -> FilterEngine.seedFrom(localFilterFor(filterId).filter, measure)
}

/**
 * @param activeReferenceStyle **no longer read by this screen** (P1-B2). It is the
 *   nav host's `remember`ed copy of the active AI 2 reference, and a copy is the
 *   problem: it dies with its composition, so an Activity recreation took the
 *   `내 감도` slot off the strip. The screen reads
 *   [com.gamdo.app.data.ResultFilterStateHolder] instead — the same value, published
 *   by the same `ReferenceRepository` event, but stored on `AppContainer` where it
 *   outlives the composition. The parameter stays only because the nav host is not
 *   this change's to edit; removing it there and here is a follow-up.
 * @param activeReferenceImageUri the picked photo behind the active reference, for
 *   the strip thumbnail (see `CameraScreen`'s param of the same name for why this
 *   can be null even when a reference is active).
 * @param onCreateReference O-10's leading `+` — opens the same picker/flow as
 *   the camera screen's.
 * @param onOpenReferenceDetail the reference slot's `⋯` badge — opens 내 감도 상세,
 *   where 취향 정교화 and 삭제 both live. It no longer deletes: see [MyReferenceThumb].
 *
 * O-10's `AI로 보정` slot (AI 3 / 사진 살리기) is wired **inside** this screen rather
 * than handed out as a callback: unlike AI 2, whose flow starts from either camera or
 * result and therefore lives at the nav host, AI 3 exists only here and every input it
 * needs — the capture, its file on disk, the active style — is already this screen's.
 * See [RescueSheet] and `docs/AI3_사진살리기_통합계약_2026-07-28.md`'s "P1 연결 계약".
 *
 * @param target which photo is open, and therefore which of O-12's two behaviours
 *   applies. See [ResultTarget]; the rules themselves are in `ResultFlowDecisions.kt`.
 */
@Composable
fun ResultScreen(
    container: AppContainer,
    target: ResultTarget,
    onBack: () -> Unit,
    activeReferenceStyle: ResolvedStyle? = null,
    activeReferenceImageUri: Uri? = null,
    onCreateReference: () -> Unit = {},
    onOpenReferenceDetail: () -> Unit = {},
) {
    val sourceKind = target.kind
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    // Null for a device photo, and that is a fact rather than a missing value —
    // everything keyed on it (AI 3, the edit stack) is gated on the same branch.
    val captureId: String? = (target as? ResultTarget.AppCapture)?.captureId
    val capture by produceState<Captures?>(initialValue = null, target, container) {
        val id = captureId ?: return@produceState
        value = withContext(Dispatchers.IO) {
            container.database.capturesDao().get(id)
        }
    }
    // AI 3 accepts both app captures and MediaStore photos. Device photos are
    // copied into an app-owned cache file once, sanitized before upload, and
    // addressed by a content hash rather than inventing a captures row.
    val rescueInput by produceState<RescueInput?>(initialValue = null, target, capture) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (capture != null) {
                    RescueInput(File(capture!!.filePath), capture!!.id)
                } else if (target is ResultTarget.DevicePhoto) {
                    val bytes = contentResolver.openInputStream(target.uri)?.use { it.readBytes() }
                        ?: error("device_photo_unreadable")
                    val hash = ReferenceRepository.sha256Hex(bytes)
                    val dir = File(context.cacheDir, "rescue-input").apply { mkdirs() }
                    val file = File(dir, "$hash.jpg")
                    if (!file.exists()) file.writeBytes(bytes)
                    ExifSanitizer.sanitizeFile(file)
                    RescueInput(file, "device_${hash.take(24)}")
                } else null
            }.onFailure { Log.w(TAG, "could not prepare rescue input", it) }.getOrNull()
        }
    }
    // ---- AI 3 (사진 살리기) --------------------------------------------------
    //
    // The controller is the single source of truth for the flow (integration
    // contract: "P1은 화면·디자인을 소유한다. 다음 호출만 연결하면 된다"). Everything
    // below is either a call into it, or a *derived* value that cannot outlive the
    // state that justifies it — see `RescueFlowDecisions.kt`'s retention rules.
    val rescueController = container.rescueController
    val rescueState by rescueController.state.collectAsState()
    // Whether the sheet is open. `Idle` alone cannot say — it means both "closed"
    // and "open, before anything has been sent".
    var rescueOpened by remember { mutableStateOf(false) }
    // The in-flight analyze/submit call, so 취소 can actually cancel it.
    var rescueJob by remember { mutableStateOf<Job?>(null) }
    var rescueResponse by remember { mutableStateOf<RescueAnalysisResponse?>(null) }
    var rescueRunningOperation by remember { mutableStateOf<JsonObject?>(null) }
    var pickedCandidateId by remember { mutableStateOf<String?>(null) }
    // The controller is an application-scoped singleton, so it can still be holding
    // *another* capture's Candidates when this screen opens. Without this, opening
    // photo B would show photo A's results and picking one would put A's generated
    // file into B's editor.
    LaunchedEffect(target) {
        rescueOpened = false
        rescueController.reset()
    }
    // One place where every retained value is re-derived from the controller state.
    // Anything set when the flow starts is dropped by the same rule that governs a
    // flow ending in success, in failure, or in a cancel — there is no per-path
    // cleanup to forget.
    // The state the last pass saw, so the effect below can act on the *edge* into an
    // outcome rather than on the outcome itself. Plain `remember`: a recreation loses
    // the running job with it (the poll lives on this screen's coroutine scope), so
    // there is no edge left to restore.
    var previousRescueState by remember { mutableStateOf(rescueState) }
    LaunchedEffect(rescueState) {
        rescueResponse = retainedRecommendations(rescueState, rescueResponse)
        rescueRunningOperation = retainedRunningOperation(rescueState, rescueRunningOperation)
        pickedCandidateId = retainedCandidateId(rescueState, pickedCandidateId)
        // A job the user stepped away from has finished — bring its result back to
        // them. See `reopensOnOutcome` for why this is an edge and not a state.
        if (reopensOnOutcome(previousRescueState, rescueState, rescueOpened)) {
            Log.d(TAG, "rescueSheetReopened outcome=${rescueState::class.simpleName}")
            rescueOpened = true
        }
        previousRescueState = rescueState
    }
    // What the job downloaded, read back from `edit_results_local` rather than from
    // the response: the response's `url` points at the server, the row's `file_path`
    // is the copy that actually landed in app storage.
    val candidates by produceState(initialValue = emptyList<RescueCandidate>(), rescueState) {
        val done = rescueState as? RescueState.Candidates
        value = if (done == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                val rows = runCatching {
                    container.database.editResultsLocalDao().forJob(done.jobId).map { row ->
                        RescueLocalResult(resultId = row.id, filePath = row.filePath, rank = row.rank)
                    }
                }.getOrElse {
                    Log.w(TAG, "could not read the downloaded results for ${done.jobId}", it)
                    emptyList()
                }
                val downloaded = if (done.downloaded.isNotEmpty()) {
                    done.downloaded.map { RescueLocalResult(it.resultId, it.filePath, it.rank) }
                } else rows
                rescueCandidates(done.results, downloaded)
            }
        }
    }
    val pickedCandidate = candidates.firstOrNull { it.resultId == pickedCandidateId }

    // ---- P1-B2: the filter strip, from the one place that owns it ------------
    //
    // `ResultFilterStateHolder` hangs off `AppContainer`, so it outlives this
    // composition, the nav host's, and an Activity recreation. It is fed by
    // `ReferenceRepository.publishActiveReference`, which is the same event that
    // used to set the `activeReferenceStyle` parameter — so this is not a second
    // reader of the same fact, it is the *first* reader of the durable copy, and
    // the parameter's copy is the one being retired.
    //
    // Two things used to live in `remember`s here and were lost with them:
    //
    //  - the item list, via the `activeReferenceStyle` parameter, which the nav
    //    host holds in a plain `remember`. An Activity recreation dropped the
    //    `내 감도` slot until an async Room read put it back, and the user saw the
    //    strip they had just added a 감도 to with nothing in it.
    //  - the selection, in a `remember` in this function, which the nav host
    //    destroys on `popBackStack()`. 앨범 → 결과 → 뒤로 → 결과 forgot the pick
    //    every time.
    //
    // Neither is a fallback for the other now; there is one store.
    val filterHolder = container.resultFilterStateHolder
    val filterState by filterHolder.state.collectAsState()
    val selectedFilterId = filterState.selectedId
    // The active reference, from the durable copy rather than from the parameter.
    val activeReference = filterState.activeReference
    val hasReferenceColor = hasReferenceSlot(filterState)
    // O-12: whether the *user* put the strip where it is. Not derivable from the
    // selection — on an app capture the screen seeds one below, and a seeded
    // selection is not a choice. Every strip tap sets this; every new photo
    // clears it, because a choice is made about a photograph, not about the app.
    //
    // `rememberSaveable`, so it survives an Activity recreation the way the holder's
    // `selectedId` does. With a plain `remember` a rotation reported "the user has
    // not chosen" and the screen re-seeded the opening look over the filter they had
    // just picked — the same class of overwrite the effect below exists to prevent,
    // arriving by a different route.
    //
    // It stores the id rather than a bare flag, because a flag outlives the thing it
    // refers to. `selectedId` lives in the app-scoped holder, which survives a
    // *rotation* but not process death; the saved flag survives both. So after the OS
    // restarted the app the screen restored "the user has chosen" on top of a holder
    // rebuilt at 원본, and `selectionOnOpen` dutifully answered 원본 — the restored
    // flag actively suppressed `recommendedDefaultFilterId`, which by then held the
    // right answer. The user's 내 감도 was gone from under them on relaunch, which is
    // 앱 재시작 복원 in the brief's §7 and D-1 (6). Saving the id makes the two
    // recoveries agree: rotation re-selects what is already selected, and a cold start
    // re-selects what the holder no longer knows.
    var chosenFilterId by rememberSaveable { mutableStateOf<String?>(null) }
    val styleChosenByUser = chosenFilterId != null
    // O-15 (1): the preset that was on screen when the shutter was pressed, not the
    // one chosen during onboarding. `CameraScreen` already records it on the
    // session; this reads that row and falls back to the profile when there is no
    // session (a gallery import) or the session recorded none. Keyed on `capture`
    // so it re-resolves once the row arrives — the first pass sees null, which is
    // exactly why the selection below cannot be re-seeded unconditionally.
    val presetIds by produceState<Pair<String?, String?>?>(null, container, capture) {
        val sessionPresetId = capture?.sessionId?.let { sessionId ->
            runCatching { container.database.sessionsDao().get(sessionId)?.stylePresetId }.getOrNull()
        }
        value = sessionPresetId to container.settingsRepository.getStylePresetId()
    }
    // A pick belongs to the photo it was made about. Declared before the effect
    // that reads it so it lands first when both restart on a new `target`.
    LaunchedEffect(target) { chosenFilterId = null }
    // What this photo opens on, pushed into the shared store.
    //
    // Re-running is safe *because* of `selectionOnOpen`: once the user has tapped,
    // it answers with what the holder already has, so the late arrival of
    // `presetIds` — one Room read for `captures`, another for `sessions` — cannot
    // reach in and undo a filter chosen inside that window. The old code re-seeded
    // on every change and did exactly that.
    LaunchedEffect(target, presetIds, filterState.items, hasReferenceColor) {
        val ids = presetIds ?: return@LaunchedEffect
        val next = selectionOnOpen(
            source = sourceKind,
            userHasChosen = styleChosenByUser,
            // The saved pick when there is one. Identical to `selectedFilterId` in
            // every case except the one this closes — a cold start, where the holder
            // has forgotten and only the saved id remembers. The effect re-runs on
            // `filterState.items`, so a pick of 내 감도 that cannot be honoured yet
            // (the reference row loads asynchronously) is retried when the slot lands
            // rather than being lost to the 원본 fallback below.
            currentSelectedId = chosenFilterId ?: selectedFilterId,
            hasActiveReferenceColor = hasReferenceColor,
            sessionPresetId = ids.first,
            profilePresetId = ids.second,
        )
        filterHolder.setRecommendedDefault(next)
        // `select` refuses an id the catalogue does not carry. That is a real case
        // — a `sessions` row naming a preset this build no longer ships — and 원본
        // is the honest answer to it rather than leaving the strip on whatever the
        // previous photo was.
        if (!filterHolder.select(next)) filterHolder.select(LocalFilter.ORIGINAL.filter.id)
    }
    val stylePick = stylePickFor(
        source = sourceKind,
        selection = stripSelectionFor(selectedFilterId),
        chosenByUser = styleChosenByUser,
    )
    // The whole O-12 decision, in one value the render below can key on.
    val passes = correctionPassesFor(sourceKind, stylePick)
    /*
     * Where the editor reads its pixels — **the one seam**, used by the preview
     * decode, the size probe and the save-time re-decode alike. A picked AI 3
     * candidate stands in for the original **as an input only**: the original is
     * never written to (contract: 원본 덮어쓰기 금지), and the save path still
     * produces a new file.
     *
     * `File(path)` cannot serve all three any more. A device photo lives behind
     * `ContentResolver.openInputStream` — scoped storage makes its real path
     * unreadable — so the branch happens once, here, rather than at each read.
     * See `edit/EditImageSource.kt`.
     *
     * `remember`ed rather than rebuilt per composition because it is a
     * `produceState` key below: a fresh instance every frame would re-decode the
     * photo every frame.
     */
    val editSource: EditImageSource? = remember(
        target,
        capture?.filePath,
        pickedCandidate?.filePath,
        contentResolver,
    ) {
        val candidate = pickedCandidate?.filePath
        when {
            candidate != null -> FileEditImageSource(File(candidate))
            target is ResultTarget.DevicePhoto -> UriEditImageSource(contentResolver, target.uri)
            else -> capture?.filePath?.let { FileEditImageSource(File(it)) }
        }
    }
    // A device photo has no `conditions_json` — no shutter, no tilt reading — so this
    // is `CaptureConditions.NONE` there. That is *not* what stops the levelling pass
    // (a zero tilt would still crop); [passes] is. See `correctionPassesFor`.
    val conditions = remember(capture?.conditionsJson) {
        CaptureConditions.parse(capture?.conditionsJson)
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
    //
    // Keyed on [passes] rather than on the selection: that value *is* what the
    // strip contributes to this pass — `applyStyle` for the `내 감도` slot, which
    // folds the reference's colour into the *plan* itself (`EditPlanner.plan`'s
    // `resolvedStyle`, the "ColorTarget → ColorParams → LocalEditor" seam the
    // integration contract names) rather than going through `QuickFilterEditor` like
    // every preset. Keying on the decision instead of the selection also means
    // switching between two presets no longer re-decodes and re-plans for an
    // identical result.
    //
    // Keyed on [editSource] rather than the capture's own path: picking an AI 3
    // candidate swaps which file this pass reads, and the levelling/exposure plan is
    // then recomputed from *that* bitmap. The generated file was produced from the
    // untouched original upload, so it arrives un-levelled and needs the same
    // correction; re-planning from its own dimensions is also what keeps the crop
    // right when the operation changed them (outpaint).
    val corrected by produceState<OpenedPhoto>(
        initialValue = OpenedPhoto.Loading,
        editSource,
        passes,
        conditions,
        activeReference,
    ) {
        val image = editSource ?: return@produceState
        // Decode off the composition thread so entering the editor never blocks
        // the first frame, and decode *small*.
        //
        // The filter is a per-pixel pass and it re-runs on every filter tap and on
        // every frame of a slider drag. Decoding at full resolution meant each of
        // those re-rendered 2904x3630 = 10.5M pixels for a preview being displayed
        // at roughly 940 wide — about twelve times more work than the screen can
        // show, on the interaction path. Saving still uses the full file; see the
        // save button below.
        // What is already on screen, read before the producer replaces it. Only a
        // *re*-run has one — the first pass through here sees `Loading`.
        val standing = value as? OpenedPhoto.Ready
        value = withContext(Dispatchers.Default) {
            // The decode used to sit outside the `runCatching` below, and that was
            // the second half of 결함 3 ("사진이 사라지는 현상"). Selecting 내 감도 is
            // the *only* strip tap that re-enters this producer — `passes` is a key,
            // and it differs for the reference slot alone — so a decode that failed
            // here failed exactly when the user picked their own look:
            //
            //  - `null` became `Unavailable`, which replaces the photo with 사진을
            //    열지 못했어요 and stays there, because nothing re-runs until a key
            //    changes again.
            //  - `OutOfMemoryError` is an `Error`, so neither `BitmapFactory`'s own
            //    `catch (Exception)` nor an absent guard here would hold it, and it
            //    escaped the producer's coroutine and took the Recomposer — the whole
            //    screen, strip included — down with it. This tap is the screen's peak:
            //    the outgoing `corrected` bitmap, the outgoing `edited` bitmap, the
            //    render loop's scratch `IntArray` and a fresh measure buffer are all
            //    live at once, and none of them is recycled.
            //
            // `runCatching` takes `Throwable`, which is the point — an allocation
            // failure on a re-decode must cost the user their *tap*, not their photo.
            val preview = runCatching { image.decode(EDITOR_DECODE_MAX_SIDE) }
                .onFailure { Log.w(TAG, "decode failed for $target; keeping what is on screen", it) }
                .getOrNull()
            // Falling back to the standing bitmap rather than to `Unavailable`: the
            // photo opened once, so "열지 못했어요" would be a false statement about a
            // file that is demonstrably readable. `Unavailable` stays the answer for
            // the first decode, which is the case it was written for.
                ?: return@withContext standing ?: OpenedPhoto.Unavailable
            // **O-12.** A device photo shows the decode and nothing else until the
            // user picks a look — no rotation, no exposure, no white balance, no
            // crop. `plan = null` carries that all the way to the save path, which
            // re-applies the plan and therefore re-applies nothing.
            if (!passes.runsAnyPass) return@withContext OpenedPhoto.Ready(preview, plan = null)
            // Every failure below falls back to the untouched decode. An
            // auto-correction the user never asked for must never be the reason a
            // photo will not open.
            runCatching {
                val fullSize = image.readSize() ?: (preview.width to preview.height)
                val editor = LocalEditor()
                val sample = editor.sample(
                    bitmap = preview,
                    tiltDeg = conditions.tiltDegOrZero,
                    subject = conditions.subject,
                    sourceWidth = fullSize.first,
                    sourceHeight = fullSize.second,
                )
                // preset = null → geometry + optical only, *unless* the reference
                // slot is selected, in which case `resolvedStyle` supplies the
                // colour stage (O-2's "auto-applying a look the user did not pick"
                // guard does not apply here — selecting the slot *is* the pick).
                val plan = editor.plan(
                    sample = sample,
                    preset = null,
                    applyStyle = passes.style,
                    resolvedStyle = activeReference.takeIf { passes.style },
                    subject = conditions.subject,
                    requestedMaxSide = EDITOR_DECODE_MAX_SIDE,
                )
                OpenedPhoto.Ready(editor.render(preview, plan).bitmap, plan)
            }.getOrElse {
                Log.w(TAG, "auto-correction failed for $target; showing the original", it)
                OpenedPhoto.Ready(preview, plan = null)
            }
        }
    }
    val source: Bitmap? = (corrected as? OpenedPhoto.Ready)?.bitmap
    // The strip's own recipe, on top of whichever bitmap `corrected` produced:
    // ORIGINAL is an identity `PhotoFilter`, so selecting `내 감도` above means
    // "no second colour pass here, only what the sliders add" — the reference's
    // colour already rendered into `source`. Every preset keeps working exactly
    // as before, unaffected by any of this.
    val effectiveLocalFilter = localFilterFor(selectedFilterId)
    // What actually gets written to `capture_edit_stack.paramsJson` (a free-form
    // string column, no schema change needed). Distinct from `effectiveLocalFilter`
    // — that one exists to reuse `QuickFilterEditor`'s ORIGINAL identity recipe,
    // but recording "ORIGINAL" here would silently lose that a reference was used.
    val filterRecordName = editRecordFilterName(selectedFilterId)
    // One luminance pass per photo, kept so seeding a filter does not re-measure.
    // It is only needed to cap a preset's exposure against this frame's headroom.
    val measure by produceState<FilterEngine.Measure?>(null, source) {
        val bitmap = source ?: return@produceState
        // Allocates an `IntArray(w*h)` of its own, on the same peak this screen hits
        // when 내 감도 is picked — guarded for the reason the decode above is. Losing
        // the measurement costs a preset its exposure cap; losing the screen costs
        // the photo.
        value = withContext(Dispatchers.Default) {
            runCatching { QuickFilterEditor.measure(bitmap) }
                .onFailure { Log.w(TAG, "measure failed for $target; presets seed uncapped", it) }
                .getOrNull()
        }
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
    //
    // `내 감도` has no `PhotoFilter` recipe to seed from — its colour is
    // already in `source` (see `corrected` above) — so it seeds to NEUTRAL: the
    // sliders start at zero and only add a *further* adjustment on top, same as
    // 원본 always has.
    //
    // This effect covers the two seedings a tap cannot: the selection the screen
    // makes for itself when a photo opens, and `measure` arriving a frame after the
    // bitmap. A **tap** seeds through [selectStrip] instead — see there for why the
    // difference is worth a whole preview pass.
    LaunchedEffect(selectedFilterId, measure) {
        val m = measure ?: return@LaunchedEffect
        val seeded = seedFor(selectedFilterId, m)
        baseline = seeded
        adjustments = seeded
    }
    // Selecting a strip item and seeding its sliders, as **one** state change.
    //
    // Doing the seeding in the effect above and nothing else here meant composition
    // saw an intermediate: the new filter with the *previous* filter's slider
    // positions. That pair is not a state the user can produce, and it is not free —
    // it is a distinct `PreviewRequest`, so the render loop spent a whole preview
    // pass on it, published it, and only then rendered the pair the tap actually
    // asked for. A filter tap cost two passes and showed the wrong one first, which
    // on a phone is most of what "필터 적용이 너무 느려" was.
    //
    // Compose batches state writes made in one event handler into a single
    // recomposition, so all three land together and the intermediate never exists.
    // The effect above still runs afterwards and writes the identical value, which
    // `mutableStateOf`'s structural equality turns into a no-op.
    //
    // The selection itself goes into the shared holder rather than into a local
    // `remember`, which is what makes it survive leaving this screen — and
    // `select` changes `selectedId` and nothing else, so the catalogue cannot be
    // rebuilt or hidden by the act of choosing from it (P1-B2).
    val selectStrip: (String) -> Unit = { id ->
        if (filterHolder.select(id)) {
            chosenFilterId = id
            measure?.let { m ->
                val seeded = seedFor(id, m)
                baseline = seeded
                adjustments = seeded
            }
        }
    }
    // Null until a save lands; then whether the gallery accepted it. Reduced from the
    // whole `SavedEdit` because the two save paths return different records — an app
    // capture's derivative is tied to a `captures` row, a device photo's is a
    // standalone file — and this is the only field the screen ever read.
    var saved by remember { mutableStateOf<Boolean?>(null) }
    // Saving re-renders the full-resolution file, which takes seconds. Without a
    // state for it the button looks inert and invites a second tap.
    var saving by remember { mutableStateOf(false) }
    // A save can fail — full disk, refused MediaStore insert — and the user has to
    // hear about it. 2f has no status line, so this stays empty except on failure.
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The interactive preview. This used to be a `produceState` keyed on
    // `adjustments`, which meant one full-frame filter pass **per ruler tick** — up
    // to sixty a second, running concurrently on every core of `Dispatchers.Default`,
    // most of them already superseded before they finished and then dropped at the
    // `withContext` boundary because the pixel loop has no suspension point for
    // cancellation to land on. `renderLatest` is the rule that replaces it: one
    // render in flight, and the ticks that pile up behind it collapse to the newest.
    // See `edit/PreviewRenderLoop.kt`.
    var edited by remember { mutableStateOf<Bitmap?>(null) }
    // Not `by`: the effect below has to read this *inside* `snapshotFlow` for the
    // flow to observe it. A captured value would pin the loop to whatever the first
    // composition happened to hold.
    val request = rememberUpdatedState(PreviewRequest(source, effectiveLocalFilter, adjustments))
    LaunchedEffect(Unit) {
        // Owned by this loop and handed to nothing else. Safe only because
        // `renderLatest` serialises the renders — see `QuickFilterEditor.apply`.
        var scratch: IntArray? = null
        renderLatest(
            requests = snapshotFlow { request.value },
            render = { pending ->
                // P2 asked for the render outcome to be reported, and the reason is
                // the defect it guards: `FilterRenderState` is a *separate* field
                // from `items`, so a preview that cannot be produced is recorded
                // without the catalogue moving. Reporting it here is what makes that
                // separation observable rather than merely available — and it is why
                // the strip below draws from `filterState.items` and never from
                // whether `edited` is null.
                filterHolder.renderStarted()
                val bitmap = pending.source
                if (bitmap == null) {
                    // No pixels yet is not a failed render — the decode is still in
                    // flight, and calling it a failure would log one on every open.
                    null
                } else {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            val buffer = pixelBuffer(scratch, bitmap.width, bitmap.height)
                            scratch = buffer
                            QuickFilterEditor.apply(bitmap, pending.filter, pending.adjustments, buffer)
                        }
                    }.onFailure {
                        // Leaving the screen cancels this loop, and a cancellation is
                        // not a failed render — swallowing it here would also break
                        // out of structured concurrency.
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        // R7-1: nothing about this reaches the user. The photo stays
                        // on `source` (the untouched decode) and every control stays
                        // where it was; a filter that will not render is not a reason
                        // to take the other six away.
                        Log.w(TAG, "preview render failed for ${pending.filter.name}", it)
                        filterHolder.renderFailed()
                    }.getOrNull()
                }
            },
            publish = { _, rendered ->
                edited = rendered
                if (rendered != null) filterHolder.renderSucceeded()
            },
        )
    }

    // 저장 moved to the header pill (시안 07), so the action is named here and the
    // pill only references it. Same body, same order, same failure handling as the
    // full-width button it replaces — only the surface it hangs off changed.
    fun performSave() {
        if (saving) return
        // The seam, not the capture: a device photo has no row to check
        // and this is the same value the preview rendered from.
        val image = editSource ?: return
        saving = true
        // A retry starts clean, or the complaint from the attempt that
        // failed stays on screen underneath one that worked.
        saveError = null
        scope.launch {
            try {
                // Re-render at full resolution rather than saving the
                // preview: `edited` is a downsampled bitmap now, and
                // writing it out would quietly ship a low-resolution
                // photo to the gallery.
                //
                // Which is what this used to do. The chain ended in
                // `?: edited ?: source`, so a full decode that came back
                // null — missing file, truncated JPEG — silently wrote the
                // editor-resolution bitmap instead and still reported
                // 갤러리에 저장됨. `renderForSave` has nowhere to put a
                // preview, so the fallback cannot come back by accident.
                // Null when O-12 held every pass back, or when the
                // correction failed — both mean there is nothing to
                // re-apply and the save writes what the screen showed.
                val plan = (corrected as? OpenedPhoto.Ready)?.plan
                val rendered = withContext(Dispatchers.Default) {
                    renderForSave(
                        decodeFullResolution = { image.decode(SAVE_MAX_SIDE) },
                        // The **same** plan the preview used, only at save
                        // resolution. Re-planning here from a larger decode
                        // would let the saved crop drift from the one the
                        // user approved.
                        correct = { full ->
                            plan?.let { p ->
                                LocalEditor().render(full, p.withProcessingMaxSide(SAVE_MAX_SIDE)).bitmap
                            } ?: full
                        },
                        style = { levelled ->
                            QuickFilterEditor.apply(levelled, effectiveLocalFilter, adjustments)
                        },
                        onCorrectionFailed = {
                            Log.w(TAG, "save-time auto-correction failed; saving uncorrected", it)
                        },
                    )
                }
                if (rendered !is SaveRender.Ready) {
                    // R7-1: what happened, in the words someone would use
                    // about their own photo. The path and the resolution go
                    // to the log, where they are useful.
                    Log.w(TAG, "save refused: could not decode $image at ${SAVE_MAX_SIDE}px")
                    saveError = "원본 사진을 열지 못했어요. 저장하지 않았습니다"
                    return@launch
                }
                // D8-6, in both branches: the edited pixels go to a new
                // file. Neither path opens the source for writing — and
                // for a device photo the source is a `content://` Uri the
                // editor only ever calls `openInputStream` on.
                val savedToGallery = when (saveTargetFor(sourceKind)) {
                    SaveTarget.CAPTURE_DERIVATIVE -> container.captureRepository
                        .saveEditedCapture(
                            // Non-null exactly when the target is an app
                            // capture; the `?:` below is unreachable
                            // rather than a fallback, and it degrades to
                            // "still saves the photo" instead of throwing.
                            captureId = captureId ?: return@launch,
                            bitmap = rendered.image,
                            // §4-1 비파괴: the record has to hold every
                            // control, not the three the old panel happened
                            // to show, or a saved edit cannot be reopened
                            // as what it was.
                            paramsJson = "{\"filter\":\"$filterRecordName\"," +
                                "\"adjustments\":${EditTool.toJson(adjustments)}}",
                        ).savedToGallery
                    // No `captures` row to hang a `capture_edit_stack` step
                    // off, and Room is frozen (R2) so one cannot be
                    // invented. The edit becomes a new file plus a gallery
                    // copy, and the parameters are not recorded — see the
                    // W3.5-6 report's 저장 section.
                    SaveTarget.NEW_FILE_ONLY -> container.captureRepository
                        .saveDevicePhotoEdit(rendered.image).savedToGallery
                }
                saved = savedToGallery
                if (!savedToGallery) {
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
    }

    // Which tool surface is open, and which one to draw while the sheet slides shut.
    //
    // 시안 07 shows this screen with **no** dials and **no** filter strip: the photo is
    // nearly the whole screen and the bottom is two words. Both tool surfaces used to be
    // permanently mounted below the photo, which is what left the photograph — the thing
    // being judged — as a strip in the middle of its own editor.
    //
    // `rememberSaveable`, same reason as `styleChosenByUser` above. The filter strip is
    // mounted *only* inside this sheet, so with a plain `remember` any Activity
    // recreation — rotation, a font-size or dark-mode change, an OS restart after
    // memory pressure — closed the panel and took the strip off screen while the
    // holder behind it was perfectly intact. That is the third way 결함 3 was seen
    // ("필터 목록이 사라진다"), and it compounds with the decode above: the 내 감도 tap
    // is the screen's peak-memory moment, so the very tap that could trigger an
    // OS-initiated recreation is the one after which the strip failed to come back.
    // `ToolTab` is an enum, so the default saver handles it.
    var openTool by rememberSaveable { mutableStateOf<ToolTab?>(null) }
    // `AnimatedVisibility` keeps its child composed for the whole exit animation, so
    // reading `openTool` inside the sheet would blank it on the first frame of the close
    // and slide an empty panel off screen. This holds the last real choice, and it is set
    // in the same event as `openTool` rather than from a `LaunchedEffect`, so there is no
    // frame where the two disagree.
    var lastTool by remember { mutableStateOf(ToolTab.ADJUST) }

    // True only while a finger is held down on the photo — 시안 07's
    // "길게 누르면 원본이 보여요". Not `rememberSaveable`: a hold cannot survive a
    // configuration change, and restoring `true` would strand the screen showing 원본
    // with no finger down and no way to notice.
    var holdingOriginal by remember { mutableStateOf(false) }

    // The AI 3 sheet overlays the whole screen, so the editor becomes one child of a
    // Box rather than the root. Nothing about the editor's own layout changes.
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(Ink900)) {
        // 시안 07 header: `‹` and the `저장` pill, and nothing between them.
        //
        // `완료` is gone rather than moved. It called `onBack` — the identical action to
        // the `‹` two positions away — so the header had two controls for one job and
        // spent this screen's single permitted amber fill on the duplicate. Now the fill
        // is on 저장, which is the only thing here that changes anything.
        //
        // Both ends are real 44dp touch targets, reached with padding so the glyphs still
        // land where the design puts them.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 2.dp),
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
                Text("‹", color = TextMid, style = GamdoType.Title)
            }
            SavePill(
                // Three states, not two. A save whose MediaStore insert was refused still
                // wrote the app's own copy, and telling the user it is "갤러리에 저장됨"
                // when it is not there is the kind of claim AGENTS.md §7-6 rules out.
                text = when {
                    saved == true -> "갤러리에 저장됨"
                    saved != null -> "앱에 저장됨"
                    saving -> "저장 중…"
                    else -> "저장"
                },
                onClick = { performSave() },
            )
        }

        // 시안 07 puts the photo at `flex:1` with an 18dp radius and only 10dp of side
        // padding — it is meant to be almost the whole screen, which is what pulling the
        // dials and the strip into [ToolSheet] finally allows.
        Box(
            modifier = Modifier.padding(horizontal = 10.dp).fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(18.dp)).background(Ink950)
                // 시안 07's "길게 누르면 원본이 보여요", wired. `onPress` suspends until
                // the finger lifts, so the reveal lasts exactly as long as the hold
                // rather than toggling — a toggle would leave the screen showing 원본
                // with nothing on it saying so.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { holdingOriginal = true },
                        onPress = {
                            tryAwaitRelease()
                            holdingOriginal = false
                        },
                    )
                },
        ) {
            // While held, the filter and the dials come off — `source` is this
            // photograph before anything the strip or the panel did to it, which is the
            // same thing the strip's own 원본 tile selects. It is **not** the untouched
            // file: for an app capture, §4-1's automatic passes are already in `source`.
            // 원본 means "no filter of yours on top" throughout this app, and this uses
            // the word the same way rather than inventing a second meaning for it.
            val displayBitmap = if (holdingOriginal) source else edited ?: source
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
                // Two different sentences, because they are two different facts. The
                // screen used to say 불러오는 중 forever for a photo that was never
                // going to arrive — a `captures` id with no row, and now also a
                // device photo deleted between the album listing it and this tap.
                Text(
                    text = if (corrected is OpenedPhoto.Unavailable) {
                        "사진을 열지 못했어요"
                    } else {
                        "사진을 불러오는 중이에요"
                    },
                    color = TextLow,
                    style = GamdoType.Body,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 시안 07's hint chip. Only over a photo, and only while the user is not
            // already holding one down — once the reveal is happening the instruction
            // for it is noise, and it would sit on top of the very thing it invited the
            // user to look at.
            if (displayBitmap != null && !holdingOriginal) {
                Text(
                    text = "길게 누르면 원본이 보여요",
                    color = TextHi,
                    style = GamdoType.Micro,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        // The design asks for `rgba(10,10,11,.5)` **plus** an 8px
                        // backdrop blur. Compose's `Modifier.blur` blurs a node's own
                        // content, not what is behind it, and there is no backdrop-filter
                        // equivalent — so this is the translucency without the blur. On a
                        // busy photo it is therefore slightly less legible than the
                        // drawing; that is a known gap, not an oversight.
                        .background(Ink950.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }

            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // P1-B3 "활성 감도의 이름을 표시해 무엇이 적용됐는지 알게 하기".
                //
                // Only over a photo. The badge is a caption on the picture, and the
                // Box it sits in also renders 사진을 불러오는 중이에요 / 사진을 열지
                // 못했어요 — a Amber 밤거리 chip floating over either of those names a
                // look on something that is not there.
                // Amber **text on a scrim**, not an amber fill. Two reasons, and P1-B3's
                // meaning is untouched by both — `appliedStyleLabel` below is exactly the
                // call it was:
                //
                //  - the redesign allows one filled amber surface per screen, and 시안 07
                //    spends this screen's on the 저장 pill;
                //  - the palette's own rule is that controls and captions over a
                //    photograph are ghost or scrim, never a fill, because the photograph
                //    is the subject. An amber block sitting on the picture outranked it.
                if (displayBitmap != null) Box(
                    modifier = Modifier.clip(RoundedCornerShape(5.dp))
                        .background(Ink950.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    // [appliedStyleLabel], not [stripLabelFor]: the badge describes the
                    // pixels, and a style pass that threw leaves the strip pointing at
                    // 밤거리 while the photo underneath is the untouched decode. The
                    // rule, including why the reference slot is exempt from it, is
                    // documented on `appliedStyleLabel`.
                    Text(
                        text = appliedStyleLabel(
                            state = filterState,
                            // The reference's colour is part of the plan, so a plan is
                            // the evidence it landed. `passes.style` is what asked for
                            // one; null after that means `corrected` fell back to the
                            // untouched decode.
                            referenceColorLanded = !passes.style ||
                                (corrected as? OpenedPhoto.Ready)?.plan != null,
                        ),
                        color = Amber,
                        style = GamdoType.Micro,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // §5-3 "**'AI 생성 보완' 뱃지** 표시". It rides on the photo itself, not
                // only on the picker tile, so a generated photo can never be on this
                // screen — or be saved from it — without saying so.
                if (pickedCandidate != null) GenerativeBadge()
            }
        }

        saveError?.let { status ->
            Text(
                text = status,
                color = TextMid,
                style = GamdoType.Body,
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp),
            )
        }

        // 시안 08: the sheet **pushes the photo up** rather than covering it, which is why
        // it is a sibling in this Column and not an overlay in the enclosing Box. The
        // photo above holds `weight(1f)`, so it gives back exactly the height the sheet
        // takes — and that is also why there is no scrim here. A scrim dims what is
        // hidden behind it; nothing is hidden.
        AnimatedVisibility(
            visible = openTool != null,
            enter = slideInVertically(animationSpec = tween(TOOL_SHEET_MILLIS)) { it },
            exit = slideOutVertically(animationSpec = tween(TOOL_SHEET_MILLIS)) { it },
        ) {
            ToolSheet(onDismiss = { openTool = null }) {
                when (lastTool) {
                    // 시안 08: "필터 탭 = 카메라와 같은 스트립".
                    ToolTab.FILTER -> Row(
                        modifier = Modifier.padding(start = 18.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
            // O-10: `[+] [AI로 보정] [원본 + 6종…] [내 감도]?`.
            //
            // **P1-B2.** Every item drawn below comes from `filterState.items` — the
            // catalogue P2 maintains — and not one of them is conditional on a
            // bitmap. The six presets and 원본 are therefore on screen while the
            // photo is still decoding, after a render fails, and after an Activity
            // recreation, because none of those touch the list.
            //
            // The trailing slot is P2's answer to whether the active reference has a
            // colour half to offer: a 구도만 reference has nothing this screen can
            // apply (composition is a camera-screen concern), so P2 leaves it out of
            // the catalogue and the screen simply draws what it is given.
            val presetItems = filterState.items.filter { it.kind != ResultFilterKind.REFERENCE }
            // AI 3 accepts device photos too (it copies them to a cache file and
            // addresses them by content hash), so this is true for both sources.
            val offersAiRestore = offersGenerativeRestore(sourceKind)
            val strip = remember(presetItems, offersAiRestore, hasReferenceColor) {
                buildFilterStrip(
                    presets = presetItems,
                    includeAiRestore = offersAiRestore,
                    hasActiveReference = hasReferenceColor,
                )
            }
            strip.forEach { entry ->
                when (entry) {
                    is StripEntry.CreateReference -> CreateReferenceThumb(
                        shape = RoundedCornerShape(11.dp),
                        size = 58.dp,
                        onClick = onCreateReference,
                    )
                    // O-10's AI 3 entry point, and the only one. Opening the sheet
                    // sends nothing anywhere — `analyze()` is one deliberate tap
                    // further in (RescueSheet's INTRO section).
                    is StripEntry.AiRestore -> AiRestoreThumb(
                        shape = RoundedCornerShape(11.dp),
                        size = 58.dp,
                        onClick = { rescueOpened = true },
                    )
                    is StripEntry.Preset -> {
                        val item = entry.value
                        FilterThumb(
                            label = stripLabelFor(item),
                            asset = filterAsset(item.id),
                            selected = selectedFilterId == item.id,
                            // O-12: this tap, and the one below, are the only things
                            // that let a correction touch a device photo.
                            onClick = { selectStrip(item.id) },
                        )
                    }
                    is StripEntry.MyReference -> MyReferenceThumb(
                        shape = RoundedCornerShape(11.dp),
                        size = 58.dp,
                        imageUri = activeReferenceImageUri,
                        selected = selectedFilterId == ResultFilterStateHolder.REFERENCE_FILTER_ID,
                        onSelect = { selectStrip(ResultFilterStateHolder.REFERENCE_FILTER_ID) },
                        // No local fix-up of the selection any more: removing the
                        // reference removes the catalogue item, and the holder moves
                        // `selectedId` off an id it no longer carries. One rule, in
                        // the place that owns the list.
                        onOpenDetail = onOpenReferenceDetail,
                    )
                }
            }
            Box(Modifier.width(12.dp))
                    }

                    // 시안 08: "조절 탭 = 다이얼 · 틱 룰러". The ruler's drag and its
                    // double-tap reset are untouched — [AdjustmentPanel] is mounted here
                    // exactly as it was mounted inline, only somewhere else.
                    ToolTab.ADJUST -> AdjustmentPanel(
                        adjustments = adjustments,
                        baseline = baseline,
                        selected = selectedTool,
                        onSelect = { selectedTool = it },
                        onChange = { adjustments = it },
                    )
                }
            }
        }

        ToolTabBar(
            selected = openTool,
            // Tapping the open tab closes the sheet, which is the other half of
            // 시안 08's "아래로 내리면 닫힘": the same two words that opened it are
            // where the finger already is, so the drag is an alternative and not the
            // only way out. `lastTool` is set in this same event — see its
            // declaration for why it cannot be derived from `openTool` later.
            onSelect = { tab ->
                if (openTool == tab) {
                    openTool = null
                } else {
                    lastTool = tab
                    openTool = tab
                }
            },
        )
    }

        // ---- AI 3 sheet -----------------------------------------------------
        //
        // Every callback below is one of the five calls the integration contract
        // lists (`analyze` / `choose` / `submit` / `reset` + collecting `state`).
        // Nothing here duplicates the controller's decisions.
        //
        // 취소 is a real cancel: the coroutine driving the controller is cancelled
        // and **joined** before `reset()`. Without the join, `submit`'s own
        // `runCatching { … }.onFailure { LocalFallback }` would land *after* the
        // reset and strand the sheet on a fallback the user just cancelled out of.
        val cancelRescue: () -> Unit = {
            rescueOpened = false
            scope.launch {
                rescueJob?.cancelAndJoin()
                rescueJob = null
                // `pickedCandidateId` is not cleared here: `reset()` takes the
                // controller to Idle, and `retainedCandidateId` drops the pick on the
                // very next state emission. One rule, one place.
                rescueController.reset()
            }
        }
        // 기본 보정 유지 — the contract's other branch out of Candidates. Unlike a
        // cancel, this is a *choice*, so it also un-records the variant.
        val keepLocalResult: () -> Unit = {
            rescueOpened = false
            scope.launch {
                rescueJob?.cancelAndJoin()
                rescueJob = null
                rescueController.reset()
                // `captureId` is null only for a device photo, which cannot reach AI 3
                // at all (the strip slot is not drawn) — so this is a type-level
                // guard, not a behaviour change.
                runCatching { captureId?.let { container.database.captureEditStackDao().setSelectedResult(it, null) } }
                    .onFailure { Log.w(TAG, "clearing selected_result_id failed", it) }
            }
        }
        // 내 감도로 정리하기 — the one recommendation that resolves on the phone.
        //
        // It is a strip selection, not a second rendering path: `selectStrip` is what
        // the preview loop and `performSave` already key on, so moving the strip to
        // [localStyleFilterId] puts the styled pixels on screen and makes the header's
        // 저장 write them at full resolution. The card used to share `keepLocalResult`
        // with the candidates section, which only closed the sheet — so the user got
        // their photo back untouched (브리프 §13 결함 2, §8 "실행 즉시 전후 변화·저장").
        //
        // The sheet closes first so the change is visible: it covers the photograph it
        // just altered, and a result nobody can see is the defect restated.
        val applyLocalStyle: () -> Unit = {
            rescueOpened = false
            selectStrip(localStyleFilterId(filterState))
            scope.launch {
                rescueJob?.cancelAndJoin()
                rescueJob = null
                rescueController.reset()
                // Same clearing as `keepLocalResult`: this is a decision *against* any
                // generated candidate, so the recorded pick goes with it and
                // `editSource` falls back off the candidate file to the capture.
                runCatching { captureId?.let { container.database.captureEditStackDao().setSelectedResult(it, null) } }
                    .onFailure { Log.w(TAG, "clearing selected_result_id failed", it) }
            }
        }
        RescueSheet(
            state = rescueState,
            opened = rescueOpened,
            recommendations = rescueResponse,
            runningOperation = rescueRunningOperation,
            candidates = candidates,
            selectedCandidateId = pickedCandidateId,
            // Which exits abandon the job and which merely hide it is
            // `dismissActionFor`'s decision now, inside the sheet — this used to be a
            // `RescueState.Candidates` special case right here, and it was the only
            // state that got the "keep it" treatment.
            //
            // Owner decision 2026-07-30: **tapping outside hides, it does not cancel.**
            // A generation can be 20s into a GPU queue, and stepping out of the sheet
            // is not a request to throw that away — re-opening AI로 보정 returns to the
            // progress line. 취소 inside the progress section is still a real cancel,
            // which is the point of splitting the two.
            onDismiss = cancelRescue,
            onHide = { rescueOpened = false },
            onAnalyze = {
                val input = rescueInput
                if (input == null) {
                    Log.w(TAG, "rescueAnalyzeSkipped input_not_ready")
                } else {
                    Log.d(TAG, "rescueAnalyzeStarted source=${sourceKind.name}")
                    rescueJob = scope.launch {
                        rescueController.analyze(
                            image = input.file,
                            captureRef = input.captureRef,
                            style = styleParamsJson(activeReference),
                            reference = referenceCompositionJson(activeReference),
                        )
                    }
                }
            },
            onChoose = { operation -> rescueController.choose(operation) },
            onRun = {
                val input = rescueInput
                val current = rescueState
                // The upload gate, restated at the call site: only from `Editing`,
                // and only for an operation that actually goes to the server.
                if (input != null && canSubmit(current) && current is RescueState.Editing) {
                    Log.d(TAG, "operationChosen type=${current.operation["type"]}")
                    rescueJob = scope.launch {
                        rescueController.submit(
                            image = input.file,
                            captureId = captureId,
                            captureRef = input.captureRef,
                            operation = current.operation,
                            style = styleParamsJson(activeReference),
                        )
                    }
                }
            },
            onKeepLocal = keepLocalResult,
            onApplyLocalStyle = applyLocalStyle,
            // The same save the header runs. It stays open over the photo so the
            // 갤러리에 저장됨 the header would have shown lands somewhere visible.
            onSaveCandidate = { performSave() },
            saving = saving,
            saved = saved,
            saveError = saveError,
            localStyleWouldChange = localStyleChangesPhoto(filterState),
            onSelectCandidate = { candidate ->
                // A different candidate is a different photograph, so the save that
                // already happened is not a statement about this one. Without the
                // reset, `saved == true` latches the sheet's 저장 button off for the
                // rest of the visit — `saved` is written only by `performSave` and
                // never cleared — and the user who wanted both candidates could save
                // exactly one. The header's own pill has the same latch, but it was
                // never a gate there: it only changes a label.
                if (candidate.resultId != pickedCandidateId) {
                    saved = null
                    saveError = null
                }
                pickedCandidateId = candidate.resultId
                scope.launch {
                    runCatching {
                        captureId?.let {
                            container.database.captureEditStackDao()
                                .setSelectedResult(it, candidate.resultId)
                        }
                    }.onFailure { Log.w(TAG, "recording selected_result_id failed", it) }
                }
            },
        )
    }
}

/**
 * Thumbnail asset, which is the style's own preset image — the ids match.
 *
 * Takes the catalogue's id rather than a [LocalFilter] because the strip is built
 * from P2's items now. An id with no matching recipe falls back to 원본's tile
 * instead of asking Coil for an asset that is not in the APK.
 */
private fun filterAsset(filterId: String): String {
    val filter = presetFilterFor(filterId) ?: LocalFilter.ORIGINAL
    return if (filter == LocalFilter.ORIGINAL) {
        "presets/clean_social.jpg"
    } else {
        "presets/${filter.filter.id}.jpg"
    }
}

/**
 * One filter tile: thumbnail, selection ring, label.
 *
 * The ring is **2dp with a 2dp gap** to the image, per the redesign — and the gap is the
 * part that was missing. The ring used to sit directly on the thumbnail's own rounded
 * edge, so on a preset whose photo is light at the corners the amber merged into the
 * image and the selected tile looked no different from its neighbours. The unselected
 * tile carries the same 2dp of padding so the image never changes size on selection,
 * which is how the strip stops jumping as the user taps along it.
 *
 * That is also the shape [com.gamdo.app.ui.reference.StripThumb] already draws for the
 * `+` / `AI로 보정` / `내 감도` slots that sit on this same strip. The two were drawn by
 * different code with different results; now they agree.
 */
@Composable
private fun FilterThumb(label: String, asset: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, Amber, RoundedCornerShape(11.dp)).padding(2.dp)
                    } else {
                        Modifier.padding(2.dp)
                    },
                )
                .clip(RoundedCornerShape(11.dp))
                .background(Ink800),
        ) {
            AsyncImage(
                model = "file:///android_asset/$asset",
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            label,
            color = AdjustmentVisuals.stripLabelColor(selected),
            style = GamdoType.Micro,
            fontWeight = AdjustmentVisuals.stripLabelWeight(selected),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}


/**
 * Longest edge of the bitmap the editor previews and re-filters interactively.
 *
 * 1440 is above the 1080 the panel can show, so the preview is not the thing that
 * limits quality on screen, and it is roughly 1/7 the pixels of a capture — which
 * is what the filter re-render costs on every tap and every slider frame.
 */
/**
 * Longest side the editor decodes at.
 *
 * Named for what it is rather than `PREVIEW_MAX_SIDE`, which is also a public
 * constant in `edit/GeometryPlan.kt` with a **different** value (2000) feeding the
 * render-budget ladder. Two same-named constants in one feature area is how a
 * "raise the preview quality" edit lands on the wrong one; the values are both
 * correct for their own job, so the fix is the name, not the number.
 */
private const val EDITOR_DECODE_MAX_SIDE = 1440

/**
 * Longest edge the save pass renders at. §4-1's target is 4000px; captures come
 * off this camera at 3630, so in practice this is "the file, unchanged".
 */
private const val SAVE_MAX_SIDE = 4000
