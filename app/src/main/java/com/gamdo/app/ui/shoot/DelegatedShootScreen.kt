package com.gamdo.app.ui.shoot

import android.graphics.Bitmap
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamdo.app.ui.theme.Amber
import com.gamdo.app.ui.theme.GamdoType
import com.gamdo.app.ui.theme.Ink950
import com.gamdo.app.ui.theme.OnAmber
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextLow
import com.gamdo.app.ui.theme.TextMid

/**
 * The `나 찍어줘` hand-off screen: show a QR, watch for photos, collect them.
 *
 * ## This file contains no judgement
 *
 * Every decision — which of the four required states is current, whether the layout
 * can even become a policy, whether a poll may run, whether the session survived a
 * claim — lives in `ShootFlowDecisions.kt` and is tested there. A `@Composable`
 * cannot be executed under `testDebugUnitTest` (no `androidTest` source set, no
 * Robolectric), so anything decided *here* would be untestable by construction. What
 * is left here is layout, and one lifecycle rule.
 *
 * ## The one lifecycle rule
 *
 * The 2s poll runs in a [LaunchedEffect] scoped to this composable, so leaving the
 * screen cancels it — that is the primary mechanism and it is enough on the happy
 * path. It is not the only guard, because "enough on the happy path" is how this
 * project got two unreleased-listener bugs in one day: every tick and every reply is
 * also stamped by [ShootPollGate], which refuses a stale token. A future edit that
 * moves the loop into a longer-lived scope therefore fails closed instead of quietly
 * polling from a screen the user has left. See the gate's KDoc.
 *
 * ## Colour, and the one deliberate exception
 *
 * Redesign rules (`ui/theme/Color.kt`): ink surfaces, amber only for the single CTA,
 * at most one filled amber surface. The CTA is 링크 만들기 / 사진 받기 / 다시 시도 —
 * whichever one the current state offers, and never two at once.
 *
 * **The QR card is white with black modules, and that is the exception.** A QR code is
 * read by a camera thresholding light against dark, and the format's own quiet-zone
 * requirement is a *light* margin around the symbol. Inverting it to fit the dark
 * theme, or tinting the modules amber, degrades or destroys scan reliability on the
 * one device that matters here — someone else's phone, handheld, in whatever light the
 * room has. Legibility to a scanner outranks palette consistency, so the QR sits on a
 * white card and nothing else on the screen does.
 *
 * @param onPickFrame offered in the 구도 없음 state once the manual frame picker
 *   exists. Null until then, which is why that state currently offers only 닫기 — P2
 *   requires "기본 프레임 선택 또는 취소 중 하나", and 취소 is the half that exists.
 * @param onOpenPhotos handed the downloaded files, in arrival order, exactly once per
 *   successful claim. The caller opens them through the same result/save flow an album
 *   photo uses; this screen does not know what a result screen is.
 */
@Composable
fun DelegatedShootScreen(
    controller: DelegatedShootController,
    onClose: () -> Unit,
    onOpenPhotos: (List<File>) -> Unit,
    modifier: Modifier = Modifier,
    onPickFrame: (() -> Unit)? = null,
) {
    val flow = controller.flow
    val stage = flow.stage

    // The poll loop, for exactly as long as this composable exists. The enter/leave
    // pairing is a try/finally inside `run()`, so cancellation — the only way this
    // effect ends — always reaches it.
    LaunchedEffect(controller) { controller.run() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Ink950)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ShootCopy.TITLE, style = GamdoType.Title, color = TextHi)
            Text(
                ShootCopy.CLOSE,
                style = GamdoType.Body,
                color = TextMid,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when (stage) {
                ShootStage.NoLayout -> Message(ShootCopy.NO_LAYOUT_TITLE, ShootCopy.NO_LAYOUT_BODY)
                // Nothing to say yet: the header names the screen and the CTA names the
                // action, so a third sentence here would only be filler.
                ShootStage.Idle -> Unit
                ShootStage.Creating -> Busy(ShootCopy.CREATING)
                ShootStage.Receiving -> Busy(ShootCopy.RECEIVING)
                ShootStage.Expired -> Message(ShootCopy.EXPIRED_TITLE, null)
                ShootStage.Failed -> Message(ShootCopy.FAILED_TITLE, null)
                is ShootStage.Waiting -> QrPanel(
                    bitmap = controller.qrBitmap,
                    headline = ShootCopy.WAITING_TITLE,
                    detail = ShootCopy.WAITING_EMPTY,
                    terms = ShootCopy.terms(controller.remainingMinutes, stage.maxPhotos),
                )
                is ShootStage.Ready -> QrPanel(
                    bitmap = controller.qrBitmap,
                    headline = ShootCopy.arrived(stage.photoCount),
                    detail = null,
                    terms = ShootCopy.terms(controller.remainingMinutes, stage.maxPhotos),
                )
            }
        }

        // The screen's single amber surface. Which action it performs is a function of
        // the stage, so there is never a second one to compete with it.
        when (stage) {
            ShootStage.Idle -> Cta(ShootCopy.CREATE) { controller.create() }
            is ShootStage.Ready -> Cta(ShootCopy.RECEIVE) { controller.receive(onOpenPhotos) }
            ShootStage.Expired -> Cta(ShootCopy.EXPIRED_ACTION) { controller.retry() }
            ShootStage.Failed -> Cta(ShootCopy.RETRY) { controller.retry() }
            ShootStage.NoLayout -> onPickFrame?.let { Cta(ShootCopy.PICK_FRAME, onClick = it) }
            else -> Unit
        }
    }
}

@Composable
private fun Message(title: String, body: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = GamdoType.Title, color = TextHi, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = GamdoType.Body, color = TextMid, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Busy(label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(color = Amber, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
        Text(label, style = GamdoType.Body, color = TextMid)
    }
}

/**
 * The QR, its headline, and the link's terms.
 *
 * White card, black modules — see this file's KDoc for why this is the one place the
 * dark palette is set aside. The bitmap is [ContentScale.FillBounds] inside a square
 * so the module grid stays on pixel boundaries at whatever width the card gets.
 */
@Composable
private fun QrPanel(bitmap: Bitmap?, headline: String, detail: String?, terms: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .width(240.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(QR_LIGHT)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = ShootCopy.TITLE,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(headline, style = GamdoType.Title, color = TextHi, textAlign = TextAlign.Center)
        if (detail != null) Text(detail, style = GamdoType.Body, color = TextMid, textAlign = TextAlign.Center)
        if (terms.isNotEmpty()) Text(terms, style = GamdoType.Micro, color = TextLow)
    }
}

@Composable
private fun Cta(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Amber)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = GamdoType.Cta, color = OnAmber)
    }
}

/**
 * The QR card's background.
 *
 * Pure white, and deliberately **not** promoted to a palette token, so it cannot be
 * mistaken for part of the design system and "unified" into an ink surface by a later
 * cleanup pass. It is local, named for the scanner rather than for the palette, and
 * used in exactly one place — see this file's KDoc for why the exception exists.
 */
private val QR_LIGHT = Color.White
