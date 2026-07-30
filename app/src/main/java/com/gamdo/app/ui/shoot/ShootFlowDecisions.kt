package com.gamdo.app.ui.shoot

import com.gamdo.app.data.ShootPolicyV2
import com.gamdo.app.data.ShootSessionRepository.ActiveSession
import com.gamdo.app.data.ShootSessionRepository.SessionSnapshot
import com.gamdo.app.guide.GuideLayoutState

/**
 * Every decision the `나 찍어줘` hand-off makes, with **no `android.*` import** so it
 * runs under `testDebugUnitTest`.
 *
 * There is no `androidTest` source set and no Robolectric in this project, so a
 * `@Composable` cannot be executed here at all. The four states P2 requires be
 * distinguishable (만료 / 오류 / 도착 사진 없음 / 수신 가능), the "is this policy even
 * sendable" check, and "the session is gone after a claim" are therefore lifted out
 * of the screen and into [ShootFlow] and [shootPolicyFor], which
 * `ShootFlowDecisionsTest` drives by hand. The screen is left with nothing but
 * layout: it reads [ShootFlow.stage] and draws it.
 */

// ---------------------------------------------------------------------------
// 1. Is there a policy to send at all?
// ---------------------------------------------------------------------------

/**
 * The result of turning the camera's current guide state into a shareable policy.
 *
 * P2's requirement is "레이아웃이 없을 때 임의의 잘못된 정책을 조용히 전송하지 않는다",
 * and the silent-send has **two** shapes, not one:
 *
 *  1. There is no fixed layout — the camera is still searching. Nothing to send.
 *  2. There *is* a fixed layout whose slots fall outside the V2 wire contract.
 *     `LayoutSlot` puts no bound on slot area; `ShootSlotV2` requires
 *     `bounds.area in 0.02..0.80` and throws `IllegalArgumentException` otherwise
 *     (P2's own `ShootPolicyTest` asserts that throw). An automatic layout is
 *     snapshotted from live detector boxes, so a ring on a table or a person filling
 *     the frame reaches [ShootPolicyV2.fromTemplate] and blows it up. Unhandled that
 *     is a crash on a button press; caught and mapped to a *policy* it would be the
 *     "임의의 잘못된 정책" the requirement forbids. So it is neither: it is the same
 *     dead end as (1), reported as [Unusable].
 *
 * Both land the user on the same choice — pick a frame, or cancel — which is exactly
 * what P2 asked for.
 */
sealed interface ShootPolicyDecision {

    /** Ready to hand to `ShootSessionRepository.create(policy)` unchanged. */
    data class Sendable(val policy: ShootPolicyV2) : ShootPolicyDecision

    /** No fixed layout yet; the camera is still looking. */
    data object NoLayout : ShootPolicyDecision

    /**
     * A layout exists but cannot be expressed as a V2 policy.
     *
     * Deliberately carries no reason string. The server's constraint names are not
     * user-facing copy, and the screen shows the same 구도 없음 state for both — the
     * distinction exists so a log/test can tell a crash-that-was-caught from a
     * camera that simply has not locked yet.
     */
    data object Unusable : ShootPolicyDecision
}

/**
 * Builds the policy for [state], or explains why there isn't one.
 *
 * @param preferredZoom the camera's current zoom, passed through to the friend's web
 *   camera. `null` when unknown — never a guessed default, because a wrong zoom
 *   silently reframes someone else's shot.
 */
fun shootPolicyFor(
    state: GuideLayoutState?,
    preferredZoom: Float? = null,
): ShootPolicyDecision {
    val fixed = state as? GuideLayoutState.Fixed ?: return ShootPolicyDecision.NoLayout
    return runCatching { ShootPolicyV2.fromTemplate(fixed.template, preferredZoom) }
        .fold(
            onSuccess = { ShootPolicyDecision.Sendable(it) },
            // IllegalArgumentException from ShootSlotV2/ShootPolicyV2's init blocks is
            // the expected failure and the only one these constructors throw.
            onFailure = { ShootPolicyDecision.Unusable },
        )
}

