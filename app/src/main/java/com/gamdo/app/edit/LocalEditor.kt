package com.gamdo.app.edit

import android.graphics.Bitmap
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
 * Orchestrates measure → plan → render. Holds no state; the concrete renderer is
 * injected so the backend decision above stays reversible.
 */
class LocalEditor(
    private val renderer: EditRenderer,
) {

    /**
     * Measures [bitmap] at analysis resolution. Downscaled on purpose: the
     * statistics only need to be representative, and a 4000px pass here would spend
     * the entire frame budget before any pixels are edited.
     */
    suspend fun sample(
        bitmap: Bitmap,
        tiltDeg: Float = 0f,
        subject: SubjectBox? = null,
    ): SourceSample = withContext(Dispatchers.Default) {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
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

    /** Pure delegate — kept here so callers have one entry point. */
    fun plan(
        sample: SourceSample,
        preset: StylePreset? = null,
        applyStyle: Boolean = preset != null,
        aspect: EditAspect = preset?.let { EditAspect.fromPresetKey(it.composition.targetAspectRatio) }
            ?: EditAspect.RATIO_4_5,
        subject: SubjectBox? = null,
        processingMaxSide: Int = FULL_MAX_SIDE,
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
        processingMaxSide = processingMaxSide,
    )

    /**
     * Renders [plan] onto [source]. D8-6 is enforced structurally: the renderer
     * returns a new bitmap and the caller writes it to a new file — see
     * `CaptureRepository.saveEditedResult`.
     */
    suspend fun render(source: Bitmap, plan: EditPlan): Bitmap =
        withContext(Dispatchers.Default) { renderer.render(source, plan) }
}
