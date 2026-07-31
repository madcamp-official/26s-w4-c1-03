package com.gamdo.app.ui.camera

import com.gamdo.app.guide.CaptureSceneMode
import com.gamdo.app.guide.LayoutCategory
import com.gamdo.app.guide.LayoutPreviewSlot
import com.gamdo.app.guide.LayoutTemplateCatalog
import com.gamdo.app.guide.LayoutTemplateSummary
import com.gamdo.app.guide.RectN
import com.gamdo.app.guide.SlotRole
import com.gamdo.app.guide.SlotVisualKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 상황 칩 → 프레임 목록 (owner instruction, 2026-07-31).
 *
 * The mapping is pure and the catalogue is fixed, so the interesting properties are all
 * decidable here rather than on a device: that every one of the six chips leaves the row
 * with something in it, that no two chips leave it with the *same* thing in it, and that
 * the row can never hide the frame the overlay is drawing.
 *
 * `SceneModeSelection.framesFor` owns the reasoning behind each rule; this file pins what
 * the rules currently produce, so changing one of them has to be a decision rather than a
 * side effect.
 */
class SceneModeFrameFilterTest {

    private val catalogue = LayoutTemplateCatalog.manualSummaries

    private fun frames(mode: CaptureSceneMode) = SceneModeSelection.framesFor(mode, catalogue)

    private fun personSlot(summary: LayoutTemplateSummary) =
        summary.slots.first { it.role == SlotRole.PERSON }

    // ---- the hard requirement ------------------------------------------------------

    /**
     * **"빈 목록이 나오는 조합이 없어야 한다."** All six situations against the shipped
     * catalogue, and then again with each frame held as the active selection —
     * `framesFor`'s two arguments are the only inputs, so this is the whole space.
     */
    @Test
    fun `no situation ever empties the row`() {
        for (mode in CaptureSceneMode.entries) {
            assertTrue("$mode left the frame row empty", frames(mode).isNotEmpty())
            for (active in catalogue) {
                assertTrue(
                    "$mode left the frame row empty while ${active.id} was selected",
                    SceneModeSelection.framesFor(mode, catalogue, active.id).isNotEmpty(),
                )
            }
        }
    }

    /**
     * The owner's complaint, turned into an assertion: six chips that leave the row
     * looking identical are six buttons that do nothing.
     */
    @Test
    fun `every situation offers a different row`() {
        val rows = CaptureSceneMode.entries.associateWith { frames(it).map { frame -> frame.id } }
        assertEquals(
            "two situations offer the same frames, so switching between them shows the " +
                "user no change: $rows",
            CaptureSceneMode.entries.size,
            rows.values.toSet().size,
        )
    }

    /** Nothing is invented, reordered or duplicated — the row is a subsequence. */
    @Test
    fun `the row is always the catalogue's own frames in the catalogue's own order`() {
        for (mode in CaptureSceneMode.entries) {
            val offered = frames(mode)
            assertEquals("$mode offered a frame twice", offered.size, offered.toSet().size)
            assertEquals("$mode reordered or invented frames", catalogue.filter { it in offered }, offered)
        }
    }

    // ---- what each chip means ------------------------------------------------------

    @Test
    fun `자동 offers the whole catalogue`() {
        assertEquals(catalogue, frames(CaptureSceneMode.AUTO))
    }

    /**
     * 인물 is the frames where a person slot is large enough to be the subject. The
     * 원경 figures are person frames too, but they belong to 배경 강조 인물 — offering
     * them here is exactly the sameness the owner reported, in the other direction.
     */
    @Test
    fun `인물 offers the frames where the person is the subject`() {
        val portrait = frames(CaptureSceneMode.PORTRAIT)
        assertEquals(
            listOf(
                LayoutTemplateCatalog.PERSON_FULL_CENTER,
                LayoutTemplateCatalog.PERSON_FULL_OFFSET,
                LayoutTemplateCatalog.PERSON_FULL_RELAXED,
                LayoutTemplateCatalog.PERSON_FULL_WALKING,
                LayoutTemplateCatalog.PERSON_UPPER,
                LayoutTemplateCatalog.PERSON_SEATED,
                LayoutTemplateCatalog.PERSON_OBJECT,
            ),
            portrait.map { it.id },
        )
        for (summary in portrait) {
            val bounds = personSlot(summary).bounds
            assertTrue(
                "${summary.id}: 인물's person must be subject-sized",
                bounds.width * bounds.height > 0.20f,
            )
        }
    }

