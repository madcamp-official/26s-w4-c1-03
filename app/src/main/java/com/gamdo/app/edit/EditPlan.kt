package com.gamdo.app.edit

import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.StylePreset
import com.gamdo.app.detect.ImageMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * §4-1 pipeline plan — **platform-free**.
 *
 * An [EditPlan] is the complete description of what the editor will do to a photo,
 * computed entirely from measurements. It is:
 *
 *  - the renderer's only input (so backends are interchangeable),
 *  - the record written to `capture_edit_stack` for D8-6 non-destructive editing
 *    (the original file is never touched; this JSON is how the edit is "stored"),
 *  - resolution-independent, so the same plan can run at 2000px for the preview
 *    and again at full resolution on save.
 *
 * The step vocabulary matches the DB schema v2.0 §3.9 CHECK constraint exactly:
 * `geometry | optical | style | semantic | generative_ref`.
 */

/** `capture_edit_stack.step_type` values this module writes. */
enum class EditStepType(val value: String) {
    GEOMETRY("geometry"),
    OPTICAL("optical"),
    STYLE("style"),
}

/**
 * Payload version. AGENTS.md §7-2: the schema is frozen, so parameter-shape changes
 * are expressed by bumping this inside `params_json`, never by adding columns.
 */
const val EDIT_PARAMS_VERSION = 1

@Serializable
data class GeometryParams(
    val v: Int = EDIT_PARAMS_VERSION,
    val rotationDeg: Float,
    val aspect: String,
    val cropX: Int,
    val cropY: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val marginExpansionCandidate: Boolean,
)

@Serializable
data class OpticalParams(
    val v: Int = EDIT_PARAMS_VERSION,
    val exposureEv: Float,
    val wbGainR: Float,
    val wbGainB: Float,
    val blackPoint: Float,
    val whitePoint: Float,
    val shadowLift: Float,
    val highlightRolloff: Float,
)

@Serializable
data class StyleParams(
    val v: Int = EDIT_PARAMS_VERSION,
    val presetId: String?,
    val color: ColorParams?,
    val grain: Float,
    val vignette: Float,
    val blurStrength: Float,
)

/** One row destined for `capture_edit_stack`. */
data class EditStep(
    val type: EditStepType,
    val order: Int,
    val paramsJson: String,
)

/**
 * The assembled plan.
 *
 * [opticalMatrix]/[styleMatrix] are 4x5 colour matrices in `ColorMatrix` layout and
 * [toneLut] is a 256-entry curve; see `EditMath.kt`. [processingMaxSide] is the
 * longest side the renderer should work at.
 */
