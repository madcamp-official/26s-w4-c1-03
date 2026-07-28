package com.gamdo.app.detect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that a GPU→CPU downgrade of the object detector is **stated**, not inferred.
 *
 * `preferGpu` is true by default and true in `guide_config.json`, and on device the
 * app never gets GPU: the capture around detector init shows only
 * `"Created TensorFlow Lite XNNPACK delegate for CPU."` — XNNPACK being the CPU
 * path — and never the GPU counterpart, `"Created TensorFlow Lite delegate for
 * GPU."`, which is a string present in the shipped
 * `libmediapipe_tasks_vision_jni.so` and would therefore have printed had GPU
 * initialised.
 *
 * The delegate loop used to swallow that: the GPU throwable went into a local
 * `lastFailure` that the subsequent CPU success discarded, and the winning
 * delegate lived in a private field nothing read. The heaviest per-frame cost in
 * the app could silently be running an order of magnitude slower than the config
 * asked for, and the only trace was a third-party log line about node counts.
 *
 * `EfficientDetSceneDetector` itself needs a `Context`, a 4.5MB TFLite asset and
 * the MediaPipe native runtime, so it cannot be constructed here (same constraint
 * `ObjectDetectorWiringTest` documents). The decidable parts — the try order, the
 * degraded verdict, the wording of the record, and whether the record is
 * reachable through the wrapper stack the host actually builds — are pure Kotlin
 * and are what this covers. The remaining assertion, that the record reaches a
 * log at init, is read from the source for the same reason that test is.
 */
class DetectorAcceleratorReportTest {

    @Test
    fun `preferGpu tries GPU first and keeps CPU as the fallback`() {
        assertEquals(
            listOf(DetectorAccelerator.GPU, DetectorAccelerator.CPU),
            DetectorAcceleratorReport.plan(preferGpu = true),
        )
    }

    /**
     * Not CPU-then-GPU. Turning the preference off means stop asking; a reordering
     * would still pay the GPU init attempt on every camera open for a delegate the
     * config just said it did not want.
     */
    @Test
    fun `preferGpu off asks for CPU only`() {
        assertEquals(
            listOf(DetectorAccelerator.CPU),
            DetectorAcceleratorReport.plan(preferGpu = false),
        )
    }

    @Test
    fun `getting the GPU that was asked for is not degraded`() {
        val report = DetectorAcceleratorReport(
            requestedGpu = true,
            accelerator = DetectorAccelerator.GPU,
        )

        assertFalse(report.degraded)
        assertTrue(report.format().contains("accelerator=GPU"))
        assertTrue(report.format().contains("degraded=false"))
    }

    @Test
    fun `asking for GPU and landing on CPU is degraded and names the reason`() {
        val report = DetectorAcceleratorReport(
            requestedGpu = true,
            accelerator = DetectorAccelerator.CPU,
            gpuFailure = "java.lang.IllegalStateException: gpu delegate unavailable",
        )

        assertTrue(report.degraded)
        val line = report.format()
        assertTrue(line, line.contains("accelerator=CPU"))
        assertTrue(line, line.contains("requested=GPU"))
        assertTrue(line, line.contains("degraded=true"))
        assertTrue(line, line.contains("gpuError=java.lang.IllegalStateException: gpu delegate unavailable"))
    }

    /** Asking for CPU and getting CPU is the config working, not a downgrade. */
    @Test
    fun `asking for CPU and landing on CPU is not degraded`() {
        val report = DetectorAcceleratorReport(
            requestedGpu = false,
            accelerator = DetectorAccelerator.CPU,
        )

        assertFalse(report.degraded)
        assertTrue(report.format().contains("requested=CPU"))
    }

    /**
     * A detector that never initialised is the worst outcome, not a neutral one —
     * object detection is off for the session and every frame returns an empty
     * batch that reads like "nothing in view".
     */
    @Test
    fun `a detector that never initialised reports none and counts as degraded`() {
        val report = DetectorAcceleratorReport(requestedGpu = true, accelerator = null)

        assertTrue(report.degraded)
        assertTrue(report.format(), report.format().contains("accelerator=none"))
    }

