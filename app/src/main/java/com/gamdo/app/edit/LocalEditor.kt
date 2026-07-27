package com.gamdo.app.edit

import android.graphics.Bitmap
import android.util.Log
import com.gamdo.app.data.preset.StylePreset
import com.gamdo.app.detect.ImageMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §4-1 local edit pipeline — the **Android side of the boundary**.
 *
 * ## The boundary rule (read before adding anything here)
 *
 * The module has no `androidTest` source set and no Robolectric, so a single line
 * of `android.graphics` code in this vertical is a line that never runs in CI. The
 * pipeline is therefore split so that everything *numeric* lives in pure Kotlin —
 * `ImageStats.kt` (measure), `EditMath.kt` (colour), `GeometryPlan.kt` (frame),
 * `EditPlan.kt` (assemble) — and this file only moves pixels. If you find yourself
 * writing an `if` about image content here, it belongs one layer down.
 *
 * ## Render-backend decision (Day 4 spike, §4-1)
 *
 * Candidates were `RenderEffect`, AGSL `RuntimeShader`, and OpenCV.
 *
 *  - **AGSL is not available.** `RuntimeShader` is API 33+; the target device
 *    (SM-G970N, Android 12) is API 31. It cannot be the primary path.
 *  - **`RenderEffect` is API 31+** against `minSdk 26`, and reading pixels back out
 *    of one requires a `HardwareRenderer` + `ImageReader` round trip. It buys us
 *    exactly one operation the alternatives lack cheaply: `createBlurEffect`.
 *  - **OpenCV** is a dependency decision (native libs against a 137MB APK) that
 *    would buy nothing the colour pipeline needs — every operation in `EditMath.kt`
 *    is an affine matrix or a 256-entry LUT.
 *
 * Chosen primary: **Canvas + `ColorMatrixColorFilter` + LUT**, which runs on every
 * supported API level, with `RenderEffect` reserved as an opt-in accelerator for
 * the blur term on API 31+. The plan handed to a renderer is backend-neutral (20
 * floats and a 256-entry table), so switching primaries means replacing one
 * [EditRenderer] implementation and nothing else.
 *
 * **This decision is unmeasured.** No device is attached (AGENTS.md §8), so the 2s
 * / 4000px target in §4-1 has not been verified and the ranking may invert once one
 * is. See the DONE-DEVICE checklist in the wave 0 report.
 */

/**
 * Applies an [EditPlan] to a bitmap. The only Android-typed seam in the vertical.
 *
 * Implementations must honour D8-6: never write to, or return a bitmap aliasing,
 * the caller's source.
 */
interface EditRenderer {
    /** Renders [source] under [plan] and returns a new bitmap. */
    fun render(source: Bitmap, plan: EditPlan): Bitmap
}

/** Source measurements needed to build a plan, sampled once per photo. */
data class SourceSample(
    val width: Int,
    val height: Int,
    val stats: LumaStats,
    val means: ChannelMeans,
    val metrics: ImageMetrics,
)

/**
 * A finished render plus how it went. [elapsedMs] feeds the §4-1 fallback and
 * [workingMaxSide] records what the render actually ran at, which is not always what
 * the plan asked for (memory pressure or an OOM retry can lower it).
 *
 * Both are diagnostics. R7-1: never put either in front of the user.
 */
data class RenderedEdit(
    val bitmap: Bitmap,
    val elapsedMs: Long,
    val workingMaxSide: Int,
    val downgraded: Boolean,
)

/** Heap the process can still commit, as the §4-1 memory budget sees it. */
fun availableHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    return (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory())
        .coerceAtLeast(0L)
}

/**
 * Orchestrates measure → plan → render. The concrete renderer is injected so the
 * backend decision above stays reversible.
 *
 * The one piece of state is [lastRenderMs]: §4-1's fallback is defined in terms of
 * "the last pass missed the budget", so somebody has to remember. The *rule* is pure
 * ([planRenderBudget]); this class only supplies the measurement.
 */
