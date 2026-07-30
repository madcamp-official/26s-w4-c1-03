package com.gamdo.app.ui.camera

import com.gamdo.app.guide.GuideLayoutState

/**
 * The two guide vocabularies [CameraOverlay] can speak, and it speaks exactly one
 * per frame.
 *
 * [SITUATION_MARKS] is 상황 우선 가이드 V2's dots/rings/silhouette; [TEMPLATE_SLOTS] is
 * the fixed layout's own rounded slot rectangles. They are alternatives rather than
 * layers because they describe the *same* subject: drawing both puts a circle and a
 * rectangle on one person, which is the "네모와 동그라미가 섞인다" the device report
 * (`docs/P2_실기기_문제조사_2026-07-31.md` §3) opened with.
 */
enum class GuideVocabulary { SITUATION_MARKS, TEMPLATE_SLOTS }

/**
 * Which vocabulary wins for one overlay frame.
 *
 * ## Why this is a decision and not `guideMarks != null`
 *
 * The overlay used to switch on the marks alone, on the stated assumption that "P2는
 * `layoutState is Fixed`인 동안에만 `guideMarks`를 채운다". P2 does *set* them under that
 * condition — `SceneGuideSessionController.updateSituationAndMarks` checks it — but the
 * condition holds at write time only. Nothing clears the marks when the layout leaves
 * `Fixed`, and `Fixed` is also what a **manual** frame selection produces. Two device
 * symptoms follow, and both read to the user as "골라도 전혀 바뀌는 게 없어":
 *
 *  - Picking one of the twelve frames latches `Fixed(MANUAL)` and publishes that
 *    template's slots, and the marks left over from the automatic scene were drawn over
 *    them. All twelve frames therefore looked identical — the same silhouette, in the
 *    same place, because `SceneTechniqueSelector.personMarks` positions it from
 *    `PortraitFramingCatalog` and never from the template the user chose.
 *  - 재탐색 / the sheet's 자동 cell returns the session to `Searching`, and the marks stay
 *    behind: the discarded scene's silhouette hovers over a search that has already
 *    thrown it away.
 *
 * `docs/P1_온디바이스_구도_통합_수정요청_2026-07-31.md` §1 names the first outright —
 * "`GuideMark`가 존재한다는 이유로 고정 슬롯이 사라져 사용자에게 다른 레이아웃처럼 보이지
 * 않게 한다" — and the rule below is that sentence plus the state it did not mention.
 *
 * ## The rule
 *
 * **A layout the user named outranks a situation the app guessed.** `LayoutSource.MANUAL`
 * is the only source that means "the user pointed at this frame in the picker".
 * `AUTO` is the classifier's own reading, and `REFERENCE` is deliberately on the same
 * side of the line: `SceneGuideCoordinator.resolveSearching` documents a reference
 * composition as a *candidate* weighed against the scene (O-13), not a command, so a
 * situation mark outranking it is the same kind of automatic-over-automatic call.
 *
 * Nothing here reduces, merges or reclassifies what P2 returned — the delegation
 * document forbids that outright. When the marks are the vocabulary, **every** mark is
 * drawn; this object only decides whether this frame is one where they speak.
 */
object GuideRenderPriority {

    /**
     * @param hasMarks whether P2 handed this frame any [com.gamdo.app.guide.GuideMark].
     * @return [GuideVocabulary.TEMPLATE_SLOTS] also while `Searching`, where there is no
     *   fixed layout to draw either — the overlay then shows the search spinner and
     *   nothing else, which is what `Searching` is supposed to look like.
     */
    fun vocabulary(layoutState: GuideLayoutState, hasMarks: Boolean): GuideVocabulary = when {
        !hasMarks -> GuideVocabulary.TEMPLATE_SLOTS
        layoutState !is GuideLayoutState.Fixed -> GuideVocabulary.TEMPLATE_SLOTS
        // Reads the same predicate the frame button and the picker's selection ring read,
        // so the amber cell, the amber button and the drawn guide cannot disagree about
        // which layout is in charge.
        ManualFrameSelection.activeManualLayoutId(layoutState) != null -> GuideVocabulary.TEMPLATE_SLOTS
        else -> GuideVocabulary.SITUATION_MARKS
    }

    /** Convenience for the draw path, which asks the question in this direction. */
    fun drawsSituationMarks(layoutState: GuideLayoutState, hasMarks: Boolean): Boolean =
        vocabulary(layoutState, hasMarks) == GuideVocabulary.SITUATION_MARKS
}
