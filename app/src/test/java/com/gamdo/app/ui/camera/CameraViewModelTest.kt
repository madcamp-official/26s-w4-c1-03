package com.gamdo.app.ui.camera

import com.gamdo.app.camera.AnalysisStats
import com.gamdo.app.camera.TiltReading
import com.gamdo.app.detect.BrightnessSample
import com.gamdo.app.detect.DetectionResult
import com.gamdo.app.detect.FaceObservation
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.PoseLandmarkPoint
import com.gamdo.app.detect.PoseObservation
import com.gamdo.app.guide.FeaturesConfigJson
import com.gamdo.app.guide.GuideConfigBundle
import com.gamdo.app.guide.StyleTarget
import com.gamdo.app.guide.LayoutTemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analysis-thread → UI-thread seam (§3-1) and the §2-4 feature budget.
 *
 * These run on the JVM because `CameraViewModel` holds no Android dependency;
 * that property is load-bearing for the §0.4 harness and is asserted here by the
 * mere fact that the class instantiates.
 */
class CameraViewModelTest {

    private val bundle = OverlayStabilityHarness.loadBundle()

    // ------------------------------------------------------------ §3-1 seam

    @Test
    fun `an analyzed frame is published as overlay state`() {
        val viewModel = CameraViewModel(config = bundle, collectDebugSignals = false)
        viewModel.setStyleTarget(StyleTarget())

        assertNull("첫 프레임 전에는 상태 없음", viewModel.overlay.value)
        feed(viewModel, personBox(0.32f, 0.25f, 0.68f, 0.85f), confidence = 0.9f)

        val overlay = viewModel.overlay.value
        assertNotNull(overlay)
        assertEquals(720, overlay!!.frameWidth)
        assertNotNull("가이드 투영이 실려야 한다", overlay.guide)
        assertTrue(overlay.guide!!.visible)
    }

    @Test
    fun `stats pass straight through`() {
        val viewModel = CameraViewModel(config = bundle)
        viewModel.onStats(AnalysisStats(processMs = 12.5, fps = 12, dropRatePercent = 4))

        assertEquals(12, viewModel.stats.value?.fps)
    }

    @Test
    fun `detaching the analyzer clears per-frame state`() {
        val viewModel = CameraViewModel(config = bundle)
        feed(viewModel, personBox(0.32f, 0.085f, 0.68f, 0.535f), confidence = 0.9f)

        viewModel.onAnalyzerDetached()

        assertNull(viewModel.overlay.value)
        assertNull(viewModel.guideDebug.value)
        assertEquals("", viewModel.detectionLabel.value)
    }

    @Test
    fun `shutter snapshot keeps fixed layout state`() {
        val viewModel = CameraViewModel(config = bundle, collectDebugSignals = false)
        viewModel.setStyleTarget(StyleTarget(layoutTemplateId = LayoutTemplateCatalog.PORTRAIT_PERSON))
        feed(viewModel, personBox(0.32f, 0.085f, 0.68f, 0.535f), confidence = 0.9f)

        assertEquals(
            LayoutTemplateCatalog.PORTRAIT_PERSON,
            viewModel.lastFrame.value?.fixedLayout?.template?.id,
        )
    }

    @Test
    fun `fixed layout does not gate the shutter on slot occupancy`() {
        val viewModel = CameraViewModel(config = bundle, collectDebugSignals = false)
        viewModel.setStyleTarget(StyleTarget(layoutTemplateId = LayoutTemplateCatalog.PORTRAIT_PERSON))

        feed(viewModel, personBox(0.32f, 0.25f, 0.68f, 0.85f), confidence = 0.9f)
        assertTrue(viewModel.lastFrame.value!!.aligned)
        repeat(2) {
            feed(viewModel, personBox(0.32f, 0.25f, 0.68f, 0.85f), confidence = 0.9f)
        }

        assertTrue(viewModel.lastFrame.value!!.aligned)
    }

    @Test
    fun `switching style target restarts the guide from cold`() {
        val viewModel = CameraViewModel(config = bundle, collectDebugSignals = false)
        viewModel.setStyleTarget(StyleTarget())
        repeat(10) { feed(viewModel, personBox(0.32f, 0.085f, 0.68f, 0.535f), confidence = 0.9f) }
        assertTrue(viewModel.overlay.value?.guide?.visible == true)

        // A preset switch invalidates both the engine's smoothing window and the
        // display damping; if either survived, the new bracket would crawl out of
        // the old one instead of appearing where the new preset asks.
        viewModel.setStyleTarget(StyleTarget(subjectAnchorX = 1f / 3f))
        feed(viewModel, null, confidence = 0.05f)

        assertFalse("리셋 직후 저신뢰 프레임은 숨김", viewModel.overlay.value?.guide?.visible == true)
    }

