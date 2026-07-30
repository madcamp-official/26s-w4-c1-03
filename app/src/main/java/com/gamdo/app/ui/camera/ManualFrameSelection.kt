package com.gamdo.app.ui.camera

import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.guide.LayoutSource
import com.gamdo.app.guide.LayoutTemplateSummary

/**
 * The manual frame picker's decisions (`docs/P2_P1_필수기능연결_요구사항_2026-07-30.md`
 * §3.1) — pure Kotlin, no `android.*`.
 *
 * ## The UI does not trust its own request
 *
 * §3.1 requires that "선택 실패(`false`)를 고정 성공으로 표시하지 않는다", and the way
 * to satisfy that is to never hold the answer locally. `CameraViewModel.selectManualLayout`
 * returns whether the **id resolves**, not whether the layout was applied — its own KDoc
 * says so outright ("Callers must not read the return value as 'applied'"), because the
 * state change is deferred onto the analysis thread like every other guide mutation.
 *
 * So [activeManualLayoutId] reads `layoutState`, i.e. what the guide engine actually did.
 * A rejected id produces no `Fixed(MANUAL)`, so nothing lights up — the requirement holds
 * because it cannot be violated, not because a call site remembered to check a boolean.
 *
 * ## Session-only, by not persisting
 *
 * "카메라 세션을 나갔다 돌아오면 기본 자동 탐색으로 복귀한다" needs no reset code either.
 * The selection is not stored here at all: it is derived from a `StateFlow` owned by a
 * `CameraViewModel` that `CameraScreen` creates with `remember`, so leaving the screen
 * disposes it and coming back builds a fresh one that is `Searching`. The requirement is
 * met by the **absence** of `rememberSaveable`, and that is why there is no
 * `selectedFrameId` state anywhere in the screen.
 */
object ManualFrameSelection {

    /**
     * The template the guide is currently holding **because the user picked it**, or null.
     *
     * `AUTO` and `REFERENCE` sources return null on purpose: an automatically resolved
     * layout is not a manual selection, and showing the picker as active for one would
     * tell the user they had chosen something they had not.
     */
    fun activeManualLayoutId(layoutState: GuideLayoutState): String? {
        val fixed = layoutState as? GuideLayoutState.Fixed ?: return null
        return fixed.template.id.takeIf { fixed.source == LayoutSource.MANUAL }
    }

    /** Whether the frame button reads as active — i.e. a manual frame is in charge. */
    fun frameButtonActive(layoutState: GuideLayoutState): Boolean =
        activeManualLayoutId(layoutState) != null

    /**
     * The label for a layout, or **null when there isn't one**.
     *
     * `LayoutTemplateCatalog.displayName` falls back to `else -> id`, and exactly one of
     * the twelve manual layouts has no case: `object_quad_hierarchy_v3`. Rendering that
     * would put a raw template id on a user-facing surface, which R7-1 bans (전문 용어)
     * and which no user can read.
     *
     * Returning null — the cell shows its thumbnail and no caption — is the honest
     * option. The alternatives were worse: printing the id is the bug, and inventing a
     * Korean name here would be P1 writing content for a catalogue 담당 B owns, in a
     * second place, where it would silently outrank theirs once they added one.
     *
     * A single unlabelled cell among eleven labelled ones is *visible*, which is the
     * point. Escalated to the lead 2026-07-30; when the name lands in `displayName` it
     * appears here with no change to this file.
     */
    fun label(summary: LayoutTemplateSummary): String? =
        summary.displayName.takeIf { it != summary.id }
}
