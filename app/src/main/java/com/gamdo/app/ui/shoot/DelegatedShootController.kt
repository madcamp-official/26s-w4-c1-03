package com.gamdo.app.ui.shoot

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gamdo.app.data.ShootSessionRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the `나 찍어줘` screen: one poll loop, four actions, no judgement of its own.
 *
 * Everything this class decides, it asks [ShootFlow] or [ShootPollGate]. What it owns
 * is the *plumbing* those two cannot be given without dragging Android into a unit
 * test: the coroutine that ticks every [POLL_INTERVAL_MS], the [Bitmap] the QR is
 * rendered into, and the clock.
 *
 * ## Lifetime
 *
 * [run] is called from a `LaunchedEffect` keyed on this controller, so the loop's
 * lifetime is the composition's and leaving the screen cancels it. [scope] **must be
 * the screen's own `rememberCoroutineScope()`**, not a longer-lived one, and that is
 * not a stylistic preference: [receive] calls `receiveAndClaim`, which deletes the
 * session on the server when it completes. Launched into an app-lived scope it would
 * run to completion after the user left, claim the session, and have nowhere to hand
 * the files — the photos would be irrecoverable. Cancelled with the screen, it stops
 * before the claim and the session survives for the next visit.
 *
 * That is the mechanism; it is not the guarantee. Every tick and every reply is
 * stamped with a [ShootPollGate] token and dropped if the screen has moved on, so a
 * later edit that hoists the loop into an application-scoped `CoroutineScope` — which
 * would compile, and would poll forever — stops at [ShootPollGate.mayPoll] instead.
 * `ShootFlowDecisionsTest` pins that behaviour, including the defect-injection case.
 *
 * ## Two things it will not do
 *
 *  - **Create a session on its own.** `POST /shoot-sessions` mints server-side state
 *    with a one-hour life, so it happens in [create] and [create] is only reachable
 *    from an `onClick`. [run] calls `refresh()`, which does not touch the network at
 *    all unless a session was already saved — so entering the screen with no session
 *    makes no request of any kind.
 *  - **Show the server's words.** Failures become [ShootStage.Failed], which carries
 *    no payload. Nothing here reads `Throwable.message` for display.
 *
 * @param nowMs injected so the remaining-time line can be tested; the real clock is
 *   the default.
 */
