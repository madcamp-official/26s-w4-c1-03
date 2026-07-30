package com.gamdo.app.ui.rescue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamdo.app.data.network.RescueAnalysisResponse
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.components.SecondaryPillButton
import com.gamdo.app.ui.theme.Amber
import com.gamdo.app.ui.theme.GamdoType
import com.gamdo.app.ui.theme.Ink700
import com.gamdo.app.ui.theme.Ink800
import com.gamdo.app.ui.theme.Outline
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextLow
import com.gamdo.app.ui.theme.TextMid
import kotlinx.serialization.json.JsonObject

/**
 * AI 3 **직접 수정** — the section that lets the user run an operation the server is
 * able to perform but did not suggest (P2's §4).
 *
 * Every rule this pane obeys is a function in `DirectEditDecisions.kt`; nothing is
 * decided here. What is decided here is only the arrangement, which §4 hands to P1
 * outright ("추천 카드, 직접 수정 화면, 비교 화면의 형태는 P1이 결정한다").
 *
 * ## The shape, and why
 *
 * One vertical run of the same question asked three times — **무엇을 / 어느 쪽 /
 * 얼마나** — each a horizontally scrolling row of chips, ending in the single amber
 * CTA. The redesign allows exactly one filled amber surface per screen and reserves
 * amber otherwise for selection rings, so a selected chip takes an amber *ring* and
 * keeps an ink fill; the CTA is the one filled amber thing in the sheet.
 *
 * The run button is two presses, not one, and that is the point of §4's second
 * condition: the first press confirms the draft with `choose()` and the second starts
 * the job. Changing any chip afterwards puts the CTA back to 준비하기, so a job can
 * never be started for parameters the user has since altered.
 *
 * ## The frame diagram
 *
 * `remove_objects` needs a rectangle, and the photo is behind the sheet rather than
 * in it. Instead of asking for the bitmap, this draws an **empty frame in the photo's
 * own aspect ratio** with the removable rectangles positioned inside it, from the
 * analysis the server already sent. It is a diagram and reads as one — no pixels, so
 * it cannot be mistaken for the photograph (AGENTS.md §7-6 / R6, which forbids
 * standing in for a real result with something that is not one).
 *
 * @param confirmed the operation the controller is currently holding
 *   ([com.gamdo.app.data.rescue.RescueState.Editing]), or null in `Recommendations`.
 *   Compared against the draft's build to decide whether the CTA confirms or runs.
 * @param canRun [allowsRun] for the current controller state, so the CTA cannot fire
 *   twice for one run action.
 */
