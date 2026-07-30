package com.gamdo.app.ui.camera

import com.gamdo.app.guide.CaptureSceneMode
import com.gamdo.app.guide.LayoutTemplateSummary
import com.gamdo.app.guide.SceneModeDecision
import com.gamdo.app.guide.SceneModeSource
import com.gamdo.app.guide.SlotRole

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

    /**
     * A person slot this tall is a **full-body** frame: head and feet are both inside it,
     * so whatever is behind the person is in the picture with them.
     *
     * The catalogue's twelve manual layouts fall either side of it with room to spare —
     * the four 전신 frames span 0.86 of the frame height, 상반신 and 앉은 인물 span 0.75 —
     * so the constant is not sitting on a boundary either group can cross by a rounding
     * error. It is deliberately the *low* end of that gap: if 담당 B ever tightens a 전신
     * frame slightly it stays a 전신 frame, and the failure this direction produces is one
     * extra cell in the row rather than a chip that offers fewer frames than it should.
     *
     * A property of the *shape*, because a shape is all a [LayoutTemplateSummary] can be
     * asked about. There is no "environmental" flag to read, and inventing one would mean
     * editing the catalogue, which 담당 B owns.
     */
    private const val FULL_BODY_MIN_HEIGHT = 0.78f

    /**
     * The frames a situation offers — the owner's 2026-07-31 instruction, "구도 선택 탭에서
     * 목록별로 해당하는 알맞은 구도만 보이도록 하고 슬라이드를 통해서 그 목록의 구도 중에서
     * 알맞은 구도를 선택하게 해".
     *
     * ## What a chip and a frame each mean, now that the chip filters
     *
     * A 상황 is what you are shooting; a 프레임 is where you put it. Before this the chip
     * only re-seeded P2's automatic search, so the twelve frames sat unchanged underneath
     * six buttons that appeared to do nothing to them. The chip now also decides **which
     * frames can hold that subject**, which is the one question the situation can answer
     * about a layout without knowing anything about the scene.
     *
     * ## The mapping, and why each line
     *
     * Every rule is read off the summary's own slots — roles, count, and the person slot's
     * height. Nothing here names a template id: the catalogue keeps old ids as aliases and
     * `ManualFrameSelectionTest` bans id literals under `ui/camera/` for exactly that
     * reason, and a rule made of shapes keeps working when 담당 B adds a thirteenth frame.
     *
     *  - **자동** — all twelve. The app has not been told what the scene is, so it has no
     *    grounds to withhold any frame. This is also the current behaviour, kept.
     *  - **인물** — every frame with a person slot (6). Any of them frames a person; the
     *    choice between 전신 and 상반신 is the user's, and that choice *is* the strip.
     *  - **배경 강조 인물** — the full-body person frames (4). Showing someone head to feet
     *    is what puts the place they are standing in the photograph, and the four that
     *    qualify each keep the person inside ≤0.45 of the frame width, so the majority of
     *    the frame is the background the chip is named after. 상반신 crops the background
     *    out and 앉은 인물 is the widest frame in the catalogue — neither emphasises it.
     *  - **여행·풍경** — the same full-body frames, plus the single-object frame (5). A
     *    travel photograph is a person in a place or one thing that *is* the place; both
     *    of those are compositions the catalogue has, and neither is a table of objects.
     *  - **카페·음식** — object frames built for two or more subjects (5). A café table is
     *    a drink and a plate, or three of them; the interesting decision there is how they
     *    are arranged, which is what those five frames differ by.
     *  - **정물·소품** — object frames for one or two subjects (3). A still life is a small
     *    deliberate arrangement, and the frames for four scattered objects are the
     *    opposite of that.
     *
     * The person modes nest (배경 강조 인물 ⊂ 인물) and the object modes overlap on the
     * two-object frames. That is deliberate: a frame that genuinely suits two situations
     * should appear in both, and forcing the six lists to be disjoint would mean hiding
     * 전신 비대칭 from someone who tapped 인물.
     *
     * ## Two things this can never do
     *
     * **Return an empty list.** The owner's hard requirement. Two independent guarantees,
     * because one of them is about a catalogue this file does not own: the rules above
     * leave every mode non-empty for the shipped twelve (pinned by
     * `SceneModeFrameFilterTest`), and if a future catalogue ever starved a mode the
     * `ifEmpty` below hands back the whole list. A strip with the wrong frames in it is a
     * bad recommendation; a strip with nothing in it is a broken screen.
     *
     * **Hide the frame that is currently drawn.** [activeLayoutId] is kept whatever the
     * filter says, so the strip always contains what the overlay is showing and the amber
     * ring always has a cell to sit on. The alternative — dropping the selection when it
     * stops matching — would have this row silently issue a 재탐색 the user did not ask
     * for, and the selection is not this row's to clear: it lives in the guide engine's
     * `layoutState`. In practice P2's `selectSceneMode` already discards the fixed scene,
     * so tapping a chip leaves [activeLayoutId] null before this list is rebuilt; the
     * clause is the guarantee that a *future* chip which does not reset cannot orphan the
     * ring, not a case the current build reaches.
     *
     * @param layouts the catalogue's list, as handed down by `availableManualLayouts`.
     * @param activeLayoutId from [ManualFrameSelection.activeManualLayoutId] — what the
     *   guide engine is holding, never what this sheet last asked for.
     */
    fun framesFor(
        mode: CaptureSceneMode,
        layouts: List<LayoutTemplateSummary>,
        activeLayoutId: String? = null,
    ): List<LayoutTemplateSummary> = layouts
        .filter { suits(mode, it) || it.id == activeLayoutId }
        .ifEmpty { layouts }

    /**
     * Whether one frame belongs to one situation. See [framesFor] for the reasoning; this
     * is only that reasoning written as arithmetic over the summary's slots.
     */
    private fun suits(mode: CaptureSceneMode, summary: LayoutTemplateSummary): Boolean {
        val people = summary.slots.count { it.role == SlotRole.PERSON }
        val objects = summary.slots.count { it.role == SlotRole.OBJECT }
        val fullBody = summary.slots.any {
            it.role == SlotRole.PERSON && it.bounds.height >= FULL_BODY_MIN_HEIGHT
        }
        return when (mode) {
            CaptureSceneMode.AUTO -> true
            CaptureSceneMode.PORTRAIT -> people > 0
            CaptureSceneMode.ENVIRONMENTAL_PORTRAIT -> fullBody
            CaptureSceneMode.TRAVEL_LANDSCAPE -> fullBody || (people == 0 && objects == 1)
            CaptureSceneMode.CAFE_FOOD -> people == 0 && objects >= 2
            CaptureSceneMode.STILL_LIFE -> people == 0 && objects in 1..2
        }
    }
}
