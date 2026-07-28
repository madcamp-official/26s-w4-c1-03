package com.gamdo.app.edit

import kotlinx.coroutines.CancellationException

/**
 * The image the save pass produced, or the reason it produced none.
 *
 * There are exactly two outcomes and neither of them is "something smaller than
 * what the user was promised" — see [renderForSave].
 */
sealed interface SaveRender<out T : Any> {

    /** The full-resolution render. The only value that may be written out. */
    data class Ready<T : Any>(val image: T) : SaveRender<T>

    /**
     * The stored capture could not be decoded, so there is nothing to save. The
     * caller must tell the user; it must not write anything.
     */
    data object SourceUnreadable : SaveRender<Nothing>
}

/**
 * The §4-1 save pass: decode the stored file at save resolution, re-apply the
 * correction the preview showed, then the chosen look.
 *
 * ## Why this is a function and not four lines in the screen
 *
 * It was four lines in the screen, and they ended in a fallback chain:
 *
 * ```
 * val result = decodeFullResolution()?.let { correct(it); style(it) } ?: edited ?: source
 * ```
 *
 * `edited` and `source` are the **preview** bitmaps, decoded under the screen's own
 * preview cap — 1440px on the long edge against a 3630px capture, so under a fifth
 * of the pixels. When the full decode failed, the save wrote that to the gallery,
 * recorded it in `capture_edit_stack` as the edit, flipped the button to
 * `갤러리에 저장됨`, and said nothing. The user asked for their photo and got a
 * reduced copy of it, with no way to find out.
 *
 * A decode returns null when the file is missing, is not an image, or is truncated
 * — none of which the preview being fine rules out, because the preview was decoded
 * subsampled and minutes earlier. Running out of memory raises an
 * `OutOfMemoryError` instead and reaches the caller's own handler.
 *
 * So the rule is: **the save either happens at full resolution or it is reported as
 * not having happened.** Making it a function is what makes the rule checkable —
 * there is no parameter here to put a preview in, so the chain cannot come back by
 * accident, and [SaveRender.SourceUnreadable] is a value the caller has to answer
 * for rather than a null it can `?:` past.
 *
 * ## The one fallback that stays
 *
 * A [correct] that *throws* still saves, uncorrected. That is the same choice the
 * open path makes ("모든 실패 경로는 보정 없는 원본 디코드로 폴백한다") and it is
 * deliberate: a levelling pass the user never asked for must not be the reason
 * their photo cannot be saved. It is reported through [onCorrectionFailed] so it
 * lands in the log rather than nowhere. Note this is a real difference from the
 * preview — worth revisiting with a device, since the saved file is then not quite
 * the frame that was on screen.
 *
 * A [style] that throws is not caught: the look is what the user picked, and
 * silently saving without it would be the same class of lie this function exists to
 * remove.
 *
 * @param decodeFullResolution decodes the stored capture at save resolution
 * @param correct re-applies the preview's `EditPlan` at save resolution
 * @param style applies the selected filter and the manual adjustments
 * @param onCorrectionFailed called when [correct] threw and was skipped
 */
suspend fun <T : Any> renderForSave(
    decodeFullResolution: suspend () -> T?,
    correct: suspend (T) -> T,
    style: suspend (T) -> T,
    onCorrectionFailed: (Throwable) -> Unit = {},
): SaveRender<T> {
    val full = decodeFullResolution() ?: return SaveRender.SourceUnreadable
    val corrected = try {
        correct(full)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        onCorrectionFailed(failure)
        full
    }
    return SaveRender.Ready(style(corrected))
}
