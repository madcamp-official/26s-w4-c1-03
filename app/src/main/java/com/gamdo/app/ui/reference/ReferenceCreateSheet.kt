package com.gamdo.app.ui.reference

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamdo.app.data.ReferenceCreateState
import com.gamdo.app.data.ReferenceResolution
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.ui.components.PrimaryPillButton
import com.gamdo.app.ui.components.SecondaryPillButton
import com.gamdo.app.ui.theme.Ink800
import com.gamdo.app.ui.theme.Ink900
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextMid
import com.gamdo.app.ui.theme.OnAmber
import com.gamdo.app.ui.theme.Amber
import com.gamdo.app.ui.theme.Scrim

/**
 * §5-2 "사진으로 만들기" flow surface — one modal that renders whichever section
 * [sheetSectionFor] says [state] is in. Hosted once at the navigation root
 * ([com.gamdo.app.ui.navigation.GamdoNavHost]) so it overlays whichever of
 * camera/result triggered it; both screens only need the `+` entry point and the
 * `내 레퍼런스` slot (see `ReferenceStrip.kt`) — this file owns everything between
 * "picked a photo" and "applied".
 *
 * R1/L-2 latitude: none of this is in design t2 (O-10 grants the entry points
 * only — see the integration task brief). Built minimally in the existing
 * language — charcoal sheet, sage accent, [PrimaryPillButton]/[SecondaryPillButton],
 * the same rounded-pill vocabulary as everywhere else — and nothing beyond what
 * the integration contract's "P1 연결 요구사항" lists. No slider for 강도: the
 * contract names a scope picker, not a strength control, so one is not added here.
 *
 * Hard contract prohibitions honoured here: no technical confidence, colour
 * temperature, camera height or scores anywhere in this UI (D2/contract); no
 * scope is offered beyond COLOR when `capabilities.composition=false`
 * ([selectableReferenceScopes]) — never an invented layout.
 *
 * @param previewImageUri the picked photo, held by the caller across the whole
 *   flow (`AwaitingConsent` alone carries a `Uri` in [ReferenceCreateState]; later
 *   states do not, so the preview step needs it threaded in from outside).
 */
@Composable
fun ReferenceCreateSheet(
    state: ReferenceCreateState,
    previewImageUri: Uri?,
    onDismiss: () -> Unit,
    onConfirmUpload: () -> Unit,
    onRetry: () -> Unit,
    onApply: (ResolvedStyle.ReferenceScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val section = sheetSectionFor(state)
    if (section == ReferenceSheetSection.HIDDEN) return

    Box(modifier = modifier.fillMaxSize()) {
        // Tap-outside-to-dismiss. Drawn first so the sheet sits on top of it.
        //
        // Being underneath is **not** what keeps the sheet's own taps away from it.
        // Compose stops hit-testing at the topmost node that *handles pointer input*,
        // not at the topmost node that draws — so a tap only stops here if nothing in
        // the sheet above claimed it. See the sheet's own handler below.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Scrim)
                .clickable(onClick = onDismiss),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ink900)
                // Claim every gesture that starts anywhere on the sheet body.
                //
                // Without this the sheet handled no pointer input of its own —
                // `background`, `clip` and `padding` are not pointer handlers — so
                // hit testing walked past it and landed on the scrim's `clickable`
                // underneath. Tapping the analysed photo, a heading, the 적용 범위
                // label or any of the padding therefore *dismissed the sheet*
                // mid-flow. The buttons and the scope chips escaped it only because
                // each of them is a pointer handler in its own right; that is what
                // made the bug look like it was about the photo specifically.
                //
                // This node is hit before the scrim because it is above it, and after
                // its own children because they are deeper — so a chip or a button
                // still wins its own tap, and everything else stops here.
                //
                // Placed after `background` and before `padding` so the claimed area
                // is exactly the area that is painted: inside the rounded clip, and
                // including the 20dp inset, which is part of the sheet to anyone
                // looking at it.
                .pointerInput(Unit) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
                }
                .padding(20.dp),
        ) {
            when (section) {
                ReferenceSheetSection.CONSENT -> ConsentSection(
                    uri = (state as ReferenceCreateState.AwaitingConsent).uri,
                    onConfirm = onConfirmUpload,
                    onCancel = onDismiss,
                )
                ReferenceSheetSection.ANALYZING -> AnalyzingSection()
                ReferenceSheetSection.PREVIEW -> PreviewSection(
                    imageUri = previewImageUri,
                    resolution = (state as ReferenceCreateState.Preview).resolution,
                    onApply = onApply,
                    onCancel = onDismiss,
                )
                ReferenceSheetSection.APPLIED -> AppliedSection(onClose = onDismiss)
                ReferenceSheetSection.ERROR -> ErrorSection(
                    retryable = (state as ReferenceCreateState.Error).retryable,
                    onRetry = onRetry,
                    onClose = onDismiss,
                )
                ReferenceSheetSection.HIDDEN -> Unit
            }
        }
    }
}

/** §5-2 "업로드 목적·삭제 고지" — one line, shown before [onConfirm] ever fires. */
@Composable
private fun ConsentSection(uri: Uri, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Text("내 감도 만들기", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Box(
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Ink800),
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "선택한 사진",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
    // D8/AGENTS §3: analysis inputs may be retained for quality debugging in this
    // closed demo, so this does not promise the photo itself is deleted — only
    // what is true and already enforced: it is used for this analysis, and
    // location metadata is stripped (ExifSanitizer + server-side GPS strip, O-9).
    Text(
        text = "선택한 사진은 구도와 색감을 분석하는 데 사용돼요. 위치 정보는 자동으로 제거돼요.",
        color = TextMid,
        fontSize = 12.5.sp,
        modifier = Modifier.padding(top = 14.dp),
    )
    PrimaryPillButton(text = "동의하고 분석하기", onClick = onConfirm, modifier = Modifier.padding(top = 16.dp))
    SecondaryPillButton(text = "취소", onClick = onCancel, modifier = Modifier.padding(top = 10.dp))
}

/** §5-2 분석 상태. Small, non-text-heavy — the same minimal loading language as D17. */
@Composable
private fun AnalyzingSection() {
    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(color = Amber, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            Text("분석하고 있어요", color = TextMid, fontSize = 12.5.sp)
        }
    }
}

