package com.gamdo.app.ui.result

import androidx.compose.ui.text.font.FontWeight
import com.gamdo.app.edit.EditTool
import com.gamdo.app.ui.camera.KotlinSourceProbe.blockAt
import com.gamdo.app.ui.camera.KotlinSourceProbe.codeLines
import com.gamdo.app.ui.theme.Amber
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextLow
import com.gamdo.app.ui.theme.TextMid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two numeric rules 시안 06·07 states for the 보정 controls, held where a
 * `@Composable` cannot be reached: the dial arc sweep and which token a dial or strip
 * label draws in.
 */
class AdjustmentVisualsTest {

    // ---- arc sweep: the design's min(|v| * 3.6, 360) ------------------------------

    @Test
    fun `a dial at rest draws no arc`() {
        assertEquals(0f, AdjustmentVisuals.arcSweepDegrees(0), 0f)
    }

    @Test
    fun `sweep is 3 point 6 degrees per unit`() {
        assertEquals(36f, AdjustmentVisuals.arcSweepDegrees(10), 1e-4f)
        assertEquals(180f, AdjustmentVisuals.arcSweepDegrees(50), 1e-4f)
        assertEquals(360f, AdjustmentVisuals.arcSweepDegrees(100), 1e-4f)
    }

    /**
     * The deliberate loss documented on [AdjustmentVisuals.arcSweepDegrees]: the arc is
     * magnitude only. If someone reintroduces a signed or range-relative sweep to "put
     * the direction back", this fails and sends them to that KDoc — where the reason the
     * sign lives in the number instead is written down.
     */
    @Test
    fun `sweep is symmetric about zero, so the arc carries no sign`() {
        for (magnitude in intArrayOf(1, 7, 25, 50, 99, 100)) {
            assertEquals(
                "±$magnitude must sweep the same arc",
                AdjustmentVisuals.arcSweepDegrees(magnitude),
                AdjustmentVisuals.arcSweepDegrees(-magnitude),
                0f,
            )
        }
    }

    /**
     * The regression this formula exists to kill. Under the old drawing —
     * `360 * EditTool.fraction(value)` — a bipolar control at its default swept 180°, so
     * an untouched photo showed a row of half-full gauges.
     */
    @Test
    fun `a bipolar control at its default is empty, not half full`() {
        val bipolar = EditTool.entries.filter { it.range.first < 0 }
        assertTrue("expected bipolar controls to exist", bipolar.isNotEmpty())
        for (tool in bipolar) {
            assertEquals(
                "${tool.name} sits at 0 by default and must draw an empty dial",
                0f,
                AdjustmentVisuals.arcSweepDegrees(tool.get(com.gamdo.app.edit.FilterEngine.Adjustments.NEUTRAL)),
                0f,
            )
            // The old formula's answer, for the record: fraction(0) == 0.5 -> 180°.
            assertEquals(0.5f, tool.fraction(0), 1e-4f)
        }
    }

    @Test
    fun `sweep never exceeds a full turn, even past the widest range`() {
        for (value in intArrayOf(100, 101, 200, 1000, -1000)) {
            assertTrue(
                "value $value swept ${AdjustmentVisuals.arcSweepDegrees(value)}",
                AdjustmentVisuals.arcSweepDegrees(value) <= 360f,
            )
        }
    }

    /** Every value any shipped control can hold stays inside 0..360. */
    @Test
    fun `sweep stays in range across every tool's whole domain`() {
        for (tool in EditTool.entries) {
            for (value in tool.range) {
                val sweep = AdjustmentVisuals.arcSweepDegrees(value)
                assertTrue("${tool.name}=$value swept $sweep", sweep in 0f..360f)
            }
        }
    }

    // ---- which token: grey at rest, amber with a value ----------------------------

    @Test
    fun `a dial at rest is grey and a dial with a value is amber`() {
        assertEquals(TextLow, AdjustmentVisuals.valueColor(0))
        assertEquals(Amber, AdjustmentVisuals.valueColor(1))
        assertEquals(Amber, AdjustmentVisuals.valueColor(-1))
        assertEquals(Amber, AdjustmentVisuals.valueColor(100))
        assertEquals(Amber, AdjustmentVisuals.valueColor(-100))
    }