@Composable
fun DirectEditPane(
    response: RescueAnalysisResponse,
    draft: DirectEditDraft,
    confirmed: JsonObject?,
    canRun: Boolean,
    onDraftChange: (DirectEditDraft) -> Unit,
    onConfirm: (JsonObject) -> Unit,
    onRun: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val candidates = maskCandidates(response)
    val operations = directEditOperations(response.capabilities, candidates.size)
    val built = directRunOperation(draft, candidates, response.capabilities)
    val readyToRun = built != null && built == confirmed

    Text(DIRECT_EDIT_TITLE, color = TextHi, style = GamdoType.Cta)

    // 무엇을. Only operations this build of the server can actually run reach this row
    // — see `directEditOperations`, and `ResultFlowDecisions.offersGenerativeRestore`
    // for why a disabled row is worse than an absent one.
    ChoiceRow(modifier = Modifier.padding(top = 12.dp)) {
        operations.forEach { operation ->
            ChoiceChip(
                label = directOperationLabel(operation),
                selected = draft.operation == operation,
                // Switching operations clears the other operations' parameters rather
                // than keeping them warm: a half-filled draft for an operation the user
                // came back to would put the CTA a single press from a job they did not
                // set up in this visit.
                onClick = { onDraftChange(DirectEditDraft(operation = operation)) },
            )
        }
    }

    when (draft.operation) {
        RescueOperation.REMOVE_OBJECTS -> {
            GroupLabel(DIRECT_GROUP_WHAT)
            MaskFrame(
                response = response,
                candidates = candidates,
                selected = draft.maskIds,
                onToggle = { candidate ->
                    val current = candidates.filter { it.id in draft.maskIds }
                    if (candidate.id in draft.maskIds) {
                        onDraftChange(draft.copy(maskIds = draft.maskIds - candidate.id))
                    } else if (canSelectMask(current, candidate)) {
                        // Refused rather than accepted-and-failed: past the server's
                        // edit-area limit the request is a 422 the user could not have
                        // predicted from looking at the picture.
                        onDraftChange(draft.copy(maskIds = draft.maskIds + candidate.id))
                    }
                },
            )
        }

        RescueOperation.OUTPAINT -> {
            GroupLabel(DIRECT_GROUP_WHERE)
            ChoiceRow {
                OutpaintDirection.entries.forEach { value ->
                    ChoiceChip(
                        label = outpaintDirectionLabel(value),
                        selected = draft.outpaintDirection == value,
                        onClick = { onDraftChange(draft.copy(outpaintDirection = value)) },
                    )
                }
            }
            GroupLabel(DIRECT_GROUP_HOW_MUCH)
            ChoiceRow {
                OutpaintAmount.entries.forEach { value ->
                    ChoiceChip(
                        label = outpaintAmountLabel(value),
                        selected = draft.outpaintAmount == value,
                        onClick = { onDraftChange(draft.copy(outpaintAmount = value)) },
                    )
                }
            }
        }

        RescueOperation.VIEWPOINT -> {
            GroupLabel(DIRECT_GROUP_WHERE)
            ChoiceRow {
                ViewpointMotion.entries.forEach { value ->
                    ChoiceChip(
                        label = viewpointMotionLabel(value),
                        selected = draft.viewpointMotion == value,
                        onClick = { onDraftChange(draft.copy(viewpointMotion = value)) },
                    )
                }
            }
            GroupLabel(DIRECT_GROUP_HOW_MUCH)
            ChoiceRow {
                ViewpointStrength.entries.forEach { value ->
                    ChoiceChip(
                        label = viewpointStrengthLabel(value),
                        selected = draft.viewpointStrength == value,
                        onClick = { onDraftChange(draft.copy(viewpointStrength = value)) },
                    )
                }
            }
        }

        RescueOperation.RELIGHT -> {
            GroupLabel(DIRECT_GROUP_WHERE)
            ChoiceRow {
                RelightDirection.entries.forEach { value ->
                    ChoiceChip(
                        label = relightDirectionLabel(value),
                        selected = draft.relightDirection == value,
                        onClick = { onDraftChange(draft.copy(relightDirection = value)) },
                    )
                }
            }
            GroupLabel(DIRECT_GROUP_HOW_MUCH)
            ChoiceRow {
                RelightStrength.entries.forEach { value ->
                    ChoiceChip(
                        label = relightStrengthLabel(value),
                        selected = draft.relightStrength == value,
                        onClick = { onDraftChange(draft.copy(relightStrength = value)) },
                    )
                }
            }
        }

        // Nothing picked yet, and `local_style` is not offered here at all.
        RescueOperation.LOCAL_STYLE, null -> Unit
    }

    // What is still missing, or what is about to happen. `confirmMessageFor` is the
    // recommendation path's own sentence for the same operation, so a direct edit and
    // a recommended one describe themselves identically.
    Text(
        text = if (readyToRun) confirmMessageFor(built!!) else directParameterHint(draft.operation),
        color = TextMid,
        style = GamdoType.Body,
        modifier = Modifier.padding(top = 16.dp),
    )

    PrimaryPillButton(
        text = if (readyToRun) DIRECT_RUN_LABEL else DIRECT_PREPARE_LABEL,
        // Disabled, never dead: an incomplete draft has no request to send, and once a
        // run is under way `canRun` is false so the same press cannot fire twice.
        enabled = built != null && (!readyToRun || canRun),
        onClick = { if (readyToRun) onRun() else built?.let(onConfirm) },
        modifier = Modifier.padding(top = 10.dp),
    )
    // Side by side rather than stacked. The sheet's `Column` does not scroll, so this
    // pane's whole height has to fit above the bottom edge; two rows of pills would
    // put the CTA of the tallest variant (the frame diagram) near a 600dp screen's top.
    Row(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SecondaryPillButton(text = DIRECT_BACK_LABEL, onClick = onBack, modifier = Modifier.weight(1f))
        SecondaryPillButton(text = "취소", onClick = onCancel, modifier = Modifier.weight(1f))
    }
}

