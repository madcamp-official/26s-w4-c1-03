package com.gamdo.app.ui.camera

import com.gamdo.app.camera.TiltReading
import com.gamdo.app.detect.BrightnessSample
import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.PoseLandmarkPoint
import com.gamdo.app.detect.PoseObservation
import com.gamdo.app.guide.LayoutTemplateCatalog
import com.gamdo.app.guide.StyleTarget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * review_report #18 — the analysis thread and the main thread share mutable state
 * with no synchronization.
 *
 * `onFrameAnalyzed` runs on CameraX's single analysis executor. `setStyleTarget`
 * runs on the Compose main dispatcher (`LaunchedEffect(activePreset)`), and so does
 * `rescanLayout` (the 재탐색 button's `onClick`). All three reach the same
 * `AlignmentEngine`, `OverlayStabilizer`, `SceneGuideSessionController` and KPI
 * counters, which are plain fields, plain maps and plain deques — no lock, no
 * `@Volatile`.
 *
 * ## Why this is a real test and not theatre
 *
 * `CameraViewModel` is pure Kotlin — no Android, no CameraX — so the actual
 * production reduction path can be driven from two real threads on the JVM. This
 * is one of the few concurrency properties in this project that is honestly
 * testable rather than reasoned about.
 *
 * ## What it can and cannot prove
 *
 * A passing run does not prove the code is thread-safe; interleavings are not
 * exhaustive. A *failing* run proves it is not. That asymmetry is the whole value:
 * before the fix this class throws `ConcurrentModificationException` within a few
 * hundred iterations, which is a fact about the shipping code, not a hypothesis.
 */
class CameraViewModelConcurrencyTest {

    private fun detection(box: NormalizedBox?): DetectionResult {
        if (box == null) return DetectionResult(faces = emptyList(), pose = null)
        val landmarks = listOf(
            box.left to box.top,
            box.right to box.top,
            box.left to box.bottom,
        ).mapIndexed { type, (x, y) ->
            PoseLandmarkPoint(type = type, x = x, y = y, inFrameLikelihood = 0.9f)
        }
        return DetectionResult(
            faces = emptyList(),
            pose = PoseObservation(landmarks = landmarks, averageInFrameLikelihood = 0.9f),
        )
    }

    private fun CameraViewModel.frame(box: NormalizedBox?) {
        onFrameAnalyzed(
            detection = detection(box),
            tilt = TiltReading(rollDeg = 0.5f, pitchDeg = 10f),
            brightness = BrightnessSample(frameMean = 0.5f),
            shake = 0.01f,
            frameWidth = 720,
            frameHeight = 960,
            mirror = false,
        )
    }

    /**
     * Drives the two threads against each other for a bounded number of rounds and
     * fails with whatever the analysis thread threw.
     */
    private fun stress(rounds: Int, mainAction: (CameraViewModel, Int) -> Unit) {
        val viewModel = CameraViewModel(collectDebugSignals = false)
        val thrown = AtomicReference<Throwable?>(null)
        val started = CountDownLatch(1)
        // AtomicBoolean rather than @Volatile — Kotlin cannot mark a local as
        // volatile, and a plain local captured by the thread gives no visibility
        // guarantee, which would make the stop signal itself a race.
        val running = java.util.concurrent.atomic.AtomicBoolean(true)

        val analysis = Thread {
            started.countDown()
            var i = 0
            try {
                while (running.get()) {
                    // Alternating subject present / absent keeps the smoothing
                    // window, the stabilizer hold and the layout resolver all
                    // mutating rather than sitting on a steady state.
                    viewModel.frame(
                        if (i % 3 == 0) null else NormalizedBox(0.3f, 0.2f, 0.7f, 0.9f),
                    )
                    i++
                    // Checked on the producing thread so a corrupt value is caught
                    // where it is written, not after a later frame has overwritten
                    // it. The shared state here is `ArrayDeque` — a torn read of
                    // its head/tail indices loses or duplicates entries and comes
                    // out as a nonsense average, not as an exception, so asserting
                    // only "nothing threw" would be a guard that cannot fire.
                    viewModel.overlay.value?.guide?.targetFrame?.let { r ->
                        require(r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite()) {
                            "non-finite target frame: $r"
                        }
                        require(r.right >= r.left && r.bottom >= r.top) { "inverted target frame: $r" }
                        require(r.left >= -0.5f && r.right <= 1.5f) { "target frame far out of range: $r" }
                    }
                }
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        analysis.isDaemon = true
        analysis.start()
        started.await(5, TimeUnit.SECONDS)

        try {
            repeat(rounds) { round ->
                if (thrown.get() != null) return@repeat
                mainAction(viewModel, round)
            }
        } finally {
            running.set(false)
            analysis.join(5_000)
        }

        assertNull(
            "the analysis thread threw while the main thread mutated shared guide " +
                "state — see this class's KDoc",
            thrown.get(),
        )
    }

    @Test
    fun `switching style while frames are analysed does not break the analysis thread`() {
        val targets = listOf(
            StyleTarget(subjectAnchorX = 0.5f),
            StyleTarget(subjectAnchorX = 1f / 3f),
            StyleTarget(subjectAnchorX = 2f / 3f, layoutTemplateId = LayoutTemplateCatalog.PORTRAIT_PERSON),
        )
        stress(rounds = 40_000) { viewModel, round ->
            viewModel.setStyleTarget(targets[round % targets.size])
        }
    }

    @Test
    fun `tapping rescan while frames are analysed does not break the analysis thread`() {
        stress(rounds = 40_000) { viewModel, _ -> viewModel.rescanLayout() }
    }

    /**
     * The realistic worst case: a user changing style and tapping 재탐색 while the
     * camera keeps running. Both main-thread paths reset overlapping state.
     */
    @Test
    fun `style switches interleaved with rescans do not break the analysis thread`() {
        stress(rounds = 40_000) { viewModel, round ->
            if (round % 2 == 0) {
                viewModel.setStyleTarget(StyleTarget(subjectAnchorX = 0.3f + (round % 5) * 0.08f))
            } else {
                viewModel.rescanLayout()
            }
        }
    }
}
