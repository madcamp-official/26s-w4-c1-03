package com.gamdo.app.ui.rescue

import com.gamdo.app.data.network.EditJobResult
import com.gamdo.app.data.network.RescueAnalysisResponse
import com.gamdo.app.data.network.RescueCapabilities
import com.gamdo.app.data.network.RescueRecommendation
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.data.rescue.RescueState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * AI 3 (사진 살리기) — the pure decisions behind the P1 wiring described in
 * `docs/AI3_사진살리기_통합계약_2026-07-28.md`'s "P1 연결 계약".
 *
 * Deliberately Compose-free and Android-free (zero `android.*` imports), for the
 * same reason as [com.gamdo.app.ui.reference.ReferenceFlowDecisions]: this project
 * has no `androidTest` source set and no Robolectric, so anything holding a
 * `Context`, `Uri` or `Bitmap` cannot execute under `testDebugUnitTest`.
 * `RescueSheet.kt` and the `ResultScreen` wiring are DONE-DEVICE; everything a
 * rule depends on is here and is pinned by `RescueFlowDecisionsTest`.
 *
 * This file adds **no state machine.** [com.gamdo.app.data.rescue.RescueController]
 * owns the flow; these functions only answer "given the state the controller is
 * already in, what may the screen show and what may the user set in motion".
 */

// ---- which section of the sheet is on screen --------------------------------

/** Which section of the rescue sheet is on screen for a given controller state. */
enum class RescueSection { HIDDEN, INTRO, RECOMMENDATIONS, CONFIRM, PROGRESS, CANDIDATES, FALLBACK }

/**
 * @param opened whether the user has the sheet open at all. This is UI state, not
 *   controller state, and it has to be a separate input because `Idle` means two
 *   different things: "the sheet is closed" and "the sheet is open on its first
 *   step, before anything has been sent anywhere". [RescueSection.INTRO] is that
 *   second one — see [RescueSheet]'s KDoc for why an explicit step exists before
 *   `analyze()`.
 *
 * `opened = false` wins over every controller state so that closing the sheet can
 * never leave a section stranded on screen while cancellation is still propagating.
 */
fun rescueSectionFor(state: RescueState, opened: Boolean): RescueSection {
    if (!opened) return RescueSection.HIDDEN
    return when (state) {
        is RescueState.Idle -> RescueSection.INTRO
        is RescueState.Analyzing -> RescueSection.PROGRESS
        is RescueState.Recommendations -> RescueSection.RECOMMENDATIONS
        is RescueState.Editing -> RescueSection.CONFIRM
        // `Submitting` and `Polling` are one thing to the user: the job is running.
        // (`Polling` is emitted — `submitAndPoll` calls `onPolling()` once, before its
        // loop. The note that used to sit here saying it never fires predates that.)
        is RescueState.Submitting -> RescueSection.PROGRESS
        is RescueState.Polling -> RescueSection.PROGRESS
        is RescueState.Candidates -> RescueSection.CANDIDATES
        is RescueState.LocalFallback -> RescueSection.FALLBACK
    }
}

/** The states in which a job the user started is still running. */
private fun isRunning(state: RescueState): Boolean =
    state is RescueState.Submitting || state is RescueState.Polling

/** The states that are a running job's outcome — something the user has to be told. */
private fun isOutcome(state: RescueState): Boolean =
    state is RescueState.Candidates || state is RescueState.LocalFallback

/**
 * Whether the sheet has to come back on its own because the job it was showing just
 * finished.
 *
 * The gap this closes is 결함 5 of the 2026-07-30 브리프 §13 — "AI 생성 후보가 도착했는지
 * … 알기 어려운 흐름". Owner decision 2026-07-30 made tapping outside *hide* the sheet
 * rather than cancel, so a generation correctly survives being stepped away from. But
 * nothing outside [RescueSheet] reads the controller, so when that job finished the
 * screen said nothing: candidates were downloaded and drawn to a sheet nobody had
 * open, and a `LocalFallback` failed equally quietly. The previous behaviour at least
 * ended loudly, by killing the job. Surviving without a surface is worse than that,
 * not better — a result the user is never told about is the same as no result.
 *
 * The rule is a *transition*, not a state, and that is what keeps it from fighting the
 * user. It fires only on the edge from running to outcome, so:
 *
 *  - Opening the screen on a controller already holding another photo's `Candidates`
 *    does not reopen anything (no edge, and `LaunchedEffect(target)` resets it anyway).
 *  - Dismissing a *finished* sheet leaves the controller on the same outcome state, so
 *    there is no second edge and the sheet stays shut. The user closes it once.
 *  - A cancel goes to `Idle`, which is not an outcome, so cancelling stays silent —
 *    which is right, because the user already knows.
 *
 * @param opened the sheet's current visibility. An open sheet needs no help; it is
 *   already showing the section [rescueSectionFor] picked.
 */
