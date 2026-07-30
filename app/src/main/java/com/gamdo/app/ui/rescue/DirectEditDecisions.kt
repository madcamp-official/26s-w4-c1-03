package com.gamdo.app.ui.rescue

import com.gamdo.app.data.network.RescueAnalysisResponse
import com.gamdo.app.data.network.RescueCapabilities
import com.gamdo.app.data.rescue.RescueState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.floor

/**
 * AI 3 **직접 수정** — the pure decisions behind the path P2 asked for in
 * `docs/P2_P1_필수기능연결_요구사항_2026-07-30.md` §4.
 *
 * The recommendation path (`RescueFlowDecisions.kt`) can only run what the server
 * *suggested*. §4 asks for the other half: an operation the server says it is **able**
 * to run (`capabilities.<x> == true`) must be reachable whether or not it was
 * recommended, once the user has supplied that operation's required parameters.
 *
 * Same Android-free rule as its sibling and for the same reason — no `androidTest`
 * source set, no Robolectric, no Compose test artifact — so every rule §4 lists is a
 * function here and is pinned by `DirectEditDecisionsTest`. `DirectEditPane.kt` draws
 * these values and decides nothing.
 *
 * ## Why the parameter values are enums and not free numbers
 *
 * `gamdo-server/app/routes/edit_jobs.py` `parse_operations()` is a **closed**
 * validator: outpaint takes one of five directions and one of exactly three ratios,
 * viewpoint one of five motions and one of two strengths, relight one of three
 * directions. Anything else is a non-retryable 422 — which reaches the user as
 * [LOCAL_FALLBACK_MESSAGE] after a pointless upload. Modelling the server's accepted
 * values as enums means an unrunnable request cannot be *built*, so the 422 branch is
 * unreachable rather than merely unlikely.
 *
 * Relight's `strength` is the exception: the server accepts any 0.1–1.0 float. It is
 * still three steps here, because a continuous dial has no everyday label (R7-1
 * forbids showing the number) and because three named steps are a thing a test can
 * enumerate.
 */

// ---- which operations 직접 수정 may offer ------------------------------------

/**
 * The four operations 직접 수정 covers, in the order §4's table lists them.
 *
 * [RescueOperation.LOCAL_STYLE] is deliberately absent: it has no parameters to
 * confirm, it never uploads ([requiresUpload]), and the result screen is already
 * showing it. "직접 수정" that changes nothing is not an operation.
 */
val DIRECT_EDIT_OPERATIONS: List<RescueOperation> = listOf(
    RescueOperation.REMOVE_OBJECTS,
    RescueOperation.OUTPAINT,
    RescueOperation.VIEWPOINT,
    RescueOperation.RELIGHT,
)

/**
 * The operations the pane may draw at all.
 *
 * Gated by the same [isEnabled] the recommendation list uses, so the two paths cannot
 * disagree about what this build of the server can run — §4's first hard condition
 * (`capability가 false인 작업은 실행할 수 없게 한다`).
 *
 * Not-drawn rather than drawn-and-disabled, on the reasoning `ResultFlowDecisions.kt`'s
 * [com.gamdo.app.ui.result.offersGenerativeRestore] already records for the strip slot:
 * "drawing a dead one is not [honest]" (AGENTS.md §7-6). A greyed row still tells the
 * user the feature is one tap away, which is a promise this build cannot keep.
 *
 * [maskCandidates] is why this is not a pure function of [capabilities].
 * `remove_objects` is the one operation whose required input comes from the photo
 * rather than from a control: with no removable object in the analysis there is
 * nothing for the user to select, so `capabilities.removeObjects = true` still leaves
 * the operation unrunnable *for this photo*. Offering it would put a row on screen
 * whose only possible outcome is a disabled run button.
 */
fun directEditOperations(capabilities: RescueCapabilities, maskCandidates: Int): List<RescueOperation> =
    DIRECT_EDIT_OPERATIONS.filter { operation ->
        isEnabled(operation, capabilities) &&
            (operation != RescueOperation.REMOVE_OBJECTS || maskCandidates > 0)
    }

/** [directEditOperations] for a whole analysis response. */
fun directEditOperations(response: RescueAnalysisResponse): List<RescueOperation> =
    directEditOperations(response.capabilities, maskCandidates(response).size)

/**
 * Whether the 직접 수정 entry point is drawn.
 *
 * The entry button is itself a button, so it falls under the same rule as the rows
 * behind it: with nothing runnable it is not drawn. A user on a server with every
 * generative capability off sees the recommendation section and no dead door.
 */
