package com.gamdo.app.ui.result

import com.gamdo.app.data.FilterRenderState
import com.gamdo.app.data.ResultFilterItem
import com.gamdo.app.data.ResultFilterKind
import com.gamdo.app.data.ResultFilterState
import com.gamdo.app.data.ResultFilterStateHolder
import com.gamdo.app.edit.LocalFilter
import com.gamdo.app.ui.reference.ReferenceLabels

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

/**
 * The preset an app capture opens on (**O-15 (1)**).
 *
 * The screen used to read `settingsRepository.getStylePresetId()` — the preset
 * picked during onboarding. That was right while a preset meant *guide*: a
 * mid-session camera pick configured one shot and had no business outliving it
 * (TEAM.md §8 records that as deliberate).
 *
 * O-13 changed what a preset is. It is **colour** now, and colour belongs to the
 * photograph. Shooting with 밤거리 on screen and opening the result in 깔끔한 소셜
 * is therefore a defect rather than a policy — and O-14's preview colour turns it
 * from a fact into something the user *watches* happen, because they will have
 * framed the shot in 밤거리 before pressing the shutter.
 *
 * Nothing new is stored: `CameraScreen` already writes `stylePresetId = preset.id`
 * into `sessions` at capture time. This only reads the right row.
 *
 * [sessionPresetId] is blank-tolerant because both it and [profilePresetId] come
 * from nullable text columns, and a row that stored `""` is stating absence rather
 * than naming a preset called empty string.
 *
 * Device photos never reach here — `opensOnPreferredStyle` keeps them on 원본
 * (O-12), which is a different question with a different answer.
 */
fun openingPresetId(sessionPresetId: String?, profilePresetId: String?): String? =
    sessionPresetId?.takeIf { it.isNotBlank() } ?: profilePresetId?.takeIf { it.isNotBlank() }

// ---- P1-B2: one filter catalogue, one selection -----------------------------
//
// The result screen used to own two private copies of what the strip shows:
// the item list came from a `ResolvedStyle?` parameter held in a `remember` at
// the nav host, and the selection from a `remember` inside the screen itself.
// Both die with their composition, so an Activity recreation dropped the
// `내 감도` slot and a trip to the album dropped the selection — while
// `ResultFilterStateHolder`, which lives on `AppContainer` and therefore
// outlives both, had the right answer and no reader.
//
// The holder is now the store. These functions are the *rules* that sit on top
// of it: which id the screen puts there when a photo opens, and what each id
// means to the pipeline. They take and return plain ids so they stay on the
// JVM side of `ResultFlowDecisions`'s platform-free rule.

/**
 * The [LocalFilter] a strip id names, or null for the `내 감도` slot and for any
 * id this build does not recognise.
 *
 * Unknown ids are reachable rather than theoretical: [ResultFilterStateHolder]
 * is P2-owned and may grow items, and `sessions.style_preset_id` can hold the id
 * of a preset a later build removed.
 */
fun presetFilterFor(filterId: String): LocalFilter? =
    if (filterId == ResultFilterStateHolder.REFERENCE_FILTER_ID) {
        null
    } else {
        LocalFilter.entries.firstOrNull { it.filter.id == filterId }
    }

/**
 * Which recipe `QuickFilterEditor` runs for a strip id.
 *
 * The reference slot rides on `ORIGINAL`'s identity recipe — its colour is folded
 * into the *plan* by `LocalEditor`, not applied a second time here (see
 * `ResultScreen`'s `corrected`). An unrecognised id lands on the same identity
 * recipe, which shows the photo rather than a look picked by accident.
 */
fun localFilterFor(filterId: String): LocalFilter =
    presetFilterFor(filterId) ?: LocalFilter.ORIGINAL

/**
 * [StripSelection] for a strip id, so [stylePickFor] can be fed straight from
 * [ResultFilterState.selectedId].
 *
 * An unrecognised id resolves to [StripSelection.ORIGINAL], never to
 * [StripSelection.PRESET]. The difference is not cosmetic: `PRESET` switches the
 * geometry and optical passes **on** for a device photo (see [correctionPassesFor]),
 * so guessing wrong here would crop and re-expose someone's library photo under a
 * label the screen could not even name.
 */
fun stripSelectionFor(filterId: String): StripSelection = when {
    filterId == ResultFilterStateHolder.REFERENCE_FILTER_ID -> StripSelection.REFERENCE
    presetFilterFor(filterId).let { it == null || it == LocalFilter.ORIGINAL } -> StripSelection.ORIGINAL
    else -> StripSelection.PRESET
}

/**
 * The word the strip and the on-photo badge put under a catalogue item.
 *
 * Presets keep P2's own [ResultFilterItem.displayName] — it is `PhotoFilter.label`,
 * the same 깔끔한 소셜 / 밤거리 the camera shows. The reference slot does not: P2
 * names it 내 감도, which is the word the leading `+` already uses for *creating*
 * one, and P1-B3 forbids the two reading the same. [ReferenceLabels] settles it for
 * both screens.
 */
