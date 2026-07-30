package com.gamdo.app.ui.rescue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamdo.app.data.network.RescueAnalysisResponse
import com.gamdo.app.data.network.RescueRecommendation
import com.gamdo.app.data.rescue.RescueState
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.components.SecondaryPillButton
import com.gamdo.app.ui.theme.Ink800
import com.gamdo.app.ui.theme.Ink900
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextMid
import com.gamdo.app.ui.theme.OnAmber
import com.gamdo.app.ui.theme.Amber
import com.gamdo.app.ui.theme.Scrim
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * AI 3 "사진 살리기" flow surface — one modal that renders whichever section
 * [rescueSectionFor] says the [com.gamdo.app.data.rescue.RescueController] is in.
 *
 * Hosted by [com.gamdo.app.ui.result.ResultScreen] (not by the nav host, unlike
 * AI 2's sheet): O-10 puts AI 3's entry point on the result screen **only**, and
 * every input the flow needs — the capture, its file, the active style — is already
 * that screen's.
 *
 * R1/L-2 latitude: none of this is in design t2. O-10 granted the entry point
 * (`AI로 보정` in the filter strip) and nothing else, so this is built minimally in
 * the existing language — charcoal sheet, sage accent, the same
 * [PrimaryPillButton]/[SecondaryPillButton] pill vocabulary and 20dp rounded sheet
 * as `ReferenceCreateSheet.kt`, so the two features do not read as different
 * products.
 *
 * Hard contract prohibitions honoured here:
 * - **no automatic upload.** [RescueSection.INTRO] exists so `analyze()` is reached
 *   by an explicit tap, and [RescueSection.CONFIRM]'s run button is the only route
 *   to `submit()` ([canSubmit] refuses every other state).
 * - **no technical scores.** `RescueRecommendation.confidence` is never drawn, nor
 *   is any job id, stage name, mask count or dimension.
 * - **no capability that is false is offered** — [offerableRecommendations] filters
 *   the list rather than disabling entries, exactly as AI 2 does with
 *   `composition=false`.
 * - **the original is never overwritten.** This sheet only ever *selects*; the write
 *   path stays `CaptureRepository.saveEditedCapture`, which writes a new file.
 *
 * @param recommendations the retained analysis ([retainedRecommendations]) so the
 *   card list survives the tap into `Editing`.
 * @param candidates already merged and filtered by [rescueCandidates] — every entry
 *   here is a file that exists on disk.
 * @param onDismiss the way out of a section that has not succeeded. The host cancels
 *   anything in flight and resets the controller; from [RescueSection.CANDIDATES] it
 *   simply closes, because there the pick is the success.
 * @param onHide put the sheet away **without** touching the flow behind it, for the
 *   states [dismissActionFor] says have something to come back to. Defaults to
 *   [onDismiss], which is what the host did for every state before P2's §4 asked for a
 *   running job to survive being dismissed — so a host that does not pass it keeps
 *   exactly today's behaviour rather than quietly acquiring new behaviour.
 * @param onKeepLocal 기본 보정 유지 — the contract's "후보 다운로드 **또는** 로컬 보정
 *   유지" branch. Clears any pick and closes.
 * @param onApplyLocalStyle 내 감도로 정리하기 — the `local_style` card's run action.
 *   Separate from [onKeepLocal] because the two read as the same sentence and are not:
 *   this one is asked for from `Editing`, where the user has just chosen a card and is
 *   expecting the photograph to change, while [onKeepLocal] is offered from
 *   `Candidates`, where the user is declining a generated result they can already see.
 *   Both used to be wired to the same host handler, and since that handler only closed
 *   the sheet, the card that promises to tidy the photo up did nothing at all — 결함 2
 *   of the 2026-07-30 브리프 §13. Nothing here leaves the phone; see [requiresUpload].
 */
@Composable
fun RescueSheet(
    state: RescueState,
    opened: Boolean,
    recommendations: RescueAnalysisResponse?,
    runningOperation: JsonObject?,
    candidates: List<RescueCandidate>,
    selectedCandidateId: String?,
    onDismiss: () -> Unit,
    onAnalyze: () -> Unit,
    onChoose: (JsonObject) -> Unit,
    onRun: () -> Unit,
    onKeepLocal: () -> Unit,
    onApplyLocalStyle: () -> Unit,
    onSelectCandidate: (RescueCandidate) -> Unit,
    onSaveCandidate: () -> Unit,
    saving: Boolean,
    saved: Boolean?,
    saveError: String?,
    localStyleWouldChange: Boolean,
    onHide: () -> Unit = onDismiss,
    modifier: Modifier = Modifier,
) {
    // ---- 직접 수정 (P2 §4) ---------------------------------------------------
    //
    // The pane is an alternative rendering of the picking pair, not a controller
    // state, so both of its values are re-derived from every controller emission by
    // the same total rules the retained analysis follows. Declared above the early
    // return so closing the sheet does not dispose them mid-flow.
    var directPane by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(DirectEditDraft()) }
    LaunchedEffect(state) {
        directPane = retainedDirectPane(state, directPane)
        draft = retainedDirectDraft(state, draft)
    }
    // §4: 한 번의 실행 동작에서 job을 정확히 한 번 생성한다. Keyed on the controller
    // state, so it is false again the instant the flow moves and true for every press
    // after the first within one state — see [allowsRun] for why `canSubmit` alone
    // cannot carry this.
    var runLaunched by remember(state) { mutableStateOf(false) }
    val runOnce: () -> Unit = {
        if (allowsRun(state, runLaunched)) {
            runLaunched = true
            onRun()
        }
    }

    // Every "put this away" in the sheet, routed through the one rule that knows
    // whether there is anything to come back to. The **only** exit that bypasses it is
    // the progress section's 취소, which is a different intent and stays a real cancel.
    //
    // This lives here rather than in the host so the host's two callbacks can each mean
    // exactly one thing. Before §4 the host carried the split itself
    // (`if (state is Candidates) close else cancel`); leaving it there and adding the
    // running states would have put the same `when` in two files.
    val dismiss: () -> Unit = {
        if (dismissActionFor(state) == RescueDismiss.CLOSE_ONLY) onHide() else onDismiss()
    }

    val section = rescueSectionFor(state, opened)
    if (section == RescueSection.HIDDEN) return

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim and sheet as siblings, sheet drawn second. Being drawn on top is
        // **not** enough on its own — Compose hit-tests the topmost node that
        // *handles pointer input*, not the topmost node that draws. `background`,
        // `clip` and `padding` handle nothing, so a tap on the sheet's own body
        // used to fall straight through to the scrim below and dismiss it. The
        // owner hit this on the reference sheet (2026-07-29) and reported it as
        // "사진을 클릭하면 갑자기 닫혀버려"; the photo was not special — headings,
        // labels and padding all dismissed too. Buttons survived only because each
        // is its own pointer handler. The sheet below claims the gesture; see
        // `ReferenceCreateSheet`, which carries the same fix.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Scrim)
                // §4: 화면 이탈·재진입 뒤 진행 중 job의 폴링 상태를 복원한다. A tap out
                // here keeps a running job running, and re-opening lands on the same
                // section — which is the restoration.
                .clickable(onClick = dismiss),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ink900)
                // Claims every gesture that starts on the sheet body, so the scrim
                // beneath never sees it. Placed after `background` and before
                // `padding` so the claimed area is exactly the painted area,
                // inset included. Children are hit first, so the buttons and the
                // cards keep their own taps.
                .pointerInput(Unit) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
                }
                .padding(20.dp),
        ) {
            when (section) {
                RescueSection.INTRO -> IntroSection(onAnalyze = onAnalyze, onCancel = dismiss)
                RescueSection.PROGRESS -> ProgressSection(
                    message = progressMessageFor(runningOperation),
                    onCancel = onDismiss,
                )
                // One step to the user, two controller states: the card list stays put
                // and the tapped card becomes selected. `Recommendations` simply has
                // nothing chosen yet.
                RescueSection.RECOMMENDATIONS, RescueSection.CONFIRM -> {
                    val chosen = (state as? RescueState.Editing)?.operation
                    if (directPane && recommendations != null) {
                        DirectEditPane(
                            response = recommendations,
                            draft = draft,
                            confirmed = chosen,
                            canRun = allowsRun(state, runLaunched),
                            onDraftChange = { draft = it },
                            onConfirm = onChoose,
                            onRun = runOnce,
                            onBack = { directPane = false },
                            onCancel = dismiss,
                        )
                    } else {
                        PickSection(
                            response = recommendations,
                            chosen = chosen,
                            onChoose = onChoose,
                            onRun = runOnce,
                            onApplyLocalStyle = onApplyLocalStyle,
                            localStyleWouldChange = localStyleWouldChange,
                            onCancel = dismiss,
                            onDirectEdit = { directPane = true },
                        )
                    }
                }
                RescueSection.CANDIDATES -> CandidatesSection(
                    candidates = candidates,
                    selectedCandidateId = selectedCandidateId,
                    onSelect = onSelectCandidate,
                    onKeepLocal = onKeepLocal,
                    onSave = onSaveCandidate,
                    saving = saving,
                    saved = saved,
                    saveError = saveError,
                    onClose = dismiss,
                )
                RescueSection.FALLBACK -> FallbackSection(
                    message = fallbackMessage((state as RescueState.LocalFallback).reason),
                    onClose = dismiss,
                )
                RescueSection.HIDDEN -> Unit
            }
        }
    }
}

