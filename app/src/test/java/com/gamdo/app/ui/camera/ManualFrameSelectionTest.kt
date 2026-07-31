package com.gamdo.app.ui.camera

import com.gamdo.app.guide.GuideLayoutState
import com.gamdo.app.guide.LayoutSource
import com.gamdo.app.guide.LayoutTemplateCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual frame picker (`docs/P2_P1_필수기능연결_요구사항_2026-07-30.md` §3.1).
 *
 * Every one of B's four mandatory conditions is checked here or in
 * [CameraRedesignGuardTest], because three of them are properties of *what the code does
 * not do* — hold a list, trust a return value, persist a selection — and those are
 * invisible on a device until they are wrong.
 */
class ManualFrameSelectionTest {

    private val template = LayoutTemplateCatalog.resolve(LayoutTemplateCatalog.PERSON_UPPER)!!

    // ---- "선택 실패를 고정 성공으로 표시하지 않는다" ------------------------------

    @Test
    fun `a manual fix is what lights the button`() {
        val state = GuideLayoutState.Fixed(template, LayoutSource.MANUAL)
        assertEquals(template.id, ManualFrameSelection.activeManualLayoutId(state))
        assertTrue(ManualFrameSelection.frameButtonActive(state))
    }

    /**
     * The condition, stated as the thing that makes it unbreakable: while the engine is
     * still searching there is no active frame, whatever the sheet just asked for. A
     * rejected id never becomes `Fixed(MANUAL)`, so nothing can light up.
     */
    @Test
    fun `searching is never an active frame`() {
        assertNull(ManualFrameSelection.activeManualLayoutId(GuideLayoutState.Searching))
        assertFalse(ManualFrameSelection.frameButtonActive(GuideLayoutState.Searching))
    }

    /**
     * An automatically resolved layout is not a manual selection. Showing the picker as
     * active for one would tell the user they had chosen something they had not — and
     * `AUTO` is the state the screen is in almost all the time, so getting this wrong
     * would leave the button permanently lit.
     */
    @Test
    fun `an automatic or reference fix does not light the button`() {
        for (source in listOf(LayoutSource.AUTO, LayoutSource.REFERENCE)) {
            val state = GuideLayoutState.Fixed(template, source)
            assertNull(
                "$source is not a manual selection",
                ManualFrameSelection.activeManualLayoutId(state),
            )
            assertFalse(ManualFrameSelection.frameButtonActive(state))
        }
    }

    @Test
    fun `exactly one source counts as manual`() {
        val manual = LayoutSource.entries.filter { source ->
            ManualFrameSelection.frameButtonActive(GuideLayoutState.Fixed(template, source))
        }
        assertEquals(listOf(LayoutSource.MANUAL), manual)
    }

    // ---- the list is B's ---------------------------------------------------------

    @Test
    fun `the catalogue supplies twenty-three manual layouts`() {
        // §3.1 named 12; the owner's 2026-07-31 expansion grew the catalogue so 배경
        // 강조 인물 and 여행·풍경 have frames of their own. The exact number is pinned
        // so a catalogue change is a decision, not drift.
        val summaries = LayoutTemplateCatalog.manualSummaries
        assertEquals("the catalogue ships 23 frames", 23, summaries.size)
        assertEquals("ids must be unique", 23, summaries.map { it.id }.toSet().size)
        assertTrue(
            "every summary must carry preview slots — the picker draws them",
            summaries.all { it.slots.isNotEmpty() },
        )
    }

    @Test
    fun `every offered id resolves, so the picker cannot ask for a bad one`() {
        for (summary in LayoutTemplateCatalog.manualSummaries) {
            assertTrue(
                "${summary.id} came from the catalogue and must resolve in it",
                LayoutTemplateCatalog.resolve(summary.id) != null,
            )
        }
    }

    // ---- the missing display name -----------------------------------------------

