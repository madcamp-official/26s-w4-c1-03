package com.gamdo.app.ui.camera

import com.gamdo.app.edit.MAX_LEVELING_DEG
import com.gamdo.app.edit.MIN_LEVELING_DEG
import com.gamdo.app.edit.levelingRotationDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2-5 / §3-2 — the sign of the horizon indicator.
 *
 * The load-bearing test here is [`지시선 회전과 레벨링 회전은 항상 서로의 음수다`], which
 * spans two verticals on purpose. Let `A` be the angle of the true horizon as it
 * appears in the frame (clockwise positive). The indicator must lie **along** that
 * line, so it draws at `rotate(A)`; levelling must **cancel** it, so it rotates by
 * `rotate(-A)`. Those are negatives of each other by definition, whatever the
 * gravity convention turns out to be — so pinning only one side proves nothing.
 * Anyone who later flips a single call site breaks this immediately.
 *
 * Reading `com.gamdo.app.edit.levelingRotationDeg` from a `ui.camera` test is a
 * test-only dependency on a pure function (lead-approved); production code in
 * `ui/camera` does not depend on `edit` for this.
 *
 * **Do not verify this on a device by colour.** The sage/red swap keys off
 * `abs(rollDeg)`, so it is blind to sign and looks identical when the indicator is
 * inverted — see [`색 전환은 부호에 무감각하다`]. That is why the "실기기 확인"
 * history on §2-5 passed with the sign wrong.
 */
class HorizonGeometryTest {

    @Test
    fun `지시선은 롤 각도를 그대로 따라 그린다`() {
        // Positive roll must rotate the line the same way, not the opposite way.
        assertEquals(8f, horizonIndicatorRotationDeg(8f), TOL)
        assertEquals(-8f, horizonIndicatorRotationDeg(-8f), TOL)
        assertEquals(2f, horizonIndicatorRotationDeg(2f), TOL)
        assertEquals(-45f, horizonIndicatorRotationDeg(-45f), TOL)
    }

    @Test
    fun `지시선 회전과 레벨링 회전은 항상 서로의 음수다`() {
        // Shared active band: past the indicator's level band, below the levelling
        // clamp. Here the two rotations are exact negatives, not merely opposite.
        sweep(from = LEVEL_BAND_DEG + 0.05f, to = MAX_LEVELING_DEG, step = 0.05f) { deg ->
            for (roll in listOf(deg, -deg)) {
                val indicator = horizonIndicatorRotationDeg(roll)
                val leveling = levelingRotationDeg(roll)
                assertEquals("roll=$roll", -leveling, indicator, TOL)
            }
        }
    }

    @Test
    fun `두 회전은 어떤 각도에서도 같은 방향으로 돌지 않는다`() {
        // The invariant that holds over the *whole* range, dead bands included:
        // one draws along the horizon, the other cancels it, so they can never
        // rotate the same way. Product <= 0 allows either side to be zero.
        sweep(from = -90f, to = 90f, step = 0.05f) { roll ->
            val product = horizonIndicatorRotationDeg(roll) * levelingRotationDeg(roll)
            assertTrue("roll=$roll 에서 두 회전이 같은 방향이다 (곱=$product)", product <= 0f)
        }
    }

    @Test
    fun `레벨링 클램프 밖에서도 두 회전의 부호는 반대다`() {
        // Past MAX_LEVELING_DEG levelling saturates while the indicator keeps
        // following the roll, so the magnitudes diverge. The *sign* must not.
        sweep(from = MAX_LEVELING_DEG + 0.5f, to = 89f, step = 0.5f) { deg ->
            for (roll in listOf(deg, -deg)) {
                val indicator = horizonIndicatorRotationDeg(roll)
                val leveling = levelingRotationDeg(roll)
                assertTrue("roll=$roll 부호가 같다", indicator * leveling < 0f)
                assertTrue("roll=$roll 클램프가 안 걸렸다", kotlin.math.abs(leveling) <= MAX_LEVELING_DEG + TOL)
            }
        }
    }

    @Test
    fun `레벨 밴드 안에서는 지시선이 0으로 스냅한다`() {
        // Regression guard — already true today. "Reached" must read as a dead
        // straight line, not as a 1.4° tilt.
        assertEquals(0f, horizonIndicatorRotationDeg(0f), TOL)
        assertEquals(0f, horizonIndicatorRotationDeg(LEVEL_BAND_DEG), TOL)
        assertEquals(0f, horizonIndicatorRotationDeg(-LEVEL_BAND_DEG), TOL)
        assertNotEquals(0f, horizonIndicatorRotationDeg(LEVEL_BAND_DEG + 0.5f))
    }

    @Test
    fun `지시선의 레벨 밴드가 레벨링 데드밴드보다 넓다`() {
        // Documents why the exact-negation test above is scoped rather than global:
        // MIN_LEVELING_DEG (0.35°) < LEVEL_BAND_DEG (1.5°), so in between the
        // indicator has already snapped to 0 while levelling still corrects.
        // Intentional — the indicator is a display band, levelling is a
        // resolution-cost band — but it means "always exact negatives" is false
        // here. The direction invariant above still holds.
        assertTrue(MIN_LEVELING_DEG < LEVEL_BAND_DEG)
        for (roll in listOf(0.5f, 1.0f, LEVEL_BAND_DEG)) {
            assertEquals("roll=$roll", 0f, horizonIndicatorRotationDeg(roll), TOL)
            assertNotEquals("roll=$roll", 0f, levelingRotationDeg(roll))
        }
    }

    @Test
    fun `색 전환은 부호에 무감각하다`() {
        // Not a requirement, a warning: the colour gate is symmetric in roll, so a
        // mirrored indicator turns sage at exactly the same moments as a correct
        // one. Device verification must compare the line against a real horizon.
        assertTrue(isHorizonLevel(1f))
        assertTrue(isHorizonLevel(-1f))
        assertFalse(isHorizonLevel(5f))
        assertFalse(isHorizonLevel(-5f))
        assertEquals(isHorizonLevel(7f), isHorizonLevel(-7f))
    }

    @Test
    fun `센서가 이상값을 주면 회전하지 않는다`() {
        // A NaN handed to rotate() poisons the whole draw matrix. Matches
        // levelingRotationDeg's guard on the other side.
        assertEquals(0f, horizonIndicatorRotationDeg(Float.NaN), TOL)
        assertEquals(0f, horizonIndicatorRotationDeg(Float.POSITIVE_INFINITY), TOL)
        assertEquals(0f, horizonIndicatorRotationDeg(Float.NEGATIVE_INFINITY), TOL)
    }

    private companion object {
        const val TOL = 1e-4f

        /** Integer-indexed so 0.05° steps don't accumulate float drift. */
        inline fun sweep(from: Float, to: Float, step: Float, block: (Float) -> Unit) {
            val steps = ((to - from) / step).toInt()
            for (i in 0..steps) block(from + i * step)
        }
    }
}
