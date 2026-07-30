package com.gamdo.app.ui.camera

import com.gamdo.app.guide.CaptureSceneMode
import com.gamdo.app.guide.SceneModeDecision
import com.gamdo.app.guide.SceneModeSource

/**
 * 상황 우선 가이드 V2의 화면 쪽 판정 — **platform-free**, same rule as
 * [ManualFrameSelection]: no `android.*`, so `testDebugUnitTest` can run it.
 *
 * P2 owns the situation itself (`SceneGuideSessionController.sceneModeDecision`,
 * `selectSceneMode`, `resetSceneMode`); this file decides only what the 구도 패널
 * draws and which chip reads as chosen. Requirement:
 * `docs/P2_P1_필수기능연결_요구사항_2026-07-30.md` §10.
 */
object SceneModeSelection {

    /**
     * The chips, in the order §10 lists them. `AUTO` leads because it is the state the
     * session starts in and returns to, the same position and grammar as the frame
     * sheet's own 자동 cell.
     */
    val chips: List<CaptureSceneMode> = listOf(
        CaptureSceneMode.AUTO,
        CaptureSceneMode.PORTRAIT,
        CaptureSceneMode.ENVIRONMENTAL_PORTRAIT,
        CaptureSceneMode.CAFE_FOOD,
        CaptureSceneMode.TRAVEL_LANDSCAPE,
        CaptureSceneMode.STILL_LIFE,
    )

    /**
     * The word on the chip. §10 gives these six verbatim and they are the user's
     * vocabulary, not the enum's — `ENVIRONMENTAL_PORTRAIT` is 배경 강조 인물, not
     * "environmental portrait", and nothing here exposes a detector's object names.
     */
    fun label(mode: CaptureSceneMode): String = when (mode) {
        CaptureSceneMode.AUTO -> "자동"
        CaptureSceneMode.PORTRAIT -> "인물"
        CaptureSceneMode.ENVIRONMENTAL_PORTRAIT -> "배경 강조 인물"
        CaptureSceneMode.CAFE_FOOD -> "카페·음식"
        CaptureSceneMode.TRAVEL_LANDSCAPE -> "여행·풍경"
        CaptureSceneMode.STILL_LIFE -> "정물·소품"
    }

    /**
     * Which chip is drawn as chosen, given what the controller currently holds.
     *
     * The rule §10 states is "`SceneModeDecision.source == USER`인 동안 Auto 제안이
     * 사용자 선택을 덮어쓰지 않는다", and this is that rule made visible: only a USER
     * decision lights a specific chip. A classifier proposal leaves 자동 lit, because
     * the user did not choose anything and a chip that moves by itself would read as
     * the app having changed their setting.
     *
     * A null decision is also 자동 — the classifier declined to guess (the conservative
     * baseline returns null below its confidence floor), and that is the state the
     * session is genuinely in.
     */
    fun selectedChip(decision: SceneModeDecision?): CaptureSceneMode = when {
        decision == null -> CaptureSceneMode.AUTO
        decision.source == SceneModeSource.USER -> decision.suggested
        else -> CaptureSceneMode.AUTO
    }

    /**
     * Whether the auto classifier's own proposal may be shown as a hint next to 자동.
     *
     * True only while the user has not chosen: once they have, the classifier's
     * opinion is not information, it is noise about a decision already made.
     *
     * The caller renders this **without** the confidence number — §10 forbids
     * confidence, scores and track ids on screen, and a proposal is either worth
     * naming or it is not.
     */
    fun autoHint(decision: SceneModeDecision?): CaptureSceneMode? = decision
        ?.takeIf { it.source == SceneModeSource.AUTO_CLASSIFIER }
        ?.suggested
}