fun stripLabelFor(item: ResultFilterItem): String = when (item.kind) {
    ResultFilterKind.REFERENCE -> ReferenceLabels.ACTIVE
    ResultFilterKind.ORIGINAL, ResultFilterKind.PRESET -> item.displayName
}

/** Whether the catalogue is currently offering the `내 감도` slot. */
fun hasReferenceSlot(state: ResultFilterState): Boolean =
    state.items.any { it.kind == ResultFilterKind.REFERENCE }

/**
 * The word the badge over the photo puts on the look **that is actually on the
 * pixels being displayed** — P1-B3's "활성 감도 이름 … 을 표시해 무엇이 적용됐는지 알
 * 수 있게 한다".
 *
 * [stripLabelFor] answers a different question: what the *selected* item is called.
 * For the strip that is the right question — the highlighted thumb is a control, and
 * a control names itself. For the badge it is not, because the badge is a claim about
 * the photograph, and the selection and the photograph can disagree:
 *
 *  - A preset's colour is applied by `QuickFilterEditor` in `ResultScreen`'s preview
 *    loop. When that pass throws, the loop publishes null, the screen falls back to
 *    the untouched `source` bitmap, and the strip deliberately keeps all seven items
 *    (P1-B2: "렌더 실패 시 현재 사진의 원본을 표시하고 필터 목록은 유지한다"). So the
 *    user is looking at an un-styled photo with 밤거리 written across it. The strip is
 *    right — 밤거리 is still what a tap would select — and the badge is lying.
 *  - The reference slot is the opposite case and must not be swept up in the same
 *    rule. Its colour is folded into `LocalEditor`'s plan and rendered into `source`;
 *    the strip recipe for it is `ORIGINAL`, an identity pass. That pass failing takes
 *    nothing off the photo, so the badge keeps saying 내 감도. What *would* remove it
 *    is the plan itself failing, which `ResultScreen` reports as [referenceColorLanded].
 *
 * The fallback word is 원본, which is not a new string and not a euphemism: it is the
 * strip item that means "no look", and on an app capture it already denotes a photo
 * that has been levelled and exposed but given no colour (see [correctionPassesFor],
 * where `APP_CAPTURE` runs geometry and optical under every pick). That is exactly the
 * state a failed style pass leaves behind.
 *
 * @param referenceColorLanded whether `LocalEditor`'s style stage produced a plan.
 *   Only consulted for the reference slot; presets never route their colour through it.
 */
fun appliedStyleLabel(state: ResultFilterState, referenceColorLanded: Boolean): String {
    val item = state.items.firstOrNull { it.id == state.selectedId }
        ?: return LocalFilter.ORIGINAL.label
    val applied = when (item.kind) {
        ResultFilterKind.REFERENCE -> referenceColorLanded
        ResultFilterKind.PRESET -> !renderFailedForSelection(state)
        // 원본 is the absence of a look, so there is nothing a failed pass could
        // have removed — it reads 원본 either way.
        ResultFilterKind.ORIGINAL -> true
    }
    return if (applied) stripLabelFor(item) else LocalFilter.ORIGINAL.label
}

/**
 * Whether the strip's own render is known to have failed *for what is selected now*.
 *
 * Keyed on the id rather than on `is Failed` alone because the holder keeps one
 * render field for the whole catalogue: a failure recorded while 밤거리 was selected
 * says nothing about 필름, and treating it as though it did would put 원본 on a photo
 * that has 필름 correctly rendered onto it.
 */
private fun renderFailedForSelection(state: ResultFilterState): Boolean =
    (state.renderState as? FilterRenderState.Failed)?.filterId == state.selectedId

/** What `capture_edit_stack.paramsJson` records as the filter, for a strip id. */
fun editRecordFilterName(filterId: String): String =
    if (filterId == ResultFilterStateHolder.REFERENCE_FILTER_ID) {
        "REFERENCE"
    } else {
        localFilterFor(filterId).name
    }

/**
 * Whether an active 내 감도 outranks the preset the shot was framed in, when an app
 * capture opens. **It does not** — owner decision, 2026-07-30.
 *
 * This constant exists because the answer is genuinely contested and the losing
 * side is written down in a contract. `docs/P2_실기기_기능수정기록_2026-07-29.md`'s
 * P2-B3 fixes the priority as `활성 레퍼런스 색감 > 세션 프리셋 > 온보딩 추천`, and
 * P2's own [ResultFilterState.recommendedDefaultFilterId] computes exactly that. So
 * anyone reading P2's side will conclude this screen is wrong and "fix" it.
 *
 * They should not, and the reason is O-13 and O-14 rather than anything about
 * references. A preset is **colour** now, and colour is a property of the
 * photograph; O-14 put that colour into the live preview, so the user *watches* the
 * look while framing and presses the shutter on what they can see. Opening that
 * photo in a different colour than the one it was taken in is a defect no matter
 * how good the other colour is. The reference is one tap away and always was.
 *
 * O-15 is the shipped, device-verified behaviour this preserves. Device photos are
 * outside the question entirely — O-12 keeps them on 원본 either way.
 *
 * Both branches are pinned by `ResultFilterSelectionTest`, so if the decision is
 * ever revisited, flipping this constant is the whole change.
 */