fun offersDirectEdit(response: RescueAnalysisResponse): Boolean =
    directEditOperations(response).isNotEmpty()

// ---- the values the server accepts -------------------------------------------

/** `outpaint.direction`, verbatim from `edit_jobs.py`'s allowed set. */
enum class OutpaintDirection(val wire: String) {
    TOP("top"), BOTTOM("bottom"), LEFT("left"), RIGHT("right"), ALL("all")
}

/**
 * `outpaint.ratio`. The server accepts 0.05, 0.10 and 0.15 and nothing between them
 * (`abs(ratio - allowed) < 1e-6`), which is why this is three values and not a slider.
 */
enum class OutpaintAmount(val wire: Double) { SMALL(0.05), MEDIUM(0.10), LARGE(0.15) }

/** `viewpoint.motion`. Note the field is `motion`, not `direction`. */
enum class ViewpointMotion(val wire: String) {
    LEFT("left"), RIGHT("right"), UP("up"), DOWN("down"), DOLLY_OUT("dolly_out")
}

/** `viewpoint.strength` — a *string* enum server-side, not a number. */
enum class ViewpointStrength(val wire: String) { SUBTLE("subtle"), STANDARD("standard") }

/** `relight.direction`. */
enum class RelightDirection(val wire: String) { FRONT("front"), LEFT("left"), RIGHT("right") }

/**
 * `relight.strength`. Three steps inside the server's 0.1–1.0 band.
 *
 * [MEDIUM] is 0.65 because that is the value the server's own relight recommendation
 * uses (`rescue_analysis.py`), so "보통" here and a recommended relight ask the
 * pipeline for the same thing.
 */
enum class RelightStrength(val wire: Double) { SOFT(0.35), MEDIUM(0.65), STRONG(0.9) }

// ---- removable objects, from the analysis the server already sent -------------

/** `edit_jobs.py` `MAX_MASK_COUNT`. */
const val MAX_DIRECT_MASKS = 8

/**
 * `edit_jobs.py` `MAX_EDIT_AREA_RATIO`.
 *
 * Both env-overridable server-side; these are the defaults. A server configured
 * *lower* would 422 a request this file was willing to build — which lands on the
 * [LOCAL_FALLBACK_MESSAGE] path and keeps the local correction, so the failure mode
 * of being out of date is a wasted upload, never a broken screen.
 */
const val MAX_DIRECT_MASK_AREA = 0.30

/** `edit_jobs.py` `MIN_MASK_DIMENSION_RATIO` — a mask too thin to inpaint. */
const val MIN_DIRECT_MASK_SIDE = 0.01

/**
 * One thing the user can ask to have removed: a normalized rectangle out of the
 * analysis, already in the shape `_validate_mask` accepts.
 *
 * [id] is positional (`m0`, `m1`, …) because the analysis gives subjects no identity
 * of their own. It is only ever used to remember a selection within one analysis
 * response, and [retainedDirectDraft] drops the selection whenever the response can
 * change, so a positional id cannot outlive the list it indexes.
 */
data class RescueMaskCandidate(
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val area: Double get() = width * height
}

private const val MASK_SAFETY_INSET = 0.0001

/**
 * Four decimals, rounded down — the precision the analyzer already emits.
 *
 * The epsilon is representation slack, not a fudge factor. `floor(x * 10_000)` on a
 * value that *is* an exact four-decimal figure can land one ulp below the integer and
 * drop a whole digit (0.1234 → 0.1233), which would silently shrink every rectangle.
 * 1e-9 here is 1e-13 in normalized units, far below the [MASK_SAFETY_INSET] this
 * function's callers reserve, so it cannot cost the `x + width <= 1.0` guarantee.
 */
private fun quantizeDown(value: Double): Double = floor(value * 10_000.0 + 1e-9) / 10_000.0