    /**
     * The gap this test used to pin is closed: `object_quad_hierarchy_v3` shipped
     * without a `displayName` case (escalated 2026-07-30) and received B-5's own name
     * "주 피사체+보조" with the 2026-07-31 catalogue expansion, exactly the outcome the
     * old expectation's message asked for. The assertion stays at "no unnamed layouts"
     * so a future frame added without a name fails loudly here instead of shipping a
     * captionless cell.
     */
    @Test
    fun `layouts without a display name render no caption`() {
        val unnamed = LayoutTemplateCatalog.manualSummaries.filter { it.displayName == it.id }
        assertEquals(
            "a manual layout shipped without a display name and its cell is now " +
                "captionless — name it in LayoutTemplateCatalog.displayName.",
            emptyList<String>(),
            unnamed.map { it.id },
        )
        for (summary in unnamed) {
            assertNull(
                "${summary.id} has no human name, so it must render none rather than its id",
                ManualFrameSelection.label(summary),
            )
        }
    }

    @Test
    fun `named layouts render their name`() {
        val named = LayoutTemplateCatalog.manualSummaries.filter { it.displayName != it.id }
        assertEquals("every manual layout is named", 23, named.size)
        for (summary in named) {
            assertEquals(summary.displayName, ManualFrameSelection.label(summary))
        }
    }

    /** No caption may ever be a template id, whatever the catalogue does. */
    @Test
    fun `no caption is ever a raw template id`() {
        for (summary in LayoutTemplateCatalog.manualSummaries) {
            val label = ManualFrameSelection.label(summary)
            assertFalse(
                "R7-1: `${summary.id}` must not reach the user as text",
                label == summary.id,
            )
        }
    }

    // ---- the screen must not hold its own copy ------------------------------------

    private val cameraPackage = File("src/main/java/com/gamdo/app/ui/camera")

    /**
     * §3.1: "프레임 목록을 하드코딩하지 않고 `availableManualLayouts`에서 읽는다."
     *
     * Checked as "no template id string appears under `ui/camera/`", which is stronger
     * than checking the list is read: a hardcoded id could be a single special case rather
     * than a whole list, and it would still be a second copy of a name the catalogue owns
     * — the catalogue keeps `person_object` as an alias of `person_object_v2` precisely
     * because those names have already moved once.
     */
    @Test
    fun `no template id is written down in the camera UI`() {
        val ids = LayoutTemplateCatalog.manualIds + LayoutTemplateCatalog.legacyIds
        val offenders = mutableListOf<String>()
        for (file in cameraPackage.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            for ((index, line) in KotlinSourceProbe.codeLines(file).withIndex()) {
                ids.filter { line.contains("\"$it\"") }.forEach {
                    offenders += "${file.name}:${index + 1} \"$it\""
                }
            }
        }
        assertEquals(
            "the frame list and its ids belong to LayoutTemplateCatalog. Read them through " +
                "CameraViewModel.availableManualLayouts.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * §3.1: "카메라 세션을 나갔다 돌아오면 기본 자동 탐색으로 복귀한다."
     *
     * Satisfied by *not* persisting the selection, so the check is for the absence of
     * persistence rather than the presence of a reset. A `rememberSaveable` holding a
     * layout id would survive both a trip to the album and process death, and would then
     * need reset code that nothing here has.
     */
    @Test
    fun `the selected frame is not persisted anywhere in the camera UI`() {
        val screen = File("src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt")
        val offenders = KotlinSourceProbe.codeLines(screen).withIndex()
            .filter { (_, line) -> line.contains("rememberSaveable") }
            .filter { (_, line) ->
                listOf("layout", "frame", "template").any { line.lowercase().contains(it) }
            }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
        assertEquals(
            "a persisted frame selection would survive leaving the camera, which §3.1 " +
                "forbids. The selection is derived from CameraViewModel.layoutState, and " +
                "that ViewModel is recreated with the composition.",
            emptyList<String>(),
            offenders,
        )
    }
}
