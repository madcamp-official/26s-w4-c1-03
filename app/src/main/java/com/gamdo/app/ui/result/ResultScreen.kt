package com.gamdo.app.ui.result

import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamdo.app.data.AppContainer
import com.gamdo.app.data.local.entity.Captures
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
import com.gamdo.app.ui.components.PrimaryPillButton
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
import com.gamdo.app.ui.rescue.rescueCandidates
import com.gamdo.app.ui.rescue.retainedCandidateId
import com.gamdo.app.ui.rescue.retainedRecommendations
import com.gamdo.app.ui.rescue.retainedRunningOperation
import com.gamdo.app.ui.rescue.styleParamsJson
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val TAG = "ResultScreen"

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
 * Which item in the O-10-wrapped filter strip is driving the render — either one
 * of the six [LocalFilter] presets (+ 원본), or the `내 레퍼런스` slot.
 *
 * A reference cannot be a [LocalFilter]: that enum is a closed, hand-authored set
 * of [com.gamdo.app.edit.PhotoFilter] recipes, and a reference's colour target is
 * arbitrary analysis output, not a recipe. So its render path is different too —
 * see [ResultScreen]'s `corrected` computation — while everything downstream
 * (the interactive sliders, the save path) treats [Reference] as riding on
 * `LocalFilter.ORIGINAL` for its *own* recipe (identity) plus whatever the
 * sliders add, exactly like every other selection.
 */
private sealed interface SelectedStripFilter {
    data class Preset(val filter: LocalFilter) : SelectedStripFilter
    data object Reference : SelectedStripFilter
}