class EditPlan(
    val geometry: GeometryPlan,
    val optical: OpticalParams,
    val style: StyleParams,
    val opticalMatrix: FloatArray,
    val styleMatrix: FloatArray,
    val toneLut: IntArray,
    val processingMaxSide: Int = FULL_MAX_SIDE,
) {
    /** Single matrix for backends that can only afford one colour pass. */
    fun combinedMatrix(): FloatArray = concatColorMatrix(styleMatrix, opticalMatrix)

    /**
     * The same plan at a different working resolution. Only [processingMaxSide]
     * moves — the colour maths is resolution-independent and the geometry is scaled
     * by the renderer once it knows the actual working bitmap size, so re-planning
     * would be both wasteful and a chance for preview and save to disagree.
     *
     * Used by the §4-1 fallback (2000px preview, full resolution on save) and by the
     * `OutOfMemoryError` retry in `LocalEditor`.
     */
    fun withProcessingMaxSide(maxSide: Int): EditPlan =
        if (maxSide == processingMaxSide) {
            this
        } else {
            EditPlan(geometry, optical, style, opticalMatrix, styleMatrix, toneLut, maxSide)
        }

    /** True when the colour stages would leave the pixels untouched. */
    fun isColorNoOp(): Boolean =
        isIdentityColorMatrix(combinedMatrix()) && isIdentityLut(toneLut)

    /** True when the geometry stage would leave the frame untouched. */
    fun isGeometryNoOp(): Boolean =
        geometry.rotationDeg == 0f &&
            geometry.crop.x == 0 &&
            geometry.crop.y == 0 &&
            geometry.crop.width == geometry.sourceWidth &&
            geometry.crop.height == geometry.sourceHeight

    /** Rows to insert into `capture_edit_stack` (D8-6). */
    fun toEditSteps(json: Json = defaultJson): List<EditStep> = listOf(
        EditStep(
            type = EditStepType.GEOMETRY,
            order = 0,
            paramsJson = json.encodeToString(
                GeometryParams.serializer(),
                GeometryParams(
                    rotationDeg = geometry.rotationDeg,
                    aspect = geometry.aspect.presetKey,
                    cropX = geometry.crop.x,
                    cropY = geometry.crop.y,
                    cropWidth = geometry.crop.width,
                    cropHeight = geometry.crop.height,
                    sourceWidth = geometry.sourceWidth,
                    sourceHeight = geometry.sourceHeight,
                    marginExpansionCandidate = geometry.marginExpansionCandidate,
                ),
            ),
        ),
        EditStep(
            type = EditStepType.OPTICAL,
            order = 1,
            paramsJson = json.encodeToString(OpticalParams.serializer(), optical),
        ),
        EditStep(
            type = EditStepType.STYLE,
            order = 2,
            paramsJson = json.encodeToString(StyleParams.serializer(), style),
        ),
    )

    companion object {
        val defaultJson: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

/**
 * Builds an [EditPlan] from measurements. Pure: no Bitmap, no clock, no IO — which
 * is exactly why the numeric behaviour of the whole pipeline is JVM-testable even
 * though the renderer is not.
 */
object EditPlanner {

    /**
     * @param stats luma summary of the source (from [lumaStats])
     * @param means per-channel means of the source (from [channelMeans])
     * @param metrics optional diagnosis input; only [ImageMetrics.tiltDeg] is used here
     * @param preset the style to apply, or null for "기본 보정" (optical stage only)
     */
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        stats: LumaStats,
        means: ChannelMeans,
        metrics: ImageMetrics? = null,
        preset: StylePreset? = null,
        aspect: EditAspect = preset?.let { EditAspect.fromPresetKey(it.composition.targetAspectRatio) }
            ?: EditAspect.RATIO_4_5,
        subject: SubjectBox? = null,
        applyStyle: Boolean = preset != null,
        processingMaxSide: Int = FULL_MAX_SIDE,
    ): EditPlan {
        val geometry = planGeometry(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            tiltDeg = metrics?.tiltDeg ?: 0f,
            aspect = aspect,
            subjectCenterX = subject?.centerX ?: 0.5f,
            subjectCenterY = subject?.centerY ?: 0.5f,
        )

        val wb = grayWorldGains(means)
        val exposureEv = autoExposureEv(stats.mean)
        val levels = contrastStretch(stats)

        // Shadow/highlight relief is driven by how much of the frame is actually
        // clipped, so a well-exposed photo gets an identity curve and one fewer pass.
        val shadowLift = (stats.shadowClipRatio * 2f).coerceIn(0f, 0.6f)
        val highlightRolloff = (stats.highlightClipRatio * 2f).coerceIn(0f, 0.6f)

        val optical = OpticalParams(
            exposureEv = exposureEv,
            wbGainR = wb.r,
            wbGainB = wb.b,
            blackPoint = levels.black,
            whitePoint = levels.white,
            shadowLift = shadowLift,
            highlightRolloff = highlightRolloff,
        )

        val color = preset?.color?.takeIf { applyStyle }
        val style = StyleParams(
            presetId = preset?.id?.takeIf { applyStyle },
            color = color,
            grain = color?.grain?.toFloat() ?: 0f,
            vignette = color?.vignette?.toFloat() ?: 0f,
            blurStrength = color?.blurStrength?.toFloat() ?: 0f,
        )

        return EditPlan(
            geometry = geometry,
            optical = optical,
            style = style,
            opticalMatrix = opticalColorMatrix(wb, exposureEv, levels),
            styleMatrix = color?.let { styleColorMatrix(it) } ?: identityColorMatrix(),
            toneLut = toneCurveLut(shadowLift, highlightRolloff),
            processingMaxSide = processingMaxSide,
        )
    }
}
