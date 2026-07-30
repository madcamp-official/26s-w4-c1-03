package com.gamdo.app.ui.reference

import com.gamdo.app.data.ProfileRefinementRepository

/**
 * 취향 더 정교하게 만들기 — the pure half, **platform-free** (zero `android.*`
 * imports), same rule and the same reason as [ReferenceFlowDecisions]: this module
 * has no `androidTest` source set, so anything holding a `Context` or a `Uri` cannot
 * execute under `testDebugUnitTest`.
 *
 * The flow is P2's `ProfileRefinementRepository.refineFromPhotos()` with a screen in
 * front of it (요구사항 2026-07-30, 6bef31b). Nothing here re-implements the
 * refinement — it decides only what the sheet may show and what the user may set in
 * motion, which is the same division `RescueFlowDecisions` draws.
 */

/** Where the refinement flow is. */
sealed interface ProfileRefineState {

    /** Nothing running. The sheet offers the action. */
    data object Idle : ProfileRefineState

    /** Photos are being resolved and merged. [count] is what the user picked. */
    data class Analyzing(val count: Int) : ProfileRefineState

    /** Merged and saved. [count] is how many photos went in. */
    data class Done(val count: Int) : ProfileRefineState

    /**
     * It did not finish. [retryable] is false for the one failure the user cannot
     * fix by trying again — no onboarding profile to refine, which
     * `refineFromPhotos` raises rather than inventing a base to merge into.
     */
    data class Failed(val retryable: Boolean) : ProfileRefineState
}

/** Which part of the detail sheet the refinement block is showing. */
enum class ProfileRefineSection { ACTION, PROGRESS, DONE, FAILED }

fun refineSectionFor(state: ProfileRefineState): ProfileRefineSection = when (state) {
    is ProfileRefineState.Idle -> ProfileRefineSection.ACTION
    is ProfileRefineState.Analyzing -> ProfileRefineSection.PROGRESS
    is ProfileRefineState.Done -> ProfileRefineSection.DONE
    is ProfileRefineState.Failed -> ProfileRefineSection.FAILED
}

/**
 * The most photos one refinement may carry, restated from P2's own constant rather
 * than typed again — 최대 20장 is the repository's bound and the picker has to ask
 * for the same number or the user picks photos that are silently dropped
 * (`refineFromPhotos` does `.take(MAX_PHOTOS)`).
 */
const val MAX_REFINE_PHOTOS: Int = ProfileRefinementRepository.MAX_PHOTOS

/**
 * Whether a pick may be sent.
 *
 * Empty is the only rejection: `refineFromPhotos` requires at least one photo and
 * throws otherwise, and the picker can come back empty because cancelling it is
 * indistinguishable from choosing nothing. Over-long picks are *not* rejected — the
 * repository truncates, and refusing the whole selection because the user was
 * enthusiastic would be worse than using the first twenty.
 */
fun canRefine(pickedCount: Int, state: ProfileRefineState): Boolean =
    pickedCount > 0 && state !is ProfileRefineState.Analyzing

/**
 * What the sheet says once it worked.
 *
 * Deliberately a claim about the *preference*, not about photographs. The refined
 * `GamdoProfileV2` is genuinely written — that part is true and testable — but as of
 * 2026-07-30 nothing on the camera or result screens reads it back, so promising
 * 다음 사진부터 달라져요 would be the same lie 결함 2 was: a control reporting a change
 * the app cannot show. See `docs/P1_브리프응답_결함진단과조치_2026-07-30.md`; when P2
 * connects the profile to the preset ranking this sentence should get stronger, and
 * it is one string in one place so that it can.
 */
fun refineDoneMessage(count: Int): String = "사진 ${count}장을 취향에 반영했어요"

/** What the sheet says when it did not work, by whether trying again could help. */
fun refineFailedMessage(retryable: Boolean): String =
    if (retryable) "지금은 분석하지 못했어요" else "먼저 온보딩에서 감도를 만들어 주세요"