/**
 * The removable objects in an analysis, as server-valid rectangles.
 *
 * Reads `analysis.subjects` — the same list P2's own `_removal_masks` reads — so the
 * app invents no geometry of its own. Three things happen to each subject:
 *
 *  - **`role == "person"` is skipped.** `_removal_masks` skips it too, and for the
 *    same reason: the analyzer returns at most one person and sorts it first as the
 *    *primary subject*, so offering it is offering to delete the photo's subject.
 *  - **the rectangle is quantised down and inset.** Subject boxes are rounded to four
 *    decimals server-side, so an object flush against the edge can arrive as
 *    `x + width = 1.0001` — which `_validate_mask` rejects outright. Flooring to the
 *    same four decimals and reserving [MASK_SAFETY_INSET] keeps the candidate instead
 *    of silently dropping exactly the edge distractions this feature exists for, and
 *    makes `x + width <= 1.0` true by construction rather than by luck.
 *  - **too small to inpaint is dropped**, per [MIN_DIRECT_MASK_SIDE].
 *
 * Capped at [MAX_DIRECT_MASKS]; the analyzer returns at most three objects today, so
 * the cap is insurance against a wider future response rather than a live limit.
 *
 * Malformed input yields an empty list rather than throwing: `analysis` is a raw
 * [JsonObject] straight off the wire, and a rescue flow must not crash the result
 * screen because a field changed shape.
 */
fun maskCandidates(analysis: JsonObject): List<RescueMaskCandidate> {
    val subjects = runCatching { analysis["subjects"]?.jsonArray }.getOrNull() ?: return emptyList()
    val candidates = mutableListOf<RescueMaskCandidate>()
    subjects.forEachIndexed { index, element ->
        val subject = runCatching { element.jsonObject }.getOrNull() ?: return@forEachIndexed
        val role = subject["role"]?.jsonPrimitive?.contentOrNull
        if (role == "person") return@forEachIndexed
        val bounds = runCatching { subject["bbox"]?.jsonArray }.getOrNull() ?: return@forEachIndexed
        if (bounds.size != 4) return@forEachIndexed
        val values = bounds.map { runCatching { it.jsonPrimitive.contentOrNull?.toDouble() }.getOrNull() }
        if (values.any { it == null || !it.isFinite() }) return@forEachIndexed
        val x = quantizeDown(values[0]!!)
        val y = quantizeDown(values[1]!!)
        if (x < 0.0 || x >= 1.0 || y < 0.0 || y >= 1.0) return@forEachIndexed
        val width = quantizeDown(minOf(values[2]!!, 1.0 - x - MASK_SAFETY_INSET))
        val height = quantizeDown(minOf(values[3]!!, 1.0 - y - MASK_SAFETY_INSET))
        if (width < MIN_DIRECT_MASK_SIDE || height < MIN_DIRECT_MASK_SIDE) return@forEachIndexed
        if (width > 1.0 || height > 1.0) return@forEachIndexed
        candidates += RescueMaskCandidate("m$index", x, y, width, height)
    }
    return candidates.take(MAX_DIRECT_MASKS)
}

/** [maskCandidates] for a whole analysis response. */
fun maskCandidates(response: RescueAnalysisResponse): List<RescueMaskCandidate> =
    maskCandidates(response.analysis)

/**
 * Whether adding [candidate] to [selected] would still be a request the server takes.
 *
 * The gate that matters is total area: `remove_objects` past
 * [MAX_DIRECT_MASK_AREA] is a 422, and the user has no way to know that from looking
 * at the picture. Answering it *before* the tap is what lets the pane refuse to
 * select rather than accept and fail.
 */
fun canSelectMask(selected: List<RescueMaskCandidate>, candidate: RescueMaskCandidate): Boolean {
    if (selected.any { it.id == candidate.id }) return true
    if (selected.size >= MAX_DIRECT_MASKS) return false
    return quantizeDown(selected.sumOf { it.area } + candidate.area) <= MAX_DIRECT_MASK_AREA
}

// ---- the draft the user is assembling ---------------------------------------

/**
 * What the user has chosen so far. **Nothing is pre-filled**, deliberately.
 *
 * §4's table calls direction and amount 필수 사용자 입력, and defaulting them would
 * make the run button live before the user has said anything about the edit — which is
 * the same automatic-generation posture the AI 3 contract forbids one step earlier.
 * Every field starts null and [buildDirectOperation] returns null until the operation's
 * own pair is complete.
 */
data class DirectEditDraft(
    val operation: RescueOperation? = null,
    val maskIds: Set<String> = emptySet(),
    val outpaintDirection: OutpaintDirection? = null,
    val outpaintAmount: OutpaintAmount? = null,
    val viewpointMotion: ViewpointMotion? = null,
    val viewpointStrength: ViewpointStrength? = null,
    val relightDirection: RelightDirection? = null,
    val relightStrength: RelightStrength? = null,
)

