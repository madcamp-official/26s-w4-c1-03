package com.gamdo.app.ui.reference

import com.gamdo.app.data.ReferenceCreateState
import com.gamdo.app.data.preset.ResolvedStyle

/**
 * AI 2 (내 감도 만들기 / 레퍼런스) — the pure decisions behind the P1 wiring
 * described in `docs/AI2_레퍼런스_통합_계약_2026-07-28.md`'s "P1 연결 요구사항".
 *
 * Kept apart from Compose/Android on purpose: this project has no `androidTest`
 * source set and no Robolectric, so anything touching `Context`, `Uri`, or
 * `android.graphics` cannot execute under `testDebugUnitTest`. Everything here has
 * zero `android.*` imports and runs on the JVM; the Compose glue that calls it
 * (`ReferenceCreateSheet.kt`, the camera/result strip wiring) is DONE-DEVICE only.
 */

// ---- §5-2 scope selection ---------------------------------------------------

/**
 * Which of `구도와 색감 / 구도만 / 색감만` the user may actually pick.
 *
 * Hard contract prohibition: "When the server answers `composition=false`, the
 * app must only allow the COLOR scope — do not invent a layout." So when the
 * analysis carries no composition data, `BOTH` and `COMPOSITION` are not merely
 * disabled — they are not offered at all.
 */
fun selectableReferenceScopes(compositionAvailable: Boolean): List<ResolvedStyle.ReferenceScope> =
    if (compositionAvailable) {
        listOf(
            ResolvedStyle.ReferenceScope.BOTH,
            ResolvedStyle.ReferenceScope.COMPOSITION,
            ResolvedStyle.ReferenceScope.COLOR,
        )
    } else {
        listOf(ResolvedStyle.ReferenceScope.COLOR)
    }

/** The scope pre-selected when the preview first appears — BOTH per the contract, degrading to COLOR. */
fun defaultReferenceScope(compositionAvailable: Boolean): ResolvedStyle.ReferenceScope =
    selectableReferenceScopes(compositionAvailable).first()

// ---- §5-2 camera-preview overlay of the current session's original ---------

/** Contract: "기본 30%, 0~60%". */
const val DEFAULT_REFERENCE_OVERLAY_ALPHA = 0.30f
const val MAX_REFERENCE_OVERLAY_ALPHA = 0.60f

fun clampReferenceOverlayAlpha(value: Float): Float =
    value.coerceIn(0f, MAX_REFERENCE_OVERLAY_ALPHA)

// ---- the two 내 감도 words, in one place -------------------------------------

/**
 * The strip's two reference labels — **P1-B3**: "`내 감도 만들기`와 `현재 내 감도 적용`을
 * 동일한 `+ 내 감도` 문구로 표현하지 않는다".
 *
 * They used to be string literals in three places: inside
 * [com.gamdo.app.ui.reference.CreateReferenceThumb], inside
 * [com.gamdo.app.ui.reference.MyReferenceThumb], and — the collision — inside P2's
 * `ResultFilterStateHolder`, whose catalogue calls the *applied* slot 내 감도, the
 * very word the `+` was using for *making* one. On device that read as `+ 내 감도`
 * and nothing else: the button that creates a 감도 and the 감도 you had just created
 * were the same two words, so a user who made one saw no evidence of it.
 *
 * That also retires 레퍼런스 from the product surface. It was the internal word for
 * this feature — AI 2, `ReferenceRepository`, `ResolvedStyle`, and still the literal
 * `displayName` that `ResolvedStyle.fromReference` stamps on every analysis — and it
 * leaked onto the strip from there. 감도 is what the app is called and what onboarding
 * taught the user, so it is the only one of the two words they have ever been shown.
 *
 * Both strips read from here, so the wording changes in one place for the camera
 * and the result screen at once; [com.gamdo.app.ui.camera.CameraScreen] renders the
 * same two composables and needs no edit of its own.
 *
 * `ResultFilterSelectionTest` pins the property that has to hold whatever the wording
 * turns out to be — the two are never the same string — and `ResultStripLabelTest`
 * pins the settled strings themselves, so a rename is an explicit edit rather than a
 * silent one.
 */
object ReferenceLabels {

    /**
     * The leading `+` slot: start making a 감도 from a photo.
     *
     * A verb, against [ACTIVE]'s possessive. It said `내 감도` — the same words as the
     * slot the finished 감도 lands in — so the control that *makes* one and the thing
     * that *is* one were indistinguishable. Owner decision 2026-07-30, against
     * P1-B3's "「내 감도 만들기」와 「현재 내 감도 적용」을 동일한 문구로 표현하지 않는다".
     */
    const val CREATE = "감도 만들기"

