package com.gamdo.app.ui.result

/**
 * 시안 08's three in-sheet tabs — **how far** the automatic correction is allowed to go.
 *
 * `원본` / `기본 보정` / `무드 보정`, which the owner defined (2026-07-30) as: nothing /
 * 수평·노출 교정 / 거기에 색감까지. Those are the three reachable shapes of
 * [CorrectionPasses], so this enum is a *reading* of that type and not a second copy of
 * the rule — [stageOf] and [narrowedTo] are the only places the two meet.
 *
 * ## This is a different axis from the filter strip
 *
 * The strip answers "which look"; this answers "how much of the pipeline runs". Owner's
 * instruction, verbatim: 스트립은 "어떤 색감", 탭은 "어디까지 적용". 둘을 섞지 마라. So
 * nothing here selects, deselects or renames a strip item.
 *
 * ## O-12 is upstream of this, not replaced by it
 *
 * Every function here **narrows** — it can switch a pass off, never on. What is available
 * to narrow comes from [correctionPassesFor], which is where O-12 lives ("기기 사진은
 * 사용자가 고르기 전까지 원본"). A device photo therefore opens on [NONE] and cannot be
 * moved off it by tapping [MOOD], because the pass that tap would enable was never
 * offered. Making the tabs authoritative instead would have let a tab re-enable
 * auto-exposure on someone's gallery photo — the exact thing O-12 forbids — and it would
 * have looked like a feature.
 */
enum class CorrectionStage(val label: String) {

    /** No correction at all. */
    NONE("원본"),

    /** 수평·노출 교정 — [CorrectionPasses.geometry] and [CorrectionPasses.optical]. */
    BASIC("기본 보정"),

    /** 기본 보정 plus colour — [CorrectionPasses.style] as well. */
    MOOD("무드 보정"),
}

/**
 * Which stage a set of passes amounts to.
 *
 * Ordered most-inclusive first so it stays total without enumerating the eight boolean
 * combinations: `style` implies the two before it in every value
 * [correctionPassesFor] can return, and a hypothetical style-without-geometry would
 * still be reported as [MOOD], which is the truthful answer to "how far did this go".
 */
fun stageOf(passes: CorrectionPasses): CorrectionStage = when {
    passes.style -> CorrectionStage.MOOD
    passes.runsAnyPass -> CorrectionStage.BASIC
    else -> CorrectionStage.NONE
}

/**
 * The stage a freshly opened photo sits on, which is O-12's answer and not a default of
 * this file's choosing: an app capture opens on 기본 보정, a device photo on 원본.
 */
fun initialCorrectionStage(source: EditSourceKind): CorrectionStage =
    stageOf(correctionPassesFor(source, initialStylePick(source)))

/**
 * [this] narrowed to what [stage] permits.
 *
 * The result is always a subset: no pass is ever true here that was not already true in
 * the receiver. [stageOf] of the result can therefore come back *lower* than [stage] —
 * that is not a bug, it is O-12 declining the request, and the tab row reads it back so
 * the user is never left looking at a highlighted tab that did nothing.
 */
fun CorrectionPasses.narrowedTo(stage: CorrectionStage): CorrectionPasses = when (stage) {
    CorrectionStage.NONE -> CorrectionPasses(geometry = false, optical = false, style = false)
    CorrectionStage.BASIC -> copy(style = false)
    CorrectionStage.MOOD -> this
}

/**
 * Whether a stage lets the strip's colour reach the pixels.
 *
 * ## Why this exists separately from [narrowedTo]
 *
 * The six presets are **not** applied by [CorrectionPasses.style]. That pass carries the
 * AI 2 reference's measured colour into the base bitmap; a preset is applied afterwards by
 * `QuickFilterEditor` on top of it. So gating the passes alone would leave 기본 보정 and
 * 무드 보정 pixel-identical for every preset — six of the eight strip items — and the tab
 * would be a control that does nothing in the ordinary case.
 *
 * Returning false here is what makes 기본 보정 mean 기본 보정. **The caller that suppresses
 * the colour must also stop the badge naming it** — P1-B3's rule is that the badge
 * describes the pixels, and a suppressed preset with its name still floating over the
 * photo is precisely the failure P1-B3 was written for.
 */
fun appliesStripColour(stage: CorrectionStage): Boolean = stage == CorrectionStage.MOOD