/**
 * §5-2 구도/색감 미리보기 + 적용 범위 선택.
 *
 * The composition preview draws [ResolvedStyle.referenceSlots] as plain sage
 * rectangles over the picked photo — reusing the same parsed shape the guide
 * overlay itself consumes, rather than re-parsing the raw analysis JSON here.
 * No score, confidence or label is drawn on them (D2 / contract's hard
 * prohibition list). When the server could not analyse composition, this box is
 * simply not drawn — never a placeholder layout.
 */
@Composable
private fun PreviewSection(
    imageUri: Uri?,
    resolution: ReferenceResolution,
    onApply: (ResolvedStyle.ReferenceScope) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedScope by remember(resolution) {
        mutableStateOf(defaultReferenceScope(resolution.compositionAvailable))
    }
    val previewSlots = remember(resolution) {
        if (resolution.compositionAvailable) {
            ResolvedStyle.fromReference(
                hash = resolution.contentHash,
                target = resolution.targetComposition,
                colorTarget = resolution.colorTarget,
                scope = ResolvedStyle.ReferenceScope.BOTH,
            ).referenceSlots
        } else {
            emptyList()
        }
    }

    Text("구도와 색감을 확인해보세요", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)

    Box(
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Ink800),
    ) {
        if (imageUri != null) {
            AsyncImage(
                model = imageUri,
                contentDescription = "분석한 사진",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (previewSlots.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 2.dp.toPx())
                previewSlots.forEach { slot ->
                    val bounds = slot.bounds
                    if (bounds.size == 4) {
                        val left = (bounds[0] * size.width).toFloat()
                        val top = (bounds[1] * size.height).toFloat()
                        val width = (bounds[2] * size.width).toFloat()
                        val height = (bounds[3] * size.height).toFloat()
                        drawRect(
                            color = Amber,
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            style = stroke,
                        )
                    }
                }
            }
        }
    }

    Text(
        text = "적용 범위",
        color = TextMid,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        selectableReferenceScopes(resolution.compositionAvailable).forEach { scope ->
            ScopeChip(scope = scope, selected = scope == selectedScope, onClick = { selectedScope = scope })
        }
    }

    PrimaryPillButton(text = "적용", onClick = { onApply(selectedScope) }, modifier = Modifier.padding(top = 18.dp))
    SecondaryPillButton(text = "취소", onClick = onCancel, modifier = Modifier.padding(top = 10.dp))
}

private fun scopeLabel(scope: ResolvedStyle.ReferenceScope): String = when (scope) {
    ResolvedStyle.ReferenceScope.BOTH -> "구도와 색감"
    ResolvedStyle.ReferenceScope.COMPOSITION -> "구도만"
    ResolvedStyle.ReferenceScope.COLOR -> "색감만"
}

@Composable
private fun ScopeChip(scope: ResolvedStyle.ReferenceScope, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = scopeLabel(scope),
        color = if (selected) OnAmber else TextMid,
        fontSize = 12.5.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Amber else Ink800)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun AppliedSection(onClose: () -> Unit) {
    Text("내 감도가 적용됐어요", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(
        "촬영과 보정에 바로 반영돼요.",
        color = TextMid,
        fontSize = 12.5.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    PrimaryPillButton(text = "닫기", onClick = onClose, modifier = Modifier.padding(top = 16.dp))
}

/**
 * §5-2 error state. Until 담당 B's API is reachable (2026-07-29 — no date yet,
 * per the lead), this is not an edge case: every `confirmUpload` lands here.
 *
 * [retryable] is [ReferenceCreateController]'s own `isRetryable()` split —
 * `IOException` (a connection that could not be made at all) vs. anything else
 * (the server was reached and rejected this specific photo/request). The two
 * read differently to the user for the same reason the lead named: "the server
 * could not be reached" and "your photo could not be used" are different facts,
 * and neither is a raw error/status code (contract: no technical detail in
 * product UI).
 *
 * - retryable: [onRetry] re-invokes `confirmUpload` on the **same** picked
 *   photo — [ReferenceCreateController.confirmUpload] reads its retained
 *   `selectedUri`, which an error does not clear, so this is a real retry, not
 *   a restart.
 * - not retryable: only a clean way out. [onClose] is wired to
 *   `ReferenceCreateController.cancel()` everywhere this composable is called,
 *   which returns to `Idle` — a working camera, no restart needed.
 */
@Composable
private fun ErrorSection(retryable: Boolean, onRetry: () -> Unit, onClose: () -> Unit) {
    if (retryable) {
        Text("서버에 연결하지 못했어요", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        PrimaryPillButton(text = "다시 시도", onClick = onRetry, modifier = Modifier.padding(top = 16.dp))
        SecondaryPillButton(text = "닫기", onClick = onClose, modifier = Modifier.padding(top = 10.dp))
    } else {
        Text("이 사진은 사용할 수 없어요", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        PrimaryPillButton(text = "닫기", onClick = onClose, modifier = Modifier.padding(top = 16.dp))
    }
}