    /**
     * **The owner's 2026-07-31 complaint, fixed and pinned**: 배경 강조 인물 used to be
     * the full-body subset of 인물 and read as the same list. It is now the small-figure
     * frames — a person the background dominates — and shares nothing with 인물.
     */
    @Test
    fun `배경 강조 인물 offers the small-figure frames and none of 인물's`() {
        val environmental = frames(CaptureSceneMode.ENVIRONMENTAL_PORTRAIT)
        assertEquals(
            listOf(
                LayoutTemplateCatalog.PERSON_ENV_THIRDS_LEFT,
                LayoutTemplateCatalog.PERSON_ENV_THIRDS_RIGHT,
                LayoutTemplateCatalog.PERSON_ENV_CENTER,
            ),
            environmental.map { it.id },
        )
        for (summary in environmental) {
            val bounds = personSlot(summary).bounds
            assertTrue(
                "${summary.id}: the person must be small enough for the background to " +
                    "dominate the frame",
                bounds.width * bounds.height <= 0.20f,
            )
            assertTrue(
                "${summary.id}: a 배경 강조 frame is the person alone in the place",
                summary.slots.all { it.role == SlotRole.PERSON },
            )
        }
        assertTrue(
            "인물 and 배경 강조 인물 must not share a frame — that sameness is the bug " +
                "the owner reported",
            frames(CaptureSceneMode.PORTRAIT).intersect(environmental.toSet()).isEmpty(),
        )
    }

    /**
     * 여행·풍경 finally has its own row: a small person beside a place-sized slot, or a
     * lone slot big enough to *be* the place. None of it is borrowed from the person
     * chips any more.
     */
    @Test
    fun `여행·풍경 offers its own travel frames`() {
        val travel = frames(CaptureSceneMode.TRAVEL_LANDSCAPE)
        assertEquals(
            listOf(
                LayoutTemplateCatalog.TRAVEL_LANDMARK_PERSON,
                LayoutTemplateCatalog.TRAVEL_SCENERY_PERSON,
                LayoutTemplateCatalog.TRAVEL_LANDMARK_THIRDS,
            ),
            travel.map { it.id },
        )
        for (mode in listOf(CaptureSceneMode.PORTRAIT, CaptureSceneMode.ENVIRONMENTAL_PORTRAIT)) {
            assertTrue(
                "여행·풍경 must not borrow $mode's frames — a travel chip that repeats a " +
                    "person chip is the owner's complaint again",
                frames(mode).intersect(travel.toSet()).isEmpty(),
            )
        }
    }

    @Test
    fun `카페·음식 offers the object frames built for two or more subjects`() {
        val cafe = frames(CaptureSceneMode.CAFE_FOOD)
        assertEquals(8, cafe.size)
        assertTrue(cafe.all { it.category == LayoutCategory.OBJECT && it.slotCount >= 2 })
    }

    @Test
    fun `정물·소품 offers the object frames for one or two tabletop subjects`() {
        val stillLife = frames(CaptureSceneMode.STILL_LIFE)
        assertEquals(5, stillLife.size)
        assertTrue(stillLife.all { it.category == LayoutCategory.OBJECT && it.slotCount <= 2 })
        assertTrue(
            "a landmark-sized slot is scenery, not a still life",
            stillLife.flatMap { it.slots }.all { it.bounds.width * it.bounds.height < 0.16f },
        )
    }

    /** The expansion's purpose: every frame in the catalogue has a chip that offers it. */
    @Test
    fun `no frame is orphaned — each is reachable from a specific situation`() {
        val reachable = CaptureSceneMode.entries
            .filter { it != CaptureSceneMode.AUTO }
            .flatMap { frames(it) }
            .toSet()
        assertEquals(
            "these frames are only reachable through 자동, so no situation recommends " +
                "them: ${catalogue.filterNot { it in reachable }.map { it.id }}",
            catalogue.toSet(),
            reachable,
        )
    }

    /** No person frame reaches a table, and no object frame reaches a portrait chip. */
    @Test
    fun `the object chips and the person chips never trade frames`() {
        val people = catalogue.filter { it.category == LayoutCategory.PERSON }.toSet()
        val objects = catalogue.filter { it.category == LayoutCategory.OBJECT }.toSet()
        for (mode in listOf(CaptureSceneMode.PORTRAIT, CaptureSceneMode.ENVIRONMENTAL_PORTRAIT)) {
            assertTrue("$mode offered an object frame", frames(mode).none { it in objects })
        }
        for (mode in listOf(CaptureSceneMode.CAFE_FOOD, CaptureSceneMode.STILL_LIFE)) {
            assertTrue("$mode offered a person frame", frames(mode).none { it in people })
        }
    }