// ---------------------------------------------------------------------------
// 2. What the screen shows
// ---------------------------------------------------------------------------

/**
 * The one thing the screen renders. The four states P2 requires be distinguished are
 * four separate types here, so "뭉치지 마라" is enforced by the compiler rather than
 * by reading the composable.
 */
sealed interface ShootStage {

    /** No sendable policy and no live session — offer 프레임 고르기 or 취소. */
    data object NoLayout : ShootStage

    /** A policy is ready; the user has not asked for a link yet. */
    data object Idle : ShootStage

    data object Creating : ShootStage

    /** **도착 사진 없음.** The link is live and empty. */
    data class Waiting(val maxPhotos: Int) : ShootStage

    /** **수신 가능.** At least one photo is on the server. */
    data class Ready(val photoCount: Int, val maxPhotos: Int) : ShootStage

    data object Receiving : ShootStage

    /** **만료.** The hour is up; the link is dead and cannot be revived. */
    data object Expired : ShootStage

    /**
     * **오류.** Carries nothing — no message, no code, no `fail_reason`.
     *
     * The server's error payloads are diagnostic strings, and putting one on screen
     * is how "연결하지 못했어요" turns into `shoot_session_unavailable`. The screen has
     * one sentence for this state and no way to interpolate anything into it.
     */
    data object Failed : ShootStage
}

/**
 * The hand-off's whole state, as an immutable value with pure transitions.
 *
 * A reducer rather than a `ViewModel` for one reason: every rule worth testing is a
 * transition, and transitions on a value can be driven in a unit test without a
 * `Looper`, a `SavedStateHandle`, or a coroutine dispatcher. The screen holds one of
 * these in a `mutableStateOf` and replaces it.
 *
 * ## Why expiry is sticky and locally computed
 *
 * `ShootSessionRepository.refresh()` handles expiry by clearing the saved session and
 * publishing an **empty** `SessionSnapshot` — identical to "no session was ever
 * created". Rendering straight off the snapshot therefore cannot tell 만료 from 시작
 * 전, which is precisely the state-collapse P2 forbids. Two things fix it and both
 * are here:
 *
 *  - [expired] is **sticky**. Once observed it survives the empty snapshots that
 *    follow, until the user explicitly starts again ([restarted]).
 *  - Expiry is decided from [ActiveSession.expiresAt] against an injected `nowMs`,
 *    **not** from a server reply. A dead server must still produce 만료 rather than
 *    오류 once the hour is up: the link really is unusable, and 다시 시도 would be a
 *    lie. `SessionSnapshot.expired` is honoured too, for the case where the
 *    repository noticed first.
 *
 * @param consecutiveFailures refresh failures in a row. A single failed poll does not
 *   flip a working screen to 오류 — with a 2s cadence that would make one dropped
 *   packet look like an outage — but [FAILURE_THRESHOLD] in a row does, because at
 *   that point the photo count on screen is stale and offering 사진 받기 against it is
 *   the quiet breakage this whole state machine exists to prevent.
 */
