package com.gamdo.app.edit

import com.gamdo.app.detect.ProblemCode
import com.gamdo.app.detect.ProblemDiagnoser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4-3: proves TILT, EXCESS_MARGIN and BACKLIGHT can actually fire.
 *
 * They could not. The result screen measured through `detect/ImageMetricsExtractor`,
 * which returns tilt and both margins as the constant 0 and backlight as null,
 * because pixels alone do not carry those facts. Three of six chips were
 * unreachable by construction — the branches existed, were tested in isolation, and
 * were fed values that could never cross their thresholds.
 *
 * This test runs the production chain: pixels + `conditions_json` facts →
 * [computeImageMetrics] → [ProblemDiagnoser]. It is here so the next person who
 * swaps the extractor back gets a failure instead of a screen that quietly stops
 * diagnosing.
 */
class DiagnosisReachabilityTest {

    private val w = 64
    private val h = 80

    /**
     * Flat background with a darker rectangle standing in for the subject, placed
     * to match [box] exactly so the backlight ratio measures the region the caller
     * claims the person is in.
     */
    private fun pixels(box: SubjectBox?, subjectDark: Boolean, background: Int): IntArray {
        val px = IntArray(w * h)
        val x0 = ((box?.left ?: 0.3f) * w).toInt()
        val x1 = ((box?.right ?: 0.7f) * w).toInt()
        val y0 = ((box?.top ?: 0.2f) * h).toInt()
        val y1 = ((box?.bottom ?: 0.8f) * h).toInt()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inSubject = x in x0 until x1 && y in y0 until y1
                val v = if (inSubject && subjectDark) 30 else background
                px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return px
    }

    private fun diagnose(
        tiltDeg: Float,
        subject: SubjectBox?,
        subjectDark: Boolean = false,
        background: Int = 128,
        maskBox: SubjectBox? = subject,
    ) = ProblemDiagnoser().diagnose(
        computeImageMetrics(
            pixels = pixels(maskBox, subjectDark, background),
            width = w,
            height = h,
            tiltDeg = tiltDeg,
            subject = subject,
        ),
    ).map { it.code }

    /** Margins 0.25 + 0.25 = 0.50, comfortably under the 0.62 threshold. */
    private val centred = SubjectBox(left = 0.25f, top = 0.2f, right = 0.75f, bottom = 0.8f)

    /** A narrow subject leaves wide empty sides: 0.42 + 0.42 = 0.84 ≥ 0.62. */
    private val narrow = SubjectBox(left = 0.42f, top = 0.2f, right = 0.58f, bottom = 0.8f)

    @Test
    fun `TILT fires on a recorded tilt and stays quiet without one`() {
        assertTrue("7.5 degrees is well past the 3 degree threshold",
            ProblemCode.TILT in diagnose(tiltDeg = -7.5f, subject = centred))
        assertFalse("no reading means no claim",
            ProblemCode.TILT in diagnose(tiltDeg = 0f, subject = centred))
    }

    @Test
    fun `EXCESS_MARGIN needs a subject box, which only the shutter can supply`() {
        assertTrue(ProblemCode.EXCESS_MARGIN in diagnose(tiltDeg = 0f, subject = narrow))
        assertFalse("centred subject is not excess margin",
            ProblemCode.EXCESS_MARGIN in diagnose(tiltDeg = 0f, subject = centred))
        assertFalse("no subject means the margins were never measured",
            ProblemCode.EXCESS_MARGIN in diagnose(tiltDeg = 0f, subject = null))
    }

    @Test
    fun `BACKLIGHT needs a subject box to compare against the background`() {
        assertTrue(
            "dark subject on a bright background",
            ProblemCode.BACKLIGHT in diagnose(
                tiltDeg = 0f, subject = centred, subjectDark = true, background = 200,
            ),
        )
        assertFalse(
            "with no box there is nothing to compare",
            ProblemCode.BACKLIGHT in diagnose(
                tiltDeg = 0f, subject = null, subjectDark = true, background = 200,
                maskBox = centred,
            ),
        )
    }

    /**
     * The exact shape of the old screen's inputs. If someone reverts the extractor,
     * this is what the user gets: a crooked, badly framed, backlit photo diagnosed
     * as having none of those problems.
     */
    @Test
    fun `the old zeroed inputs make all three unreachable at once`() {
        // The photo really is crooked, badly framed and backlit — the pixels carry
        // a dark off-centre subject on a bright field. Only the shutter facts are
        // missing, which is exactly what the old extractor threw away.
        val codes = diagnose(
            tiltDeg = 0f, subject = null, subjectDark = true, background = 200,
            maskBox = narrow,
        )
        assertFalse(ProblemCode.TILT in codes)
        assertFalse(ProblemCode.EXCESS_MARGIN in codes)
        assertFalse(ProblemCode.BACKLIGHT in codes)
    }

    @Test
    fun `pixel-measurable problems never depended on conditions_json`() {
        // Exposure is in the file, so it works with or without a shutter document.
        assertTrue(ProblemCode.UNDEREXPOSED in diagnose(0f, null, background = 12))
        assertTrue(ProblemCode.OVEREXPOSED in diagnose(0f, null, background = 252))
    }
}