/**
 * The draft that survives into the next controller state.
 *
 * Exactly [retainedRecommendations]'s rule and for the same reason: the draft is
 * meaningful only while the user is picking, and `choose()` moves the controller from
 * `Recommendations` to `Editing` without changing what is on screen. Keeping the draft
 * across that one pair is what makes the run button's confirmed/unconfirmed pair work;
 * dropping it everywhere else means a finished, failed or cancelled flow cannot leave
 * a stale set of parameters behind for the next photo.
 *
 * Written as a total function over every state rather than as cleanup at each exit,
 * because the retained values in this feature are the exact shape of variable that
 * caused AI 2's ghost-overlay defect.
 */
fun retainedDirectDraft(state: RescueState, previous: DirectEditDraft): DirectEditDraft =
    when (state) {
        is RescueState.Recommendations, is RescueState.Editing -> previous
        else -> DirectEditDraft()
    }

/**
 * Whether the 직접 수정 pane is still the section on screen.
 *
 * The pane is an alternative rendering of the picking pair, not a controller state, so
 * it lives and dies with that pair — same rule, same reason as [retainedDirectDraft].
 * A job that starts, finishes or falls back returns the sheet to the sections
 * [rescueSectionFor] names.
 */
fun retainedDirectPane(state: RescueState, previous: Boolean): Boolean =
    previous && (state is RescueState.Recommendations || state is RescueState.Editing)

// ---- the request ------------------------------------------------------------

/**
 * The `operations[0]` object for [draft], or **null when it is not runnable**.
 *
 * Null is the whole point: it is what the run button's `enabled` reads, so an
 * incomplete draft cannot be submitted, and `edit-job`s exist only for drafts the user
 * finished (§4's second condition).
 *
 * [candidates] is passed rather than embedded in the draft so the geometry always
 * comes from the current analysis. A draft holds mask *ids*; if the response is gone,
 * the ids resolve to nothing and this returns null instead of posting a rectangle
 * measured against a different photo.
 *
 * Field names and value domains are `edit_jobs.py`'s, checked against it rather than
 * against the recommendation payloads: `viewpoint` really does take `motion` and a
 * *string* `strength`, while `relight` takes `direction` and a *numeric* one.
 */
fun buildDirectOperation(
    draft: DirectEditDraft,
    candidates: List<RescueMaskCandidate>,
): JsonObject? = when (draft.operation) {
    RescueOperation.REMOVE_OBJECTS -> {
        val selected = candidates.filter { it.id in draft.maskIds }
        val area = quantizeDown(selected.sumOf { it.area })
        if (selected.isEmpty() || selected.size > MAX_DIRECT_MASKS || area > MAX_DIRECT_MASK_AREA) {
            null
        } else {
            buildJsonObject {
                put("type", RescueOperation.REMOVE_OBJECTS.wire)
                putJsonArray("masks") {
                    selected.forEach { candidate ->
                        addJsonObject {
                            putJsonObject("rect") {
                                put("x", candidate.x)
                                put("y", candidate.y)
                                put("width", candidate.width)
                                put("height", candidate.height)
                            }
                        }
                    }
                }
                // The server re-measures and overwrites this. Sending it anyway keeps
                // the request self-describing, and keeps the client honest about the
                // limit it believes it is inside.
                put("maskAreaRatio", area)
            }
        }
    }

    RescueOperation.OUTPAINT -> {
        val direction = draft.outpaintDirection
        val amount = draft.outpaintAmount
        if (direction == null || amount == null) null else buildJsonObject {
            put("type", RescueOperation.OUTPAINT.wire)
            put("direction", direction.wire)
            put("ratio", amount.wire)
        }
    }

    RescueOperation.VIEWPOINT -> {
        val motion = draft.viewpointMotion
        val strength = draft.viewpointStrength
        if (motion == null || strength == null) null else buildJsonObject {
            put("type", RescueOperation.VIEWPOINT.wire)
            put("motion", motion.wire)
            put("strength", strength.wire)
        }
    }

    RescueOperation.RELIGHT -> {
        val direction = draft.relightDirection
        val strength = draft.relightStrength
        if (direction == null || strength == null) null else buildJsonObject {
            put("type", RescueOperation.RELIGHT.wire)
            put("direction", direction.wire)
            put("strength", strength.wire)
        }
    }

    // Neither is a 직접 수정 operation. `local_style` is not even in the server's
    // allowed set, so building it would be an upload for work that never leaves
    // the phone.
    RescueOperation.LOCAL_STYLE, null -> null
}

