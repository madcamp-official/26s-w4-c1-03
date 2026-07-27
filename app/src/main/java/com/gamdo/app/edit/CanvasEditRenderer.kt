package com.gamdo.app.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.gamdo.app.camera.scaledToMaxSide

/**
 * §4-1 renderer — Canvas + `ColorMatrixColorFilter` + LUT.
 *
 * ## Why this backend
 *
 * Decided in the Day 4 spike (full reasoning in `edit/LocalEditor.kt`): AGSL
 * `RuntimeShader` is API 33+ and the target device is API 31, `RenderEffect` is
 * API 31+ against `minSdk 26` and needs a `HardwareRenderer`/`ImageReader` round
 * trip to read pixels back, and OpenCV is a native-library dependency that buys
 * nothing an affine matrix plus a 256-entry table cannot already do. Canvas runs
 * unmodified from API 26 up, which removes the fallback path from the requirements
 * instead of implementing it.
 *
 * **Still unmeasured.** No device is attached (AGENTS.md §8), so the §4-1 "2초 /
 * 4000px" target is unverified and this ranking can invert. The insurance is that
 * everything above this file speaks in `FloatArray(20)`, `IntArray(256)` and nine
 * affine floats, so swapping the backend means replacing this one class.
 *
 * ## What actually happens per photo
 *
 *  1. **One resample.** Levelling and the aspect crop are composed into a single
 *     affine matrix (`GeometryPlan.toAffineMatrixValues`) and applied in one
 *     `drawBitmap`. Rotating into an intermediate and then cropping it would
 *     allocate the rotated bounding box and interpolate twice.
 *  2. **Colour rides along.** The combined optical+style matrix goes on the same
 *     `Paint`, so white balance, exposure, the contrast stretch, temperature,
 *     contrast, saturation and fade cost nothing beyond the resample.
 *  3. **One banded software pass**, and only when the plan needs it: the tone curve
 *     (not expressible as an affine matrix), grain and vignette. Bands cap the
 *     transient `IntArray` at [DEFAULT_MAX_BAND_BYTES] instead of the 48 MB a
 *     full-frame 4000x3000 buffer would take.
 *
 * ## D8-6
 *
 * [render] allocates its own output and never draws into, or returns, [source].
 *
 * ## D8-1
 *
 * `ColorParams.blurStrength` is read into the plan (it is preset data and belongs in
 * `capture_edit_stack`) but is **deliberately not rendered**. §4-1 lists the style
 * parameters to apply and blur is not among them; more to the point, an unmasked
 * smoothing pass over a portrait is the "피부 매끄럽게" surface D8-1 forbids, and
 * there is no subject mask here to confine it to a background. Do not add one.
 */
class CanvasEditRenderer(
    private val maxBandBytes: Int = DEFAULT_MAX_BAND_BYTES,
) : EditRenderer {

    override fun render(source: Bitmap, plan: EditPlan): Bitmap {
        require(!source.isRecycled) { "source bitmap is recycled" }

        val working = source.scaledToMaxSide(plan.processingMaxSide)
        val ownsWorking = working !== source
        try {
            val geometry = plan.geometry.scaledTo(plan.geometry.sourceWidth, working.width)
            val output = drawGeometryAndColor(working, geometry, plan)
            if (needsSoftwarePass(plan)) {
                runSoftwarePass(output, plan)
            }
            return output
        } finally {
            if (ownsWorking && !working.isRecycled) working.recycle()
        }
    }

    /** Step 1+2: one resample carrying the whole colour transform. */
    private fun drawGeometryAndColor(
        working: Bitmap,
        geometry: GeometryPlan,
        plan: EditPlan,
    ): Bitmap {
        // Only a floor. An upper clamp would silently disagree with the matrix, which
        // is derived from the *unclamped* crop — the output would then be a shifted
        // window rather than the planned one. `processingMaxSide` already bounds the
        // size, and a genuinely absurd plan should fail loudly as an OOM the ladder
        // in `LocalEditor.render` can retry.
        val outWidth = geometry.crop.width.coerceAtLeast(1)
        val outHeight = geometry.crop.height.coerceAtLeast(1)

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val combined = plan.combinedMatrix()
        if (!isIdentityColorMatrix(combined)) {
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(combined))
        }

        val matrix = Matrix().apply { setValues(geometry.toAffineMatrixValues()) }
        canvas.drawBitmap(working, matrix, paint)
        return output
    }

    /**
     * Step 3: tone curve, grain and vignette, one horizontal band at a time.
     *
     * `getPixels`/`setPixels` per band rather than one whole-frame round trip — the
     * point of the split is that the transient buffer stays a few MB no matter how
     * large the photo is.
     */
    private fun runSoftwarePass(output: Bitmap, plan: EditPlan) {
        val width = output.width
        val height = output.height
        val bands = planBands(width, height, maxBandBytes)
        val buffer = IntArray(width * bands.bandHeight)

        for (index in 0 until bands.bandCount) {
            val top = index * bands.bandHeight
            val rows = bands.rowsIn(index, height)
            if (rows <= 0) break
            output.getPixels(buffer, 0, width, 0, top, width, rows)
            applySoftwareBand(
                pixels = buffer,
                plan = plan,
                imageWidth = width,
                imageHeight = height,
                bandTop = top,
                rows = rows,
            )
            output.setPixels(buffer, 0, width, 0, top, width, rows)
        }
    }
}
