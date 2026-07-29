package com.gamdo.app.detect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins **CPU 선행 + GPU 후행 검증**: the object detector is built on the CPU at cold
 * start on every device, and the GPU delegate is only adopted after a real
 * inference on it has succeeded.
 *
 * ## The measurement (SM-G970N, 2026-07-29)
 *
 * ```
 * 10:51:29.590 I/EfficientDet: objectDetector accelerator=GPU requested=GPU degraded=false
 * 10:51:29.606 D/CameraStartup: detectorBuild face=20 pose=25 object=7570 seg=15 total=7630ms
 * 10:51:31.320 W/EfficientDet: GPU inference failed mid-session — rebuilding on CPU
 * 10:51:31.431 W/EfficientDet: accelerator=CPU degraded=true runtimeDowngrade=true
 * ```
 *
 * The GPU delegate **is created successfully** and the *first inference* then fails
 * with `[GL_INVALID_VALUE]: glMapBufferRange` out of `gl_interop.cc`, 3/3
 * reproducible. So cold start spent 7.6s compiling a delegate that was thrown away
 * 1.7s later.
 *
 * Forcing CPU-only in an isolated worktree, cold process, twice: `object=281ms` and
 * `228ms`, `total=376ms` and `313ms`. **The 7.5s is GPU delegate compilation, not
 * model file reading** — a cold CPU build reads the same 4.5MB asset in ~250ms.
 *
 * ## Why `preferGpu = false` is not the fix
 *
 * GPU works in 담당 B's environment (owner, 2026-07-29). A global flag would throw
 * away a working fast path on hardware that has one. The cold start is therefore
 * fixed by *ordering*, not by preference: CPU first so the guide arrives in ~350ms
 * everywhere, GPU attempted afterwards off the critical path, and adopted only if
 * it proves itself on a real inference — because on this device *creation
 * succeeding proves nothing*.
 *
 * ## Why part of this is a source-text test
 *
 * `EfficientDetSceneDetector` needs a `Context`, a 4.5MB TFLite asset and the
 * MediaPipe native runtime, none of which exist on the JVM — the same constraint
 * `ObjectDetectorWiringTest` and `DetectorAcceleratorReportTest` document. The
 * decidable half (when to attempt, what counts as success, what the record becomes)
 * is pure Kotlin in [GpuUpgradePolicy] and is tested directly. The half that is
 * *wiring* — which accelerator the cold start asks for, that the GPU build does not
 * land on the analysis executor, that adoption is gated on an inference — is
 * visible in the source, so that is what these read.
 */
class GpuUpgradePolicyTest {

    private val detectorSource = File("src/main/java/com/gamdo/app/detect/EfficientDetSceneDetector.kt")

