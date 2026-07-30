package com.gamdo.app.ui.camera

import com.gamdo.app.guide.SlotVisualKind

/**
 * How a fixed-layout slot is drawn — the decision the renderer was **discarding**.
 *
 * 담당 B publishes [SlotVisualKind] on every `LayoutSlot`, and `CameraOverlay` drew
 * every slot as the same rounded rectangle plus corner bracket. So a template meaning
 * "a person stands here, and a cup sits there" rendered as two identical boxes, and
 * `PERSON_SILHOUETTE` was carried through the whole guide chain to be thrown away in
 * the last five lines (`docs/P2_P1_필수기능연결_요구사항_2026-07-30.md` §3.3).
 *
 * ## Why three styles and not five
 *
 * The requirement groups the object kinds itself: "`GENERIC_OBJECT`, `CUP`, `PLATE`:
 * 일반 객체 목표로 처리한다". So the distinction that must survive is **person vs
 * object**, and inventing a cup pictogram and a plate pictogram would be adding
 * meaning P2 did not ask to express — with the specific risk that a drawn cup reads as
 * "put a cup here", i.e. an instruction, which is what R7-2 and D2-1 keep off this
 * screen.
 *
 * The two person kinds stay apart because P2 distinguishes them and the difference is
 * about how much the guide claims to know: a silhouette states a body's shape and
 * where the feet go, a bracket states only the region. Flattening them would discard a
 * distinction the same way this file exists to stop.
 *
 * ## What this must not become
 *
 * D2 still holds in full: no pose dots or bars, no alignment score, no auto-capture.
 * The silhouette drawn for [PERSON_SILHOUETTE] is the one §3-2 already names
 * ("실루엣(발 위치 마커)") and which the preset guide has always drawn — this reuses
 * that vocabulary rather than introducing a second one.
 *
 * Pure Kotlin, no `android.*`, so the mapping is JVM-testable; the drawing it selects
 * is not, and lives in `CameraOverlay`.
 */
enum class SlotRenderStyle {
    /** A body outline with a foot-position mark on its base edge. */
    PERSON_SILHOUETTE,

    /** Corner marks plus a head-position mark — "a person, somewhere in here". */
    PERSON_BRACKET,

    /** Corner marks alone. What every slot used to get. */
    OBJECT_BRACKET,
    ;

    /** Whether this style says "a person" rather than "a thing". */
    val isPerson: Boolean get() = this != OBJECT_BRACKET

    companion object {
        /**
         * The style for a slot's [SlotVisualKind].
         *
         * Exhaustive `when` with no `else`, deliberately: adding a kind to
         * [SlotVisualKind] should fail to compile here rather than silently fall
         * through to a generic bracket, which is exactly how the current information
         * loss happened.
         */
        fun of(visualKind: SlotVisualKind): SlotRenderStyle = when (visualKind) {
            SlotVisualKind.PERSON_SILHOUETTE -> PERSON_SILHOUETTE
            SlotVisualKind.PERSON_BRACKET -> PERSON_BRACKET
            SlotVisualKind.GENERIC_OBJECT,
            SlotVisualKind.CUP,
            SlotVisualKind.PLATE,
            -> OBJECT_BRACKET
        }
    }
}
