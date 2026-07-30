package com.gamdo.app.ui.camera

import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.guide.LayoutSource
import com.gamdo.app.guide.LayoutTemplateCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which guide vocabulary one overlay frame speaks.
 *
 * This is the (B) half of the owner's 2026-07-31 report — "아래에서 선택해도 전혀 바뀌는
 * 게 없어". Manual frame selection was device-verified working before 상황 우선 가이드 V2
 * landed; what V2 changed is not the selection but the *drawing*, and that is the one
 * thing `CameraOverlay` cannot be made to prove about itself here. So the decision was
 * lifted out of the Canvas into [GuideRenderPriority] and is pinned here instead.
 */
class GuideRenderPriorityTest {

    private val template = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.PERSON_UPPER)!!

    private fun fixed(source: LayoutSource) = GuideLayoutState.Fixed(template, source)

    /**
     * The bug, stated as the rule that ends it. A user who taps 전신 비대칭 gets 전신
     * 비대칭 — not the silhouette `SceneTechniqueSelector` placed for the scene the
     * camera happened to latch onto first.
     */
    @Test
    fun `a frame the user picked outranks the situation marks`() {
        assertEquals(
            GuideVocabulary.TEMPLATE_SLOTS,
            GuideRenderPriority.vocabulary(fixed(LayoutSource.MANUAL), hasMarks = true),
        )
    }

    /**
     * The other side of the same rule: nothing is taken away from V2 where V2 is the one
     * that chose the layout. An automatic fix — and a reference, which
     * `SceneGuideCoordinator` treats as a candidate rather than a command — still draws
     * marks when there are marks.
     */
    @Test
    fun `an automatically chosen layout still lets the marks speak`() {
        for (source in listOf(LayoutSource.AUTO, LayoutSource.REFERENCE)) {
            assertEquals(
                "$source did not choose the layout by name, so it does not outrank the marks",
                GuideVocabulary.SITUATION_MARKS,
                GuideRenderPriority.vocabulary(fixed(source), hasMarks = true),
            )
        }
    }

    @Test
    fun `exactly one layout source suppresses the marks`() {
        val suppressing = LayoutSource.entries.filter { source ->
            GuideRenderPriority.vocabulary(fixed(source), hasMarks = true) == GuideVocabulary.TEMPLATE_SLOTS
        }
        assertEquals(listOf(LayoutSource.MANUAL), suppressing)
    }

    /**
     * Marks outlive the state that produced them: `SceneGuideSessionController` fills the
     * field while `Fixed` and — before the same change that added this test — never
     * emptied it on 재탐색. Rendering must not depend on that field being tidy, so
     * `Searching` draws no marks whatever is left in it. There is no fixed layout while
     * searching either, so the overlay is the spinner and nothing else, which is what
     * 탐색 중 is supposed to look like.
     */
    @Test
    fun `searching draws no marks, however many are left over`() {
        assertEquals(
            GuideVocabulary.TEMPLATE_SLOTS,
            GuideRenderPriority.vocabulary(GuideLayoutState.Searching, hasMarks = true),
        )
    }

    @Test
    fun `no marks is always the slot vocabulary`() {
        assertEquals(
            GuideVocabulary.TEMPLATE_SLOTS,
            GuideRenderPriority.vocabulary(GuideLayoutState.Searching, hasMarks = false),
        )
        for (source in LayoutSource.entries) {
            assertEquals(
                GuideVocabulary.TEMPLATE_SLOTS,
                GuideRenderPriority.vocabulary(fixed(source), hasMarks = false),
            )
        }
    }

    @Test
    fun `the convenience predicate cannot drift from the decision`() {
        val states = listOf(GuideLayoutState.Searching) + LayoutSource.entries.map(::fixed)
        for (state in states) {
            for (hasMarks in listOf(true, false)) {
                assertEquals(
                    GuideRenderPriority.vocabulary(state, hasMarks) == GuideVocabulary.SITUATION_MARKS,
                    GuideRenderPriority.drawsSituationMarks(state, hasMarks),
                )
            }
        }
    }

    // ---- wired, not merely written ---------------------------------------------------

    /**
     * `CameraOverlay` is a `@Composable` with a `DrawScope` body and cannot run here, so
     * the wiring is read from its source — the same technique, and the same reason, as
     * [CameraOverlayD2Test].
     */
    @Test
    fun `the overlay asks this object instead of testing the marks for null`() {
        val overlay = File("src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt")
        assertTrue("the overlay source this test guards must exist", overlay.isFile)
        val code = KotlinSourceProbe.codeLines(overlay)
        assertTrue(
            "the marks/slots branch must go through GuideRenderPriority",
            code.any { it.contains("GuideRenderPriority.drawsSituationMarks(") },
        )
        val offenders = code.withIndex()
            .filter { (_, line) -> line.trim() == "val marks = guideMarks?.marks" }
            .map { (index, _) -> "line ${index + 1}" }
        assertEquals(
            "an unconditional `guideMarks?.marks` is the bug: it draws leftover marks over " +
                "a manually chosen frame and over a search that already discarded them.",
            emptyList<String>(),
            offenders,
        )
    }
}