    /**
     * Init-time refusal and a mid-session GL fault point at different
     * investigations, so the record distinguishes them.
     */
    @Test
    fun `a mid-session rebuild is marked in the record`() {
        val atInit = DetectorAcceleratorReport(
            requestedGpu = true,
            accelerator = DetectorAccelerator.CPU,
        )
        val afterFault = atInit.copy(runtimeDowngrade = true)

        assertFalse(atInit.format(), atInit.format().contains("runtimeDowngrade"))
        assertTrue(afterFault.format(), afterFault.format().contains("runtimeDowngrade=true"))
    }

    @Test
    fun `the throttle forwards the accelerator record of the detector it wraps`() {
        val reported = DetectorAcceleratorReport(
            requestedGpu = true,
            accelerator = DetectorAccelerator.CPU,
        )
        val throttled = ThrottledObjectSceneDetector(FakeReportingDetector(reported))

        assertEquals(reported, throttled.acceleratorReport)
    }

    /** A wrapped detector with nothing to report must not fake one. */
    @Test
    fun `the throttle reports nothing for a detector that cannot say`() {
        val throttled = ThrottledObjectSceneDetector(FakeObjectDetector())

        assertNull(throttled.acceleratorReport)
    }

    /**
     * The host builds `SceneDetector(objectDetector = ThrottledObjectSceneDetector(
     * EfficientDetSceneDetector(...)))`, so a HUD holding the `SceneDetector` must
     * be able to read through two wrappers. This is the path that actually ships.
     */
    @Test
    fun `SceneDetector surfaces the object stage accelerator through the throttle`() {
        val reported = DetectorAcceleratorReport(
            requestedGpu = true,
            accelerator = DetectorAccelerator.CPU,
            gpuFailure = "java.lang.IllegalStateException: no gpu",
        )
        val scene = SceneDetector(
            faceDetector = NoFaces,
            poseDetector = NoPose,
            objectDetector = ThrottledObjectSceneDetector(FakeReportingDetector(reported)),
        )

        assertEquals(reported, scene.objectAcceleratorReport)
    }

    @Test
    fun `SceneDetector reports nothing when no wired detector can say`() {
        val scene = SceneDetector(faceDetector = NoFaces, poseDetector = NoPose)

        assertNull(scene.objectAcceleratorReport)
    }

    /**
     * Source-text, for the reason in this class's KDoc: the detector cannot be
     * constructed on the JVM, but "the outcome is written down" is visible in the
     * source. This is the assertion that fails on the pre-fix tree.
     */
    @Test
    fun `the detector writes the accelerator record to a log at init`() {
        val source = File("src/main/java/com/gamdo/app/detect/EfficientDetSceneDetector.kt")
        assertTrue("expected ${source.absolutePath} to exist", source.exists())
        val code = source.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

        assertTrue(
            "EfficientDetSceneDetector must record which accelerator init resolved to. " +
                "Without it a GPU→CPU downgrade on the heaviest per-frame stage is only " +
                "visible as a TFLite node-count line from a third-party library.",
            code.contains("acceleratorState"),
        )
        assertTrue(
            "the accelerator record must reach a log line, not just a field",
            Regex("""Log\.[iw]\([^)]*acceleratorState\.format\(\)""").containsMatchIn(code),
        )
        assertTrue(
            "a refused GPU delegate must be logged where it happens — the old loop " +
                "kept it in a local that the following CPU success discarded",
            Regex("""Log\.w\(\s*TAG,\s*"GPU delegate refused""").containsMatchIn(code),
        )
    }
}

private class FakeReportingDetector(
    private val report: DetectorAcceleratorReport,
) : ObjectSceneDetector, AcceleratorReporting {
    override val acceleratorReport: DetectorAcceleratorReport = report
    override fun detect(frame: AnalysisFrame): List<ObjectObservation> = emptyList()
    override fun close() = Unit
}

private class FakeObjectDetector : ObjectSceneDetector {
    override fun detect(frame: AnalysisFrame): List<ObjectObservation> = emptyList()
    override fun close() = Unit
}

private object NoFaces : FaceDetector {
    override fun detect(frame: AnalysisFrame): List<FaceObservation> = emptyList()
    override fun close() = Unit
}

private object NoPose : PoseDetector {
    override fun detect(frame: AnalysisFrame): PoseObservation? = null
    override fun close() = Unit
}