    // ---------------------------------------------------------- D2 / release

    @Test
    fun `release builds collect no debug signals and no raw detection boxes`() {
        val viewModel = CameraViewModel(config = bundle, collectDebugSignals = false)
        feed(viewModel, personBox(0.3f, 0.1f, 0.7f, 0.6f), confidence = 0.9f)

        // D2-5: matchScore must not exist anywhere the product UI can reach.
        assertNull(viewModel.guideDebug.value)
        // §3-2: the product overlay is bracket + silhouette + horizon only.
        assertTrue(viewModel.overlay.value?.faces.isNullOrEmpty())
        assertNull(viewModel.overlay.value?.personCenter)
    }

    @Test
    fun `debug builds expose both scores and they are different quantities`() {
        val viewModel = CameraViewModel(config = bundle, collectDebugSignals = true)
        viewModel.setStyleTarget(StyleTarget())
        feed(viewModel, personBox(0.05f, 0.6f, 0.25f, 0.95f), confidence = 0.9f)

        val debug = viewModel.guideDebug.value
        assertNotNull(debug)
        // TEAM.md §8: AlignmentEngine's `matchScore` is an IoU used for alignment,
        // NOT the §4.2 weighted score. Only the latter is loggable (§3-3). A badly
        // placed subject scores ~0 IoU while still scoring above 0 on the weighted
        // metric, which is exactly why they must never be conflated.
        assertEquals(0f, debug!!.iou, 0.0001f)
        assertTrue("가중 점수는 IoU와 다른 값이어야 한다", debug.matchScore > 0f)
        assertTrue(viewModel.overlay.value?.faces?.isNotEmpty() == true)
    }

    // ------------------------------------------------------------ §2-4 budget

    @Test
    fun `feature extraction is measured against the 30ms budget`() {
        val viewModel = CameraViewModel(config = bundle, collectDebugSignals = false)
        val warmup = 50
        val measured = 300
        repeat(warmup + measured) {
            feed(viewModel, personBox(0.32f, 0.085f, 0.68f, 0.535f), confidence = 0.9f)
        }

        val stats = viewModel.featureBudget.value
        assertNotNull(stats)
        println(
            "§2-4 FrameFeatureCalculator (JVM, n=%d): mean %.4fms · max %.4fms · budget %.0fms · over %d".format(
                stats!!.frames, stats.meanMs, stats.maxMs, stats.budgetMs, stats.overBudgetFrames,
            ),
        )
        assertEquals((warmup + measured).toLong(), stats.frames)
        // Deliberately asserted on the mean, not the max: a single JIT/GC pause on
        // a loaded build box says nothing about the device. The max is reported.
        assertTrue(
            "평균 %.4fms 가 예산 %.0fms 초과".format(stats.meanMs, stats.budgetMs),
            stats.meanMs < stats.budgetMs,
        )
    }

    @Test
    fun `budget log fires periodically and on the first breach`() {
        val lines = mutableListOf<String>()
        val viewModel = CameraViewModel(
            // A zero budget forces a breach on frame 1 so the first-breach path is
            // covered without depending on how fast the host machine is.
            config = bundle.copy(
                features = FeaturesConfigJson(analysisBudgetMs = 0.0, budgetLogEveryFrames = 10),
            ),
            collectDebugSignals = false,
            logSink = { lines += it },
        )

        repeat(20) { feed(viewModel, personBox(0.3f, 0.1f, 0.7f, 0.6f), confidence = 0.9f) }

        assertTrue("최초 초과 + 10프레임 주기 로그", lines.size >= 3)
        assertTrue(lines.first().contains("FrameFeatures"))
        assertTrue(lines.last().contains("budget=0ms"))
    }

    @Test
    fun `budget logging can be switched off entirely`() {
        val lines = mutableListOf<String>()
        val viewModel = CameraViewModel(
            config = bundle.copy(features = FeaturesConfigJson(budgetLogEveryFrames = 0)),
            collectDebugSignals = false,
            logSink = { lines += it },
        )

        repeat(50) { feed(viewModel, personBox(0.3f, 0.1f, 0.7f, 0.6f), confidence = 0.9f) }

        assertTrue("주기 0이면 무음", lines.isEmpty())
        assertNotNull("그래도 계측은 계속된다", viewModel.featureBudget.value)
    }

