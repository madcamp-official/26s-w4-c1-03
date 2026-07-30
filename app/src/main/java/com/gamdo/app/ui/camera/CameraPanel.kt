package com.gamdo.app.ui.camera

/**
 * What the camera screen has open or armed, and the rules for getting in and out
 * (owner's final UI redesign, 2026-07-30).
 *
 * Pure Kotlin, no `android.*` — same reason as [DebugHudGate] and
 * [resolveTapFocusPoint]: this module has no `androidTest` source set, no
 * Robolectric and no Compose UI test, so a decision written inside a `@Composable`
 * cannot be tested at all. Every rule below has already been the kind of thing that
 * regresses silently — "picking a filter closed the sheet" is a one-character
 * change — so the rules live here and `CameraPanelTest` pins them.
 *
 * ## Why one enum instead of three booleans
 *
 * The redesign puts three things behind buttons: the filter sheet, the settings
 * sheet and area-select (연필). They are **mutually exclusive**, and the exclusivity
 * is not a nicety:
 *
 *  - the filter and settings sheets occupy the same strip of screen;
 *  - the requirement for area-select is explicit — "활성 표시는 버튼의 앰버 하나로,
 *    필터·프레임 패널은 닫는다" — because the sheet covers the bottom of the preview
 *    and that is where a lasso drag ends up.
 *
 * Three booleans can represent "filter sheet open *and* area-select armed", which is
 * a state no rule below is written for. One enum cannot, so the exclusivity is
 * structural rather than an invariant somebody has to remember to maintain.
 *
 * ## Cancel is one gesture: re-tap the button that opened it
 *
 * `docs/P1_P2_카메라UX_AI개선_요구사항_2026-07-29.md` §4 P2-1 leaves the choice to P1
 * — "취소는 `×`, 시스템 뒤로가기 또는 활성 버튼 재탭 중 P1이 일관된 한 방식을 선택한다"
 * — and [toggled] is that choice, for all three modes at once. The alternatives were
 * each worse for a specific reason: an `×` adds another control on top of the photo
 * (the redesign's amber rule already restricts what may sit there), and system-back
 * on the camera screen means leave-the-app, which is not a thing a drawing mode
 * should be able to mean.
 */
enum class CameraOverlayMode {
    /** Nothing open. The preview is a preview. */
    NONE,

    /** 시안 05 — the six presets and `내 레퍼런스`, raised from the filter button. */
    FILTER_SHEET,

    /**
     * The 12 manual composition frames (§3.1), raised from the preview's frame button.
     *
     * A separate mode rather than a section of [FILTER_SHEET], because a preset is a
     * **colour** and a frame is a **composition** (O-13). Putting them in one sheet is
     * the confusion O-13 exists to have fixed — the colour control used to be the
     * composition control.
     */
    FRAME_SHEET,

    /** Debug-only. Holds the HUD toggle; see [settingsSheetAvailable]. */
    SETTINGS_SHEET,

    /** 연필 — the preview collects a lasso path instead of framing gestures. */
    AREA_SELECT,
}

/**
 * The transitions. Free functions rather than methods so a call site reads as the
 * event that happened (`toggled(mode, FILTER_SHEET)`) rather than as a mutation.
 */
object CameraPanels {

    /**
     * A tap on the button for [target].
     *
     * Opens it, or closes it when it is already the current mode — and, because
     * there is only one mode, opening any of the three closes the other two
     * without a separate rule for it.
     */
    fun toggled(current: CameraOverlayMode, target: CameraOverlayMode): CameraOverlayMode =
        if (current == target) CameraOverlayMode.NONE else target

    /**
     * A tap outside an open **sheet**.
     *
     * Deliberately does nothing in [CameraOverlayMode.AREA_SELECT], and the
     * signature is why: area-select has no scrim to tap, because in that mode the
     * whole preview is the drawing surface. If this returned `NONE` for every input
     * then the first stroke of a lasso would cancel the lasso, and the bug would
     * live in the *caller* — which cannot be tested here. So it is impossible to
     * express instead.
     */
    fun scrimTapped(current: CameraOverlayMode): CameraOverlayMode = when (current) {
        CameraOverlayMode.FILTER_SHEET,
        CameraOverlayMode.FRAME_SHEET,
        CameraOverlayMode.SETTINGS_SHEET,
        -> CameraOverlayMode.NONE
        CameraOverlayMode.NONE, CameraOverlayMode.AREA_SELECT -> current
    }

    /**
     * A filter was picked in the sheet — **the sheet stays open.**
     *
     * The identity function, and it exists to be called. The redesign states this
     * outright ("필터를 골라도 시트가 닫히지 않는다. 선택만 강조된다") because the
     * conventional bottom-sheet behaviour is the opposite, and a filter is a colour
     * (O-13): comparing two colours means seeing both against the same live scene,
     * which is not possible if the first tap dismisses the comparison. Written as a
     * named function so that changing it is an edit to a tested rule rather than a
     * quietly added `mode = NONE` at a call site.
     */
    fun filterPicked(current: CameraOverlayMode): CameraOverlayMode = current

    /**
     * Whether a bottom sheet is showing. The shutter stays live while one is —
     * the sheet is a picker, not a modal.
     */
    fun sheetVisible(mode: CameraOverlayMode): Boolean = when (mode) {
        CameraOverlayMode.FILTER_SHEET,
        CameraOverlayMode.FRAME_SHEET,
        CameraOverlayMode.SETTINGS_SHEET,
        -> true
        CameraOverlayMode.NONE, CameraOverlayMode.AREA_SELECT -> false
    }

    /** Whether the preview should collect a lasso path rather than framing gestures. */
    fun areaSelectArmed(mode: CameraOverlayMode): Boolean = mode == CameraOverlayMode.AREA_SELECT

    /**
     * Whether the 설정 button — and so the settings sheet — exists in this build.
     *
     * Delegates to [DebugHudGate.availableIn] rather than testing `isDebugBuild`
     * itself, because the sheet's entire content is the HUD toggle: the sheet exists
     * *because* moving that chip out of the top bar left it without a home. Two
     * gates reading the same build flag would be two things to keep in step, and the
     * demo build's top bar dropping to three icons is a consequence of this one
     * answer rather than of a second copy of it.
     *
     * When a setting that is not debug-only arrives, this splits — and the split
     * will be visible here rather than spread across call sites.
     */
    fun settingsSheetAvailable(isDebugBuild: Boolean): Boolean =
        DebugHudGate.availableIn(isDebugBuild)

    /**
     * The mode actually rendered, given whether the settings sheet exists.
     *
     * The same defence [DebugHudGate.visible] makes, for the same reason: the mode
     * survives process death through `rememberSaveable`, so a bundle written by a
     * debug build must not be able to raise a debug-only sheet in a build that has
     * no such sheet. Restoring into [CameraOverlayMode.NONE] rather than refusing to
     * restore keeps the screen usable in that case.
     */
    fun resolve(mode: CameraOverlayMode, isDebugBuild: Boolean): CameraOverlayMode =
        if (mode == CameraOverlayMode.SETTINGS_SHEET && !settingsSheetAvailable(isDebugBuild)) {
            CameraOverlayMode.NONE
        } else {
            mode
        }
}

/** Sheet slide up/down, per the redesign. */
const val CAMERA_SHEET_ANIM_MS: Int = 260

/**
 * Bracket white→amber, shutter white→amber, and the mood cross-fade — all 200ms,
 * all from the redesign, so they are one constant rather than three literals that
 * drift apart.
 */
const val CAMERA_ALIGN_FADE_MS: Int = 200
