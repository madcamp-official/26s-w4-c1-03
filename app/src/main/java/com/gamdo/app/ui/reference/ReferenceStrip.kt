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
 * The 투명도 slider for [ReferenceOverlayLayer] — **the small control, and only
 * the small control.** Mounts in `CameraPreviewPane`'s `referenceControl` slot,
 * which places it as the pane's last child inside the aspect window.
 *
 * ## The report this exists for (2026-07-31, 사용 불가)
 *
 * It used to be drawn by [ReferenceOverlayLayer] itself, at the preview box's
 * `BottomStart`, `bottom = 12.dp` — i.e. under two layers the pane mounts above
 * the reference layer, neither of which can move:
 *
 * 1. **The sheet-dismiss layer.** While any sheet is open the pane's topmost
 *    child is a transparent full-size `clickable(onDismissSheet)`, deliberately,
 *    so "버튼 바깥 탭으로 닫는다" works. Compose hit-tests siblings in reverse
 *    z-order and stops at the first pointer-input node, so every touch inside
 *    the pane went to *dismiss the sheet* — including one landing exactly on the
 *    slider's thumb. The slider was not merely obscured while the filter sheet
 *    was up; it was inoperable, and dragging it closed the sheet.
 * 2. **The aspect mask.** Its letterbox bars are opaque `Ink950` and are drawn
 *    above the reference layer, because nothing may spill onto them. At 4:5 on a
 *    phone pane those bars are tens of dp tall, so a control 12dp off the
 *    *pane's* bottom edge sits behind one.
 *
 * The fix is z-order plus the mask's own insets, both applied at the mount site.
 * The first version of this fix instead moved the slider out of the pane
 * altogether, into the screen's Column — which fixed both symptoms and broke
 * something worse: a Column sibling takes layout height from the `weight(1f)`
 * pane, and the pane's ratio is the CameraX viewport, so every photo taken while
 * 내 감도 was selected was silently cropped narrower. See `CameraScreen`'s Column
 * comment for the device measurement.
 *
 * ## What this composable may and may not be
 *
 * It must stay **content-sized**. P2's constraint is "기존 포커스·핀치·올가미
 * 터치 표면 위에 별도 전체 화면 pointer handler로 올리지 않는다. 작은 실제
 * 컨트롤만 소비해야 카메라 제스처가 유지된다", and now that this is mounted over
 * the gesture surface, a `fillMaxSize`/`fillMaxWidth` here would be exactly the
 * banned thing: the pane hit-tests this first, so anything it covers is taken
 * from pinch, tap-to-focus and the lasso. It also does no positioning of its own
 * — [Alignment] against the pane is the mount site's business, since only the
 * pane knows where the visible window is.
 *
 * @param imageUri the photo this fades — the *already gated* value handed to
 *   [ReferenceOverlayLayer], not a second decision. Null means there is nothing
 *   to fade, and then this control does not exist rather than sitting inert.
 *   That is also what keeps the pane's gestures whole while no 감도 is selected:
 *   nothing is mounted at all, so nothing is hit-tested.
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
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(Color(0x99141614))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = alpha,
            onValueChange = { onAlphaChange(clampReferenceOverlayAlpha(it)) },
            valueRange = 0f..MAX_REFERENCE_OVERLAY_ALPHA,
            // Wider than the 110dp it carried before, and that is the width budget
            // spent rather than a preference: 0..60% across 110dp put every step
            // the user cares about inside a thumb's travel of each other.
            modifier = Modifier.width(160.dp),
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Outline,
            ),
        )
    }
}