    // ------------------------------------------------- 재탐색 (layout re-scan)

    /**
     * The auto layout resolver latches a template after a few confirming frames
     * and, by design, never un-latches inside a session. Before this existed the
     * only escape was `setStyleTarget`, i.e. the user had to change their style to
     * get the guide to look at the scene again — and on device that read as "the
     * app decided once and stopped paying attention".
     */
    @Test
    fun `a latched layout survives further frames`() {
        val viewModel = CameraViewModel(collectDebugSignals = false)
        repeat(8) { feed(viewModel, personBox(0.3f, 0.2f, 0.7f, 0.9f), confidence = 0.9f) }
        val latched = viewModel.lastFrame.value?.fixedLayout
        assertNotNull("8 confirming frames should latch a template", latched)

        repeat(8) { feed(viewModel, null, confidence = 0f) }
        assertNotNull(
            "the latch is deliberately sticky — losing the subject must not drop it",
            viewModel.lastFrame.value?.fixedLayout,
        )
    }

    @Test
    fun `rescan drops the latched layout so the next frames search again`() {
        val viewModel = CameraViewModel(collectDebugSignals = false)
        repeat(8) { feed(viewModel, personBox(0.3f, 0.2f, 0.7f, 0.9f), confidence = 0.9f) }
        assertNotNull(viewModel.lastFrame.value?.fixedLayout)

        viewModel.rescanLayout()

        // The next analyzed frame is the first one that can observe the cleared
        // state, so feed one empty frame to read it back.
        feed(viewModel, null, confidence = 0f)
        assertNull(
            "rescan must return the resolver to searching",
            viewModel.lastFrame.value?.fixedLayout,
        )
    }

    @Test
    fun `rescan before anything latched is harmless`() {
        val viewModel = CameraViewModel(collectDebugSignals = false)
        viewModel.rescanLayout()
        feed(viewModel, null, confidence = 0f)
        assertNull(viewModel.lastFrame.value?.fixedLayout)
    }

    /**
     * Rescan clears the layout, not the style. A user tapping 재탐색 is saying
     * "look at the scene again", not "forget which preset I picked" — dropping the
     * style target here would silently reset their composition guide too.
     */
    @Test
    fun `rescan leaves the style target alone`() {
        val viewModel = CameraViewModel(collectDebugSignals = false)
        val target = StyleTarget(subjectAnchorX = 1f / 3f)
        viewModel.setStyleTarget(target)
        repeat(8) { feed(viewModel, personBox(0.3f, 0.2f, 0.7f, 0.9f), confidence = 0.9f) }

        viewModel.rescanLayout()

        assertEquals(target, viewModel.styleTarget.value)
    }

    // ------------------------------------------------------------------ util

    private fun feed(viewModel: CameraViewModel, box: NormalizedBox?, confidence: Float) {
        viewModel.onFrameAnalyzed(
            detection = detection(box, confidence),
            tilt = TiltReading(rollDeg = 0.5f, pitchDeg = 10f),
            brightness = BrightnessSample(frameMean = 0.5f),
            shake = 0.01f,
            frameWidth = 720,
            frameHeight = 960,
            mirror = false,
        )
    }

    private fun personBox(l: Float, t: Float, r: Float, b: Float) = NormalizedBox(l, t, r, b)

    private fun detection(box: NormalizedBox?, confidence: Float): DetectionResult {
        if (box == null) return DetectionResult(faces = emptyList(), pose = null)
        val landmarks = listOf(
            box.left to box.top,
            box.right to box.top,
            box.left to box.bottom,
            box.right to box.bottom,
        ).mapIndexed { type, (x, y) ->
            PoseLandmarkPoint(type = type, x = x, y = y, inFrameLikelihood = confidence)
        }
        return DetectionResult(
            faces = listOf(
                FaceObservation(
                    box = NormalizedBox(box.left, box.top, box.right, box.top + box.height * 0.2f),
                    leftEyeOpenProbability = 0.9f,
                    rightEyeOpenProbability = 0.9f,
                    headEulerAngleZ = 0f,
                ),
            ),
            pose = PoseObservation(landmarks = landmarks, averageInFrameLikelihood = confidence),
        )
    }
}