/**
 * [buildDirectOperation], re-checked against [capabilities]. **The pane's only entry
 * point to a request.**
 *
 * The chip list is already built from [directEditOperations], so a disabled operation
 * cannot normally be drafted at all. This is the second lock on the same door, and it
 * is here because the two are not equivalent: the chip list is a rendering decision
 * that a later layout change could re-derive differently, while this is on the path
 * every request must take. §4's first condition is a statement about what can *run*,
 * so it belongs where running is decided.
 */
fun directRunOperation(
    draft: DirectEditDraft,
    candidates: List<RescueMaskCandidate>,
    capabilities: RescueCapabilities,
): JsonObject? {
    val operation = draft.operation ?: return null
    if (!isEnabled(operation, capabilities)) return null
    if (operation !in DIRECT_EDIT_OPERATIONS) return null
    return buildDirectOperation(draft, candidates)
}

/**
 * Whether the **recommendation** section owns the operation the controller is holding,
 * and may therefore draw its own confirm line and run button.
 *
 * Before 직접 수정 existed, `Editing` could only have been reached by tapping a card, so
 * the confirm block was unconditional. Now the same state is also reachable from the
 * pane, and a user who confirmed an outpaint there and then went back to the cards
 * would find a run button under a list where nothing is selected — a primary action
 * with no visible antecedent. The block belongs to the section that offered the
 * operation; the pane draws its own.
 */
fun offersConfirmFor(response: RescueAnalysisResponse?, chosen: JsonObject?): Boolean {
    if (response == null || chosen == null) return false
    return offerableRecommendations(response).any { it.operation == chosen }
}

// ---- one run action, one job -------------------------------------------------

/**
 * Whether the run button may fire, given that a run has [alreadyLaunched] for the
 * controller state now on screen.
 *
 * §4's third condition — 한 번의 실행 동작에서 job을 정확히 한 번 생성한다 — is not
 * covered by [canSubmit] alone. `RescueController.submit` sets `Submitting` as its
 * first statement, but it is a `suspend fun` reached through `scope.launch`, and the
 * screen reads the state through `collectAsState`, so the transition is **two hops
 * away from the tap**. Two taps inside one frame therefore both observe `Editing`,
 * both pass [canSubmit], and both post `/edit-jobs` with a freshly generated `jobId`.
 * The server answers the second with `active_job_limit` (409) — but the first job is
 * still running with nothing attached to it, and the UI follows the loser. Doubling
 * GPU time and splitting the result is exactly what the condition is about.
 *
 * [alreadyLaunched] is held by the sheet keyed on the controller state, so it is false
 * again whenever the state changes and true for every tap after the first within one
 * state. That makes the latch a property of the *run action*, not of the operation:
 * re-running after a fallback is fine (the state left `Editing`), re-tapping the same
 * button is not.
 */
fun allowsRun(state: RescueState, alreadyLaunched: Boolean): Boolean =
    !alreadyLaunched && canSubmit(state)

// ---- leaving the sheet while a job runs -------------------------------------

/** What closing the sheet means for the flow behind it. */
enum class RescueDismiss {
    /** Hide the sheet, leave the controller alone. Re-opening lands on the same section. */
    CLOSE_ONLY,

    /** Cancel anything in flight and reset the controller to `Idle`. */
    CANCEL_AND_RESET,
}

/**
 * Whether dismissing the sheet abandons the flow or merely hides it.
 *
 * §4's fourth condition asks that a running job's polling state be **restored** after
 * leaving and re-entering. Today every dismiss cancels, so stepping out of the sheet
 * for a moment destroys a job that may be twenty seconds into a GPU queue, and
 * re-opening starts at the intro.
 *
 * The split is by whether there is anything to come back to:
 *
 *  - `Submitting`/`Polling` — a job is running. Hiding keeps it running and re-opening
 *    shows [RescueSection.PROGRESS] again, which *is* the restored state.
 *  - `Candidates` — the results are the success; this already behaved this way.
 *  - everything else — nothing is in flight and nothing has been produced, so the
 *    dismiss is an abandon and should leave the controller idle rather than parked.
 *
 * The explicit 취소 button is unaffected: it is a different intent from "put this
 * away" and stays a real cancel. That is why this function is about *dismissal* and
 * not about the progress section's own action.
 */
