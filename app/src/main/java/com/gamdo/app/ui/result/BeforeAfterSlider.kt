package com.gamdo.app.ui.result

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamdo.app.ui.theme.Charcoal950
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.Sage
import com.gamdo.app.ui.theme.Scrim

/**
 * §4-2 before/after comparison: one frame, a draggable curtain, pinch zoom.
 *
 * ## Why every gesture value is read inside a lambda
 *
 * §4-2's completion bar is "슬라이더 60fps 체감" and there is no device to measure on
 * (AGENTS.md §8), so the only move available is to remove the work that makes a drag
 * slow in the first place. Reading a `MutableState` in a composable body subscribes
 * the *composition* to it, so a finger crossing the screen would recompose and
 * re-lay-out this subtree every frame. Reading it inside `graphicsLayer {}` or
 * `drawWithContent {}` subscribes only the draw phase, and a drag becomes a redraw of
 * two already-uploaded textures.
 *
 * That is a structural argument, not a measurement. 60fps stays on DONE-DEVICE.
 *
 * ## Curtain, not two panes
 *
 * The split is in viewport space and the zoom transform is shared by both layers, so
 * the images stay registered pixel-for-pixel while zoomed. Panning each side
 * independently would let them drift apart, which is the one thing a before/after
 * view must not do.
 */
@Composable
fun BeforeAfterSlider(
    before: ImageBitmap,
    after: ImageBitmap?,
    modifier: Modifier = Modifier,
    beforeLabel: String = "원본",
    afterLabel: String = "보정",
) {
    var fraction by remember { mutableFloatStateOf(DEFAULT_SPLIT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var containerWidth by remember { mutableFloatStateOf(0f) }

    // A tab switch replaces the compared image; a curtain left at 10% would make the
    // new result look like it did nothing.
    LaunchedEffect(after) { fraction = DEFAULT_SPLIT }

    val transform = Modifier.graphicsLayer {
        scaleX = zoom
        scaleY = zoom
        translationX = panX
        translationY = panY
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Charcoal950)
            .onSizeChanged { containerWidth = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // Without this, a pinched-in view has no way back on a phone.
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val next = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                    zoom = next
                    if (next <= 1f) {
                        panX = 0f
                        panY = 0f
                    } else {
                        // Keep the image overlapping the viewport at any zoom.
                        val maxX = size.width * (next - 1f) / 2f
                        val maxY = size.height * (next - 1f) / 2f
                        panX = (panX + pan.x).coerceIn(-maxX, maxX)
                        panY = (panY + pan.y).coerceIn(-maxY, maxY)
                    }
                }
            },
    ) {
        Image(
            bitmap = before,
            contentDescription = beforeLabel,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().then(transform),
        )

        if (after != null) {
            Image(
                bitmap = after,
                contentDescription = afterLabel,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .then(transform)
                    .drawWithContent {
                        // Draw-phase clip: the curtain moves without recomposing.
                        clipRect(left = size.width * fraction) {
                            this@drawWithContent.drawContent()
                        }
                    },
            )

            CurtainHandle(
                containerWidth = containerWidth,
                fraction = { fraction },
                onDrag = { delta ->
                    if (containerWidth > 0f) {
                        fraction = (fraction + delta / containerWidth).coerceIn(0f, 1f)
                    }
                },
            )

            EdgeLabel(text = beforeLabel, alignment = Alignment.TopStart)
            EdgeLabel(text = afterLabel, alignment = Alignment.TopEnd, highlighted = true)
        }
    }
}

/**
 * The divider line and its grab target.
 *
 * [fraction] is a lambda, not a value: sampling it inside `graphicsLayer` keeps the
 * handle's position a draw-phase read, so dragging it does not recompose anything.
 * The touch strip is [HANDLE_TOUCH_WIDTH] wide while the visible line is 2dp — the
 * line is what you aim at, the strip is what you actually hit.
 */
@Composable
private fun BoxScope.CurtainHandle(
    containerWidth: Float,
    fraction: () -> Float,
    onDrag: (deltaPx: Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(HANDLE_TOUCH_WIDTH)
            .align(Alignment.CenterStart)
            .graphicsLayer {
                translationX = fraction() * containerWidth - HANDLE_TOUCH_WIDTH.toPx() / 2f
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    // Consume so the pinch/pan detector on the parent does not also
                    // move the image while the curtain is being dragged.
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(OnDarkHigh))
        Box(
            modifier = Modifier.size(HANDLE_KNOB).clip(CircleShape).background(Sage),
            contentAlignment = Alignment.Center,
        ) {
            Text("↔", color = OnSage, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Which side is which. Plain words only — R7-1. */
@Composable
private fun BoxScope.EdgeLabel(
    text: String,
    alignment: Alignment,
    highlighted: Boolean = false,
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (highlighted) Sage else Scrim)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = if (highlighted) OnSage else OnDarkHigh,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Start with the curtain in the middle. */
private const val DEFAULT_SPLIT = 0.5f

/** Past this a pinch stops being a comparison and starts being a pixel inspector. */
private const val MAX_ZOOM = 4f

private val HANDLE_TOUCH_WIDTH = 48.dp
private val HANDLE_KNOB = 32.dp