data class ShootFlow(
    val policy: ShootPolicyV2? = null,
    val session: ActiveSession? = null,
    val photoCount: Int = 0,
    val maxPhotos: Int = 0,
    val busy: Busy = Busy.NONE,
    val expired: Boolean = false,
    val hardFailure: Boolean = false,
    val consecutiveFailures: Int = 0,
) {

    /** What the screen is waiting on, if anything. */
    enum class Busy { NONE, CREATING, RECEIVING }

    /**
     * Order matters, and this order is the contract:
     *
     *  - 만료 outranks 오류. An expired link that also fails to refresh is expired.
     *  - A live session outranks a missing policy. The layout that made the link is
     *    irrelevant once photos are on their way; the user must still be able to
     *    collect them.
     *  - 오류 outranks 수신 가능 only past [FAILURE_THRESHOLD] (see the KDoc above).
     */
    val stage: ShootStage
        get() = when {
            expired -> ShootStage.Expired
            busy == Busy.RECEIVING -> ShootStage.Receiving
            busy == Busy.CREATING -> ShootStage.Creating
            hardFailure || consecutiveFailures >= FAILURE_THRESHOLD -> ShootStage.Failed
            session == null && policy == null -> ShootStage.NoLayout
            session == null -> ShootStage.Idle
            photoCount > 0 -> ShootStage.Ready(photoCount, maxPhotos)
            else -> ShootStage.Waiting(maxPhotos)
        }

    /**
     * May the screen ask the server for the photo count right now?
     *
     * Not "is the screen open" — that is [ShootPollGate]'s job. This is the
     * *state* half: there is a session, it is not expired, and we are not in the
     * middle of downloading it. Polling deliberately continues while [stage] is
     * [ShootStage.Failed] so an outage heals itself without a tap; it stops dead on
     * [ShootStage.Expired] so the sticky flag above is never overwritten by the empty
     * snapshot the next refresh would produce.
     */
    val pollable: Boolean get() = session != null && !expired && busy != Busy.RECEIVING

    /**
     * May a session be created right now?
     *
     * The gate on the only network call in this flow that *creates server-side state*.
     * There must be no path to `create()` that the user did not ask for — the same
     * rule as "확정 전 edit-job 금지" on the AI side — so the screen calls it from an
     * `onClick` lambda and nowhere else, and this predicate makes the precondition
     * testable: no policy, an existing session, or a call already in flight all say no.
     * A second tap while [Busy.CREATING] is the realistic way to get two sessions from
     * one intent, and it is refused here rather than by hoping the button is hidden.
     */
    val mayCreate: Boolean
        get() = policy != null && session == null && busy == Busy.NONE && !expired

    /** May `receiveAndClaim()` run? Only with photos actually reported by the server. */
    val mayReceive: Boolean get() = session != null && photoCount > 0 && busy == Busy.NONE && !expired

    /** The link the QR must encode, or null. Never assembled here — see [ShootShareUrl]. */
    val shareUrl: String? get() = session?.shareUrl

    // -- transitions ---------------------------------------------------------

    /** The camera handed over a (possibly absent) layout. */
    fun withPolicy(decision: ShootPolicyDecision): ShootFlow = copy(
        policy = (decision as? ShootPolicyDecision.Sendable)?.policy,
    )

    fun createStarted(): ShootFlow = copy(busy = Busy.CREATING, hardFailure = false, consecutiveFailures = 0)

    fun created(session: ActiveSession): ShootFlow = copy(
        session = session,
        photoCount = 0,
        maxPhotos = session.maxPhotos,
        busy = Busy.NONE,
        expired = false,
        hardFailure = false,
        consecutiveFailures = 0,
    )

    /**
     * A tap produced nothing. Unlike a failed poll this surfaces immediately — the
     * user pressed a button and is owed an answer on the first attempt.
     */
    fun createFailed(): ShootFlow = copy(busy = Busy.NONE, hardFailure = true)

    /**
     * A poll came back. [nowMs] is injected so expiry is testable without the clock.
     *
     * Note what this does **not** touch: [busy]. A snapshot describes the server's
     * session, not the user's pending action, and clearing [busy] from here would let
     * a refresh that was already in flight when the user tapped 링크 만들기 land
     * afterwards, flip the screen back to [ShootStage.Idle], and invite a second tap —
     * two sessions from one intent. Only the create/receive transitions move [busy].
     */
    fun withSnapshot(snapshot: SessionSnapshot, nowMs: Long): ShootFlow {
        // First, and independent of whether either side still holds the session: the
        // repository publishes expiry *with the session already dropped*, so keying
        // this off a non-null session would miss exactly the case it exists for.
        if (snapshot.expired) return expire()
        val known = snapshot.session ?: session
        if (known != null && known.expiresAt <= nowMs) return expire()
        // An empty snapshot on a session we no longer hold is the post-claim state,
        // not a failure and not an expiry.
        if (known == null) return copy(session = null, photoCount = 0)
        val status = snapshot.status
        return if (snapshot.error != null && status == null) {
            copy(session = known, consecutiveFailures = consecutiveFailures + 1)
        } else if (status == null) {
            // In-flight refresh (loading, no status yet): nothing new to say.
            copy(session = known)
        } else {
            copy(
                session = known,
                photoCount = status.photos.size,
                maxPhotos = status.maxPhotos,
                // `consecutiveFailures` is poll health and a good reply is proof it
                // recovered. [hardFailure] is **not** cleared here: it records a *tap*
                // that failed, and a poll landing 2s later would otherwise wipe the
                // message off the screen before the user had read it, leaving them with
                // a 사진 받기 button and no idea the last press did not work. Only an
                // explicit [restarted] clears it.
                consecutiveFailures = 0,
            )
        }
    }

    /** The hour is up. Idempotent, and never goes back to a live state on its own. */
    fun expire(): ShootFlow = copy(
        session = null,
        photoCount = 0,
        busy = Busy.NONE,
        expired = true,
        hardFailure = false,
        consecutiveFailures = 0,
    )

    fun receiveStarted(): ShootFlow = copy(busy = Busy.RECEIVING, hardFailure = false, consecutiveFailures = 0)

    /**
     * `receiveAndClaim()` succeeded. The session is **gone** — P2 deletes it server
     * side and clears it from settings, so holding on to it here would leave the
     * screen polling a 404 and offering 사진 받기 on photos that no longer exist.
     */
    fun received(): ShootFlow = copy(
        session = null,
        photoCount = 0,
        maxPhotos = 0,
        busy = Busy.NONE,
        expired = false,
        hardFailure = false,
        consecutiveFailures = 0,
    )

    /**
     * The download failed. The session is **kept**: the photos are still on the
     * server and the whole point of the retry is to go back for them.
     */
    fun receiveFailed(): ShootFlow = copy(busy = Busy.NONE, hardFailure = true)

    /**
     * The claim succeeded and brought back nothing.
     *
     * A narrow race — the photos went away between the poll that counted them and the
     * claim — but a total state machine has to name it. The session is **gone** (the
     * server deleted it on claim, so [receiveFailed] would leave the screen offering a
     * retry against a 404) and there is nothing to show, so this is 오류 with no session:
     * the retry becomes 새 링크, which is the only thing that can still work.
     */
    fun receivedNothing(): ShootFlow = received().copy(hardFailure = true)

    /** 다시 만들기 / 다시 시도 — clears the sticky flags so the flow can run again. */
    fun restarted(): ShootFlow = copy(
        expired = false,
        hardFailure = false,
        consecutiveFailures = 0,
        busy = Busy.NONE,
    )

    companion object {
        /** Two consecutive failed polls ≈ 4s of real outage at the 2s cadence. */
        const val FAILURE_THRESHOLD = 2
    }
}