fun reopensOnOutcome(previous: RescueState, current: RescueState, opened: Boolean): Boolean =
    !opened && isRunning(previous) && isOutcome(current)

// ---- what the screen may keep between states --------------------------------

/**
 * Whether the analysis response stays on screen for the next controller state.
 *
 * The sheet keeps the last [RescueState.Recommendations] payload so the card list
 * does not vanish the instant a card is tapped: `choose()` moves the controller to
 * [RescueState.Editing], which carries only the chosen operation, and the controller
 * offers no way back to `Recommendations` short of uploading the photo again. Keeping
 * the list rendered across that one transition is what makes a mis-tap cost nothing;
 * without it the only escape from a wrong card is a second upload, which is the
 * opposite of what the contract is trying to minimise.
 *
 * **This is a render cache, not a second state machine**, and it is exactly the shape
 * of variable that caused AI 2's ghost-overlay defect (a UI value that outlived the
 * controller reset that should have cleared it). So the rule is written here, pinned
 * by a test, and stated as a total function over every state: the response survives
 * the picking pair and nothing else. Analysing, submitting, polling, candidates,
 * fallback and idle all drop it — including every path that ends the flow without
 * success.
 */
fun retainedRecommendations(
    state: RescueState,
    previous: RescueAnalysisResponse?,
): RescueAnalysisResponse? = when (state) {
    is RescueState.Recommendations -> state.response
    is RescueState.Editing -> previous
    else -> null
}

/**
 * The operation a running job is running, for the progress line.
 *
 * [RescueState.Submitting] and [RescueState.Polling] carry no operation — only
 * [RescueState.Editing] does — so §5-3's "방해 요소를 지우는 중…" would be
 * unwriteable without carrying it across that transition. Seeded from `Editing`,
 * held for exactly the two running states, dropped everywhere else so a finished or
 * abandoned job cannot leave its verb behind on the next one.
 */
fun retainedRunningOperation(state: RescueState, previous: JsonObject?): JsonObject? = when (state) {
    is RescueState.Editing -> state.operation
    is RescueState.Submitting, is RescueState.Polling -> previous
    else -> null
}

/**
 * Whether the picked candidate still applies for the next controller state.
 *
 * Picking a candidate swaps what the result screen renders and saves, so it is the
 * single most dangerous thing to leave stranded: a selection that outlived its job
 * would keep another photo's generated file on screen. It is therefore derived
 * rather than stored — it survives only while the controller is still in
 * [RescueState.Candidates], and any other state (including the `reset()` the result
 * screen performs when it opens a capture) drops it and the screen is back on the
 * original with no user action required.
 */
fun retainedCandidateId(state: RescueState, previous: String?): String? =
    if (state is RescueState.Candidates) previous else null

// ---- what the app is willing to run -----------------------------------------

/**
 * The operations this app will ever offer, each paired with the [RescueCapabilities]
 * flag that gates it. **A whitelist on purpose.**
 *
 * The server's `ALLOWED_OPERATIONS` (`gamdo-server/app/routes/edit_jobs.py`) is much
 * wider — it includes `eye_fix`, `skin_tone_even`, `relight`, `simplify_background`,
 * `deblur_light`, `fill_rotation_gap`. Two of those alter a face, which AGENTS.md §6
 * 규칙 3 puts beyond re-discussion without an explicit owner reversal. With a
 * blacklist, a server build that started recommending one would put it on screen; with
 * this list, a kind the app does not name cannot be offered no matter what comes back.
 *
 * It is also exactly the contract's capability rule: `capabilities`가 false인 기능은
 * 제공하지 않는다 — a kind with no flag of its own has no way to be true.
 */