class LocalEditor(
    private val renderer: EditRenderer = CanvasEditRenderer(),
    private val availableBytes: () -> Long = ::availableHeapBytes,
) {

    /**
     * Duration of the most recent full-resolution pass, or null before the first.
     * Volatile because [sample]/[render] hop to `Dispatchers.Default` and the plan
     * may be built on another thread.
     */
    @Volatile
    var lastRenderMs: Long? = null
        private set

    /**
     * Measures [bitmap] at analysis resolution. Downscaled on purpose: the
     * statistics only need to be representative, and a 4000px pass here would spend
     * the entire frame budget before any pixels are edited.
     */
    suspend fun sample(
        bitmap: Bitmap,
        tiltDeg: Float = 0f,
        subject: SubjectBox? = null,
        sourceWidth: Int = bitmap.width,
        sourceHeight: Int = bitmap.height,
    ): SourceSample = withContext(Dispatchers.Default) {
        // [sourceWidth]/[sourceHeight] default to the bitmap's own size but can be
        // overridden with the original file's dimensions when statistics are measured
        // on a downscaled preview. The geometry plan is then expressed in
        // full-resolution coordinates and `GeometryPlan.scaledTo` maps it onto
        // whatever the renderer actually works at, so preview and save agree.
        bitmap.withAnalysisPixels(ImageMetricsExtractor.ANALYSIS_MAX_SIDE) { pixels, width, height ->
            val luma = lumaOf(pixels)
            SourceSample(
                width = sourceWidth,
                height = sourceHeight,
                stats = lumaStats(lumaHistogram(luma)),
                means = channelMeans(pixels),
                metrics = computeImageMetrics(pixels, width, height, tiltDeg, subject),
            )
        }
    }

    /**
     * Builds the plan, choosing a working resolution with [planRenderBudget].
     *
     * @param forSave §4-1 "저장 시 원본 해상도 재적용" — a save re-plans at full
     *   resolution even if the preview was downgraded for missing the 2s budget.
     * @param aspect defaults to the **photo's own ratio**, not the preset's. The
     *   shutter already applied the user's 4:5 / 1:1 choice
     *   (`CameraScreen` calls `centerCropToRatio` before saving), so the stored file's
     *   proportions *are* the framing intent. Re-cropping a deliberately square shot
     *   to 4:5 because a colour preset's `composition` block says so would silently
     *   discard 20% of its width — and that block describes the *shooting guide*, not
     *   a re-crop of an already-framed photo. `EditAspect.nearest` keeps D9-1 intact
     *   (it can only return 4:5 or 1:1) and still normalizes an odd-shaped gallery
     *   import in §4-3.
     */
    fun plan(
        sample: SourceSample,
        preset: StylePreset? = null,
        applyStyle: Boolean = preset != null,
        aspect: EditAspect = EditAspect.nearest(sample.width.toFloat() / sample.height),
        subject: SubjectBox? = null,
        forSave: Boolean = false,
        requestedMaxSide: Int = FULL_MAX_SIDE,
    ): EditPlan = EditPlanner.plan(
        sourceWidth = sample.width,
        sourceHeight = sample.height,
        stats = sample.stats,
        means = sample.means,
        metrics = sample.metrics,
        preset = preset,
        aspect = aspect,
        subject = subject,
        applyStyle = applyStyle,
        processingMaxSide = budget(
            sample.width,
            sample.height,
            forSave,
            requestedMaxSide,
        ).workingMaxSide,
    )

    /** The resolution decision on its own, for callers that want to inspect it. */
    fun budget(
        sourceWidth: Int,
        sourceHeight: Int,
        forSave: Boolean = false,
        requestedMaxSide: Int = FULL_MAX_SIDE,
    ): RenderBudget = planRenderBudget(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        availableBytes = availableBytes(),
        forSave = forSave,
        lastRenderMs = lastRenderMs,
        requestedMaxSide = requestedMaxSide,
    )

    /**
     * Renders [plan] onto [source]. D8-6 is enforced structurally: the renderer
     * returns a new bitmap and the caller writes it to a new file — see
     * `CaptureRepository.saveEditedResult`.
     *
     * ## The OutOfMemoryError catch
     *
     * Catching an `Error` is normally wrong, and here it is the documented §4-1
     * mitigation. A 4000x3000 ARGB_8888 frame is 48 MB and the pipeline briefly
     * holds two; on a 2019 mid-range heap that fails as an OOM at a single known
     * allocation site (`Bitmap.createBitmap`) with nothing else corrupted. The failed
     * allocation is already unreachable by the time we are here, so retrying one rung
     * down the resolution ladder is a real recovery rather than a wish. It is bounded
     * by [RESOLUTION_LADDER] and rethrows at the bottom.
     *
     * The alternative — sizing perfectly up front — is not available: no device is
     * attached, so the memory model is an estimate (AGENTS.md §8).
     */
    suspend fun render(source: Bitmap, plan: EditPlan): RenderedEdit =
        withContext(Dispatchers.Default) { renderWithFallback(source, plan) }

    private fun renderWithFallback(source: Bitmap, plan: EditPlan): RenderedEdit {
        var current = plan
        while (true) {
            val startedAt = System.nanoTime()
            try {
                val bitmap = renderer.render(source, current)
                val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MS
                lastRenderMs = elapsedMs
                return RenderedEdit(
                    bitmap = bitmap,
                    elapsedMs = elapsedMs,
                    workingMaxSide = current.processingMaxSide,
                    downgraded = current.processingMaxSide < plan.processingMaxSide,
                )
            } catch (oom: OutOfMemoryError) {
                val next = nextRungBelow(current.processingMaxSide) ?: throw oom
                Log.w(TAG, "render OOM at ${current.processingMaxSide}px, retrying at ${next}px")
                current = current.withProcessingMaxSide(next)
            }
        }
    }

    private companion object {
        const val TAG = "LocalEditor"
        const val NANOS_PER_MS = 1_000_000L
    }
}