    /**
     * The trailing slot: the 감도 that is active and can be applied to this photo.
     *
     * `내 감도`, not `내 레퍼런스` — 레퍼런스 is our word for it, not the user's, and it
     * appears nowhere else they can see. It also matches what
     * `ResultFilterStateHolder` already calls its reference entry, so the strip and
     * the screen that now takes its labels from the holder cannot drift apart.
     */
    const val ACTIVE = "내 감도"
}

// ---- O-10 filter-strip ordering ---------------------------------------------

/** One slot in a style/filter strip once wrapped with the AI 2 / AI 3 entry points. */
sealed interface StripEntry<out T> {
    /** `+` — always leftmost. Opens `내 필터 만들기` (AI 2). */
    data object CreateReference : StripEntry<Nothing>

    /**
     * `AI로 보정` — result-screen only, immediately right of [CreateReference].
     * This is AI 3's landing slot; wiring its behaviour is out of scope here.
     */
    data object AiRestore : StripEntry<Nothing>

    /** One of the catalogue presets/filters, in existing order. */
    data class Preset<T>(val value: T) : StripEntry<T>

    /** `내 레퍼런스` — trailing, present only while a reference is active. */
    data object MyReference : StripEntry<Nothing>
}

/**
 * O-10 (2026-07-29, 재론 불가): wraps [presets] with the AI 2 / AI 3 entry points
 * in the owner-specified order.
 *
 * - Camera: `[+] [presets…] [내 레퍼런스]?`
 * - Result: `[+] [AI로 보정] [presets…] [내 레퍼런스]?` — pass [includeAiRestore] = true.
 *
 * The trailing slot appears iff [hasActiveReference] — a reference is a single
 * local slot (§ 범위: "로컬의 단일 `내 레퍼런스` 슬롯"), not a list, so this is a
 * presence flag rather than a count.
 */
fun <T> buildFilterStrip(
    presets: List<T>,
    includeAiRestore: Boolean,
    hasActiveReference: Boolean,
): List<StripEntry<T>> = buildList {
    add(StripEntry.CreateReference)
    if (includeAiRestore) add(StripEntry.AiRestore)
    presets.forEach { add(StripEntry.Preset(it)) }
    if (hasActiveReference) add(StripEntry.MyReference)
}

/**
 * §5-2 camera-preview overlay visibility.
 *
 * Deliberately takes [state] and ignores it in the body — the parameter exists
 * so a test can pin the property that matters: no sheet state changes the
 * answer, only [hasActiveReference] and [referenceSelected] do.
 *
 * This guards a real bug: the overlay used to be keyed on "was a photo ever
 * picked this session" (a `Uri` held next to the controller), which a flow that
 * ended without applying — a failed analysis closed, a cancel, dismissing the
 * sheet — never cleared. The sheet correctly returned to Idle and the `내
 * 레퍼런스` strip slot correctly stayed absent (nothing was ever applied), but
 * the picked photo kept ghosting over the live preview anyway, and because no
 * reference slot existed, neither did its `×` — there was no control anywhere
 * that could remove it. The only way out was killing the app.
 *
 * A photo merely picked, awaiting consent, analysing, previewed, or one whose
 * analysis errored, is not a reference — "선택한 사진은 ... 분석하는 데
 * 사용돼요" is a promise about analysis, not about becoming the thing framed
 * against. So the fix is not a new "remove overlay" control (that would be
 * visible UI R1 does not allow, papering over a leak instead of closing it) —
 * it is refusing to let anything but an *active* reference reach this decision
 * at all. The call site now keys the overlay's image on a URI that is only
 * ever set when a reference actually becomes active (mirroring
 * [hasActiveReference]), never on the transient picked-photo URI the sheet
 * itself uses.
 *
 * During a replace-in-progress (picking a new photo while one is already
 * active), this correctly keeps showing the *old* active reference — there is
 * something to frame against right up until the new one replaces it, never the
 * unconfirmed candidate.
 *
 * ## [referenceSelected] — the second half of the gate (owner report 2026-07-31)
 *
 * Owning a 감도 and *shooting with* it are different things, and the overlay
 * belongs to the second. The strip is one-style-at-a-time (see
 * `CameraScreen`'s `referenceSelected`): picking 깔끔한 소셜 or any other preset
 * takes 내 감도 off, the guide target goes back to neutral and the filter
 * button's mood dot goes out — but the translucent photo and its slider stayed
 * on screen anyway, because this gate only asked whether a reference *existed*.
 * The owner's words for it: "필터를 내 감도가 아닌 다른 것으로 바꾸면 반투명
 * 슬라이드바와 레퍼런스 가이드도 당연히 없어져야 해."
 *
 * So the overlay now follows the *selected* style, not the stored one. Both
 * halves of the overlay — the photo and the 투명도 slider — read this one
 * answer, which is why they cannot come apart: `ReferenceOverlayLayer` and
 * `ReferenceOverlayAlphaControl` both render nothing for a null image.
 *
 * **Hiding is not resetting.** P2's §2 requirement is explicit — "필터 선택
 * 상태나 시트 닫힘으로 값을 다시 초기화하지 않는다" — and the shape of this
 * function is what keeps the two apart: it decides *visibility* and has no
 * access to the alpha at all, so no amount of filter switching can move the
 * value. Coming back to 내 감도 finds the user's own 투명도 where they left it.
 * `ReferenceOverlayAlphaTest` holds the other end of that: exactly one line in
 * the whole app writes `overlayAlpha`, and it is the slider's callback.
 */