/**
 * @param activeReferenceStyle the active AI 2 reference's resolved
 *   composition+color, or null. Only [ResolvedStyle.color] is consumed here —
 *   composition is a camera-screen concern. See the integration contract's
 *   "결과 화면의 `내 레퍼런스` 색감 항목".
 * @param activeReferenceImageUri the picked photo behind [activeReferenceStyle],
 *   for the strip thumbnail (see `CameraScreen`'s param of the same name for why
 *   this can be null even when a reference is active).
 * @param onCreateReference O-10's leading `+` — opens the same picker/flow as
 *   the camera screen's.
 * @param onDeleteReference the reference slot's `×` badge (삭제).
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
    onDeleteReference: () -> Unit = {},
) {
    val sourceKind = target.kind
    // Null for a device photo, and that is a fact rather than a missing value —
    // everything keyed on it (AI 3, the edit stack) is gated on the same branch.
    val captureId: String? = (target as? ResultTarget.AppCapture)?.captureId
    val capture by produceState<Captures?>(initialValue = null, target, container) {
        val id = captureId ?: return@produceState
        value = withContext(Dispatchers.IO) {
            container.database.capturesDao().get(id)
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
    LaunchedEffect(rescueState) {
        rescueResponse = retainedRecommendations(rescueState, rescueResponse)
        rescueRunningOperation = retainedRunningOperation(rescueState, rescueRunningOperation)
        pickedCandidateId = retainedCandidateId(rescueState, pickedCandidateId)
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
                rescueCandidates(done.results, rows)
            }
        }
    }
    val pickedCandidate = candidates.firstOrNull { it.resultId == pickedCandidateId }

    // §5-2 결과 화면의 `내 레퍼런스` 색감 항목. A reference is not a `LocalFilter`
    // (see [SelectedStripFilter]'s KDoc), so which strip item is active has to be
    // known *before* `corrected` below — selecting it changes which pass produces
    // the base bitmap, not just what runs on top of it.
    var selectedStrip by remember { mutableStateOf<SelectedStripFilter>(SelectedStripFilter.Preset(LocalFilter.ORIGINAL)) }
    // O-12: whether the *user* put the strip where it is. Not derivable from
    // `selectedStrip` — on an app capture the screen seeds it below, and a seeded
    // selection is not a choice. Every strip tap sets this.
    var styleChosenByUser by remember { mutableStateOf(false) }
    // Every style in presets.json now has a filter of its own, so this is a lookup
    // rather than a when-chain with a fallback. The chain listed four of the six
    // and sent `clean_social` and `casual_portrait` to a different style's look.
    val preferredFilter by produceState(LocalFilter.ORIGINAL, container) {
        value = LocalFilter.forPresetId(container.settingsRepository.getStylePresetId())
    }
    // O-12's quieter half: seeding the saved style would put a look nobody picked
    // onto a photo from the user's own library. An app capture keeps the seed.
    LaunchedEffect(preferredFilter, sourceKind) {
        if (opensOnPreferredStyle(sourceKind)) selectedStrip = SelectedStripFilter.Preset(preferredFilter)
    }
    val stylePick = stylePickFor(
        source = sourceKind,
        selection = when (val sel = selectedStrip) {
            is SelectedStripFilter.Preset ->
                if (sel.filter == LocalFilter.ORIGINAL) StripSelection.ORIGINAL else StripSelection.PRESET
            SelectedStripFilter.Reference -> StripSelection.REFERENCE
        },
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
    val contentResolver = LocalContext.current.contentResolver
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
    // Keyed on [passes] rather than on `selectedStrip`: that value *is* what the
    // strip contributes to this pass — `applyStyle` for the `내 레퍼런스` slot, which
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
        activeReferenceStyle,
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
        value = withContext(Dispatchers.Default) {
            val preview = image.decode(EDITOR_DECODE_MAX_SIDE)
                ?: return@withContext OpenedPhoto.Unavailable
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
                    resolvedStyle = activeReferenceStyle.takeIf { passes.style },
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
    // ORIGINAL is an identity `PhotoFilter`, so selecting `내 레퍼런스` above means
    // "no second colour pass here, only what the sliders add" — the reference's
    // colour already rendered into `source`. Every preset keeps working exactly
    // as before, unaffected by any of this.
    val effectiveLocalFilter = when (val sel = selectedStrip) {
        is SelectedStripFilter.Preset -> sel.filter
        SelectedStripFilter.Reference -> LocalFilter.ORIGINAL
    }
    // What actually gets written to `capture_edit_stack.paramsJson` (a free-form
    // string column, no schema change needed). Distinct from `effectiveLocalFilter`
    // — that one exists to reuse `QuickFilterEditor`'s ORIGINAL identity recipe,
    // but recording "ORIGINAL" here would silently lose that a reference was used.
    val filterRecordName = when (selectedStrip) {
        is SelectedStripFilter.Preset -> effectiveLocalFilter.name
        SelectedStripFilter.Reference -> "REFERENCE"
    }
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
    //
    // `내 레퍼런스` has no `PhotoFilter` recipe to seed from — its colour is
    // already in `source` (see `corrected` above) — so it seeds to NEUTRAL: the
    // sliders start at zero and only add a *further* adjustment on top, same as
    // 원본 always has.
    LaunchedEffect(selectedStrip, measure) {
        val m = measure ?: return@LaunchedEffect
        val seeded = when (val sel = selectedStrip) {
            is SelectedStripFilter.Preset -> FilterEngine.seedFrom(sel.filter.filter, m)
            SelectedStripFilter.Reference -> FilterEngine.Adjustments.NEUTRAL
        }
        baseline = seeded
        adjustments = seeded
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
                pending.source?.let { bitmap ->
                    withContext(Dispatchers.Default) {
                        val buffer = pixelBuffer(scratch, bitmap.width, bitmap.height)
                        scratch = buffer
                        QuickFilterEditor.apply(bitmap, pending.filter, pending.adjustments, buffer)
                    }
                }
            },
            publish = { _, rendered -> edited = rendered },
        )
    }

    // The AI 3 sheet overlays the whole screen, so the editor becomes one child of a
    // Box rather than the root. Nothing about the editor's own layout changes.
    Box(modifier = Modifier.fillMaxSize()) {
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
                    color = OnDarkMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(5.dp))
                        .background(Sage).padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = when (val sel = selectedStrip) {
                            is SelectedStripFilter.Preset -> sel.filter.label
                            SelectedStripFilter.Reference -> "내 레퍼런스"
                        },
                        color = OnSage,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // §5-3 "**'AI 생성 보완' 뱃지** 표시". It rides on the photo itself, not
                // only on the picker tile, so a generated photo can never be on this
                // screen — or be saved from it — without saying so.
                if (pickedCandidate != null) GenerativeBadge()
            }
        }

        Row(
            modifier = Modifier.padding(start = 20.dp, top = 14.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // O-10: `[+] [AI로 보정] [원본 + 6종…] [내 레퍼런스]?`. The trailing slot
            // only appears when the reference has a colour half to offer — a
            // 구도만 reference has nothing this screen can apply (composition is a
            // camera-screen concern), and offering it here would silently do
            // nothing when tapped.
            val hasReferenceColor = activeReferenceStyle != null &&
                activeReferenceStyle.referenceScope != ResolvedStyle.ReferenceScope.COMPOSITION
            // AI 3 is keyed on a `captures` row at every step (`analyze(captureRef=)`,
            // `submit(captureId=)`, `selected_result_id`), which a device photo does
            // not have — so the slot is not drawn rather than drawn dead.
            val offersAiRestore = offersGenerativeRestore(sourceKind)
            val strip = remember(hasReferenceColor, offersAiRestore) {
                buildFilterStrip(
                    presets = LocalFilter.entries.toList(),
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
                        val filter = entry.value
                        FilterThumb(
                            label = filter.label,
                            asset = filterAsset(filter),
                            selected = selectedStrip == SelectedStripFilter.Preset(filter),
                            // O-12: this tap, and the one below, are the only things
                            // that let a correction touch a device photo.
                            onClick = {
                                styleChosenByUser = true
                                selectedStrip = SelectedStripFilter.Preset(filter)
                            },
                        )
                    }
                    is StripEntry.MyReference -> MyReferenceThumb(
                        shape = RoundedCornerShape(11.dp),
                        size = 58.dp,
                        imageUri = activeReferenceImageUri,
                        selected = selectedStrip == SelectedStripFilter.Reference,
                        onSelect = {
                            styleChosenByUser = true
                            selectedStrip = SelectedStripFilter.Reference
                        },
                        onDelete = {
                            if (selectedStrip == SelectedStripFilter.Reference) {
                                selectedStrip = SelectedStripFilter.Preset(preferredFilter)
                            }
                            onDeleteReference()
                        },
                    )
                }
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
                    saved == true -> "갤러리에 저장됨"
                    saved != null -> "앱에 저장됨"
                    saving -> "저장 중…"
                    else -> "저장"
                },
                onClick = {
                    if (saving) return@PrimaryPillButton
                    // The seam, not the capture: a device photo has no row to check
                    // and this is the same value the preview rendered from.
                    val image = editSource ?: return@PrimaryPillButton
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
                },
            )
        }
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
        RescueSheet(
            state = rescueState,
            opened = rescueOpened,
            recommendations = rescueResponse,
            runningOperation = rescueRunningOperation,
            candidates = candidates,
            selectedCandidateId = pickedCandidateId,
            // Closing on Candidates keeps the pick — there the pick *is* the
            // success, and re-opening the strip slot returns to the same list.
            // Every other section's close is an abandon, so it cancels and resets.
            onDismiss = {
                if (rescueState is RescueState.Candidates) rescueOpened = false else cancelRescue()
            },
            onAnalyze = {
                val captureValue = capture
                if (captureValue == null) {
                    Log.w(TAG, "rescue analyze skipped: capture $captureId is not loaded yet")
                } else {
                    rescueJob = scope.launch {
                        rescueController.analyze(
                            image = File(captureValue.filePath),
                            captureRef = captureValue.id,
                            style = styleParamsJson(activeReferenceStyle),
                            reference = referenceCompositionJson(activeReferenceStyle),
                        )
                    }
                }
            },
            onChoose = { operation -> rescueController.choose(operation) },
            onRun = {
                val captureValue = capture
                val current = rescueState
                // The upload gate, restated at the call site: only from `Editing`,
                // and only for an operation that actually goes to the server.
                if (captureValue != null && canSubmit(current) && current is RescueState.Editing) {
                    rescueJob = scope.launch {
                        rescueController.submit(
                            image = File(captureValue.filePath),
                            captureId = captureValue.id,
                            captureRef = captureValue.id,
                            operation = current.operation,
                            style = styleParamsJson(activeReferenceStyle),
                        )
                    }
                }
            },
            onKeepLocal = keepLocalResult,
            onSelectCandidate = { candidate ->
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
