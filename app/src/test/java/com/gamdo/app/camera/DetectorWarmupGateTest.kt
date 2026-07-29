package com.gamdo.app.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the warm-up policy that turned "the guide appears ~11 seconds after launch"
 * into "the model load starts before the camera screen exists".
 *
 * The load itself is 7.6s of MediaPipe and is not testable here, and neither is
 * "did the preview come up sooner" — those are device numbers. What *is* decidable
 * without a device is everything that can silently go wrong around the load:
 * building it twice, building it for a user who will never open the camera,
 * building it once and never giving the memory back, or giving the memory back
 * while a camera screen is still using it. Each of those is a test below.
 *
 * The trim levels are written as the raw `ComponentCallbacks2` numbers on purpose.
 * The production code restates them rather than importing `android.*`; restating a
 * constant is only safe if something checks the number, and this is that check.
 */
class DetectorWarmupGateTest {

    private companion object {
        const val TRIM_RUNNING_MODERATE = 5
        const val TRIM_RUNNING_LOW = 10
        const val TRIM_RUNNING_CRITICAL = 15
        const val TRIM_UI_HIDDEN = 20
        const val TRIM_BACKGROUND = 40
        const val TRIM_COMPLETE = 80
    }

    private val gate = DetectorWarmupGate()

    // --- when to start -----------------------------------------------------

    @Test
    fun `온보딩을 마친 사용자는 앱 시작 시점에 미리 로드한다`() {
        assertEquals(
            WarmupDecision.BUILD,
            gate.preload(onboardingComplete = true, cameraPermissionGranted = true),
        )
    }

    @Test
    fun `첫 실행 사용자는 온보딩으로 가므로 미리 로드하지 않는다`() {
        assertEquals(
            WarmupDecision.SKIP_ONBOARDING,
            gate.preload(onboardingComplete = false, cameraPermissionGranted = true),
        )
        assertTrue("아무것도 만들지 않았어야 한다", gate.needsWarmUp())
    }

    @Test
    fun `카메라 권한이 없으면 볼 프리뷰가 없으므로 미리 로드하지 않는다`() {
        assertEquals(
            WarmupDecision.SKIP_NO_CAMERA_PERMISSION,
            gate.preload(onboardingComplete = true, cameraPermissionGranted = false),
        )
        assertTrue(gate.needsWarmUp())
    }

    /**
     * The first-run user still gets a detector — just later, at the point they
     * actually reach the camera. This is the pre-change behaviour, unchanged.
     */
    @Test
    fun `미리 로드하지 않았어도 카메라 화면은 직접 만든다`() {
        gate.preload(onboardingComplete = false, cameraPermissionGranted = true)
        assertEquals(WarmupDecision.BUILD, gate.attach())
    }

    // --- how a late consumer adopts an in-flight build ----------------------

    @Test
    fun `로드가 끝나기 전에 카메라를 열어도 두 번 만들지 않는다`() {
        assertEquals(
            WarmupDecision.BUILD,
            gate.preload(onboardingComplete = true, cameraPermissionGranted = true),
        )
        // The build is still running on the analysis thread at this point — the
        // gate deliberately does not distinguish "in flight" from "finished",
        // because the executor's FIFO order already makes both safe to adopt.
        assertEquals(WarmupDecision.ADOPT, gate.attach())
    }

    @Test
    fun `두 번째 프리로드 요청도 다시 만들지 않는다`() {
        gate.preload(onboardingComplete = true, cameraPermissionGranted = true)
        assertEquals(
            WarmupDecision.ADOPT,
            gate.preload(onboardingComplete = true, cameraPermissionGranted = true),
        )
    }

    @Test
    fun `앨범에 갔다 돌아와도 다시 만들지 않는다`() {
        assertEquals(WarmupDecision.BUILD, gate.attach())
        gate.detach()
        assertEquals(WarmupDecision.ADOPT, gate.attach())
    }

