package com.gamdo.app.guide

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O-13 (2) — a reference's composition is a **candidate**, not a command.
 *
 * The rule under test is the one proposed to the owner:
 *
 * > 레퍼런스 구도는 **지금 화면에서 채울 수 있을 때만** 가이드가 된다. 채울 수 없으면
 * > AI가 읽은 장면 구도를 쓰고, AI가 아직 아무것도 못 읽었으면 잠깐 기다렸다가
 * > 레퍼런스를 띄운다.
 *
 * It is stated as three branches so each one can be rejected separately:
 *
 *  1. **채울 수 있을 때만** — a 3-cup reference held over an empty desk draws three
 *     brackets nobody can fill. That is the "command" behaviour O-13 names, and it
 *     is what ships today: `SceneGuideCoordinator.update` builds `referenceTemplate`
 *     from `styleTarget.referenceSlots` and latches it before `autoLayoutResolver`
 *     is ever consulted.
 *  2. **동점은 사용자 편** — when the reference *does* fit, it wins over the scene
 *     reading. The user chose it explicitly; the AI did not.
 *  3. **AI가 아무것도 못 읽으면 레퍼런스** — on a blank wall the resolver returns
 *     null forever. Without this branch, selecting a reference there would show
 *     nothing at all, which is worse than the state O-13 is fixing.
 *
 * Pure by construction (`android.*` = 0) so the rule can be re-argued with numbers
 * rather than by pointing a phone at a table.
 */
class GuideCompositionChoiceTest {

    private val grace = 8

    private fun choose(
        reference: ReferenceCompositionOffer?,
        scene: SceneCompositionReading,
        framesWithoutScene: Int = 0,
    ) = GuideCompositionChoice.choose(
        reference = reference,
        scene = scene,
        framesWithoutScene = framesWithoutScene,
        referenceGraceFrames = grace,
    )

    // --- no reference: the AI is the only candidate ---------------------------

    @Test
    fun `without a reference a confirmed scene drives the guide`() {
        val source = choose(
            reference = null,
            scene = SceneCompositionReading(confirmed = true, objectDetections = 2),
        )
        assertEquals(GuideCompositionSource.SCENE, source)
    }

    @Test
    fun `without a reference an unconfirmed scene shows nothing`() {
        val source = choose(reference = null, scene = SceneCompositionReading(confirmed = false))
        assertEquals(GuideCompositionSource.NONE, source)
    }

    @Test
    fun `a colour-only reference offers no slots and is not a candidate`() {
        // ResolvedStyle.ReferenceScope.COLOR already empties referenceSlots; this
        // pins that an empty offer is treated as absent rather than as "fits
        // trivially", which would let a colour-only reference win every time.
        val source = choose(
            reference = ReferenceCompositionOffer(personSlots = 0, objectSlots = 0),
            scene = SceneCompositionReading(confirmed = true, objectDetections = 2),
        )
        assertEquals(GuideCompositionSource.SCENE, source)
    }

    // --- reference vs a confirmed scene ---------------------------------------

    @Test
    fun `a reference the scene can satisfy wins`() {
        val source = choose(
            reference = ReferenceCompositionOffer(personSlots = 1, objectSlots = 1),
            scene = SceneCompositionReading(
                confirmed = true,
                personDetections = 1,
                objectDetections = 1,
            ),
        )
        assertEquals(GuideCompositionSource.REFERENCE, source)
    }

    @Test
    fun `a reference wanting a person the scene has not got loses`() {
        val source = choose(
            reference = ReferenceCompositionOffer(personSlots = 1, objectSlots = 0),
            scene = SceneCompositionReading(
                confirmed = true,
                personDetections = 0,
                objectDetections = 3,
            ),
        )
        assertEquals(GuideCompositionSource.SCENE, source)
    }

    @Test
    fun `a reference wanting more objects than the scene holds loses`() {
        val source = choose(
            reference = ReferenceCompositionOffer(objectSlots = 3),
            scene = SceneCompositionReading(confirmed = true, objectDetections = 2),
        )
        assertEquals(GuideCompositionSource.SCENE, source)
    }

    @Test
    fun `a scene richer than the reference still satisfies it`() {
        // Extra objects are not a mismatch — the reference asks for two brackets and
        // the table has three cups, so two of them can be placed.
        val source = choose(
            reference = ReferenceCompositionOffer(objectSlots = 2),
            scene = SceneCompositionReading(confirmed = true, objectDetections = 3),
        )
        assertEquals(GuideCompositionSource.REFERENCE, source)
    }

    // --- reference while the AI is still searching -----------------------------

    @Test
    fun `the AI gets its grace window before the reference is shown`() {
        val source = choose(
            reference = ReferenceCompositionOffer(objectSlots = 2),
            scene = SceneCompositionReading(confirmed = false),
            framesWithoutScene = grace - 1,
        )
        assertEquals(GuideCompositionSource.NONE, source)
    }

    @Test
    fun `once the grace window expires the reference is the only candidate left`() {
        val source = choose(
            reference = ReferenceCompositionOffer(objectSlots = 2),
            scene = SceneCompositionReading(confirmed = false),
            framesWithoutScene = grace,
        )
        assertEquals(GuideCompositionSource.REFERENCE, source)
    }

    @Test
    fun `a zero grace window shows the reference immediately`() {
        // The owner may want the reference up the instant it is picked. That is a
        // config value, not a code change — pinned so the tuning knob stays real.
        val source = GuideCompositionChoice.choose(
            reference = ReferenceCompositionOffer(objectSlots = 1),
            scene = SceneCompositionReading(confirmed = false),
            framesWithoutScene = 0,
            referenceGraceFrames = 0,
        )
        assertEquals(GuideCompositionSource.REFERENCE, source)
    }

    // --- the property O-13 (1) is really about --------------------------------

    @Test
    fun `the rule has no preset input at all`() {
        // O-13 (1): a preset is colour. If a StylePreset ever became an argument
        // here, "필터가 구도를 바꾼다" would be back with a nicer name.
        val parameterTypes = GuideCompositionChoice::class.java.methods
            .first { it.name == "choose" }
            .parameterTypes
            .map { it.name }
        assertEquals(
            listOf(
                ReferenceCompositionOffer::class.java.name,
                SceneCompositionReading::class.java.name,
                Int::class.javaPrimitiveType!!.name,
                Int::class.javaPrimitiveType!!.name,
            ),
            parameterTypes,
        )
    }
}
