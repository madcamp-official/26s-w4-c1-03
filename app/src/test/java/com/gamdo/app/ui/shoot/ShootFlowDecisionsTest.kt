package com.gamdo.app.ui.shoot

import com.gamdo.app.data.ShootSessionRepository.ActiveSession
import com.gamdo.app.data.ShootSessionRepository.SessionSnapshot
import com.gamdo.app.data.network.ShootPhotoInfo
import com.gamdo.app.data.network.ShootSessionStatus
import com.gamdo.app.detect.GuideObjectCategory
import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.guide.LayoutSlot
import com.gamdo.app.guide.LayoutSource
import com.gamdo.app.guide.LayoutTemplate
import com.gamdo.app.guide.LayoutTemplateCatalog
import com.gamdo.app.guide.RectN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `나 찍어줘` hand-off's judgement, driven without a device.
 *
 * P2's five conditions for calling this feature connected map onto these tests:
 * (1) no silent bad policy → `policy …` cases; (2) the server's absolute shareUrl only
 * → `share url …`; (3) 만료·오류·도착 없음·수신 가능 stay four states → `stage …`;
 * (5) the session is gone after a claim → `received …`. Condition (4) — opening the
 * received file through the existing result flow — is navigation, verified on device.
 */
class ShootFlowDecisionsTest {

    private val now = 1_000_000L
    private val hour = 3_600_000L

    private fun session(
        expiresAt: Long = now + hour,
        maxPhotos: Int = 5,
        shareUrl: String = "https://example.test/shoot/tok",
    ) = ActiveSession(
        sessionId = "shoot_x",
        ownerToken = "owner",
        shareUrl = shareUrl,
        expiresAt = expiresAt,
        maxPhotos = maxPhotos,
    )

    private fun status(photos: Int, maxPhotos: Int = 5, expiresAt: Long = now + hour) = ShootSessionStatus(
        sessionId = "shoot_x",
        expiresAt = expiresAt,
        maxPhotos = maxPhotos,
        photos = (1..photos).map { ShootPhotoInfo("shot_$it", now) },
    )