/**
 * The step before anything is sent.
 *
 * `analyze()` uploads the photo, so it does not happen on the strip tap — the strip
 * tap opens this. The wording promises only what is actually true and already
 * enforced (O-9: the server strips GPS on receipt), and mirrors AI 2's consent line
 * so the two flows make the same promise in the same words.
 */
@Composable
private fun IntroSection(onAnalyze: () -> Unit, onCancel: () -> Unit) {
    Text("AI로 보정", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(
        text = "이 사진을 보내서 지울 수 있는 것들을 찾아볼게요. 위치 정보는 자동으로 제거돼요.",
        color = TextMid,
        fontSize = 12.5.sp,
        modifier = Modifier.padding(top = 12.dp),
    )
    PrimaryPillButton(text = "사진 살펴보기", onClick = onAnalyze, modifier = Modifier.padding(top = 16.dp))
    SecondaryPillButton(text = "취소", onClick = onCancel, modifier = Modifier.padding(top = 10.dp))
}

/**
 * Analysing, submitting, polling. The same small non-text-heavy loading language as
 * D17 and AI 2's `AnalyzingSection` — no percentage, no stage name, no elapsed time.
 *
 * 취소 is §5-3's required cancel: the host cancels the coroutine driving the
 * controller and resets it, so this is a real cancel, not a hide. It is the one exit in
 * the sheet that does **not** go through [dismissActionFor] — tapping out of a running
 * job leaves it running (P2 §4), and this button is how the user stops it. The two
 * would be indistinguishable if both were wired the same way.
 */
@Composable
private fun ProgressSection(message: String, onCancel: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(color = Amber, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            Text(message, color = TextMid, fontSize = 12.5.sp)
        }
    }
    SecondaryPillButton(text = "취소", onClick = onCancel)
}