fun dismissActionFor(state: RescueState): RescueDismiss = when (state) {
    is RescueState.Submitting, is RescueState.Polling, is RescueState.Candidates ->
        RescueDismiss.CLOSE_ONLY
    else -> RescueDismiss.CANCEL_AND_RESET
}

// ---- copy -------------------------------------------------------------------
//
// Every user-visible string 직접 수정 introduces is in this one block, so the owner
// can approve or replace the wording in one place. Everyday words only, no numbers,
// no field names, no stage names (R7-1/R7-2, D10).

/** The pane's own title, and the label on the button that opens it. */
const val DIRECT_EDIT_TITLE = "직접 고르기"

/** What each operation is called where the user picks it. */
fun directOperationLabel(operation: RescueOperation): String = when (operation) {
    RescueOperation.REMOVE_OBJECTS -> "방해 요소 지우기"
    RescueOperation.OUTPAINT -> "여백 늘리기"
    RescueOperation.VIEWPOINT -> "보는 위치 바꾸기"
    RescueOperation.RELIGHT -> "빛 균형 맞추기"
    // Not offered by [DIRECT_EDIT_OPERATIONS]; total for the compiler's sake.
    RescueOperation.LOCAL_STYLE -> "지금 보정 그대로"
}

/** The one line that says what is still missing before the run button can fire. */
fun directParameterHint(operation: RescueOperation?): String = when (operation) {
    null -> "무엇을 바꿀지 골라주세요"
    RescueOperation.REMOVE_OBJECTS -> "지울 것을 골라주세요"
    RescueOperation.OUTPAINT -> "어느 쪽을 얼마나 넓힐지 골라주세요"
    RescueOperation.VIEWPOINT -> "어느 쪽에서 얼마나 볼지 골라주세요"
    RescueOperation.RELIGHT -> "빛을 어느 쪽에서 얼마나 줄지 골라주세요"
    RescueOperation.LOCAL_STYLE -> "무엇을 바꿀지 골라주세요"
}

/** Heading over a group of choices. */
const val DIRECT_GROUP_WHAT = "지울 것"
const val DIRECT_GROUP_WHERE = "어느 쪽"
const val DIRECT_GROUP_HOW_MUCH = "얼마나"

fun outpaintDirectionLabel(value: OutpaintDirection): String = when (value) {
    OutpaintDirection.TOP -> "위"
    OutpaintDirection.BOTTOM -> "아래"
    OutpaintDirection.LEFT -> "왼쪽"
    OutpaintDirection.RIGHT -> "오른쪽"
    OutpaintDirection.ALL -> "네 방향 모두"
}

fun outpaintAmountLabel(value: OutpaintAmount): String = when (value) {
    OutpaintAmount.SMALL -> "조금"
    OutpaintAmount.MEDIUM -> "보통"
    OutpaintAmount.LARGE -> "많이"
}

fun viewpointMotionLabel(value: ViewpointMotion): String = when (value) {
    ViewpointMotion.LEFT -> "왼쪽에서"
    ViewpointMotion.RIGHT -> "오른쪽에서"
    ViewpointMotion.UP -> "위에서"
    ViewpointMotion.DOWN -> "아래에서"
    ViewpointMotion.DOLLY_OUT -> "한 발 뒤에서"
}

fun viewpointStrengthLabel(value: ViewpointStrength): String = when (value) {
    ViewpointStrength.SUBTLE -> "살짝"
    ViewpointStrength.STANDARD -> "보통"
}

fun relightDirectionLabel(value: RelightDirection): String = when (value) {
    RelightDirection.FRONT -> "앞"
    RelightDirection.LEFT -> "왼쪽"
    RelightDirection.RIGHT -> "오른쪽"
}

fun relightStrengthLabel(value: RelightStrength): String = when (value) {
    RelightStrength.SOFT -> "약하게"
    RelightStrength.MEDIUM -> "보통"
    RelightStrength.STRONG -> "세게"
}

/** The run button, before and after the draft has been confirmed with `choose()`. */
const val DIRECT_PREPARE_LABEL = "이대로 준비하기"
const val DIRECT_RUN_LABEL = "실행하기"

/** Back to the recommendation cards. */
const val DIRECT_BACK_LABEL = "추천으로 돌아가기"

/** Read out for the frame diagram, which carries no text of its own. */
const val DIRECT_MASK_FRAME_DESCRIPTION = "사진 안에서 지울 수 있는 것들의 위치"