    /**
     * "Has a value" and "is selected" have to stay on different channels — the reason is
     * on [AdjustmentVisuals.valueColor]. Selecting a control must not be able to make it
     * look like it holds a value, so the label palette and the ring palette are disjoint.
     */
    @Test
    fun `selection and value are different channels`() {
        assertNotEquals(AdjustmentVisuals.labelColor(true), AdjustmentVisuals.valueColor(1))
        assertNotEquals(AdjustmentVisuals.labelColor(false), AdjustmentVisuals.valueColor(1))
    }

    @Test
    fun `the held dial's label is TextHi at 700 and the rest are TextLow at 500`() {
        assertEquals(TextHi, AdjustmentVisuals.labelColor(true))
        assertEquals(TextLow, AdjustmentVisuals.labelColor(false))
        assertEquals(FontWeight(700), AdjustmentVisuals.labelWeight(true))
        assertEquals(FontWeight(500), AdjustmentVisuals.labelWeight(false))
    }

    // ---- filter strip ------------------------------------------------------------

    @Test
    fun `the selected filter's label is amber at 700 and the rest are TextMid at 500`() {
        assertEquals(Amber, AdjustmentVisuals.stripLabelColor(true))
        assertEquals(TextMid, AdjustmentVisuals.stripLabelColor(false))
        assertEquals(FontWeight(700), AdjustmentVisuals.stripLabelWeight(true))
        assertEquals(FontWeight(500), AdjustmentVisuals.stripLabelWeight(false))
    }

    // ---- and that the screen actually asks --------------------------------------
    //
    // Everything above tests a function nothing is obliged to call. `ToolDial` is a
    // `@Composable`, so the call cannot be observed by running it — the same position
    // the three camera guards are in, and this uses their tool for the same reason.

    @Test
    fun `the dial draws its arc and its colours from AdjustmentVisuals`() {
        val lines = codeLines(File("src/main/java/com/gamdo/app/ui/result/AdjustmentPanel.kt"))
        val dial = blockAt("private fun ToolDial(", lines)
        val body = lines.slice(dial).joinToString("\n")

        assertTrue(
            "ToolDial must take its sweep from AdjustmentVisuals.arcSweepDegrees",
            body.contains("AdjustmentVisuals.arcSweepDegrees("),
        )
        assertTrue(
            "ToolDial must take its ring/number colour from AdjustmentVisuals.valueColor",
            body.contains("AdjustmentVisuals.valueColor("),
        )
        assertTrue(
            "ToolDial must take its label colour from AdjustmentVisuals.labelColor",
            body.contains("AdjustmentVisuals.labelColor("),
        )
        assertTrue(
            "ToolDial must take its label weight from AdjustmentVisuals.labelWeight",
            body.contains("AdjustmentVisuals.labelWeight("),
        )
    }

    /**
     * The specific regression: `EditTool.fraction` is the range-relative sweep this
     * design replaced, and it is still on the enum because other code may want it. If it
     * comes back into the dial, the half-filled-at-rest row comes back with it and every
     * assertion above keeps passing.
     */
    @Test
    fun `the dial does not compute a sweep of its own`() {
        val lines = codeLines(File("src/main/java/com/gamdo/app/ui/result/AdjustmentPanel.kt"))
        val dial = blockAt("private fun ToolDial(", lines)
        val body = lines.slice(dial).joinToString("\n")

        assertFalse(
            "the range-relative sweep is back in ToolDial — see AdjustmentVisuals.arcSweepDegrees",
            body.contains(".fraction("),
        )
        // Two arcs are drawn and only two: the full-circle track, and the value. Anything
        // else assigning a sweep means a third source of truth for the angle.
        val sweeps = Regex("""sweepAngle\s*=\s*(.*)$""", RegexOption.MULTILINE)
            .findAll(body)
            // Reads to end of line rather than to the next `,` or `)`, because the
            // expression is itself a call and a comma-bounded match would cut it in half.
            .map { it.groupValues[1].trim().trimEnd(',').substringBefore(", useCenter") }
            .toList()
        assertEquals(
            "expected exactly the track sweep and the value sweep, got $sweeps",
            listOf("360f", "AdjustmentVisuals.arcSweepDegrees(value)"),
            sweeps,
        )
    }
}