// ---------------------------------------------------------------------------
// 3. The share URL is the server's, verbatim
// ---------------------------------------------------------------------------

/**
 * The one check applied to the link before it becomes a QR code.
 *
 * P2's rule is "QR에는 서버가 발급한 절대 `shareUrl`만 넣는다. URL을 앱에서 조립하지
 * 마라". The app-side assembly that does exist is P2's own: the server returns a
 * *relative* `/shoot/{token}` and `ShootSessionRepository` runs it through
 * `GamdoApiClient.publicUrl()`. Honouring the rule from here therefore means one
 * thing — **pass `session.shareUrl` through untouched** — and this function exists to
 * make the failure loud instead of silent when that value is not usable.
 *
 * `ShootQrCode.encode` already `require`s an absolute URL, i.e. it throws. Checking
 * first turns a crash on a button press into [ShootStage.Failed], which is a state
 * the screen knows how to draw.
 */
object ShootShareUrl {

    fun isScannable(url: String?): Boolean =
        url != null && (url.startsWith("https://") || url.startsWith("http://")) && url.length > "https://".length
}

// ---------------------------------------------------------------------------
// 4. Polling stops when the screen closes
// ---------------------------------------------------------------------------

/**
 * Who is allowed to poll, and whose reply is allowed to land.
 *
 * A 2s poll is a repeating timer, and this project has already been bitten twice by
 * work that outlived the screen that started it — see
 * `camera/AnalysisPauseGate.kt` and `ui/camera/CameraTeardownGate.kt`, both of which
 * solve it with a generation stamp. Same discipline, same shape, for the same two
 * failure modes:
 *
 *  1. **The loop that keeps going.** Cancellation of the composable's
 *     `LaunchedEffect` is the primary mechanism and it is enough on the happy path.
 *     It is not enough to *rely* on, because a poll launched into a longer-lived
 *     scope by a later edit would compile and would keep hitting the network from a
 *     screen the user left. [mayPoll] refuses on a stale token, so that edit fails
 *     closed.
 *  2. **The reply that lands late.** A refresh already in flight when the user
 *     navigates away completes afterwards and writes state — including, at the wrong
 *     moment, an empty snapshot that would erase a sticky 만료. [mayApply] drops it.
 *
 * One screen is open at a time, so a single counter is the whole implementation.
 * Deliberately **not** a singleton: unlike the camera there is no hardware being
 * shared, and a per-screen instance means a test can hold two.
 */