enum class RescueOperation(val wire: String) {
    /** Keep the local correction. Never leaves the phone — see [requiresUpload]. */
    LOCAL_STYLE("local_style"),
    REMOVE_OBJECTS("remove_objects"),
    OUTPAINT("outpaint"),
    VIEWPOINT("viewpoint"),
    RELIGHT("relight"),
    ;

    companion object {
        fun fromWire(value: String?): RescueOperation? = entries.firstOrNull { it.wire == value }
    }
}

/** Whether [capabilities] says this build of the server can actually run [operation]. */
fun isEnabled(operation: RescueOperation, capabilities: RescueCapabilities): Boolean = when (operation) {
    RescueOperation.LOCAL_STYLE -> capabilities.localStyle
    RescueOperation.REMOVE_OBJECTS -> capabilities.removeObjects
    // Hidden until CAMP-2 has the FLUX.1 Fill workflow installed (contract, "CAMP-2
    // 운영 상태"). The app does not decide this — it just refuses to offer what the
    // response says is not runnable.
    RescueOperation.OUTPAINT -> capabilities.outpaint
    RescueOperation.VIEWPOINT -> capabilities.viewpoint
    RescueOperation.RELIGHT -> capabilities.relight
}

/**
 * The recommendation cards the sheet may draw.
 *
 * A card survives only if all four hold: its `kind` is one this app knows, its
 * capability is on, it carries an `operation` (a card with nothing to `choose()` is
 * a dead tap), and the operation's own `type` agrees with the gated kind — otherwise
 * the gate would be checking one thing and the submit would send another.
 */
fun offerableRecommendations(response: RescueAnalysisResponse): List<RescueRecommendation> =
    response.recommendations.filter { recommendation ->
        val kind = RescueOperation.fromWire(recommendation.kind) ?: return@filter false
        if (!isEnabled(kind, response.capabilities)) return@filter false
        val operation = recommendation.operation ?: return@filter false
        RescueOperation.fromWire(operationType(operation)) == kind
    }

/** The `type` field every operation carries, or null when the object has none. */
fun operationType(operation: JsonObject): String? = operation["type"]?.jsonPrimitive?.contentOrNull

/**
 * Whether running [operation] means sending the photo to the server.
 *
 * `local_style` is not in the server's `ALLOWED_OPERATIONS`, so posting it would be
 * a 422 — and, worse, an upload of the user's photo for work that never needed to
 * leave the phone. It resolves entirely on device: the result screen is already
 * showing the local correction.
 */
fun requiresUpload(operation: JsonObject): Boolean =
    when (RescueOperation.fromWire(operationType(operation))) {
        RescueOperation.REMOVE_OBJECTS, RescueOperation.OUTPAINT,
        RescueOperation.VIEWPOINT, RescueOperation.RELIGHT -> true
        RescueOperation.LOCAL_STYLE, null -> false
    }

/**
 * Whether `submit(...)` may be called right now.
 *
 * The contract's hard prohibition is 생성 전 자동 업로드 금지 — "ONLY on an explicit
 * user action". This is the other half of that: the *state* must be [RescueState.Editing],
 * i.e. the user has already chosen a specific operation through `choose()`, and that
 * operation must be one that actually goes to the server.
 */
fun canSubmit(state: RescueState): Boolean =
    state is RescueState.Editing && requiresUpload(state.operation)

// ---- copy -------------------------------------------------------------------

/**
 * Progress line while a job runs. Everyday words, no numbers, no stage names — D10
 * (전문 용어 금지) and the contract's 기술 점수 노출 금지.
 *
 * `EditJobStatus.progressStage` exists on the wire but the controller does not
 * surface it, so this is derived from what the user chose rather than invented.
 */