class DelegatedShootController(
    private val repository: ShootSessionRepository,
    private val importer: ReceivedPhotoImporter,
    private val scope: CoroutineScope,
    policyDecision: ShootPolicyDecision,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {

    /** The screen's whole state. Replaced, never mutated. */
    var flow: ShootFlow by mutableStateOf(ShootFlow().withPolicy(policyDecision))
        private set

    /** The encoded share link, or null when there is nothing to show. */
    var qrBitmap: Bitmap? by mutableStateOf(null)
        private set

    /** Minutes left on the link, recomputed on each poll tick — not on a timer. */
    var remainingMinutes: Int by mutableStateOf(0)
        private set

    private val gate = ShootPollGate()

    /**
     * The token of the current visit.
     *
     * Read by the one-shot actions so a reply that lands after the user leaves can be
     * recognised as stale. Written only by [run].
     */
    private var token: Long = ShootPollGate.FIRST_GENERATION

    /**
     * Restore, then poll every [pollIntervalMs] for as long as the screen is composed.
     *
     * The first `refresh()` is what makes re-entry work: a session created before the
     * user wandered off is still in settings, and this picks it back up rather than
     * offering to make a second one. With no saved session it is a no-op that issues no
     * request.
     */
    suspend fun run() {
        val visit = gate.enter()
        token = visit
        try {
            // Before anything else: photos a previous visit downloaded but could not
            // file away. Their session is already claimed, so nothing else will ever
            // come back for them.
            //
            // Wrapped, because this is a bonus and the poll loop is the job. A
            // filesystem that refuses to be walked must not cost the user the screen
            // they actually opened; the files stay put and the next visit tries again.
            runCatching { reconcilePendingImports() }
            apply(visit, repository.refresh())
            while (true) {
                delay(pollIntervalMs)
                if (!gate.mayPoll(visit, flow)) continue
                val session = flow.session ?: continue
                apply(visit, repository.refresh(session))
            }
        } finally {
            // Runs on cancellation too — this is the pairing that stops the loop and
            // invalidates every token handed out during this visit.
            gate.leave()
        }
    }

    /** 링크 만들기. The only path to `POST /shoot-sessions`. */
    fun create() {
        if (!flow.mayCreate) return
        val policy = flow.policy ?: return
        val visit = token
        flow = flow.createStarted()
        scope.launch {
            runCatching { repository.create(policy) }.fold(
                onSuccess = { session ->
                    if (!gate.mayApply(visit)) return@launch
                    // The server hands back a relative path and the repository makes it
                    // absolute; if that produced something a camera cannot resolve, the
                    // link is useless and saying so beats drawing an unscannable square.
                    if (!ShootShareUrl.isScannable(session.shareUrl)) {
                        flow = flow.createFailed()
                        return@launch
                    }
                    flow = flow.created(session)
                    updateRemaining()
                    encodeQr(session.shareUrl)
                },
                onFailure = { if (gate.mayApply(visit)) flow = flow.createFailed() },
            )
        }
    }

    /**
     * 사진 받기 — download every arrived photo, then let the server drop the session.
     *
     * [onOpenPhotos] is invoked once, with the files in arrival order, and only while
     * this screen is still the current one: navigating on behalf of a screen the user
     * already left would yank them somewhere they did not ask to go.
     */
    fun receive(onOpenCapture: (String) -> Unit) {
        if (!flow.mayReceive) return
        val session = flow.session ?: return
        val visit = token
        flow = flow.receiveStarted()
        scope.launch {
            runCatching {
                // Two stages, and the boundary between them is the claim. The download
                // is all-or-nothing and retryable; the import runs after the session is
                // already gone, so it tolerates partial failure and keeps what it could
                // not write. See `ReceivedPhotoImport.kt`.
                importReceivedPhotos(repository.receiveAndClaim(session), importer)
            }.fold(
                onSuccess = { result ->
                    if (!gate.mayApply(visit)) return@launch
                    qrBitmap = null
                    remainingMinutes = 0
                    val opened = result.firstCaptureId
                    if (opened == null) {
                        // Either nothing arrived or nothing could be written. Both leave
                        // the user with nothing to look at, and the files (if any) are
                        // still on disk for the next visit to retry.
                        flow = flow.receivedNothing()
                    } else {
                        flow = flow.received()
                        onOpenCapture(opened)
                    }
                },
                onFailure = { if (gate.mayApply(visit)) flow = flow.receiveFailed() },
            )
        }
    }

    /**
     * Finish any import a previous visit could not.
     *
     * The claim already happened for these files, so the server copy is gone and this is
     * the only thing that can still rescue them. It runs on entry and **navigates
     * nowhere**: the user just opened this screen and yanking them into a result screen
     * for a photo they may have already seen would be a side effect they did not ask
     * for. The photos land in the album, which is where the rest of a received batch
     * lives anyway.
     *
     * Silent by design — there is no message for it, because there is nothing for the
     * user to decide.
     */
    private suspend fun reconcilePendingImports() {
        val pending = pendingReceivedPhotos(repository.receivedPhotosRoot)
        if (pending.isEmpty()) return
        importReceivedPhotos(pending, importer)
    }

    /**
     * 다시 시도 / 다시 만들기 — one tap, one outcome.
     *
     * With a session still in hand there is nothing to remake: clearing the failure
     * flags lets the poll loop pick straight back up. Without one (a create that
     * failed, or a link that expired) the tap has to produce a new link, and it does so
     * here rather than leaving the user to press a second button — the tap *was* the
     * explicit request, so [create]'s rule is honoured.
     */
    fun retry() {
        flow = flow.restarted()
        if (flow.session == null) {
            qrBitmap = null
            remainingMinutes = 0
            create()
        }
    }

    private fun apply(visit: Long, snapshot: ShootSessionRepository.SessionSnapshot) {
        if (!gate.mayApply(visit)) return
        flow = flow.withSnapshot(snapshot, nowMs())
        updateRemaining()
        val url = flow.shareUrl
        if (url == null) {
            qrBitmap = null
        } else if (qrBitmap == null && ShootShareUrl.isScannable(url)) {
            // Re-entry with a restored session: the bitmap did not survive the screen,
            // the session did.
            scope.launch { encodeQr(url) }
        }
    }

    private fun updateRemaining() {
        remainingMinutes = flow.session?.let { shootRemainingMinutes(it.expiresAt, nowMs()) } ?: 0
    }

    /**
     * Encode off the main thread.
     *
     * `ShootQrCode.encode` writes the matrix with one `Bitmap.setPixel` per pixel —
     * 262144 calls at the default 512px — which is not a main-thread operation. A
     * failure leaves [qrBitmap] null and the panel simply shows the white card, because
     * the state machine's opinion of the situation is not this function's to change.
     */
    private suspend fun encodeQr(url: String) {
        val encoded = withContext(Dispatchers.Default) { runCatching { ShootQrCode.encode(url) }.getOrNull() }
        qrBitmap = encoded
    }

    companion object {
        /** P2's required cadence: `snapshot을 2초 간격으로 refresh`. */
        const val POLL_INTERVAL_MS = 2_000L
    }
}