    private fun sendablePolicy() = shootPolicyFor(
        GuideLayoutState.Fixed(
            template = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.OBJECT_TRIO_TRIANGLE)!!,
            source = LayoutSource.AUTO,
        ),
    ) as ShootPolicyDecision.Sendable

    /** A flow that has a policy but no session — what the screen shows on entry. */
    private fun idleFlow() = ShootFlow().withPolicy(sendablePolicy())

    private fun liveFlow(photos: Int = 0, maxPhotos: Int = 5) = idleFlow()
        .createStarted()
        .created(session(maxPhotos = maxPhotos))
        .withSnapshot(SessionSnapshot(session = session(maxPhotos = maxPhotos), status = status(photos, maxPhotos)), now)

    // -- 1. is there a policy to send at all? --------------------------------

    @Test
    fun `policy is absent while the camera is still searching`() {
        assertEquals(ShootPolicyDecision.NoLayout, shootPolicyFor(GuideLayoutState.Searching))
        assertEquals(ShootPolicyDecision.NoLayout, shootPolicyFor(null))
    }

    @Test
    fun `policy comes from the fixed template and carries the zoom through`() {
        val template = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.OBJECT_TRIO_TRIANGLE)!!
        val decision = shootPolicyFor(GuideLayoutState.Fixed(template, LayoutSource.MANUAL), preferredZoom = 2f)

        val policy = assertSendable(decision)
        assertEquals(template.id, policy.layoutId)
        assertEquals(template.slots.size, policy.slots.size)
        assertEquals(2f, policy.preferredZoom)
    }

    /**
     * The case that would otherwise crash on a button press.
     *
     * `LayoutSlot` accepts any bounds; `ShootSlotV2` requires an area in 0.02..0.80 and
     * throws below it. An automatic layout is snapshotted from detector boxes, so a
     * small object produces exactly this template.
     */
    @Test
    fun `a slot too small for the wire contract is unusable, not a crash and not a policy`() {
        val tiny = LayoutTemplate(
            id = "tiny",
            slots = listOf(LayoutSlot(id = "s1", bounds = RectN(0.40f, 0.40f, 0.48f, 0.48f))),
        )
        assertEquals(
            ShootPolicyDecision.Unusable,
            shootPolicyFor(GuideLayoutState.Fixed(tiny, LayoutSource.AUTO)),
        )
    }

    @Test
    fun `a slot filling the frame is unusable too`() {
        val huge = LayoutTemplate(
            id = "huge",
            slots = listOf(
                LayoutSlot(
                    id = "s1",
                    expectedCategory = GuideObjectCategory.PERSON,
                    bounds = RectN(0.01f, 0.01f, 0.99f, 0.99f),
                ),
            ),
        )
        assertEquals(
            ShootPolicyDecision.Unusable,
            shootPolicyFor(GuideLayoutState.Fixed(huge, LayoutSource.AUTO)),
        )
    }

    @Test
    fun `an unusable layout leaves the screen with nothing to send`() {
        val flow = ShootFlow().withPolicy(ShootPolicyDecision.Unusable)

        assertNull(flow.policy)
        assertEquals(ShootStage.NoLayout, flow.stage)
        assertFalse(flow.mayCreate)
    }

    // -- 2. the four states stay four states --------------------------------

    @Test
    fun `no policy and no session is the frame-or-cancel state`() {
        assertEquals(ShootStage.NoLayout, ShootFlow().stage)
    }

    @Test
    fun `a policy with no session is idle, not waiting`() {
        val stage = idleFlow().stage
        assertEquals(ShootStage.Idle, stage)
        assertNotEquals(ShootStage.Waiting(5), stage)
    }

    @Test
    fun `a live empty session is 도착 사진 없음`() {
        assertEquals(ShootStage.Waiting(5), liveFlow(photos = 0).stage)
    }

    @Test
    fun `photos on the server is 수신 가능 with the count`() {
        assertEquals(ShootStage.Ready(2, 5), liveFlow(photos = 2).stage)
    }

    /**
     * The regression this whole file exists for.
     *
     * `refresh()` drops the session on expiry and publishes an empty snapshot, so the
     * cheap implementation renders 만료 as "no photos yet" — a link the user will wait
     * on forever. 만료 and 도착 사진 없음 must never be the same stage.
     */
    @Test
    fun `만료 is never rendered as 도착 사진 없음`() {
        val expired = liveFlow(photos = 0)
            .withSnapshot(SessionSnapshot(session = session(expiresAt = now - 1)), now)

        assertEquals(ShootStage.Expired, expired.stage)
        assertNotEquals(ShootStage.Waiting(5), expired.stage)
        assertNotEquals(ShootStage.Idle, expired.stage)
        assertNotEquals(ShootStage.NoLayout, expired.stage)
        assertEquals(ShootStage.Failed, ShootFlow().createFailed().stage)
        assertNotEquals(ShootStage.Failed, expired.stage)
    }

    @Test
    fun `the repository's expired snapshot is honoured even with the session already dropped`() {
        // What refresh() actually publishes: expired = true, session = null.
        val flow = liveFlow(photos = 1).withSnapshot(SessionSnapshot(expired = true), now)

        assertEquals(ShootStage.Expired, flow.stage)
    }

    @Test
    fun `expiry is decided locally so a dead server still says 만료 rather than 오류`() {
        val flow = liveFlow(photos = 0).withSnapshot(
            SessionSnapshot(session = session(expiresAt = now - 1), error = "boom"),
            now,
        )

        assertEquals(ShootStage.Expired, flow.stage)
    }

    @Test
    fun `expiry is sticky through the empty snapshots that follow`() {
        var flow = liveFlow(photos = 0).withSnapshot(SessionSnapshot(session = session(expiresAt = now - 1)), now)
        assertEquals(ShootStage.Expired, flow.stage)

        flow = flow.withSnapshot(SessionSnapshot(), now)
        flow = flow.withSnapshot(SessionSnapshot(), now)

        assertEquals(ShootStage.Expired, flow.stage)
        assertFalse(flow.pollable)
    }

    @Test
    fun `다시 만들기 clears the sticky expiry`() {
        val restarted = liveFlow()
            .withSnapshot(SessionSnapshot(session = session(expiresAt = now - 1)), now)
            .restarted()

        assertEquals(ShootStage.Idle, restarted.stage)
        assertTrue(restarted.mayCreate)
    }

    // -- 3. 오류, and how much of it to believe -----------------------------

    @Test
    fun `one failed poll does not throw away a count we already have`() {
        val flow = liveFlow(photos = 2)
            .withSnapshot(SessionSnapshot(session = session(), error = "timeout"), now)

        assertEquals(ShootStage.Ready(2, 5), flow.stage)
        assertTrue(flow.pollable)
    }

    @Test
    fun `two failed polls in a row is 오류`() {
        val flow = liveFlow(photos = 2)
            .withSnapshot(SessionSnapshot(session = session(), error = "timeout"), now)
            .withSnapshot(SessionSnapshot(session = session(), error = "timeout"), now)

        assertEquals(ShootStage.Failed, flow.stage)
    }

    @Test
    fun `polling continues while 오류 so an outage heals itself`() {
        val failed = liveFlow(photos = 0)
            .withSnapshot(SessionSnapshot(session = session(), error = "timeout"), now)
            .withSnapshot(SessionSnapshot(session = session(), error = "timeout"), now)
        assertEquals(ShootStage.Failed, failed.stage)
        assertTrue(failed.pollable)

        val healed = failed.withSnapshot(SessionSnapshot(session = session(), status = status(1)), now)
        assertEquals(ShootStage.Ready(1, 5), healed.stage)
    }

    @Test
    fun `a failed tap surfaces on the first attempt`() {
        assertEquals(ShootStage.Failed, idleFlow().createStarted().createFailed().stage)
    }

    @Test
    fun `an error string never reaches the stage`() {
        val flow = liveFlow(photos = 0)
            .withSnapshot(SessionSnapshot(session = session(), error = "shoot_session_unavailable"), now)
            .withSnapshot(SessionSnapshot(session = session(), error = "shoot_session_unavailable"), now)

        // ShootStage.Failed is an object: there is no field an error could ride in on.
        assertEquals(ShootStage.Failed, flow.stage)
        assertFalse(ShootCopy.FAILED_TITLE.contains("shoot"))
        assertFalse(ShootCopy.FAILED_TITLE.contains("%"))
    }

    // -- 4. no session without an explicit tap ------------------------------

    @Test
    fun `handing over a layout does not create a session`() {
        val flow = ShootFlow().withPolicy(sendablePolicy())

        assertNull(flow.session)
        assertEquals(ShootStage.Idle, flow.stage)
        assertFalse(flow.pollable)
    }

    @Test
    fun `a snapshot never invents a session`() {
        val flow = idleFlow().withSnapshot(SessionSnapshot(), now)

        assertNull(flow.session)
        assertEquals(ShootStage.Idle, flow.stage)
    }

    @Test
    fun `creating is refused unless there is a policy, no session, and nothing in flight`() {
        assertFalse("no policy", ShootFlow().mayCreate)
        assertTrue("policy, nothing in flight", idleFlow().mayCreate)
        assertFalse("already creating", idleFlow().createStarted().mayCreate)
        assertFalse("session already live", liveFlow().mayCreate)
        assertFalse(
            "expired",
            liveFlow().withSnapshot(SessionSnapshot(session = session(expiresAt = now - 1)), now).mayCreate,
        )
    }

    @Test
    fun `a stale refresh landing mid-create cannot re-arm the create button`() {
        // The hazard: an entry refresh still in flight when the user taps. If a
        // snapshot cleared `busy`, the screen would fall back to Idle and a second tap
        // would mint a second session.
        val creating = idleFlow().createStarted()
        val afterStaleSnapshot = creating.withSnapshot(SessionSnapshot(), now)

        assertEquals(ShootStage.Creating, afterStaleSnapshot.stage)
        assertFalse(afterStaleSnapshot.mayCreate)
    }

    // -- 5. receive, and the session that must not survive it ---------------

    @Test
    fun `receiving is refused until the server reports a photo`() {
        assertFalse(liveFlow(photos = 0).mayReceive)
        assertTrue(liveFlow(photos = 1).mayReceive)
        assertFalse(liveFlow(photos = 1).receiveStarted().mayReceive)
    }

    @Test
    fun `receiveAndClaim success leaves no session behind`() {
        val received = liveFlow(photos = 3).receiveStarted().received()

        assertNull(received.session)
        assertNull(received.shareUrl)
        assertEquals(0, received.photoCount)
        assertFalse(received.pollable)
        assertFalse(received.mayReceive)
        assertEquals(ShootStage.Idle, received.stage)
    }

    @Test
    fun `the empty snapshot after a claim is not mistaken for 만료`() {
        val flow = liveFlow(photos = 3).receiveStarted().received().withSnapshot(SessionSnapshot(), now)

        assertEquals(ShootStage.Idle, flow.stage)
    }

    @Test
    fun `a failed download keeps the session so the photos can be fetched again`() {
        val flow = liveFlow(photos = 3).receiveStarted().receiveFailed()

        assertEquals(ShootStage.Failed, flow.stage)
        assertEquals(3, flow.photoCount)
        assertTrue(flow.session != null)
    }

    @Test
    fun `a failed download is not wiped off the screen by the next successful poll`() {
        val flow = liveFlow(photos = 3).receiveStarted().receiveFailed()
            .withSnapshot(SessionSnapshot(session = session(), status = status(3)), now)

        assertEquals("the tap that failed still has to be reported", ShootStage.Failed, flow.stage)
        assertEquals(ShootStage.Ready(3, 5), flow.restarted().stage)
    }

    @Test
    fun `polling pauses while the download runs`() {
        assertFalse(liveFlow(photos = 2).receiveStarted().pollable)
    }

    // -- 6. the share URL is the server's ------------------------------------

    @Test
    fun `only an absolute share url is scannable`() {
        assertTrue(ShootShareUrl.isScannable("https://api.example.test/shoot/abc.def"))
        assertTrue(ShootShareUrl.isScannable("http://192.168.0.2:8000/shoot/abc.def"))
        assertFalse("the server's raw relative path", ShootShareUrl.isScannable("/shoot/abc.def"))
        assertFalse(ShootShareUrl.isScannable(null))
        assertFalse(ShootShareUrl.isScannable(""))
        assertFalse("scheme with no host", ShootShareUrl.isScannable("https://"))
    }

    @Test
    fun `the session's url reaches the screen untouched`() {
        val url = "https://api.example.test/shoot/eyJzaWQiOiJzaG9vdF8xIn0.sig"
        val flow = idleFlow().createStarted().created(session(shareUrl = url))

        assertEquals(url, flow.shareUrl)
    }

    // -- 7. polling stops when the screen closes ----------------------------

    @Test
    fun `an open screen with a live session may poll`() {
        val gate = ShootPollGate()
        val token = gate.enter()

        assertTrue(gate.mayPoll(token, liveFlow()))
        assertTrue(gate.mayApply(token))
    }

    @Test
    fun `leaving the screen stops polling and drops a late reply`() {
        val gate = ShootPollGate()
        val token = gate.enter()
        gate.leave()

        assertFalse(gate.isOpen)
        assertFalse("the loop must not keep going", gate.mayPoll(token, liveFlow()))
        assertFalse("a refresh already in flight must not write state", gate.mayApply(token))
    }

    @Test
    fun `a token from the previous visit is worthless after re-entry`() {
        val gate = ShootPollGate()
        val stale = gate.enter()
        gate.leave()
        val fresh = gate.enter()

        assertFalse(gate.mayPoll(stale, liveFlow()))
        assertFalse(gate.mayApply(stale))
        assertTrue(gate.mayPoll(fresh, liveFlow()))
    }

    @Test
    fun `an open screen still refuses to poll when the state has nothing to poll for`() {
        val gate = ShootPollGate()
        val token = gate.enter()

        assertFalse("no session", gate.mayPoll(token, idleFlow()))
        assertFalse(
            "expired",
            gate.mayPoll(token, liveFlow().withSnapshot(SessionSnapshot(expired = true), now)),
        )
        assertFalse("download in flight", gate.mayPoll(token, liveFlow(photos = 1).receiveStarted()))
    }

    @Test
    fun `no token equals the sentinel generation`() {
        val gate = ShootPollGate()

        assertNotEquals(ShootPollGate.FIRST_GENERATION, gate.enter())
        assertFalse(gate.mayApply(ShootPollGate.FIRST_GENERATION))
    }

    // -- 8. the link's terms come from the server ---------------------------

    @Test
    fun `remaining minutes round up and never read zero while the link works`() {
        assertEquals(60, shootRemainingMinutes(now + hour, now))
        assertEquals(42, shootRemainingMinutes(now + 42 * 60_000L, now))
        assertEquals(3, shootRemainingMinutes(now + 2 * 60_000L + 1, now))
        assertEquals(1, shootRemainingMinutes(now + 1, now))
        assertEquals(0, shootRemainingMinutes(now, now))
        assertEquals(0, shootRemainingMinutes(now - hour, now))
    }

    @Test
    fun `the terms line interpolates the server's numbers and writes neither down`() {
        assertEquals("42분 남았어요 · 최대 5장", ShootCopy.terms(42, 5))
        // A server that raises the cap changes the sentence with no app edit.
        assertEquals("42분 남았어요 · 최대 9장", ShootCopy.terms(42, 9))
        assertEquals("최대 5장", ShootCopy.terms(0, 5))
        assertEquals("", ShootCopy.terms(0, 0))
        // The retired literals must not be hiding anywhere in the copy object.
        assertFalse(ShootCopy.WAITING_TITLE.contains("1시간"))
        assertFalse(ShootCopy.WAITING_EMPTY.contains("5장"))
    }

    private fun assertSendable(decision: ShootPolicyDecision) =
        (decision as? ShootPolicyDecision.Sendable)?.policy
            ?: throw AssertionError("expected a sendable policy, was $decision")
}