    /** Strips comments so the assertions read code, not the prose explaining it. */
    private fun code(): String = detectorSource.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the detector source this test reads is actually there`() {
        assertTrue(
            "expected ${detectorSource.absolutePath} to exist — these assertions would " +
                "otherwise pass vacuously. Check the working directory assumption (app/).",
            detectorSource.exists(),
        )
    }

    /**
     * The cold-start regression in one assertion.
     *
     * The old build walked `DetectorAcceleratorReport.plan(config.preferGpu)`, which
     * puts GPU first, and paid 7.5s of delegate compilation *before the first frame
     * could be analysed at all*. The plan is still the record of what this config
     * wants; it is no longer what the constructor executes.
     */
    @Test
    fun `the cold-start build does not walk the GPU-first plan`() {
        val offenders = code().lines().withIndex()
            .filter { (_, line) -> line.contains("DetectorAcceleratorReport.plan(") }
            .map { (i, line) -> "EfficientDetSceneDetector.kt:${i + 1}: ${line.trim()}" }

        assertTrue(
            "the cold-start build must not iterate the GPU-first accelerator plan. " +
                "On SM-G970N that cost 7570ms of GPU delegate compilation before the " +
                "first analysed frame, for a delegate whose first inference then failed. " +
                "Cold start builds on GpuUpgradePolicy.coldStart; the GPU attempt moves " +
                "off the critical path.\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** The positive half: the cold start asks the policy, and the policy says CPU. */
    @Test
    fun `the cold-start build takes its accelerator from the upgrade policy`() {
        assertTrue(
            "EfficientDetSceneDetector must build its first detector on " +
                "GpuUpgradePolicy.coldStart so the choice is stated in one testable place",
            code().contains("GpuUpgradePolicy.coldStart"),
        )
    }

    /**
     * A 7.5s build on the analysis executor would move the stall, not remove it —
     * `AnalysisThreadResource` runs the detector build and every frame task on the
     * same single FIFO thread, so a task queued there blocks the guide for its whole
     * duration.
     */
    @Test
    fun `the GPU attempt gets its own thread rather than the analysis executor`() {
        val code = code()
        assertTrue(
            "the GPU build must run on a thread of its own — queued on the analysis " +
                "executor a 7.5s build blocks every frame behind it, which is the stall " +
                "this change exists to remove",
            code.contains("newSingleThreadExecutor"),
        )
        assertTrue(
            "name the upgrade thread so a device trace says which thread spent 7.5s, " +
                "instead of pool-N-thread-1",
            code.contains(GPU_UPGRADE_THREAD_NAME),
        )
    }

    /**
     * The trap this device demonstrates: `createFromOptions` returned a usable
     * handle and `accelerator=GPU degraded=false` was logged, and the delegate was
     * still dead. Adoption has to be gated on an inference, not on a constructor.
     */
    @Test
    fun `the GPU detector is adopted only after a real inference on it succeeds`() {
        val code = code()
        assertTrue(
            "creation succeeding proves nothing on SM-G970N — the GPU handle was " +
                "created and the first detect() then threw GL_INVALID_VALUE. The upgrade " +
                "must run a validation inference before it is adopted.",
            code.contains("validateGpu") || code.contains("validationBitmap"),
        )
        assertTrue(
            "the two outcomes must be distinguished: a delegate that could not be " +
                "created and a delegate that was created and cannot infer point at " +
                "different investigations",
            code.contains("GpuUpgradeStage.CREATE_FAILED") ||
                code.contains("GpuUpgradePolicy.resolve"),
        )
    }

    /**
     * The thread-affinity hedge, stated as a wiring property.
     *
     * `TaskRunner.process` is `synchronized` and hands the packet to the MediaPipe
     * graph, which runs the calculators on its own scheduler threads — so the
     * caller's thread very likely never touches GL. "Very likely" is not a basis for
     * a native crash on hardware we cannot reproduce, and confinement buys a second
     * thing outright: the validation inference then runs on the *same* thread the
     * production inferences will, so validating actually validates the path that
     * ships.
     */
    @Test
    fun `the GPU detector is confined to the thread that built it`() {
        assertTrue(
            "the GPU ObjectDetector must be wrapped in ThreadConfined so that it is " +
                "created, validated and invoked on one thread for its whole life. " +
                "Without it the validation inference proves something about the upgrade " +
                "thread and nothing about the analysis thread.",
            code().contains("ThreadConfined"),
        )
    }

    // ── When the upgrade is attempted ──────────────────────────────────────────

    /**
     * The one-line summary of this whole change. `preferGpu` still means "this
     * config wants the GPU"; it no longer means "pay for it before the first frame".
     */
    @Test
    fun `the cold start runs on the CPU whatever the config prefers`() {
        assertEquals(DetectorAccelerator.CPU, GpuUpgradePolicy.coldStart)
    }

    @Test
    fun `an upgrade is pending when GPU was preferred and the cold start landed on CPU`() {
        assertEquals(
            GpuUpgradeStage.PENDING,
            GpuUpgradePolicy.initialStage(preferGpu = true, coldStart = DetectorAccelerator.CPU),
        )
    }

    /** `preferGpu = false` is an instruction to stop asking, at any point in the session. */
    @Test
    fun `no upgrade is pending when the config did not ask for GPU`() {
        assertEquals(
            GpuUpgradeStage.NOT_REQUESTED,
            GpuUpgradePolicy.initialStage(preferGpu = false, coldStart = DetectorAccelerator.CPU),
        )
    }

    /**
     * A cold start that produced nothing means the model asset or the MediaPipe
     * runtime is unusable, and the GPU delegate reads the same asset through the
     * same runtime. Spending a thread and 7.5s to fail again helps nobody.
     */
    @Test
    fun `no upgrade is pending when the cold start produced no detector at all`() {
        assertEquals(
            GpuUpgradeStage.NOT_REQUESTED,
            GpuUpgradePolicy.initialStage(preferGpu = true, coldStart = null),
        )
    }

    /**
     * "Never retry in a loop" as a property rather than as a comment: `PENDING` is
     * the only stage that permits an attempt, and no attempt can resolve back to it.
     */
    @Test
    fun `the attempt is made once and never retried`() {
        assertTrue(GpuUpgradePolicy.shouldAttempt(GpuUpgradeStage.PENDING))

        val resolved = GpuUpgradeStage.entries.filter { it != GpuUpgradeStage.PENDING }
        resolved.forEach { stage ->
            assertFalse(
                "a $stage upgrade must not be attempted again — a GL driver that " +
                    "refuses once refuses on a loop, and the loop costs 7.5s a turn",
                GpuUpgradePolicy.shouldAttempt(stage),
            )
        }
    }

    // ── What counts as success ─────────────────────────────────────────────────

    /**
     * The trap, as an assertion. On SM-G970N `createFromOptions` returned a handle
     * and logged `accelerator=GPU degraded=false`; the delegate was dead.
     */
    @Test
    fun `creation alone is never adoption`() {
        val stage = GpuUpgradePolicy.resolve(created = true, validated = false)

        assertEquals(GpuUpgradeStage.VALIDATION_FAILED, stage)
        assertFalse("a delegate that cannot infer must not serve frames", stage.adopted)
    }

    @Test
    fun `a delegate that cannot be created is a different stage from one that cannot infer`() {
        assertEquals(
            GpuUpgradeStage.CREATE_FAILED,
            GpuUpgradePolicy.resolve(created = false, validated = false),
        )
        assertNotEquals(
            GpuUpgradePolicy.resolve(created = false, validated = false),
            GpuUpgradePolicy.resolve(created = true, validated = false),
        )
    }

    @Test
    fun `only a validated GPU delegate is adopted`() {
        val stage = GpuUpgradePolicy.resolve(created = true, validated = true)

        assertEquals(GpuUpgradeStage.ADOPTED, stage)
        assertTrue(stage.adopted)
        assertEquals(
            "exactly one stage may serve frames on the GPU",
            listOf(GpuUpgradeStage.ADOPTED),
            GpuUpgradeStage.entries.filter { it.adopted },
        )
    }

    // ── What the record becomes ────────────────────────────────────────────────

    @Test
    fun `adopting the upgrade reports GPU and is no longer degraded`() {
        val adopted = coldStartReport().adoptingGpu()

        assertEquals(DetectorAccelerator.GPU, adopted.accelerator)
        assertEquals(GpuUpgradeStage.ADOPTED, adopted.upgrade)
        assertFalse("the config asked for GPU and now has it", adopted.degraded)
    }

    @Test
    fun `a refused upgrade keeps CPU, stays degraded and names the step it failed at`() {
        val refused = coldStartReport().refusingGpu(
            GpuUpgradeStage.VALIDATION_FAILED,
            "com.google.mediapipe.framework.MediaPipeException: [GL_INVALID_VALUE]: glMapBufferRange",
        )

        assertEquals(DetectorAccelerator.CPU, refused.accelerator)
        assertEquals(GpuUpgradeStage.VALIDATION_FAILED, refused.upgrade)
        assertTrue("GPU was asked for and CPU is running", refused.degraded)
        assertFalse(
            "a refusal before adoption is not a mid-session downgrade — nothing was " +
                "serving frames on the GPU to downgrade from",
            refused.runtimeDowngrade,
        )
        assertTrue(refused.format(), refused.format().contains("glMapBufferRange"))
    }

    /** A refusal that records an adoption would make the record lie about what is running. */
    @Test
    fun `refusing cannot record an adoption`() {
        assertThrows(IllegalArgumentException::class.java) {
            coldStartReport().refusingGpu(GpuUpgradeStage.ADOPTED, null)
        }
    }

    /**
     * The one path where `runtimeDowngrade` still applies: the GPU passed validation,
     * served frames, and then faulted. CPU takes over for the rest of the session.
     */
    @Test
    fun `revoking an adopted GPU records a runtime downgrade`() {
        val revoked = coldStartReport().adoptingGpu().revokingGpu("java.lang.IllegalStateException: gl")

        assertEquals(DetectorAccelerator.CPU, revoked.accelerator)
        assertEquals(GpuUpgradeStage.REVOKED, revoked.upgrade)
        assertTrue(revoked.runtimeDowngrade)
        assertTrue(revoked.degraded)
    }

    /**
     * The readability requirement, stated as an assertion: a reader with only a
     * logcat capture must be able to tell the three outcomes apart.
     */
    @Test
    fun `the record tells upgraded, refused and never-tried apart in one line`() {
        val neverTried = DetectorAcceleratorReport(
            requestedGpu = false,
            accelerator = DetectorAccelerator.CPU,
        )
        val upgraded = coldStartReport().adoptingGpu()
        val refused = coldStartReport().refusingGpu(GpuUpgradeStage.VALIDATION_FAILED, null)
        val pending = coldStartReport()

        assertTrue(neverTried.format(), neverTried.format().contains("gpuUpgrade=NOT_REQUESTED"))
        assertTrue(upgraded.format(), upgraded.format().contains("gpuUpgrade=ADOPTED"))
        assertTrue(refused.format(), refused.format().contains("gpuUpgrade=VALIDATION_FAILED"))
        assertTrue(
            "a still-running attempt must not read as a refusal — on a slow device the " +
                "GPU build takes seconds and a reader will screenshot the log during it",
            pending.format().contains("gpuUpgrade=PENDING"),
        )
        assertEquals(
            "every stage must produce a distinguishable line",
            4,
            setOf(neverTried, upgraded, refused, pending).map { it.format() }.toSet().size,
        )
    }

    /** The state the detector is in for the first ~7.5s of a preferGpu session. */
    private fun coldStartReport() = DetectorAcceleratorReport(
        requestedGpu = true,
        accelerator = DetectorAccelerator.CPU,
        upgrade = GpuUpgradeStage.PENDING,
    )

    /**
     * The comment that motivated the old design is now disproven, and a stale
     * rationale makes the new code look unmotivated.
     *
     * Old claim: *"기기에서는 GPU가 잡히지 않는다 … 그 문구가 없다는 사실 자체가 GPU
     * 초기화가 성공하지 못했다는 증거다."* The 2026-07-29 capture shows
     * `accelerator=GPU requested=GPU degraded=false`: initialisation succeeds.
     */
    @Test
    fun `the disproven claim that GPU never initialises is gone from the detector`() {
        val prose = detectorSource.readText()
        assertTrue(
            "EfficientDetSceneDetector still claims GPU initialisation never succeeds. " +
                "The 2026-07-29 device capture logged accelerator=GPU degraded=false at " +
                "init and failed on the first inference instead. That distinction is the " +
                "entire basis of the CPU-first design.",
            !prose.contains("GPU 초기화가 성공하지 못했다는 증거다"),
        )
        assertTrue(
            "the corrected note must say what actually fails — the first inference — " +
                "so the next reader does not re-derive it from a device they may not have",
            prose.contains("glMapBufferRange"),
        )
    }
}

/**
 * Restated rather than imported: the constant lives in the Android shell, and this
 * test asserts on the shell's *source text*, not on a symbol it can load.
 */
private const val GPU_UPGRADE_THREAD_NAME = "gamdo-gpu-upgrade"
