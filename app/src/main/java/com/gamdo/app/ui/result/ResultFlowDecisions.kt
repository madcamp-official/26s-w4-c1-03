package com.gamdo.app.ui.result

/**
 * 보정(결과) 화면의 순수 판정 — **platform-free** (zero `android.*` imports), same
 * rule and the same reason as [com.gamdo.app.ui.rescue.RescueFlowDecisions] and
 * [com.gamdo.app.ui.reference.ReferenceFlowDecisions]: this module has no
 * `androidTest` source set and no Robolectric, so anything holding a `Context`,
 * `Uri` or `Bitmap` cannot execute under `testDebugUnitTest`.
 *
 * The screen itself (`ResultScreen.kt`) is DONE-DEVICE. What it is *allowed to do
 * to someone's photo* is decided here and pinned by `ResultFlowDecisionsTest`.
 */

/** Where the photo on the result screen came from. */
enum class EditSourceKind {
    /** This app's own shutter — a `captures` row with `conditions_json`. */
    APP_CAPTURE,

    /** A `MediaStore` photo the user tapped in the album (W3.5-6). No row, no tilt. */
    DEVICE_PHOTO,
}

/** What the user has actively chosen on the filter strip, if anything. */
enum class StylePick {
    /** Nothing tapped yet — the screen just opened. */
    NONE,

    /** `원본` was tapped, explicitly asking for no look. */
    ORIGINAL,

    /** One of the six presets was tapped. */
    PRESET,

    /** `내 레퍼런스` was tapped (AI 2). */
    REFERENCE,
}

/** Which stages of the §4-1 pipeline may run. */
data class CorrectionPasses(
    val geometry: Boolean,
    val optical: Boolean,
    val style: Boolean,
) {
    /** False means "show the decode untouched" — no plan, no render. */
    val runsAnyPass: Boolean get() = geometry || optical || style
}

/**
 * Which correction passes apply to a photo that just opened — **O-12**.
 *
 * The rule, in the owner's words (remain_plan §0, 2026-07-29): 기기 사진은 열었을
 * 때 원본 그대로 보인다. 사용자가 고르지 않은 보정이 남의 사진에 적용되지 않게 하는
 * 것이 우선이다. 필터를 직접 고르면 그때부터 적용된다.
 *
 * So the branch is on **where the photo came from**, not on what the pipeline is
 * capable of:
 *
 *  - [EditSourceKind.APP_CAPTURE] keeps today's behaviour exactly — geometry and
 *    optical always run, because the user pointed this app's own camera at the
 *    scene and the shutter recorded the tilt that makes levelling meaningful. The
 *    style stage still only runs for [StylePick.REFERENCE]; the six presets are
 *    applied by `QuickFilterEditor` on top of this pass, not inside it.
 *  - [EditSourceKind.DEVICE_PHOTO] runs **nothing** until the user picks a look.
 *    Not "runs levelling with a tilt of zero" — nothing: no rotation, no exposure,
 *    no white balance, no crop. Tapping `원본` counts as asking for no look, so it
 *    keeps the photo untouched rather than quietly turning auto-exposure back on
 *    under a label that says otherwise.
 *
 * The one reading this fixes: 필터를 직접 고르면 그때부터 적용된다's subject is
 * 보정 (the sentence before it), so picking a preset turns the geometry+optical
 * pass on as well as the preset's own colour. A device photo therefore *can* be
 * cropped to 4:5/1:1 — but only after a deliberate tap, never on open.
 */
fun correctionPassesFor(source: EditSourceKind, pick: StylePick): CorrectionPasses = when (source) {
    EditSourceKind.APP_CAPTURE -> CorrectionPasses(
        geometry = true,
        optical = true,
        style = pick == StylePick.REFERENCE,
    )
    EditSourceKind.DEVICE_PHOTO -> when (pick) {
        StylePick.NONE, StylePick.ORIGINAL -> CorrectionPasses(
            geometry = false,
            optical = false,
            style = false,
        )
        StylePick.PRESET, StylePick.REFERENCE -> CorrectionPasses(
            geometry = true,
            optical = true,
            style = pick == StylePick.REFERENCE,
        )
    }
}

/**
 * Whether opening the screen may pre-select the user's saved style preset.
 *
 * This is the *other* half of O-12 and it is easy to miss: the result screen seeds
 * `selectedStrip` from `SettingsRepository.getStylePresetId()` the moment it opens,
 * which for a device photo would apply a colour look nobody picked — the exact
 * thing O-12 forbids — even with every pipeline pass switched off.
 */
fun opensOnPreferredStyle(source: EditSourceKind): Boolean = source == EditSourceKind.APP_CAPTURE

/** The three strip items the screen can be sitting on, as a pure value. */
enum class StripSelection { ORIGINAL, PRESET, REFERENCE }

/**
 * [StylePick] from what the strip is showing plus whether the user put it there.
 *
 * [chosenByUser] is not redundant with [selection]: on an app capture the screen
 * opens with a selection already made for it (see [opensOnPreferredStyle]), so
 * "something is selected" and "the user picked something" are different facts, and
 * O-12 turns on the second one.
 */
fun stylePickFor(
    source: EditSourceKind,
    selection: StripSelection,
    chosenByUser: Boolean,
): StylePick {
    if (!chosenByUser && !opensOnPreferredStyle(source)) return StylePick.NONE
    return when (selection) {
        StripSelection.ORIGINAL -> StylePick.ORIGINAL
        StripSelection.PRESET -> StylePick.PRESET
        StripSelection.REFERENCE -> StylePick.REFERENCE
    }
}

/**
 * The pick before the user has touched the strip — what [correctionPassesFor] gets
 * on the very first frame.
 */
fun initialStylePick(source: EditSourceKind): StylePick =
    stylePickFor(source, StripSelection.PRESET, chosenByUser = false)

/**
 * Whether the `AI로 보정` strip slot (O-10 / AI 3) is offered for this source.
 *
 * Every call AI 3 makes is keyed on a `captures` row — `analyze(captureRef =)`,
 * `submit(captureId =)`, and `capture_edit_stack.selected_result_id`. A device
 * photo has no row, so the slot would be a tap that cannot do anything. Not
 * drawing it is honest; drawing a dead one is not (AGENTS.md §7-6).
 */
fun offersGenerativeRestore(source: EditSourceKind): Boolean = true

/** Where 저장 puts the edited pixels. Never the source image, in either branch. */
enum class SaveTarget {
    /**
     * There is a `captures` row: a new derivative file, a `capture_edit_stack` row
     * recording the parameters, a gallery copy, and `saved_to_gallery`.
     */
    CAPTURE_DERIVATIVE,

    /**
     * There is no row to hang an edit stack off: a brand-new file plus a gallery
     * copy, and nothing written to the database.
     */
    NEW_FILE_ONLY,
}

/**
 * D8-6 비파괴 보존, restated for W3.5-6. A device photo's bytes are reachable only
 * through `ContentResolver.openInputStream` and the save path never opens an output
 * stream on that `Uri` — the edit lands in a new file in the app's own storage and,
 * if MediaStore accepts it, a new gallery entry. The user's original is untouched.
 */
fun saveTargetFor(source: EditSourceKind): SaveTarget = when (source) {
    EditSourceKind.APP_CAPTURE -> SaveTarget.CAPTURE_DERIVATIVE
    EditSourceKind.DEVICE_PHOTO -> SaveTarget.NEW_FILE_ONLY
}
