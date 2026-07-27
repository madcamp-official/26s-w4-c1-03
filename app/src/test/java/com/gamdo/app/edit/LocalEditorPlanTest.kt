package com.gamdo.app.edit

import android.graphics.Bitmap
import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.StylePreset
import com.gamdo.app.detect.ImageMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM coverage for `LocalEditor.plan` — specifically **which aspect ratio an already
 * framed photo gets re-cropped to**.
 *
 * `LocalEditor` names `android.graphics.Bitmap` in two signatures, but `plan()` never
 * touches one: it is pure arithmetic over a [SourceSample]. The stub renderer below
 * is only ever loaded, never called, so this runs on the JVM without Robolectric.
 *
 * Why this file exists: the camera shutter applies the user's 4:5 / 1:1 choice before
 * saving (`CameraScreen` → `centerCropToRatio`), so the stored file's proportions are
 * the framing intent. A default that re-derived the ratio from the preset instead
 * silently trimmed 20% off the width of every square capture — invisible in a diff,
 * invisible without a device, and destructive.
 */
class LocalEditorPlanTest {

    /** Never invoked; `plan()` does not render. */
    private object StubRenderer : EditRenderer {
        override fun render(source: Bitmap, plan: EditPlan): Bitmap =
            throw UnsupportedOperationException("plan() must not render")
    }

    private fun editor() = LocalEditor(
        renderer = StubRenderer,
        availableBytes = { 512L * 1024 * 1024 },
    )

    private fun sampleOf(width: Int, height: Int): SourceSample = SourceSample(
        width = width,
        height = height,
        stats = LumaStats(
            pixelCount = 1024,
            mean = AUTO_EXPOSURE_TARGET,
            shadowClipRatio = 0f,
            highlightClipRatio = 0f,
            blackPoint = 0f,
            whitePoint = 1f,
        ),
        means = ChannelMeans(0.5f, 0.5f, 0.5f),
        metrics = ImageMetrics(
            tiltDeg = 0f,
            brightnessMean = AUTO_EXPOSURE_TARGET,
            laplacianVariance = 500f,
            leftMargin = 0f,
            rightMargin = 0f,
        ),
    )

    /** A preset whose composition block asks for a ratio the photo does not have. */
    private fun presetWanting(aspectKey: String) = StylePreset(
        id = "p",
        name = "p",
        displayName = "프리셋",
        composition = Composition(
            targetAspectRatio = aspectKey,
            subjectScaleRange = listOf(0.3, 0.6),
            subjectPosition = "center",
            headroomRange = listOf(0.05, 0.15),
            horizonPosition = 0.5,
            cameraPitchRange = listOf(-5.0, 5.0),
            posePattern = "standing",
            backgroundRatio = listOf(0.4, 0.7),
        ),
        color = ColorParams(
            colorTemperature = NEUTRAL_KELVIN.toDouble(),
            exposureBias = 0.0,
            contrast = 0.0,
            saturation = 0.0,
            grain = 0.0,
            vignette = 0.0,
            blurStrength = 0.0,
            fade = 0.0,
        ),
    )

    @Test
    fun `a square capture is not re-cropped on the basic tab`() {
        val plan = editor().plan(sampleOf(2000, 2000))
        assertEquals(EditAspect.RATIO_1_1, plan.geometry.aspect)
        assertEquals(2000, plan.geometry.crop.width)
        assertEquals(2000, plan.geometry.crop.height)
        assertEquals(0, plan.geometry.crop.x)
    }

    @Test
    fun `a square capture survives a 4 by 5 preset`() {
        // The regression: the preset's composition block describes the shooting
        // guide, not a licence to trim 400px off a square photo the user framed.
        val plan = editor().plan(sampleOf(2000, 2000), preset = presetWanting("4:5"))
        assertEquals(EditAspect.RATIO_1_1, plan.geometry.aspect)
        assertEquals(2000, plan.geometry.crop.width)
    }

    @Test
    fun `a 4 by 5 capture is not re-cropped either`() {
        val plan = editor().plan(sampleOf(1600, 2000))
        assertEquals(EditAspect.RATIO_4_5, plan.geometry.aspect)
        assertEquals(1600, plan.geometry.crop.width)
        assertEquals(2000, plan.geometry.crop.height)
    }

    @Test
    fun `an explicit aspect still wins over the source`() {
        // §4-3 and the reference flow need to be able to override.
        val plan = editor().plan(sampleOf(2000, 2000), aspect = EditAspect.RATIO_4_5)
        assertEquals(EditAspect.RATIO_4_5, plan.geometry.aspect)
        assertEquals(1600, plan.geometry.crop.width)
    }

    @Test
    fun `an odd shaped gallery import is normalized to a supported ratio`() {
        // D9-1: 16:9 is not a supported output, so an import must land on 4:5 or 1:1.
        val plan = editor().plan(sampleOf(1920, 1080))
        assertEquals(EditAspect.RATIO_1_1, plan.geometry.aspect)
        assertEquals(1080, plan.geometry.crop.height)
    }

    @Test
    fun `a portrait import is normalized without exceeding the frame`() {
        val plan = editor().plan(sampleOf(1080, 1920))
        assertEquals(EditAspect.RATIO_4_5, plan.geometry.aspect)
        assertEquals(1080, plan.geometry.crop.width)
        assertEquals(1350, plan.geometry.crop.height)
    }

    @Test
    fun `saving re-plans at full resolution after a slow preview`() {
        // The §4-1 fallback, exercised through the orchestrator rather than the pure
        // budget function, so the wiring is covered too.
        val editor = editor()
        val preview = editor.plan(sampleOf(4000, 3000), requestedMaxSide = PREVIEW_MAX_SIDE)
        assertEquals(PREVIEW_MAX_SIDE, preview.processingMaxSide)

        val save = editor.plan(sampleOf(4000, 3000), forSave = true)
        assertEquals(FULL_MAX_SIDE, save.processingMaxSide)
    }

    @Test
    fun `the plan records the steps the edit stack needs`() {
        val steps = editor().plan(sampleOf(2000, 2000)).toEditSteps()
        assertEquals(3, steps.size)
        assertEquals(EditStepType.GEOMETRY, steps[0].type)
        assertEquals(EditStepType.OPTICAL, steps[1].type)
        assertEquals(EditStepType.STYLE, steps[2].type)
    }
}
