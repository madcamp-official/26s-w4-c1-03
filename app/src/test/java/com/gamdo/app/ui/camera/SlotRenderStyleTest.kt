package com.gamdo.app.ui.camera

import com.gamdo.app.guide.FixedLayoutGuide
import com.gamdo.app.guide.LayoutTemplateCatalog
import com.gamdo.app.guide.SlotRole
import com.gamdo.app.guide.SlotVisualKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixed-layout slots must keep 담당 B's kind
 * (`docs/P2_P1_필수기능연결_요구사항_2026-07-30.md` §3.3).
 *
 * The defect these guard: `CameraOverlay` drew every slot as the same rounded
 * rectangle plus corner bracket, so `PERSON_SILHOUETTE` travelled the whole guide chain
 * and was discarded in the last five lines. A template meaning "a person stands here
 * and a cup sits there" rendered as two identical boxes.
 */
class SlotRenderStyleTest {

    // ---- the mapping ------------------------------------------------------------

    @Test
    fun `person kinds stay apart from each other`() {
        assertEquals(
            SlotRenderStyle.PERSON_SILHOUETTE,
            SlotRenderStyle.of(SlotVisualKind.PERSON_SILHOUETTE),
        )
        assertEquals(
            SlotRenderStyle.PERSON_BRACKET,
            SlotRenderStyle.of(SlotVisualKind.PERSON_BRACKET),
        )
    }

    /**
     * The requirement groups these itself — "`GENERIC_OBJECT`, `CUP`, `PLATE`: 일반 객체
     * 목표로 처리한다" — so a cup pictogram would be meaning P2 did not ask to express,
     * with the specific risk that a drawn cup reads as "put a cup here", i.e. an
     * instruction (R7-2, D2-1).
     */
    @Test
    fun `every object kind is one object style`() {
        for (kind in listOf(SlotVisualKind.GENERIC_OBJECT, SlotVisualKind.CUP, SlotVisualKind.PLATE)) {
            assertEquals(
                "$kind is a general object target, per §3.3",
                SlotRenderStyle.OBJECT_BRACKET,
                SlotRenderStyle.of(kind),
            )
        }
    }

    @Test
    fun `every visual kind has a style and no kind is unhandled`() {
        for (kind in SlotVisualKind.entries) {
            val style = SlotRenderStyle.of(kind)
            assertEquals(
                "$kind's person-ness must survive the mapping",
                kind == SlotVisualKind.PERSON_SILHOUETTE || kind == SlotVisualKind.PERSON_BRACKET,
                style.isPerson,
            )
        }
    }

    /**
     * The whole point: a person slot and an object slot must not render the same. Stated
     * as "the two groups share no style" rather than by listing pairs, so it holds if a
     * kind is added.
     */
    @Test
    fun `no style serves both a person and an object`() {
        val personStyles = SlotVisualKind.entries
            .filter { SlotRenderStyle.of(it).isPerson }
            .map { SlotRenderStyle.of(it) }
            .toSet()
        val objectStyles = SlotVisualKind.entries
            .filterNot { SlotRenderStyle.of(it).isPerson }
            .map { SlotRenderStyle.of(it) }
            .toSet()
        assertTrue("person kinds must map to something", personStyles.isNotEmpty())
        assertTrue("object kinds must map to something", objectStyles.isNotEmpty())
        assertEquals(
            "a style shared across the divide is the information loss this file fixes",
            emptySet<SlotRenderStyle>(),
            personStyles intersect objectStyles,
        )
    }

    // ---- against the real catalogue ---------------------------------------------

