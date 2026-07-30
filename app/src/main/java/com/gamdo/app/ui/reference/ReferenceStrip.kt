package com.gamdo.app.ui.reference

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gamdo.app.ui.theme.Ink700
import com.gamdo.app.ui.theme.Ink950
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextMid
import com.gamdo.app.ui.theme.Outline
import com.gamdo.app.ui.theme.Amber

/**
 * O-10 filter-strip entry points, shared between the camera and result screens so
 * both stay pixel-consistent with their own existing thumb shape (circle on
 * camera, rounded square on result — see [buildFilterStrip]'s callers).
 *
 * All three composables here render inside the *existing* strip item slot shape
 * — no new visual vocabulary (R1/L-2 latitude note in `ReferenceCreateSheet.kt`).
 */
@Composable
private fun StripThumb(
    label: String,
    shape: Shape,
    size: Dp,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, Amber, shape).padding(2.dp)
                    } else {
                        Modifier.padding(2.dp)
                    },
                )
                .clip(shape)
                .background(Ink700),
            content = content,
        )
        Text(
            text = label,
            color = if (selected) Amber else TextMid,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * `+` — leftmost on both strips. Opens the picker straight away (§5-2 flow start).
 *
 * Labelled with a verb, and [MyReferenceThumb] with a possessive, because the two
 * used to read as the same thing. This one said `내 감도` and the applied slot said
 * `내 레퍼런스`, so the button that *creates* a 감도 and the slot that *is* one were
 * named by whichever word came to hand — and once the result screen started taking
 * its labels from `ResultFilterStateHolder`, whose reference entry is also `내 감도`,
 * both would have said `내 감도` outright. Owner decision 2026-07-30; the requirement
 * is P1-B3's "「내 감도 만들기」와 「현재 내 감도 적용」을 동일한 문구로 표현하지 않는다".
 */
@Composable
fun CreateReferenceThumb(
    shape: Shape,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StripThumb(label = ReferenceLabels.CREATE, shape = shape, size = size, selected = false, onClick = onClick, modifier = modifier) {
        Text(
            text = "+",
            color = Amber,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * `AI로 보정` — result-screen only, O-10's slot for AI 3. **Wired to nothing here**
 * by explicit instruction; [onClick] defaults to a no-op so AI 3's own agent can
 * hand this composable a real callback without any change to this file.
 */
@Composable
fun AiRestoreThumb(
    shape: Shape,
    size: Dp,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    StripThumb(label = "AI로 보정", shape = shape, size = size, selected = false, onClick = onClick, modifier = modifier) {
        Text(
            text = "AI",
            color = TextMid,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * `내 레퍼런스` — trailing slot, present only while a reference is active
 * ([buildFilterStrip]). [imageUri] is the picked photo held for the current
 * session; when it is null (e.g. app relaunched since the reference was created)
 * the thumb falls back to the plain background rather than a fabricated image —
 * AGENTS §7-6 bans standing in a fixed/dummy photo for a real one.
 *
 * 교체 (replace) is not a separate control: tapping `+` again and completing the
 * flow overwrites the single reference slot (§ 범위: "로컬의 단일 `내 레퍼런스`
 * 슬롯").
 *
 * **The corner badge opens [ReferenceDetailSheet]; it does not delete.** That is the
 * fix for 결함 3 of `docs/P1_전체기능_사용자시나리오_테스트·시연개선요청_2026-07-30.md`
 * §13 — "내 감도 필터 선택 시 필터 목록/사진이 사라지는 현상", reported as recurring on
 * device after the single-source `ResultFilterStateHolder` work had already landed.
 *
 * It kept recurring because the holder was never the cause. The badge was 15dp inside
 * a 58dp thumb, Compose hit-tests innermost-first, and it ran
 * `ReferenceCreateController.clearActive()` — which drops the row from Room and
 * republishes a null reference, so `synchronizeReference` takes the 내 감도 entry out
 * of the catalogue for good. A finger landing in the top-right corner of the tile the
 * user meant to *select* therefore deleted their 감도 outright, with no confirmation
 * and no undo, and it read from the user's seat as "I picked 내 감도 and it disappeared
 * from the list". Enlarging the badge to the 48dp minimum would only make the mis-tap
 * likelier, since the destructive target sits *inside* the one being aimed at.
 *
 * So the destructive action left the strip altogether and lives in the sheet, where it
 * has room for a full label. What the badge does now is non-destructive by
 * construction: the worst a mis-tap can cost is a sheet the user dismisses. The glyph
 * changes from `×` to `⋯` to say so — a visible affordance rather than the hidden
 * long-press this briefly used, which was undiscoverable and, keyed on recomposing
 * lambdas, intermittent besides.
 */
@Composable
fun MyReferenceThumb(
    shape: Shape,
    size: Dp,
    imageUri: Uri?,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StripThumb(label = ReferenceLabels.ACTIVE, shape = shape, size = size, selected = selected, onClick = onSelect, modifier = modifier) {
        if (imageUri != null) {
            AsyncImage(
                model = imageUri,
                contentDescription = ReferenceLabels.ACTIVE,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(15.dp)
                .clip(CircleShape)
                .background(Ink950)
                .clickable(onClick = onOpenDetail),
            contentAlignment = Alignment.Center,
        ) {
            // `⋯`, not `×`. The badge is still smaller than the 48dp minimum and a
            // corner mis-tap is still going to happen — what changed is that the
            // action behind it is now recoverable, so the size stops mattering.
            Text("⋯", color = TextHi, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * §5-2 camera-preview overlay: the current session's picked photo, translucent,
 * over the live preview. Mounts inside [com.gamdo.app.ui.camera.CameraScreen]'s
 * `referenceLayer` slot — "drawn inside the preview box, above the camera preview
 * and below the guide overlay" per that slot's own KDoc.
 *
 * **The photo only.** Its 투명도 slider used to live in here, at the preview box's
 * `BottomStart`, and moved out to [ReferenceOverlayAlphaControl] — see that
 * function for the two ways the preview box was swallowing it.
 *
 * Renders nothing when there is no image to show — this is a *this-session*
 * aid for framing like the reference, not a persistent product surface.
 */
@Composable
fun ReferenceOverlayLayer(
    imageUri: Uri?,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    if (imageUri == null) return
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = coil.compose.rememberAsyncImagePainter(imageUri),
            contentDescription = null,
            alpha = alpha,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The 투명도 slider for [ReferenceOverlayLayer] — **a sibling of the preview pane,
 * never a layer inside it.** Mounts in `CameraScreen`'s `referenceOverlayControl`
 * slot, between the preview and the sheet slot.
 *
 * ## Why it left the preview box (owner report 2026-07-31, 사용 불가)
 *
 * It used to sit at the preview box's `BottomStart`, `bottom = 12.dp`, and the
 * preview box is the one place on this screen where a small control cannot
 * survive. Two independent layers above it eat it, and both are load-bearing:
 *
 * 1. **The sheet-dismiss layer.** While any sheet is open, `CameraPreviewPane`
 *    mounts a transparent full-size `clickable(onDismissSheet)` as its topmost
 *    child — deliberately, so "버튼 바깥 탭으로 닫는다" works. Compose hit-tests
 *    innermost-first and stops at the first pointer-input node, so every touch
 *    inside the preview goes to *dismiss the sheet*, including a touch that
 *    lands exactly on the slider's thumb. The slider was therefore not merely
 *    obscured while the filter sheet was up — it was inoperable, and dragging it
 *    closed the sheet instead. That is the 사용 불가 the owner hit.
 * 2. **The aspect mask.** The letterbox bars are opaque `Ink950` and are drawn
 *    *above* the reference layer (they have to be: nothing may spill onto the
 *    bars). At 4:5 on a phone pane those bars are tens of dp tall, so a control
 *    12dp off the preview's bottom edge is behind one of them.
 *
 * ## Why a sibling rather than a nicer position inside the box
 *
 * Because the same argument this screen already uses for the shutter row
 * applies: `CameraSheetSlot`'s call site notes the sheet is "a **sibling** of
 * the shutter row in this Column, never a layer over it — that is why 시트가
 * 열린 상태에서도 셔터는 계속 쓸 수 있다 holds structurally instead of depending
 * on where the sheet's top edge happens to land". Moving the slider above the
 * mask and re-anchoring it to the aspect window would have fixed both symptoms
 * today while leaving it one layer-order edit away from coming back; a sibling
 * cannot be covered by the sheet or the mask at all, because neither is above it.
 *
 * It also satisfies P2's constraint on this control outright ("기존 포커스·핀치·
 * 올가미 터치 표면 위에 별도 전체 화면 pointer handler로 올리지 않는다"): the
 * pointer-input node this adds is now outside the preview box entirely, so pinch
 * and tap-to-focus never see it.
 *
 * The cost is ~56dp of preview height while 내 감도 is the selected style, paid
 * by the `weight(1f)` pane exactly as an open sheet is. That is the honest trade:
 * a control the user can reach is worth more than the preview rows it covers.
 *
 * @param imageUri the photo this fades — the *already gated* value handed to
 *   [ReferenceOverlayLayer], not a second decision. Null means there is nothing
 *   to fade, and then this control does not exist rather than sitting inert.
 */
@Composable
fun ReferenceOverlayAlphaControl(
    imageUri: Uri?,
    alpha: Float,
    onAlphaChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (imageUri == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The pill is kept from the in-preview version on purpose. On the screen's
        // own Ink950 background it is no longer needed for legibility, but it is
        // what ties this control to the translucent photo above it now that the two
        // no longer touch.
        Row(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(Color(0x99141614))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = alpha,
                onValueChange = { onAlphaChange(clampReferenceOverlayAlpha(it)) },
                valueRange = 0f..MAX_REFERENCE_OVERLAY_ALPHA,
                modifier = Modifier.width(160.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Amber,
                    activeTrackColor = Amber,
                    inactiveTrackColor = Outline,
                ),
            )
        }
    }
}