fun shouldShowReferenceOverlay(
    state: ReferenceCreateState,
    hasActiveReference: Boolean,
    referenceSelected: Boolean,
): Boolean = hasActiveReference && referenceSelected

/**
 * Whether finishing 내 감도 만들기 should make that 감도 the camera's selected
 * style — the companion to [shouldShowReferenceOverlay]'s `referenceSelected`.
 *
 * Gating the overlay on the *selection* creates a hole at the one moment the
 * user most expects to see it: the strip's 내 감도 slot appears unselected the
 * instant it is created, so the sheet would close on 적용됐어요 with a preset
 * still highlighted, the neutral guide target still published, the filter
 * button's mood dot still out, and no overlay. "적용" has to mean applied, so
 * applying selects.
 *
 * @param appliedKey identifies the 감도 that is active *because this session
 *   applied it* — in practice `activeReferenceImageUri.toString()`, which
 *   `GamdoNavHost` sets at the instant `apply()` succeeds and nowhere else.
 *   Null means either no reference or one merely restored from Room on launch,
 *   and a restored one must **not** be selected: that would silently override
 *   the onboarding style the user chose (§6-2) with a 감도 they made days ago.
 * @param lastAutoSelectedKey what this screen has already auto-selected. Held in
 *   `rememberSaveable` state by the caller, and it is the whole reason this is a
 *   function rather than a null-check: "select it once" and "select it on every
 *   recomposition" differ only here, and the second one makes the owner's
 *   "필터를 내 감도가 아닌 다른 것으로 바꾸면 없어져야 해" impossible — every
 *   attempt to pick a preset would be undone on the next frame. It must also
 *   survive a rebuilt composition (album round trip, rotation), or coming back
 *   to the camera would re-select a 감도 the user had deliberately switched off.
 *
 * Known and accepted: re-picking the *same* photo while that photo's 감도 is
 * already the applied one does not re-select it, because the key has not
 * changed. It is a replace that replaces nothing, and paying for it would mean
 * carrying a per-apply counter through the host for a case the user cannot tell
 * apart from having done nothing.
 */
fun shouldAutoSelectReference(appliedKey: String?, lastAutoSelectedKey: String?): Boolean =
    appliedKey != null && appliedKey != lastAutoSelectedKey

// ---- what each ReferenceCreateState renders ---------------------------------

/** Which section of the create-flow sheet is on screen for a given controller state. */
enum class ReferenceSheetSection { HIDDEN, CONSENT, ANALYZING, PREVIEW, APPLIED, ERROR }

fun sheetSectionFor(state: ReferenceCreateState): ReferenceSheetSection = when (state) {
    is ReferenceCreateState.Idle -> ReferenceSheetSection.HIDDEN
    is ReferenceCreateState.AwaitingConsent -> ReferenceSheetSection.CONSENT
    is ReferenceCreateState.Analyzing -> ReferenceSheetSection.ANALYZING
    is ReferenceCreateState.Preview -> ReferenceSheetSection.PREVIEW
    is ReferenceCreateState.Applied -> ReferenceSheetSection.APPLIED
    is ReferenceCreateState.Error -> ReferenceSheetSection.ERROR
}
