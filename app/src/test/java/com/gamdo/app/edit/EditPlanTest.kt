package com.gamdo.app.edit

import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.StylePreset
import com.gamdo.app.detect.ImageMetrics
import com.gamdo.app.detect.ProblemCode
import com.gamdo.app.detect.ProblemDiagnoser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the assembled plan and the `capture_edit_stack` payload it produces.
 *
 * The last test closes the loop 담당 B's module was missing a producer for: pixels
 * in, `ImageMetrics` out, `ProblemDiagnoser` verdict — end to end on the JVM.
 */
class EditPlanTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun flatPixels(count: Int, level: Int): IntArray =
        IntArray(count) { argb(level, level, level) }

    private fun softFilmPreset() = StylePreset(
        id = "soft_film",
        name = "soft_film",
        displayName = "소프트 필름",
        composition = Composition(
            targetAspectRatio = "4:5",
            subjectScaleRange = listOf(0.3, 0.6),
            subjectPosition = "center",
            headroomRange = listOf(0.05, 0.15),
            horizonPosition = 0.5,
            cameraPitchRange = listOf(-5.0, 5.0),
            posePattern = "standing",
            backgroundRatio = listOf(0.4, 0.7),
        ),
        color = ColorParams(
            colorTemperature = 5200.0,
            exposureBias = 0.1,
            contrast = -0.05,
            saturation = -0.05,
            grain = 0.22,
            vignette = 0.15,
            blurStrength = 0.0,
            fade = 0.25,
        ),
    )

    private fun planFor(
        width: Int = 4000,
        height: Int = 3000,
        level: Int = 128,
        tiltDeg: Float = 0f,
        preset: StylePreset? = null,
    ): EditPlan {
        val pixels = flatPixels(1024, level)
        return EditPlanner.plan(
            sourceWidth = width,
            sourceHeight = height,
            stats = lumaStats(lumaHistogram(lumaOf(pixels))),
            means = channelMeans(pixels),
            metrics = ImageMetrics(
                tiltDeg = tiltDeg,
                brightnessMean = level / 255f,
                laplacianVariance = 500f,
                leftMargin = 0f,
                rightMargin = 0f,
            ),
            preset = preset,
        )
    }

    @Test
    fun `plan emits exactly the three pipeline steps in order`() {
        val steps = planFor().toEditSteps(json)
        assertEquals(3, steps.size)
        assertEquals(listOf(0, 1, 2), steps.map { it.order })
        assertEquals(
            listOf("geometry", "optical", "style"),
            steps.map { it.type.value },
        )
    }

    @Test
    fun `step types match the frozen schema vocabulary`() {
        // DB schema v2.0 §3.9 CHECK (step_type IN ('geometry','optical','style',...)).
        val allowed = setOf("geometry", "optical", "style", "semantic", "generative_ref")
        EditStepType.entries.forEach { assertTrue(it.value in allowed) }
    }

    @Test
    fun `geometry params round trip through json`() {
        val plan = planFor(tiltDeg = 6f, preset = softFilmPreset())
        val step = plan.toEditSteps(json).first { it.type == EditStepType.GEOMETRY }
        val decoded = json.decodeFromString(GeometryParams.serializer(), step.paramsJson)

        assertEquals(EDIT_PARAMS_VERSION, decoded.v)
        assertEquals("4:5", decoded.aspect)
        assertEquals(-6f, decoded.rotationDeg, 1e-4f)
        assertEquals(plan.geometry.crop.width, decoded.cropWidth)
        assertEquals(plan.geometry.crop.height, decoded.cropHeight)
        assertEquals(4000, decoded.sourceWidth)
    }

    @Test
    fun `optical params round trip through json`() {
        val plan = planFor(level = 40)
        val step = plan.toEditSteps(json).first { it.type == EditStepType.OPTICAL }
        val decoded = json.decodeFromString(OpticalParams.serializer(), step.paramsJson)

        assertEquals(EDIT_PARAMS_VERSION, decoded.v)
        assertTrue("a dark frame should be brightened", decoded.exposureEv > 0f)
        assertTrue("auto exposure must respect the 1 EV cap", decoded.exposureEv <= 1f)
    }

    @Test
    fun `style params round trip and carry the preset`() {
        val plan = planFor(preset = softFilmPreset())
        val step = plan.toEditSteps(json).first { it.type == EditStepType.STYLE }
        val decoded = json.decodeFromString(StyleParams.serializer(), step.paramsJson)

        assertEquals("soft_film", decoded.presetId)
        assertNotNull(decoded.color)
        assertEquals(0.22f, decoded.grain, 1e-4f)
        assertEquals(0.15f, decoded.vignette, 1e-4f)
    }

    @Test
    fun `basic correction records no style`() {
        val plan = planFor(preset = null)
        val step = plan.toEditSteps(json).first { it.type == EditStepType.STYLE }
        val decoded = json.decodeFromString(StyleParams.serializer(), step.paramsJson)

        assertNull(decoded.presetId)
        assertNull(decoded.color)
        assertTrue(isIdentityColorMatrix(plan.styleMatrix))
    }

    @Test
    fun `a preset can be loaded for its crop while its colour is withheld`() {
        val plan = EditPlanner.plan(
            sourceWidth = 1000,
            sourceHeight = 1000,
            stats = lumaStats(lumaHistogram(lumaOf(flatPixels(1024, 128)))),
            means = channelMeans(flatPixels(1024, 128)),
            preset = softFilmPreset(),
            applyStyle = false,
        )
        assertNull(plan.style.presetId)
        assertTrue(isIdentityColorMatrix(plan.styleMatrix))
    }

    @Test
    fun `preset aspect drives the crop`() {
        val squarePreset = softFilmPreset().let {
            it.copy(composition = it.composition.copy(targetAspectRatio = "1:1"))
        }
        val plan = EditPlanner.plan(
            sourceWidth = 1000,
            sourceHeight = 1000,
            stats = lumaStats(lumaHistogram(lumaOf(flatPixels(1024, 128)))),
            means = channelMeans(flatPixels(1024, 128)),
            preset = squarePreset,
        )
        assertEquals(EditAspect.RATIO_1_1, plan.geometry.aspect)
        assertEquals(plan.geometry.crop.width, plan.geometry.crop.height)
    }

    @Test
    fun `a well exposed square frame needs no geometry work`() {
        val plan = EditPlanner.plan(
            sourceWidth = 1000,
            sourceHeight = 1000,
            stats = lumaStats(lumaHistogram(lumaOf(flatPixels(1024, 118)))),
            means = channelMeans(flatPixels(1024, 118)),
            aspect = EditAspect.RATIO_1_1,
        )
        assertTrue(plan.isGeometryNoOp())
    }

    @Test
    fun `a clipped frame gets a real tone curve`() {
        val pixels = IntArray(1000) { if (it < 400) argb(2, 2, 2) else argb(120, 120, 120) }
        val plan = EditPlanner.plan(
            sourceWidth = 1000,
            sourceHeight = 1000,
            stats = lumaStats(lumaHistogram(lumaOf(pixels))),
            means = channelMeans(pixels),
        )
        assertFalse("crushed shadows should produce a lift", isIdentityLut(plan.toneLut))
        assertTrue(plan.optical.shadowLift > 0f)
    }

    @Test
    fun `combined matrix folds both colour stages`() {
        val plan = planFor(level = 40, preset = softFilmPreset())
        assertFalse(isIdentityColorMatrix(plan.combinedMatrix()))
        assertFalse(plan.isColorNoOp())
    }

    @Test
    fun `processing resolution defaults to the full size target`() {
        assertEquals(FULL_MAX_SIDE, planFor().processingMaxSide)
        assertEquals(4000, FULL_MAX_SIDE)
        assertEquals(2000, PREVIEW_MAX_SIDE)
    }

    @Test
    fun `extracted metrics feed the diagnoser end to end`() {
        // The producer 담당 B's ProblemDiagnoser was missing: pixels -> ImageMetrics
        // -> Problem list, with no Android type anywhere in the chain.
        val w = 48
        val dark = IntArray(w * w) { argb(6, 6, 6) }
        val metrics = computeImageMetrics(dark, w, w, tiltDeg = 9f)
        val problems = ProblemDiagnoser().diagnose(metrics)
        val codes = problems.map { it.code }

        assertTrue("a 9 degree tilt must be reported", ProblemCode.TILT in codes)
        assertTrue("a near-black frame must be reported", ProblemCode.UNDEREXPOSED in codes)
        assertFalse("nothing is blown out here", ProblemCode.OVEREXPOSED in codes)
    }
}