fun progressMessageFor(operation: JsonObject?): String =
    when (RescueOperation.fromWire(operation?.let(::operationType))) {
        RescueOperation.REMOVE_OBJECTS -> "방해 요소를 지우고 있어요"
        RescueOperation.OUTPAINT -> "여백을 넓히고 있어요"
        RescueOperation.VIEWPOINT -> "보는 위치를 바꾸고 있어요"
        RescueOperation.RELIGHT -> "빛의 균형을 맞추고 있어요"
        // Also the analyze step, which has no operation yet.
        RescueOperation.LOCAL_STYLE, null -> "사진을 살펴보고 있어요"
    }

/**
 * The line above the run button once a card has been chosen ([RescueState.Editing]).
 *
 * Derived from the operation rather than the recommendation's own `title` so the
 * confirm step says what is about to happen even though `Editing` carries only the
 * operation. Same vocabulary as [progressMessageFor], one tense earlier.
 */
fun confirmMessageFor(operation: JsonObject): String =
    when (RescueOperation.fromWire(operationType(operation))) {
        RescueOperation.REMOVE_OBJECTS -> "방해 요소를 지울게요"
        RescueOperation.OUTPAINT -> "여백을 넓힐게요"
        RescueOperation.VIEWPOINT -> "보는 위치를 바꿀게요"
        RescueOperation.RELIGHT -> "빛의 균형을 맞출게요"
        // Was "지금 보정만 그대로 둘게요", which was true of the handler and false of
        // the card: 내 감도로 정리하기 now applies the user's 감도 to the photo instead
        // of closing the sheet on an unchanged one (브리프 §13 결함 2). Still nothing
        // leaves the phone — the sentence promises a local change, not a generation.
        RescueOperation.LOCAL_STYLE -> "내 감도로 정리할게요"
        null -> "지금 보정만 그대로 둘게요"
    }

/** The contract's exact sentence for [RescueState.LocalFallback]. */
const val LOCAL_FALLBACK_MESSAGE = "자연스러운 보정만 적용했어요"

/**
 * What the user is told when the flow fell back to the local correction.
 *
 * [reason] is `analysis_unavailable`, `generation_unavailable`, or the server's raw
 * `fail_reason` forwarded through `RescueRepository.submitAndPoll`'s
 * `error(status.failReason ?: …)`. **None of it is shown.** The parameter is taken
 * and dropped on purpose: it makes the call site read honestly (this is the message
 * *for* that reason) while the type system stops anyone from concatenating it in.
 * Log the reason instead — that is where it is useful.
 */
@Suppress("UNUSED_PARAMETER")
fun fallbackMessage(reason: String): String = LOCAL_FALLBACK_MESSAGE

/**
 * What the photograph behind the sheet is currently showing, for the candidates
 * section's comparison row.
 *
 * 브리프 §8 asks for `원본·현재 감도·AI 후보 비교`, and the screen already has the one
 * surface big enough to judge a photograph on — the photograph. So the row is a
 * *selector* over that surface rather than three thumbnails: tapping moves the full
 * image, which is where over-saturation or a bad generation is actually visible. Two
 * more full-resolution renders to fill preview tiles would also land on this screen's
 * peak-memory moment, which is what blanked the photo in 결함 3.
 *
 * The mapping from screen state to this lives in `ResultFlowDecisions` — it is the
 * result screen's strip and pick that answer it, not anything the sheet knows.
 */
enum class RescueComparison { ORIGINAL, CURRENT_GAMDO, CANDIDATE }

/** §5-3 "결과 후보(최대 2개)". */
const val MAX_RESCUE_CANDIDATES = 2

/** §5-3 "**'AI 생성 보완' 뱃지** 표시" — required on every generated candidate. */
const val GENERATIVE_BADGE_LABEL = "AI 생성 보완"

// ---- candidates -------------------------------------------------------------

/**
 * One `edit_results_local` row, reduced to the three fields the screen needs.
 *
 * Mapped from the Room entity by the caller so this file stays free of `androidx.room`
 * and so the merge below is testable with plain values.
 */
data class RescueLocalResult(val resultId: String, val filePath: String, val rank: Int)

/** A result the user can actually pick: a downloaded file that is on disk. */
data class RescueCandidate(val resultId: String, val filePath: String, val rank: Int)