const val ACTIVE_REFERENCE_OPENS_APP_CAPTURES = false

/**
 * The strip item a freshly opened photo selects.
 *
 * - [EditSourceKind.DEVICE_PHOTO] opens on 원본. **O-12**, and it does not depend on
 *   [hasActiveReferenceColor]: an active reference is still a look the user did not
 *   pick *for this photo*, and applying it to something out of their own library is
 *   the exact thing O-12 exists to prevent.
 * - [EditSourceKind.APP_CAPTURE] opens on O-15's preset — the one on screen when the
 *   shutter was pressed, else the onboarding profile's, else 원본 — unless
 *   [ACTIVE_REFERENCE_OPENS_APP_CAPTURES] says the reference wins.
 */
fun openingFilterId(
    source: EditSourceKind,
    hasActiveReferenceColor: Boolean,
    sessionPresetId: String?,
    profilePresetId: String?,
    referenceFirst: Boolean = ACTIVE_REFERENCE_OPENS_APP_CAPTURES,
): String = when (source) {
    EditSourceKind.DEVICE_PHOTO -> LocalFilter.ORIGINAL.filter.id
    EditSourceKind.APP_CAPTURE ->
        if (referenceFirst && hasActiveReferenceColor) {
            ResultFilterStateHolder.REFERENCE_FILTER_ID
        } else {
            LocalFilter.forPresetId(openingPresetId(sessionPresetId, profilePresetId)).filter.id
        }
}

/**
 * What the strip should be sitting on right now — the whole rule, in one value.
 *
 * Two failures this closes, both of which arrive as a *late* recomposition rather
 * than as a user action:
 *
 *  - The screen resolves its opening preset asynchronously (`captures` row →
 *    `sessions` row → `style_preset_id`), so the answer changes at least once after
 *    the first frame. The old effect re-seeded the selection unconditionally each
 *    time it changed, so a filter tapped inside that window was silently undone.
 *    [userHasChosen] makes a tap win over every later re-resolution.
 *  - [ResultFilterState.selectedId] is app-scoped now, so without a per-photo reset
 *    the 밤거리 left over from an app capture would carry into the next photo the
 *    user opens — and if that is a device photo, O-12 has been broken by a look
 *    nobody picked for it. Opening always re-answers from [openingFilterId].
 *
 * @param currentSelectedId what the holder has, used only once the user owns it.
 */
fun selectionOnOpen(
    source: EditSourceKind,
    userHasChosen: Boolean,
    currentSelectedId: String,
    hasActiveReferenceColor: Boolean,
    sessionPresetId: String?,
    profilePresetId: String?,
): String = if (userHasChosen) {
    currentSelectedId
} else {
    openingFilterId(source, hasActiveReferenceColor, sessionPresetId, profilePresetId)
}

/**
 * The strip item `내 감도로 정리하기` applies — AI 3's one recommendation that never
 * leaves the phone.
 *
 * Until now the card was wired to a handler that closed the sheet, reset the
 * controller and changed nothing else: no bitmap was produced, and the user who
 * tapped a card promising to tidy their photo got the identical photo back. That is
 * 결함 2 of `docs/P1_전체기능_사용자시나리오_테스트·시연개선요청_2026-07-30.md` §13,
 * and its §8 restates the requirement as 실행 즉시 전후 변화·저장 가능.
 *
 * The fix is a *selection*, not a second rendering path. Moving the strip is what
 * the preview loop and `performSave` both already key on, so one write puts the
 * styled pixels on screen and makes the header's 저장 write them at full size — no
 * new editor call, no server job.
 *
 * Which item: [ResultFilterState.recommendedDefaultFilterId], the holder's own
 * answer to 활성 레퍼런스 색감 → 촬영 세션 스타일 → 온보딩 추천 → 원본. `내 감도` is
 * that chain, not a synonym for the reference slot, so a user who never analysed a
 * reference photo still gets their own look rather than a dead tap. It is also the
 * field the brief's §7 lists as `코어 구현, P1 연결 필요` — this is the consumer it
 * was asking for.
 *
 * The holder validates the id against its own catalogue every time it sets this
 * ([ResultFilterStateHolder.setRecommendedDefault]), so the result is always
 * selectable and the caller does not need a membership check of its own.
 */
fun localStyleFilterId(state: ResultFilterState): String = state.recommendedDefaultFilterId

/**
 * Whether tapping `내 감도로 정리하기` would actually change the photograph.
 *
 * False when the chain already resolved to the item the strip is sitting on — an app
 * capture opens on its session preset ([ACTIVE_REFERENCE_OPENS_APP_CAPTURES]), so a
 * user with no reference photo is *already* looking at their 감도. Applying it again
 * is a correct no-op, which is exactly the silence the defect reports.
 *
 * The caller uses this to say which of the two it is rather than to hide the card:
 * "이미 적용돼 있다" is information, and a card that vanishes when the state is fine
 * reads as a bug of its own.
 */
fun localStyleChangesPhoto(state: ResultFilterState): Boolean =
    localStyleFilterId(state) != state.selectedId