/**
 * 추천 카드 + 실행. One section for both [RescueSection.RECOMMENDATIONS] and
 * [RescueSection.CONFIRM] because they are one step to the user: the list stays put
 * and the tapped card becomes selected.
 *
 * That is also the only way back from a mis-tap. `choose()` moves the controller to
 * `Editing` and there is no transition back to `Recommendations` short of uploading
 * the photo again, so tapping a *different* card — which `choose()` accepts from
 * `Editing` too — is what "undo" has to be here.
 *
 * `local_style` never posts anything (it is not in the server's allowed operations,
 * and there is nothing to send: the local correction is already what the screen is
 * showing), so its action closes the sheet instead of running a job.
 *
 * @param onDirectEdit opens [DirectEditPane]. Offered in **both** branches below,
 *   including the one where nothing was recommended — a server with capabilities on
 *   and no suggestion for this photo is exactly the case P2's §4 exists for, and it is
 *   the branch where a user would otherwise have nowhere to go.
 */
@Composable
private fun PickSection(
    response: RescueAnalysisResponse?,
    chosen: JsonObject?,
    onChoose: (JsonObject) -> Unit,
    onRun: () -> Unit,
    onApplyLocalStyle: () -> Unit,
    localStyleWouldChange: Boolean,
    onCancel: () -> Unit,
    onDirectEdit: () -> Unit,
) {
    val offered = response?.let { offerableRecommendations(it) }.orEmpty()
    // Drawn only when something behind it can run — see [offersDirectEdit].
    val offersDirect = response != null && offersDirectEdit(response)

    Text("이렇게 살려볼 수 있어요", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)

    if (offered.isEmpty()) {
        // Not an error: every capability can legitimately be off (CAMP-2 without the
        // FLUX workflow, a photo with nothing removable). Saying so plainly beats an
        // empty list, and beats offering something that cannot run.
        Text(
            text = "이 사진에서는 지울 만한 게 보이지 않아요.",
            color = TextMid,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (offersDirect) {
            SecondaryPillButton(
                text = DIRECT_EDIT_TITLE,
                onClick = onDirectEdit,
                modifier = Modifier.padding(top = 16.dp),
            )
            SecondaryPillButton(text = "닫기", onClick = onCancel, modifier = Modifier.padding(top = 10.dp))
        } else {
            SecondaryPillButton(text = "닫기", onClick = onCancel, modifier = Modifier.padding(top = 16.dp))
        }
        return
    }

    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        offered.forEach { recommendation ->
            RecommendationCard(
                recommendation = recommendation,
                selected = recommendation.operation != null && recommendation.operation == chosen,
                onClick = { recommendation.operation?.let(onChoose) },
            )
        }
    }

    // Only for an operation this section itself offered — see [offersConfirmFor].
    if (chosen != null && offersConfirmFor(response, chosen)) {
        // 내 감도로 정리하기 on a photo that is *already* wearing the resolved 감도.
        // The apply is a correct no-op there, and a correct no-op is indistinguishable
        // from the defect unless the sheet says which one it is — an app capture opens
        // on its session preset, so a user with no reference photo hits this every
        // time. Saying it is already tidy is an answer; closing on an unchanged photo
        // is the silence 결함 2 was reported as.
        val noOp = !requiresUpload(chosen) && !localStyleWouldChange
        Text(
            text = if (noOp) "이미 내 감도로 정리돼 있어요" else confirmMessageFor(chosen),
            color = TextMid,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 16.dp),
        )
        when {
            requiresUpload(chosen) ->
                PrimaryPillButton(text = "실행하기", onClick = onRun, modifier = Modifier.padding(top = 10.dp))
            // Still the apply, not a bare close: it puts the strip on the resolved
            // 감도 explicitly, so the state the line just claimed is the state the
            // screen is actually in rather than one it happened to agree with.
            noOp ->
                PrimaryPillButton(text = "확인", onClick = onApplyLocalStyle, modifier = Modifier.padding(top = 10.dp))
            // Same word as the uploading branch, because from the user's side it is
            // the same act — they chose a card and are running it. It read 이대로 두기
            // while the handler behind it did nothing, which described the bug
            // accurately but was never what the card offered.
            else ->
                PrimaryPillButton(text = "적용하기", onClick = onApplyLocalStyle, modifier = Modifier.padding(top = 10.dp))
        }
    }
    if (offersDirect) {
        SecondaryPillButton(text = DIRECT_EDIT_TITLE, onClick = onDirectEdit, modifier = Modifier.padding(top = 10.dp))
    }
    SecondaryPillButton(text = "취소", onClick = onCancel, modifier = Modifier.padding(top = 10.dp))
}