class ShootPollGate {

    private val lock = Any()
    private var generation: Long = FIRST_GENERATION
    private var open: Boolean = false

    /** The screen appeared. Returns the token every later call must present. */
    fun enter(): Long = synchronized(lock) {
        generation += 1
        open = true
        generation
    }

    /** The screen went away. Every outstanding token is now worthless. */
    fun leave() = synchronized(lock) {
        open = false
    }

    /** Test/observability only. */
    val isOpen: Boolean get() = synchronized(lock) { open }

    /**
     * Both halves of "should I poll": the screen is still this one ([token] current
     * and open) **and** the state has something to poll for ([ShootFlow.pollable]).
     */
    fun mayPoll(token: Long, flow: ShootFlow): Boolean =
        synchronized(lock) { open && token == generation } && flow.pollable

    /** May a reply stamped [token] write to the screen's state? */
    fun mayApply(token: Long): Boolean = synchronized(lock) { open && token == generation }

    companion object {
        /** No token is ever handed out with this value. */
        const val FIRST_GENERATION = 0L
    }
}

// ---------------------------------------------------------------------------
// 5. The link's terms come from the server, never from a literal
// ---------------------------------------------------------------------------

/**
 * Minutes left on the link, from the server's own `expiresAt`.
 *
 * The screen used to be going to say `1시간 동안 열려 있어요 · 최대 5장`. Both numbers are
 * the server's (`SESSION_TTL_MS`, `MAX_PHOTOS` in `routes/shoot_sessions.py`) and the
 * server can change either without telling the app, which is how a screen goes quietly
 * stale — the same failure `guide_config.json` exists to prevent. So neither number is
 * written down here: the count comes from `SessionSnapshot`'s `maxPhotos` and the time
 * is computed from `expiresAt` by this function.
 *
 * Rounded **up**, and floored at 1 while any time remains: "0분 남았어요" on a link that
 * still works is false, and the 만료 state is what says the time is gone.
 *
 * Recomputed on each 2s poll tick, deliberately not on a 1s timer of its own — a
 * second repeating clock is a second thing to cancel, and minute-resolution copy does
 * not need one.
 */
fun shootRemainingMinutes(expiresAt: Long, nowMs: Long): Int {
    val remainingMs = expiresAt - nowMs
    if (remainingMs <= 0L) return 0
    return ((remainingMs + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1)
}

private const val MINUTE_MS = 60_000L
