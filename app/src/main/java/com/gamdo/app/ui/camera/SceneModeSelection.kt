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
     * A person slot at or under this share of the frame's area is a **distant figure**:
     * the background around it is most of the photograph, which is the statement 배경
     * 강조 인물 and 여행·풍경 both make. Above it, the person is the subject.
     *
     * The catalogue falls either side with room to spare: the 원경/여행 person slots
     * have area 0.08–0.11, and the smallest subject-person slot (전신 비대칭, 인물과
     * 소품) has ≈ 0.31 — so 0.20 is not sitting on a boundary a rounding error can
     * cross. It replaced a height threshold (0.78) when the small-figure frames landed:
     * height separated 전신 from 상반신, but a distant figure and a full-body portrait
     * are *both* head-to-feet, and area is what actually differs between them.
     *
     * A property of the *shape*, because a shape is all a [LayoutTemplateSummary] can be
     * asked about. There is no "environmental" flag to read, and inventing one would mean
     * editing the catalogue, which 담당 B owns.
     */
    private const val DISTANT_PERSON_MAX_AREA = 0.20f

    /**
     * A lone object slot at or above this area reads as scenery or architecture — a
     * landmark — rather than a tabletop subject. The catalogue's landmark slot has area
     * 0.25 and its largest tabletop slot ≈ 0.12, so the constant sits in a real gap.
     * It routes the one-object frames: landmark-sized to 여행·풍경, the rest to 정물·소품.
     */
    private const val LANDMARK_MIN_AREA = 0.16f

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
     * Every rule is read off the summary's own slots — roles, count, and slot area.
     * Nothing here names a template id: the catalogue keeps old ids as aliases and
     * `ManualFrameSelectionTest` bans id literals under `ui/camera/` for exactly that
     * reason, and a rule made of shapes keeps working when 담당 B adds another frame.
     *
     * The 2026-07-31 owner instruction redrew this table: 배경 강조 인물 used to be the
     * full-body subset of 인물 ("인물이랑 배경 강조 인물이 같아") and 여행·풍경 had no
     * frame of its own. Both now key on **slot area** — whether the person (or lone
     * object) is the subject or a small figure inside a dominant background — which is
     * the distinction those chips were always trying to make.
     *
     *  - **자동** — everything. The app has not been told what the scene is, so it has
     *    no grounds to withhold any frame. This is also the previous behaviour, kept.
     *  - **인물** — frames where a person slot is large enough to be the subject
     *    (area > [DISTANT_PERSON_MAX_AREA]): the four 전신, 상반신, 앉은 인물, and
     *    인물과 소품. The 원경 figures are deliberately *not* here — a distant speck is
     *    not what someone tapping 인물 is composing.
     *  - **배경 강조 인물** — frames whose only content is a **small** person (every
     *    person slot ≤ [DISTANT_PERSON_MAX_AREA], no objects). The person is placed;
     *    everything around them — most of the frame — is the background the chip is
     *    named after. Disjoint from 인물 by construction, which is the owner's fix.
     *  - **여행·풍경** — a small person *with* a place-sized companion slot (landmark,
     *    distant feature), or a lone object big enough to *be* the place
     *    (≥ [LANDMARK_MIN_AREA]). These are the travel-authored frames and nothing else
     *    reaches the rule, so the chip finally has its own row.
     *  - **카페·음식** — object frames built for two or more subjects. A café table is
     *    a drink and a plate, or three or four of them; the interesting decision there
     *    is the arrangement, which is what these frames differ by.
     *  - **정물·소품** — object frames for one or two tabletop-sized subjects
     *    (< [LANDMARK_MIN_AREA]). A still life is a small deliberate arrangement, and
     *    both the four-object flat-lays and the landmark frame are the opposite of it.
     *
     * The object modes still overlap on the two-object frames. That is deliberate: a
     * frame that genuinely suits two situations should appear in both, and forcing the
     * six lists to be disjoint would mean hiding 음료와 접시 대각 from one of the two
     * chips it honestly serves.
     *
     * ## Two things this can never do
     *
     * **Return an empty list.** The owner's hard requirement. Two independent guarantees,
     * because one of them is about a catalogue this file does not own: the rules above
     * leave every mode non-empty for the shipped catalogue (pinned by
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
        val maxPersonArea = summary.slots
            .filter { it.role == SlotRole.PERSON }
            .maxOfOrNull { it.bounds.width * it.bounds.height } ?: 0f
        val maxObjectArea = summary.slots
            .filter { it.role == SlotRole.OBJECT }
            .maxOfOrNull { it.bounds.width * it.bounds.height } ?: 0f
        // "Distant" is a statement about every person in the frame: one large person
        // slot makes the person the subject no matter how many small ones sit beside it.
        val distantPerson = people > 0 && maxPersonArea <= DISTANT_PERSON_MAX_AREA
        return when (mode) {
            CaptureSceneMode.AUTO -> true
            CaptureSceneMode.PORTRAIT -> people > 0 && !distantPerson
            CaptureSceneMode.ENVIRONMENTAL_PORTRAIT -> distantPerson && objects == 0
            CaptureSceneMode.TRAVEL_LANDSCAPE ->
                (distantPerson && objects > 0) ||
                    (people == 0 && objects == 1 && maxObjectArea >= LANDMARK_MIN_AREA)
            CaptureSceneMode.CAFE_FOOD -> people == 0 && objects >= 2
            CaptureSceneMode.STILL_LIFE ->
                people == 0 && objects in 1..2 && maxObjectArea < LANDMARK_MIN_AREA
        }
    }
}
