package com.gamdo.app.ui.camera

import com.gamdo.app.guide.CaptureSceneMode
import com.gamdo.app.guide.SceneModeDecision
import com.gamdo.app.guide.SceneModeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 상황 우선 가이드 V2 (요구사항 §10) — the chip row's decisions, pinned here because
 * `CameraScreen` cannot run under `testDebugUnitTest`.
 */
class SceneModeSelectionTest {

    private fun user(mode: CaptureSceneMode) =
        SceneModeDecision(mode, 0.9f, SceneModeSource.USER)

    private fun auto(mode: CaptureSceneMode) =
        SceneModeDecision(mode, 0.8f, SceneModeSource.AUTO_CLASSIFIER)

    @Test
    fun `all six situations are offered, auto first`() {
        assertEquals(6, SceneModeSelection.chips.size)
        assertEquals(CaptureSceneMode.AUTO, SceneModeSelection.chips.first())
        assertEquals(CaptureSceneMode.entries.toSet(), SceneModeSelection.chips.toSet())
    }

    /** §10 gives these words; they are the user's vocabulary, not the enum's. */
    @Test
    fun `every chip has its contract label`() {
        assertEquals("자동", SceneModeSelection.label(CaptureSceneMode.AUTO))
        assertEquals("인물", SceneModeSelection.label(CaptureSceneMode.PORTRAIT))
        assertEquals("배경 강조 인물", SceneModeSelection.label(CaptureSceneMode.ENVIRONMENTAL_PORTRAIT))
        assertEquals("카페·음식", SceneModeSelection.label(CaptureSceneMode.CAFE_FOOD))
        assertEquals("여행·풍경", SceneModeSelection.label(CaptureSceneMode.TRAVEL_LANDSCAPE))
        assertEquals("정물·소품", SceneModeSelection.label(CaptureSceneMode.STILL_LIFE))
    }

    /**
     * The rule §10 states outright: "`SceneModeDecision.source == USER`인 동안 Auto
     * 제안이 사용자 선택을 덮어쓰지 않는다". A user pick lights its own chip.
     */
    @Test
    fun `a user pick is the selected chip`() {
        assertEquals(CaptureSceneMode.CAFE_FOOD, SceneModeSelection.selectedChip(user(CaptureSceneMode.CAFE_FOOD)))
        assertEquals(CaptureSceneMode.PORTRAIT, SceneModeSelection.selectedChip(user(CaptureSceneMode.PORTRAIT)))
    }

    /**
     * A classifier proposal must **not** move the selection. The user did not choose,
     * and a chip that lights by itself reads as the app having changed their setting.
     */
    @Test
    fun `a classifier proposal leaves 자동 selected`() {
        assertEquals(CaptureSceneMode.AUTO, SceneModeSelection.selectedChip(auto(CaptureSceneMode.STILL_LIFE)))
        assertEquals(CaptureSceneMode.AUTO, SceneModeSelection.selectedChip(auto(CaptureSceneMode.PORTRAIT)))
    }

    /** No decision is the state the session is genuinely in, and that is 자동. */
    @Test
    fun `no decision is 자동`() {
        assertEquals(CaptureSceneMode.AUTO, SceneModeSelection.selectedChip(null))
    }

    /**
     * The proposal is still worth surfacing next to 자동 — but only while the user has
     * not chosen, after which it is noise about a settled decision.
     */
    @Test
    fun `the auto hint exists only before the user chooses`() {
        assertEquals(CaptureSceneMode.STILL_LIFE, SceneModeSelection.autoHint(auto(CaptureSceneMode.STILL_LIFE)))
        assertNull(SceneModeSelection.autoHint(user(CaptureSceneMode.STILL_LIFE)))
        assertNull(SceneModeSelection.autoHint(null))
    }

    /**
     * D2/§10 forbid technical exposure. The copy this file can produce is a closed
     * set, so it can simply be asserted to contain none of it.
     */
    @Test
    fun `no label leaks a score, an object name or a mode id`() {
        val banned = listOf("confidence", "score", "track", "AUTO", "PORTRAIT", "0.", "%")
        SceneModeSelection.chips.map(SceneModeSelection::label).forEach { label ->
            banned.forEach { needle ->
                assertTrue("'$label' must not contain '$needle'", !label.contains(needle))
            }
        }
    }
}
