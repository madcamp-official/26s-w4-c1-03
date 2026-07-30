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
import com.gamdo.app.ui.theme.Charcoal600
import com.gamdo.app.ui.theme.Charcoal950
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMedium
import com.gamdo.app.ui.theme.OutlineDim
import com.gamdo.app.ui.theme.Sage

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
                        Modifier.border(2.dp, Sage, shape).padding(2.dp)
                    } else {
                        Modifier.padding(2.dp)
                    },
                )
                .clip(shape)
                .background(Charcoal600),
            content = content,
        )
        Text(
            text = label,
            color = if (selected) Sage else OnDarkMedium,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** `+` — leftmost on both strips. Opens the picker straight away (§5-2 flow start). */
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
            color = Sage,
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
            color = OnDarkMedium,
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
 * 슬롯"). 삭제 (delete) is the small badge in the corner, on its own tap target so
 * it does not fire the select action underneath it.
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
                .background(Charcoal950)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = OnDarkHigh, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                    thumbColor = Sage,
                    activeTrackColor = Sage,
                    inactiveTrackColor = OutlineDim,
                ),
            )
        }
    }
}