    /**
     * Not a hypothetical: the shipped templates really do mix the kinds, so the flattened
     * renderer really was losing information on real input. This is the case §3.3 names
     * outright ("인물 1명과 물체가 함께 선택되면").
     *
     * Reached through [LayoutTemplateCatalog.PERSON_OBJECT] rather than a literal. A first
     * draft wrote `"person_object"` and failed — the shipped id is `person_object_v2`,
     * because the catalogue keeps the old ids as compatibility aliases. Hardcoding it
     * would have made this test a second copy of a name 담당 B owns, which is the same
     * mistake §3.1 forbids for the layout *list*.
     */
    @Test
    fun `the shipped person-plus-object template renders two different styles`() {
        val template = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.PERSON_OBJECT)
        assertTrue(
            "${LayoutTemplateCatalog.PERSON_OBJECT} must exist in the catalogue",
            template != null,
        )
        val styles = template!!.slots.map { SlotRenderStyle.of(it.visualKind) }
        assertEquals("it has a person slot and an object slot", 2, styles.size)
        assertEquals("the two must not draw the same", 2, styles.toSet().size)
        assertTrue(styles.any { it.isPerson })
        assertTrue(styles.any { !it.isPerson })
    }

    /**
     * **Where the two person styles and the mixed case can actually be reached from.**
     *
     * Characterisation, not a wish — and it was a surprise. A first draft asserted that
     * *some manual layout* mixes a person with an object; it failed, and the failure is a
     * fact about the catalogue rather than a bug in the mapping:
     *
     *  - the 12 manual layouts are 6 person templates and 6 object templates, and **no
     *    manual template mixes the two**;
     *  - every manual person template is `PERSON_BRACKET` (they all come from
     *    `portraitFrame`), so **`PERSON_SILHOUETTE` is not reachable from the manual
     *    list at all**. It lives in `person_object_v2` and the legacy `portrait_person`,
     *    both of which arrive through *automatic* scene resolution.
     *
     * That matters for verification, which is why it is written down here instead of
     * being noticed on a device: the silhouette and the mixed case cannot be checked by
     * opening the frame picker. They need a scene where the detector finds a person and
     * an object together.
     *
     * If 담당 B adds a mixed template to `manualIds`, this test fails — and that is the
     * right outcome, because it would make the picker reach a case this note says it
     * cannot.
     */
    @Test
    fun `the manual list is person-only or object-only per template`() {
        val mixed = LayoutTemplateCatalog.manualSummaries.filter { summary ->
            summary.slots.map { SlotRenderStyle.of(it.visualKind).isPerson }.toSet().size > 1
        }
        assertEquals(
            "a manual template now mixes person and object slots. That is fine — but it " +
                "changes what the frame picker can reach, so update this test's note " +
                "rather than deleting it.",
            emptyList<String>(),
            mixed.map { it.id },
        )
        val manualStyles = LayoutTemplateCatalog.manualSummaries
            .flatMap { it.slots }
            .map { SlotRenderStyle.of(it.visualKind) }
            .toSet()
        assertFalse(
            "PERSON_SILHOUETTE is not reachable from the manual list — verify it through " +
                "an automatic person+object scene, not the picker",
            SlotRenderStyle.PERSON_SILHOUETTE in manualStyles,
        )
        assertTrue(
            "the manual list must still reach both a person style and an object style",
            SlotRenderStyle.PERSON_BRACKET in manualStyles &&
                SlotRenderStyle.OBJECT_BRACKET in manualStyles,
        )
    }

    @Test
    fun `every manual layout's person slots map to a person style`() {
        val summaries = LayoutTemplateCatalog.manualSummaries
        assertTrue("the manual catalogue must not be empty", summaries.isNotEmpty())
        var personSlots = 0
        for (summary in summaries) {
            for (slot in summary.slots) {
                val style = SlotRenderStyle.of(slot.visualKind)
                if (slot.role == SlotRole.PERSON) {
                    personSlots++
                    assertTrue(
                        "${summary.id}/${slot.visualKind}: a PERSON-role slot must render as " +
                            "a person, or the role is decoration",
                        style.isPerson,
                    )
                }
            }
        }
        assertTrue("the catalogue must contain person slots to check", personSlots > 0)
    }

    // ---- the renderer must actually consume it ----------------------------------

    private val overlay = File("src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt")

    private fun code(): String = KotlinSourceProbe.codeLines(overlay).joinToString("\n")

    /**
     * A tested mapping nobody calls is worth nothing — the failure mode this project
     * already has on record twice (`selectManualLayout`, `availableManualLayouts`).
     */
    @Test
    fun `the overlay selects a style per slot`() {
        val source = code()
        assertTrue(
            "CameraOverlay must map each slot's visualKind through SlotRenderStyle",
            source.contains("SlotRenderStyle.of(slot.visualKind)"),
        )
        assertTrue(
            "and dispatch on the result",
            source.contains("drawSlotForStyle("),
        )
    }

    /**
     * The regression in the other direction: going back to one shape for every slot, by
     * commenting the person marks out — the way the preset bracket was once disabled
     * wholesale on a parallel branch (see `CameraOverlayD2Test`).
     *
     * **Structural, and it had to become structural.** A first draft counted lines
     * containing `drawPersonSilhouette(` that were not comments. Re-injection defeated
     * it: commenting out the *call* leaves the function's own **declaration**, which
     * contains the same text, so the count stayed above zero and the guard passed while
     * the silhouette was gone. Asking instead what the dispatch block contains cannot be
     * satisfied by a declaration somewhere else in the file.
     */
    @Test
    fun `each style dispatches to its own marks`() {
        val lines = KotlinSourceProbe.codeLines(overlay)
        val dispatch = KotlinSourceProbe.blockAt("private fun DrawScope.drawSlotForStyle(", lines)
        val body = lines.subList(dispatch.first, dispatch.last + 1).joinToString("\n")
        for (call in listOf("drawPersonSilhouette(", "drawFootMarker(", "drawHeadMarker(", "drawLayoutSlotBracket(")) {
            assertTrue(
                "`$call` must be reachable from drawSlotForStyle — commenting it out is " +
                    "how the flattening comes back",
                body.contains(call),
            )
        }
        for (style in SlotRenderStyle.entries) {
            assertTrue(
                "$style must have a branch in drawSlotForStyle",
                body.contains("SlotRenderStyle.${style.name}"),
            )
        }
    }

    /**
     * The three styles must not draw the same marks, or the mapping is decoration. Read
     * off each branch of the dispatch: the person branches must reach a mark the object
     * branch does not.
     */
    @Test
    fun `the object branch draws none of the person marks`() {
        val lines = KotlinSourceProbe.codeLines(overlay)
        val dispatch = KotlinSourceProbe.blockAt("private fun DrawScope.drawSlotForStyle(", lines)
        val body = lines.subList(dispatch.first, dispatch.last + 1)
        val objectLine = body.single { it.contains("SlotRenderStyle.OBJECT_BRACKET ->") }
        for (personMark in listOf("drawPersonSilhouette(", "drawFootMarker(", "drawHeadMarker(")) {
            assertFalse(
                "the object branch must not draw `$personMark` — that is the flattening " +
                    "in reverse",
                objectLine.contains(personMark),
            )
        }
    }

    /**
     * D2 still holds after adding marks. A slot's appearance may depend on its **kind**
     * and never on whether it is filled — `CameraOverlayD2Test` bans the occupancy
     * symbols; this bans the shape of the mistake in this file's own vocabulary.
     */
    @Test
    fun `slot style depends on kind, never on occupancy`() {
        val source = code()
        for (banned in listOf("assignments", "isFilled", "occupied", "matchScore")) {
            assertFalse(
                "slot rendering must not consult `$banned` — a slot that changes when " +
                    "filled is a match gauge in another shape (D2-1)",
                source.contains("SlotRenderStyle.of($banned") || source.contains(".$banned)"),
            )
        }
        assertFalse(
            "FixedLayoutGuide.assignments is KPI data, not a rendering input",
            source.contains("fixed.assignments"),
        )
    }

    /** The KPI field this rendering must not start reading still exists — so the ban means something. */
    @Test
    fun `the assignments field the renderer ignores is really there`() {
        val template = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.PERSON_OBJECT)!!
        val guide = FixedLayoutGuide(template = template)
        assertTrue("assignments exists and is empty by default", guide.assignments.isEmpty())
    }
}
