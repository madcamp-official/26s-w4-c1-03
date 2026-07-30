package com.gamdo.app.ui.reference

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
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
 * 슬롯"). 삭제 (delete) is the small badge in the corner.
 *
 * **The badge deletes on long-press, not on tap**, and that is the fix for 결함 3 of
 * `docs/P1_전체기능_사용자시나리오_테스트·시연개선요청_2026-07-30.md` §13 — "내 감도
 * 필터 선택 시 필터 목록/사진이 사라지는 현상", reported as recurring on device after
 * the single-source `ResultFilterStateHolder` work had already landed.
 *
 * It kept recurring because the holder was never the cause. The badge is 15dp inside
 * a 58dp thumb, Compose hit-tests innermost-first, and [onDelete] runs
 * `ReferenceCreateController.clearActive()` — which drops the row from Room and
 * republishes a null reference, so `synchronizeReference` takes the 내 감도 entry out
 * of the catalogue for good. A finger landing in the top-right corner of the tile the
 * user meant to *select* therefore deleted their reference outright, with no
 * confirmation and no undo, and it read from the user's seat as "I picked 내 감도 and
 * it disappeared from the list". Enlarging the badge to the 48dp minimum would only
 * make the mis-tap likelier, since the destructive target is *inside* the one the
 * user is aiming at.
 *
 * So the tap goes to [onSelect] — on a control whose whole job is selection that is
 * the overwhelmingly likely intent, and it makes a corner mis-tap do the right thing
 * instead of the worst thing — while deletion takes the deliberate gesture. Long-press
 * is the destructive-action idiom already in use on this screen (`ResultScreen`'s
 * 길게 누르면 원본이 보여요), so it costs no new vocabulary. Nothing is lost by the
 * demotion: the slot is single, and `+` overwrites it, so deleting is the rare path.
 */
@Composable
fun MyReferenceThumb(
    shape: Shape,
    size: Dp,
    imageUri: Uri?,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on `Unit` below, so the detector is installed once and survives
    // recomposition — the call sites pass lambda literals, and keying the
    // `pointerInput` on those would tear the detector down and rebuild it on every
    // recomposition, cancelling a long-press that was already in progress. That would
    // make deletion intermittent, which for a destructive action is worse than either
    // outcome. Same pattern `ResultScreen` uses for its own 길게 누르기.
    val select by rememberUpdatedState(onSelect)
    val delete by rememberUpdatedState(onDelete)
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
                // Consumed here rather than left to fall through: an inner pointer
                // handler swallows the gesture either way, so the tap has to be
                // forwarded explicitly for the corner of the tile to stay selectable.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { select() },
                        onLongPress = { delete() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = TextHi, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * §5-2 camera-preview overlay: the current session's picked photo, translucent,
 * with a strength slider. Mounts inside [com.gamdo.app.ui.camera.CameraScreen]'s
 * `referenceLayer` slot — "drawn inside the preview box, above the camera preview
 * and below the guide overlay" per that slot's own KDoc.
 *
 * Renders nothing when there is no image to show — this is a *this-session*
 * aid for framing like the reference, not a persistent product surface.
 */
@Composable
fun ReferenceOverlayLayer(
    imageUri: Uri?,
    alpha: Float,
    onAlphaChange: (Float) -> Unit,
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
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 12.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(Color(0x99141614))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = alpha,
                onValueChange = { onAlphaChange(clampReferenceOverlayAlpha(it)) },
                valueRange = 0f..MAX_REFERENCE_OVERLAY_ALPHA,
                modifier = Modifier.width(110.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Amber,
                    activeTrackColor = Amber,
                    inactiveTrackColor = Outline,
                ),
            )
        }
    }
}
