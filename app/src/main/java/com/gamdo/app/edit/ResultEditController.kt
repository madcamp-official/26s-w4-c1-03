package com.gamdo.app.edit

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gamdo.app.data.CaptureRepository
import com.gamdo.app.data.SavedEdit
import com.gamdo.app.data.preset.StylePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives §4-2: turns one stored capture into the three images the result screen
 * compares (원본 / 기본 보정 / 스타일 보정) and owns the save.
 *
 * It lives in `edit/` rather than `ui/result/` because it is pipeline state, not
 * screen state — `ResultScreen.kt` reads it and draws, and knows nothing about
 * planning, budgets or bitmap lifetimes.
 *
 * ## Two resolutions, on purpose
 *
 * The preview renders at [PREVIEW_DISPLAY_MAX_SIDE]; the save re-decodes the
 * original and renders at full resolution with `forSave = true`. Rendering 4000px
 * for a 1080px screen would spend the §4-1 budget on pixels nobody sees, and the
 * plan is resolution-independent, so the two agree by construction rather than by
 * duplication.
 *
 * ## D8-6
 *
 * Nothing here writes a file. Saving goes through
 * `CaptureRepository.saveEditedResult`, which allocates a new path and refuses one
 * equal to `captures.file_path`. The original bitmap in memory is only ever read.
 */