/**
 * The candidates to draw, from what the job returned ([results]) and what
 * `RescueRepository.submitAndPoll` actually wrote to `edit_results_local` ([rows]).
 *
 * The rows are the authority, not the response: the response's `url` points at the
 * server, while the row's `file_path` is the copy that was downloaded into app
 * storage. A row whose file is missing is dropped — a tile over a file that is not
 * there renders as an empty box that still claims to be a result, which is
 * AGENTS.md §6 규칙 6 (더미·고정 이미지를 실제 결과로 보이지 않는다) in its most
 * literal form.
 *
 * [results] is taken as a parameter and used only for its count so that a response
 * carrying fewer results than there are stale rows cannot over-report.
 */
fun rescueCandidates(results: List<EditJobResult>, rows: List<RescueLocalResult>): List<RescueCandidate> =
    rows.sortedBy { it.rank }
        .filter { File(it.filePath).isFile }
        .take(minOf(MAX_RESCUE_CANDIDATES, results.size))
        .map { RescueCandidate(resultId = it.resultId, filePath = it.filePath, rank = it.rank) }

/**
 * Whether a candidate carries the "AI 생성 보완" badge. **Always true, on purpose.**
 *
 * The obvious implementation is `result.generative`, and it is wrong in the direction
 * that matters. `/edit-jobs` only accepts generative operation types
 * (`routes/edit_jobs.py` `ALLOWED_OPERATIONS`) and `db.py` writes `generative = 1`
 * for every result row, so every candidate that reaches this screen is AI-completed.
 * But `EditJobResult.generative` defaults to `false` in the Kotlin model, so any
 * server that stopped sending the field would silently ship a generated photo with
 * no badge — and §5-3 calls the badge 필수.
 *
 * [result] is kept in the signature so the call site reads as a question about that
 * result rather than a constant, and so a future rule that legitimately *does* vary
 * has somewhere to live.
 */
@Suppress("UNUSED_PARAMETER")
fun showsGenerativeBadge(result: EditJobResult): Boolean = true

// ---- what rides along with analyze()/submit() -------------------------------

/**
 * `styleParams` for `analyze(...)`/`submit(...)`.
 *
 * Only the composition fields the server actually reads (`rescue_analysis.py`'s
 * `_target_margin` looks at `composition.backgroundRatio` / `backgroundRatioRange`
 * to decide how much margin the outpaint recommendation should aim for). Colour is
 * deliberately left out: it changes nothing server-side and the local pipeline owns
 * it, so sending it would only widen what leaves the phone.
 *
 * Null style → `{}`, never a fabricated default: an invented target margin would
 * make the server recommend an outpaint the user's style never asked for.
 */
fun styleParamsJson(style: ResolvedStyle?): JsonObject {
    if (style == null) return JsonObject(emptyMap())
    return buildJsonObject {
        putJsonObject("composition") {
            put("targetAspectRatio", style.composition.targetAspectRatio)
            putJsonArray("backgroundRatio") { style.composition.backgroundRatio.forEach { add(it) } }
        }
    }
}

/**
 * `referenceComposition` for `analyze(...)` — the active AI 2 reference's layout
 * slots, in the `{"layoutSlots":[{"bounds":[…]}]}` shape `_target_margin` parses.
 *
 * Empty unless there is a reference *whose composition half is in scope*. A
 * 색감만 (COLOR) reference carries slots in memory but the user chose not to apply
 * its composition, and a preset is not a reference at all — in both cases sending
 * slots would let a composition the user declined steer the recommendation.
 */
fun referenceCompositionJson(style: ResolvedStyle?): JsonObject {
    val usable = style
        ?.takeIf { it.source == ResolvedStyle.Source.REFERENCE }
        ?.takeIf { it.referenceScope != ResolvedStyle.ReferenceScope.COLOR }
    val slots = usable?.referenceSlots.orEmpty()
    if (slots.isEmpty()) return JsonObject(emptyMap())
    return buildJsonObject {
        putJsonArray("layoutSlots") {
            slots.forEach { slot ->
                addJsonObject {
                    putJsonArray("bounds") { slot.bounds.forEach { add(it) } }
                }
            }
        }
    }
}