/**
 * One recommendation. `title`/`reason` come from the server, which authors them as
 * product copy in Korean; `confidence` is deliberately not drawn — a number next to
 * a suggestion is the 기술 점수 the contract forbids.
 */
@Composable
private fun RecommendationCard(
    recommendation: RescueRecommendation,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink800)
            .then(if (selected) Modifier.border(1.5.dp, Amber, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = recommendation.title,
            color = if (selected) Amber else TextHi,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = recommendation.reason,
            color = TextMid,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * §5-3 결과 후보. At most two ([rescueCandidates]), each carrying the required
 * `AI 생성 보완` badge, plus the contract's other branch — keeping the local
 * correction — as the secondary action.
 *
 * Tapping a tile updates the photo behind the sheet immediately, because the result
 * screen derives its source from the selection. Nothing is written until the user
 * saves, and the save writes a new file.
 *
 * The primary action **is** that save. It used to be 완료, which only closed the
 * sheet — so the contract's 후보 비교 → 새 파일 저장 ended one step early and the user
 * was left to find the header's 저장 pill, behind the sheet that had been covering it.
 * That is the tail of 결함 5 in the 2026-07-30 브리프 §13: 저장됐는지 알기 어려운 흐름.
 * The save is the same [performSave] the header calls; only the entry point is new.
 */
@Composable
private fun CandidatesSection(
    candidates: List<RescueCandidate>,
    selectedCandidateId: String?,
    onSelect: (RescueCandidate) -> Unit,
    onKeepLocal: () -> Unit,
    onSave: () -> Unit,
    saving: Boolean,
    saved: Boolean?,
    saveError: String?,
    onClose: () -> Unit,
) {
    if (candidates.isEmpty()) {
        // The job reported done but nothing usable landed on disk. There is no
        // result to show, so this is the fallback state in everything but name —
        // and showing empty tiles would be claiming results that do not exist.
        FallbackSection(message = LOCAL_FALLBACK_MESSAGE, onClose = onClose)
        return
    }

    Text("마음에 드는 걸 골라주세요", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Row(
        modifier = Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        candidates.forEach { candidate ->
            CandidateTile(
                candidate = candidate,
                selected = candidate.resultId == selectedCandidateId,
                onClick = { onSelect(candidate) },
                modifier = Modifier.weight(1f),
            )
        }
        // One candidate leaves a half-width tile; a spacer keeps it at the size the
        // two-up layout draws rather than stretching a single result across the sheet.
        if (candidates.size < MAX_RESCUE_CANDIDATES) Box(Modifier.weight(1f))
    }
    // Saving needs something chosen. Without a pick the photo behind the sheet is
    // still the local correction, and a 저장 that quietly wrote *that* under a heading
    // asking the user to choose a candidate would be the flow lying about what it
    // saved — 기본 보정 그대로 두기 below is the deliberate way to that outcome.
    val picked = candidates.any { it.resultId == selectedCandidateId }
    PrimaryPillButton(
        text = when {
            saving -> "저장 중이에요"
            saved == true -> "갤러리에 저장됨"
            else -> "이 사진으로 저장"
        },
        onClick = onSave,
        enabled = picked && !saving && saved != true,
        modifier = Modifier.padding(top = 18.dp),
    )
    // The header's own save status is behind this sheet, so it has to be repeated
    // here — a failed save that reports itself somewhere the user cannot see is the
    // same silence this section exists to remove.
    if (saveError != null) {
        Text(text = saveError, color = Amber, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
    }
    SecondaryPillButton(
        text = if (saved == true) "닫기" else "기본 보정 그대로 두기",
        onClick = if (saved == true) onClose else onKeepLocal,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun CandidateTile(
    candidate: RescueCandidate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(170.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Ink800)
            .then(if (selected) Modifier.border(2.dp, Amber, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = File(candidate.filePath),
            contentDescription = "생성 결과",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        GenerativeBadge(modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}

/** §5-3 "**'AI 생성 보완' 뱃지** 표시" — same chip shape as the result screen's style label. */
@Composable
fun GenerativeBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Amber)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = GENERATIVE_BADGE_LABEL,
            color = OnAmber,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * R5 / [RescueState.LocalFallback]. The contract fixes the wording and forbids the
 * raw reason, so [message] is always [LOCAL_FALLBACK_MESSAGE] — see [fallbackMessage].
 *
 * There is no retry button: `LocalFallback` carries no retryability signal (unlike
 * AI 2's `Error`), and deciding it from the reason string would mean reading the very
 * thing that must not reach the user. Closing returns to a working result screen
 * still showing the local correction, and the strip's `AI로 보정` is the retry.
 */
@Composable
private fun FallbackSection(message: String, onClose: () -> Unit) {
    Text(message, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(
        text = "지금은 AI 보정을 쓸 수 없어서 원래 보정 결과를 그대로 두었어요.",
        color = TextMid,
        fontSize = 12.5.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    PrimaryPillButton(text = "닫기", onClick = onClose, modifier = Modifier.padding(top = 16.dp))
}