    // ---- the selection survives the filter ------------------------------------------

    /**
     * The decision recorded in `framesFor`'s KDoc: a frame that stops matching is **kept
     * visible** rather than deselected. The strip must always contain what the overlay is
     * drawing, or the amber ring has no cell to sit on and the user cannot get back to
     * the frame they are looking at.
     */
    @Test
    fun `the active frame stays in the row whatever the situation says`() {
        for (mode in CaptureSceneMode.entries) {
            for (active in catalogue) {
                val offered = SceneModeSelection.framesFor(mode, catalogue, active.id)
                assertTrue(
                    "$mode hid ${active.id} while it was the selected frame",
                    offered.any { it.id == active.id },
                )
                assertEquals(
                    "keeping the active frame must not reorder the row",
                    catalogue.filter { it in offered },
                    offered,
                )
            }
        }
    }

    /** An id nothing in the row matches is simply not there — no phantom cell. */
    @Test
    fun `an unknown active id adds nothing`() {
        assertEquals(
            frames(CaptureSceneMode.CAFE_FOOD),
            SceneModeSelection.framesFor(CaptureSceneMode.CAFE_FOOD, catalogue, "not-a-layout"),
        )
    }

    // ---- the guarantee that does not depend on the current catalogue ------------------

    /**
     * The 담당 B-owned catalogue could change under this file, so the non-empty guarantee
     * has a second, catalogue-independent half: a situation that matches nothing falls
     * back to everything.
     *
     * The fixture is a person-with-prop frame — the same shape as the catalogue's own
     * `person_object_v2`. 카페·음식 requires an object-only frame, so it can never match
     * this one and must take the fallback rather than the rules.
     */
    @Test
    fun `a situation that matches nothing still fills the row`() {
        val personWithProp = LayoutTemplateSummary(
            id = "test_person_with_prop",
            displayName = "테스트",
            category = LayoutCategory.PERSON,
            slots = listOf(
                LayoutPreviewSlot(SlotRole.PERSON, SlotVisualKind.PERSON_SILHOUETTE, RectN(0.08f, 0.14f, 0.50f, 0.88f)),
                LayoutPreviewSlot(SlotRole.OBJECT, SlotVisualKind.GENERIC_OBJECT, RectN(0.60f, 0.56f, 0.84f, 0.78f)),
            ),
        )
        val only = listOf(personWithProp)
        for (mode in CaptureSceneMode.entries) {
            assertEquals("$mode emptied a one-frame catalogue", only, SceneModeSelection.framesFor(mode, only))
        }
    }

    @Test
    fun `an empty catalogue is left empty rather than fabricated`() {
        for (mode in CaptureSceneMode.entries) {
            assertTrue(SceneModeSelection.framesFor(mode, emptyList()).isEmpty())
        }
    }

    // ---- wired, not merely written ---------------------------------------------------

    /**
     * The failure this repo has already had once (`CameraRedesignGuardTest`): a complete,
     * tested decision with zero callers. `CameraScreen` is a `@Composable` and cannot run
     * here, so the wiring is read from its source.
     */
    @Test
    fun `the frame row draws the filtered list, not the whole catalogue`() {
        val screen = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")
        val code = KotlinSourceProbe.codeLines(screen)
        assertTrue(
            "the row must be narrowed by SceneModeSelection.framesFor",
            code.any { it.contains("SceneModeSelection.framesFor(") },
        )
        val offenders = code.withIndex()
            .filter { (_, line) -> line.contains("items(layouts") }
            .map { (index, line) -> "line ${index + 1}: ${line.trim()}" }
        assertEquals(
            "the LazyRow must render the filtered list. Rendering `layouts` is the bug " +
                "the owner reported: the situation chip changes the guide and the row " +
                "underneath stays at twelve.",
            emptyList<String>(),
            offenders,
        )
        assertFalse(
            "the chip's own selection must feed the filter, so the lit pill and the row " +
                "cannot describe different situations",
            code.none { it.contains("SceneModeSelection.selectedChip(") },
        )
    }
}