    /**
     * `Application.onCreate`'s coroutine and the composition thread genuinely race
     * on a fast relaunch. Two builds means two 4.5MB models and two sets of native
     * detectors, one of which nothing would ever close.
     */
    @Test
    fun `여러 스레드가 동시에 요청해도 빌드는 정확히 한 번이다`() {
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val builds = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { index ->
                pool.execute {
                    start.await()
                    val decision = if (index % 2 == 0) {
                        gate.attach()
                    } else {
                        gate.preload(onboardingComplete = true, cameraPermissionGranted = true)
                    }
                    if (decision == WarmupDecision.BUILD) builds.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("스레드가 끝나지 않았다", done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals("빌드는 한 번뿐이어야 한다", 1, builds.get())
        assertEquals("attach 한 만큼만 소비자로 잡혀야 한다", threads / 2, gate.consumerCount())
    }

    // --- when to give the memory back --------------------------------------

    @Test
    fun `카메라 화면이 떠 있는 동안에는 트림이 와도 유지한다`() {
        gate.attach()
        assertEquals(WarmupDecision.NONE, gate.onTrimMemory(TRIM_UI_HIDDEN))
        assertEquals(WarmupDecision.NONE, gate.onTrimMemory(TRIM_COMPLETE))
        assertFalse("해제되지 않았어야 한다", gate.needsWarmUp())
    }

    @Test
    fun `보이지 않는 상태에서 소비자가 없으면 해제한다`() {
        gate.attach()
        gate.detach()
        assertEquals(WarmupDecision.RELEASE, gate.onTrimMemory(TRIM_UI_HIDDEN))
        assertTrue(gate.needsWarmUp())
    }

    @Test
    fun `백그라운드 LRU 단계에서도 해제한다`() {
        gate.preload(onboardingComplete = true, cameraPermissionGranted = true)
        assertEquals(WarmupDecision.RELEASE, gate.onTrimMemory(TRIM_BACKGROUND))
    }

    /**
     * Foreground trims are not a reason to throw away a warm cache — the user is
     * one tap from the camera. Only the level that means "processes are about to
     * be killed" is.
     */
    @Test
    fun `포그라운드 경미한 압박에서는 유지하고 위급 단계에서만 해제한다`() {
        gate.preload(onboardingComplete = true, cameraPermissionGranted = true)
        assertEquals(WarmupDecision.NONE, gate.onTrimMemory(TRIM_RUNNING_MODERATE))
        assertEquals(WarmupDecision.NONE, gate.onTrimMemory(TRIM_RUNNING_LOW))
        assertEquals(WarmupDecision.RELEASE, gate.onTrimMemory(TRIM_RUNNING_CRITICAL))
    }

    @Test
    fun `만든 적이 없으면 해제할 것도 없다`() {
        assertEquals(WarmupDecision.NONE, gate.onTrimMemory(TRIM_UI_HIDDEN))
    }

    @Test
    fun `해제된 뒤 포그라운드로 돌아오면 다시 만든다`() {
        gate.attach()
        gate.detach()
        assertEquals(WarmupDecision.RELEASE, gate.onTrimMemory(TRIM_UI_HIDDEN))
        assertEquals(
            WarmupDecision.BUILD,
            gate.preload(onboardingComplete = true, cameraPermissionGranted = true),
        )
    }

    // --- failure and counter hygiene ---------------------------------------

    /**
     * Before the stack was process-scoped it lived in a `remember`, so a failed
     * build was retried the next time the camera screen composed. Losing that
     * would be a regression caused by the warm-up, not by the failure.
     */
    @Test
    fun `빌드가 실패하면 다음 진입에서 다시 만든다`() {
        assertEquals(WarmupDecision.BUILD, gate.attach())
        gate.detach()
        assertTrue("버릴 것이 있었다고 알려야 한다", gate.invalidate())
        assertEquals(WarmupDecision.BUILD, gate.attach())
    }

    @Test
    fun `버릴 것이 없으면 invalidate 는 아무것도 없었다고 답한다`() {
        assertFalse(gate.invalidate())
    }

    /**
     * A stranded or doubled detach must not corrupt the counter: a negative count
     * would let a trim release the stack out from under a mounted camera screen.
     */
    @Test
    fun `소비자 수는 0 아래로 내려가지 않는다`() {
        gate.detach()
        gate.detach()
        assertEquals(0, gate.consumerCount())

        gate.attach()
        assertEquals(1, gate.consumerCount())
        assertEquals("아직 쓰는 중이다", WarmupDecision.NONE, gate.onTrimMemory(TRIM_UI_HIDDEN))
    }

    @Test
    fun `여러 화면이 겹쳐 붙어도 마지막 하나가 떨어질 때까지 유지한다`() {
        gate.attach()
        gate.attach()
        gate.detach()
        assertEquals(WarmupDecision.NONE, gate.onTrimMemory(TRIM_UI_HIDDEN))
        gate.detach()
        assertEquals(WarmupDecision.RELEASE, gate.onTrimMemory(TRIM_UI_HIDDEN))
    }
}