/** A row of choices that scrolls rather than wrapping — the app's existing strip idiom. */
@Composable
private fun ChoiceRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, color = TextLow, style = GamdoType.Micro, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

/**
 * One choice. Amber appears as a ring only — the filled amber surface this sheet is
 * allowed is the CTA.
 */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Ink700 else Ink800)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Amber else Outline,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = if (selected) TextHi else TextMid, style = GamdoType.Body)
    }
}

/**
 * Where the removable things are, drawn as an empty frame in the photo's aspect ratio.
 *
 * The rectangles come from `analysis.subjects` by way of [maskCandidates], so their
 * geometry is the server's own and is already valid for `_validate_mask`.
 *
 * A [MIN_TAP] floor applies to the drawn box. Subjects are only bounded below by the
 * analyzer's 0.3% area floor, which on a sheet-width frame is a few device pixels —
 * accurate and untappable. The frame is a diagram of *where*, not a measurement of
 * *how big*, so a small marker at the right position is the honest trade; the request
 * still carries the true rectangle.
 */
@Composable
private fun MaskFrame(
    response: RescueAnalysisResponse,
    candidates: List<RescueMaskCandidate>,
    selected: Set<String>,
    onToggle: (RescueMaskCandidate) -> Unit,
) {
    val width = response.image.width
    val height = response.image.height
    // 4:5 is the app's own default capture ratio and the fallback when the response
    // carries no dimensions. Clamped so a nonsense response cannot draw a sliver.
    val ratio = if (width > 0 && height > 0) {
        (width.toFloat() / height.toFloat()).coerceIn(0.5f, 2.0f)
    } else {
        4f / 5f
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            modifier = Modifier
                // Height-led, and a shorter box for a landscape photo, so the widest
                // ratio this can produce is 260dp — inside the sheet's content width on
                // the narrowest phone the app supports. `aspectRatio` silently gives up
                // and returns the incoming constraints when its preferred size does not
                // fit, which would draw a frame that is not the photo's shape at all.
                .height(if (ratio >= 1f) 130.dp else 160.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(10.dp))
                .background(Ink800)
                .border(1.dp, Outline, RoundedCornerShape(10.dp))
                .semantics { contentDescription = DIRECT_MASK_FRAME_DESCRIPTION },
        ) {
            val frameWidth = maxWidth
            val frameHeight = maxHeight
            candidates.forEach { candidate ->
                val isSelected = candidate.id in selected
                val boxWidth = maxOf(frameWidth * candidate.width.toFloat(), MIN_TAP)
                val boxHeight = maxOf(frameHeight * candidate.height.toFloat(), MIN_TAP)
                Box(
                    modifier = Modifier
                        .offset(
                            x = minOf(frameWidth * candidate.x.toFloat(), frameWidth - boxWidth),
                            y = minOf(frameHeight * candidate.y.toFloat(), frameHeight - boxHeight),
                        )
                        .size(width = boxWidth, height = boxHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) Ink700 else Ink800)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Amber else TextLow,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .clickable { onToggle(candidate) },
                )
            }
        }
    }
}

/** Smallest a marker in the frame may be drawn, so every candidate stays tappable. */
private val MIN_TAP: Dp = 30.dp
