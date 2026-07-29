package com.gamdo.app.guide

/**
 * What a selected reference is offering the guide, reduced to the only thing the
 * choice depends on: **how many brackets it wants, and of what kind**.
 *
 * Deliberately not `List<ReferenceTargetSlot>`. The rule must not be able to reach
 * for a coordinate — the moment it can, "does this reference fit the scene" starts
 * drifting toward "is the user already standing in the right place", which is the
 * behaviour O-13 is removing.
 */
data class ReferenceCompositionOffer(
    val personSlots: Int = 0,
    val objectSlots: Int = 0,
) {
    /** A colour-scope reference offers nothing; so does a reference with no slots. */
    val isEmpty: Boolean get() = personSlots + objectSlots <= 0
}

/** What the scene analyser currently makes of the live frame. */
data class SceneCompositionReading(
    /** The resolver produced a template — the AI has an opinion about this scene. */
    val confirmed: Boolean = false,
    val personDetections: Int = 0,
    val objectDetections: Int = 0,
)

/** Which candidate the overlay should draw. */
enum class GuideCompositionSource {
    /** Nothing is drawable yet — still looking. */
    NONE,

    /** The AI's reading of the live scene. */
    SCENE,

    /** The composition carried by the reference the user picked. */
    REFERENCE,
}

/**
 * O-13 (2) — picks between the AI's reading of the scene and the reference the user
 * brought, once per frame.
 *
 * ## The rule (proposed 2026-07-29 — owner has not ruled on it)
 *
 * > **레퍼런스 구도는 지금 화면에서 채울 수 있을 때만 가이드가 된다.** 채울 수 없으면
 * > AI가 읽은 장면 구도를 쓰고, AI가 아직 아무것도 못 읽었으면 잠깐 기다렸다가
 * > 레퍼런스를 띄운다.
 *
 * Three branches, each rejectable on its own:
 *
 * 1. **채울 수 있을 때만.** A three-cup reference held over an empty desk draws
 *    three brackets nobody can fill. Today that is exactly what happens:
 *    [SceneGuideCoordinator.update] builds `referenceTemplate` out of
 *    `styleTarget.referenceSlots` and latches it into `GuideLayoutState.Fixed`
 *    *before* `autoLayoutResolver` is consulted, and only an explicit 재탐색 can
 *    unstick it. That is a command. O-13 says it is a candidate.
 *
 * 2. **동점은 사용자 편.** When the reference does fit, it beats the scene reading.
 *    The user picked it on purpose; the AI merely noticed something.
 *
 * 3. **AI가 아무것도 못 읽으면 레퍼런스.** On a blank wall `resolve()` returns null
 *    on every frame, forever. Without this branch, picking a reference there would
 *    show nothing at all — worse than the state O-13 is fixing. The wait exists so
 *    that on a scene the AI *can* read, the AI is heard first rather than being
 *    beaten to the latch by a reference that happens to be ready at frame 0.
 *
 * ## What "fits" means, and what it deliberately does not
 *
 * Counts only. A reference fits when the scene holds at least as many people and at
 * least as many objects as it has slots for. A richer scene still fits — two
 * brackets over three cups is a composition, not a mismatch.
 *
 * It does **not** compare positions, sizes or categories. Position is the whole
 * point of showing the guide (the user has not moved yet — of course it does not
 * match), and category matching already happens upstream in `StableSceneTracker`
 * and `AutoLayoutTemplateResolver.choose`, which is where the semantic thresholds
 * and their config live. Re-deciding it here would put two different answers to the
 * same question in the tree.
 *
 * Pure (`android.*` = 0) so the rule can be re-argued from a test run rather than
 * from a phone pointed at a table.
 */
object GuideCompositionChoice {

    /**
     * @param reference what the selected reference offers, or null when none is
     *   selected. An [ReferenceCompositionOffer.isEmpty] offer counts as null —
     *   a colour-scope reference must not win by fitting trivially.
     * @param scene the analyser's current reading.
     * @param framesWithoutScene consecutive analysed frames with no confirmed
     *   scene. Reset whenever the scene confirms or the user rescans.
     * @param referenceGraceFrames how long the AI is heard first. Externalised —
     *   `objectGuide.referenceGraceFrames` in `guide_config.json` — because it is a
     *   feel value, and 0 (show the reference the instant it is picked) is a
     *   legitimate owner answer that must not need a code change.
     */
    fun choose(
        reference: ReferenceCompositionOffer?,
        scene: SceneCompositionReading,
        framesWithoutScene: Int,
        referenceGraceFrames: Int,
    ): GuideCompositionSource {
        val offer = reference?.takeUnless { it.isEmpty }

        if (offer == null) {
            return if (scene.confirmed) GuideCompositionSource.SCENE else GuideCompositionSource.NONE
        }

        if (scene.confirmed) {
            val fits = offer.personSlots <= scene.personDetections &&
                offer.objectSlots <= scene.objectDetections
            return if (fits) GuideCompositionSource.REFERENCE else GuideCompositionSource.SCENE
        }

        return if (framesWithoutScene >= referenceGraceFrames) {
            GuideCompositionSource.REFERENCE
        } else {
            GuideCompositionSource.NONE
        }
    }
}