@Stable
class ResultEditController(
    private val editor: LocalEditor = LocalEditor(),
) {

    enum class Phase { IDLE, LOADING, READY, FAILED }

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** Untouched source, at preview resolution. */
    var original by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Geometry + optical stages only — the 기본 보정 tab. */
    var basic by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Geometry + optical + preset — the 스타일 보정 tab. Null until a preset is picked. */
    var styled by mutableStateOf<ImageBitmap?>(null)
        private set

    var selectedPresetId by mutableStateOf<String?>(null)
        private set

    /** True while a style render is in flight, so the strip can show it is working. */
    var styling by mutableStateOf(false)
        private set

    /**
     * Last preview render duration and working resolution. Diagnostics only —
     * R7-1 forbids putting numbers like these in front of the user, so they are for
     * `BuildConfig.DEBUG` surfaces and logs.
     */
    var lastPreviewMs by mutableStateOf(0L)
        private set
    var lastWorkingMaxSide by mutableStateOf(0)
        private set

    private var sourceFile: File? = null
    private var sample: SourceSample? = null
    private var selectedPreset: StylePreset? = null

    /**
     * Where the person is, in stored-file coordinates, or null when nothing was
     * detected. Kept so every re-plan (preset switch, save) centres the crop the same
     * way — §4-1's "인물 중심 유지". Null falls back to the frame centre.
     */
    private var subject: SubjectBox? = null

    /*
     * The bitmaps behind the three `ImageBitmap`s above, held so they can be freed.
     *
     * Each slot is recycled the moment it is replaced rather than being collected at
     * dispose: a preview frame is ~7 MB at 1600px, and browsing five presets would
     * otherwise pile up 35 MB of dead bitmaps on exactly the heap §4-1 is trying not
     * to exhaust. Replacement always assigns the new state first and recycles second,
     * both on the main thread, so no frame can draw a recycled bitmap.
     */
    private var previewSource: Bitmap? = null
    private var basicBitmap: Bitmap? = null
    private var styledBitmap: Bitmap? = null

    /**
     * Decodes [file], measures it and renders the basic correction.
     *
     * @param conditionsJson `captures.conditions_json`, read only for the shutter-time
     *   tilt. A gallery import has none and levels by 0 degrees.
     */
    suspend fun load(file: File, conditionsJson: String? = null) {
        phase = Phase.LOADING
        val conditions = CaptureConditions.parse(conditionsJson)
        val loaded = runCatching {
            withContext(Dispatchers.Default) {
                val fullSize = EditSourceLoader.readSize(file)
                    ?: error("not an image: ${file.name}")
                val preview = EditSourceLoader.decode(file, PREVIEW_DISPLAY_MAX_SIDE)
                    ?: error("cannot decode ${file.name}")
                val measured = editor.sample(
                    bitmap = preview,
                    tiltDeg = conditions.tiltDegOrZero,
                    subject = conditions.subject,
                    sourceWidth = fullSize.first,
                    sourceHeight = fullSize.second,
                )
                preview to measured
            }
        }.getOrElse {
            Log.w(TAG, "load failed for ${file.name}", it)
            phase = Phase.FAILED
            return
        }

        sourceFile = file
        previewSource = loaded.first
        sample = loaded.second
        subject = conditions.subject
        original = loaded.first.asImageBitmap()

        renderBasic()
        phase = if (basic != null) Phase.READY else Phase.FAILED
    }

    /** Renders (or clears) the 스타일 보정 tab for [preset]. */
    suspend fun applyPreset(preset: StylePreset?) {
        val source = previewSource ?: return
        val measured = sample ?: return
        selectedPreset = preset
        selectedPresetId = preset?.id
        if (preset == null) {
            setStyled(null)
            return
        }
        styling = true
        try {
            val plan = editor.plan(
                sample = measured,
                preset = preset,
                subject = subject,
                requestedMaxSide = PREVIEW_DISPLAY_MAX_SIDE,
            )
            setStyled(editor.render(source, plan))
        } catch (oom: OutOfMemoryError) {
            // The ladder in LocalEditor.render is already exhausted at this point.
            Log.w(TAG, "style render ran out of memory; keeping the basic result", oom)
            setStyled(null)
        } finally {
            styling = false
        }
    }

    /**
     * Re-renders at full resolution and writes a **new** file plus the
     * `capture_edit_stack` rows (D8-6).
     *
     * @param applyStyle true to save the 스타일 보정 result, false for 기본 보정.
     * @return null when the source could not be re-read; the caller keeps the user on
     *   the screen rather than claiming a save that did not happen (AGENTS.md §7-6).
     */
    suspend fun save(
        repository: CaptureRepository,
        captureId: String,
        applyStyle: Boolean,
    ): SavedEdit? {
        val file = sourceFile ?: return null
        val measured = sample ?: return null
        val preset = selectedPreset.takeIf { applyStyle }

        return runCatching {
            val full = withContext(Dispatchers.Default) {
                EditSourceLoader.decode(file, FULL_MAX_SIDE)
            } ?: error("cannot re-read ${file.name}")

            try {
                // §4-1: the save re-applies at the original resolution even if the
                // preview was downgraded for missing the 2s budget.
                val plan = editor.plan(
                    sample = measured,
                    preset = preset,
                    applyStyle = preset != null,
                    subject = subject,
                    forSave = true,
                )
                val rendered = editor.render(full, plan)
                try {
                    repository.saveEditedResult(
                        captureId = captureId,
                        edited = rendered.bitmap,
                        steps = plan.toEditSteps(),
                        variant = if (preset != null) "style" else "basic",
                    )
                } finally {
                    if (!rendered.bitmap.isRecycled) rendered.bitmap.recycle()
                }
            } finally {
                if (!full.isRecycled) full.recycle()
            }
        }.onFailure { Log.w(TAG, "save failed for $captureId", it) }.getOrNull()
    }

    /** Frees every bitmap this controller allocated. Call from a `DisposableEffect`. */
    fun release() {
        original = null
        basic = null
        styled = null
        recycle(previewSource)
        recycle(basicBitmap)
        recycle(styledBitmap)
        previewSource = null
        basicBitmap = null
        styledBitmap = null
        phase = Phase.IDLE
    }

    private suspend fun renderBasic() {
        val source = previewSource ?: return
        val measured = sample ?: return
        try {
            val plan = editor.plan(
                sample = measured,
                preset = null,
                subject = subject,
                requestedMaxSide = PREVIEW_DISPLAY_MAX_SIDE,
            )
            val rendered = editor.render(source, plan)
            noteTiming(rendered)
            val previous = basicBitmap
            basicBitmap = rendered.bitmap
            basic = rendered.bitmap.asImageBitmap()
            recycle(previous)
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "basic render ran out of memory", oom)
            basic = null
        }
    }

    /** Swaps in a new style result, freeing the one it replaces. */
    private fun setStyled(rendered: RenderedEdit?) {
        rendered?.let(::noteTiming)
        val previous = styledBitmap
        styledBitmap = rendered?.bitmap
        styled = rendered?.bitmap?.asImageBitmap()
        recycle(previous)
    }

    private fun noteTiming(rendered: RenderedEdit) {
        lastPreviewMs = rendered.elapsedMs
        lastWorkingMaxSide = rendered.workingMaxSide
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    companion object {
        private const val TAG = "ResultEditController"

        /**
         * Longest edge for on-screen renders. Above a phone's pixel count the extra
         * work is invisible, and the before/after slider has to stay responsive while
         * two layers are composited.
         */
        const val PREVIEW_DISPLAY_MAX_SIDE = 1600
    }
}

@Composable
fun rememberResultEditController(): ResultEditController = remember { ResultEditController() }
