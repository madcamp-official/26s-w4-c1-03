package com.gamdo.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the `captures.conditions_json` contract with guide-capture-agent.
 *
 * This is a cross-agent seam that no device can validate for us and that fails
 * *quietly* when it is wrong — a mistyped key or a missing subject reads as "no tilt,
 * no person", which looks exactly like a correctly-handled photo. The round-trip test
 * below is the only thing standing between a writer-side rename and levelling
 * silently switching itself off.
 */
class CaptureConditionsTest {

    @Test
    fun `a document written by the contract reads back identically`() {
        // The property that matters: writer and reader cannot drift, because they are
        // the same type.
        val written = CaptureConditions(
            tiltDeg = -2.4f,
            subject = SubjectBox(0.21f, 0.09f, 0.79f, 0.94f),
        )
        val read = CaptureConditions.parse(written.encodeToString())

        assertEquals(written, read)
    }

    @Test
    fun `a document with no subject round-trips as absent`() {
        val written = CaptureConditions(tiltDeg = 1.5f, subject = null)
        val json = written.encodeToString()

        // Absent must mean the key is missing, not present-and-null: a reader that
        // sees `"subject": null` and one that sees nothing must agree, and a
        // 0,0,0,0 box would look valid and drag the crop into the corner.
        assertTrue("subject key should be omitted, got $json", !json.contains("subject"))
        assertNull(CaptureConditions.parse(json).subject)
    }

    @Test
    fun `an empty document is the no-information case`() {
        // Today's live path: nothing populates conditions_json until §3-3.
        assertEquals(CaptureConditions.NONE, CaptureConditions.parse("{}"))
        assertEquals(CaptureConditions.NONE, CaptureConditions.parse(null))
        assertEquals(CaptureConditions.NONE, CaptureConditions.parse(""))
        assertEquals(CaptureConditions.NONE, CaptureConditions.parse("   "))
    }

    @Test
    fun `no information means no levelling and no subject`() {
        assertNull(CaptureConditions.NONE.tiltDeg)
        assertNull(CaptureConditions.NONE.subject)
        // The collapse to a usable number happens at exactly one accessor, and it
        // must produce zero rotation rather than a near-zero wobble.
        assertEquals(0f, CaptureConditions.NONE.tiltDegOrZero, 1e-6f)
        assertEquals(0f, levelingRotationDeg(CaptureConditions.NONE.tiltDegOrZero), 1e-6f)
    }

    @Test
    fun `an unrecorded tilt is distinguishable from a measured zero`() {
        // The whole reason tiltDeg is nullable. A device with no gravity sensor, and
        // a shutter that beats the first onSensorChanged, both yield *no reading* —
        // which is not the fact "this photo is level". The writer omits the key in
        // that case, and that omission has to survive the trip.
        val unrecorded = CaptureConditions.parse("""{"subject":null}""")
        val measuredLevel = CaptureConditions.parse("""{"tiltDeg":0.0}""")

        assertNull(unrecorded.tiltDeg)
        assertNotNull(measuredLevel.tiltDeg)
        assertEquals(0f, measuredLevel.tiltDeg!!, 1e-6f)
        assertNotEquals(unrecorded, measuredLevel)

        // Both still behave identically downstream — the distinction is information,
        // not a behaviour change.
        assertEquals(unrecorded.tiltDegOrZero, measuredLevel.tiltDegOrZero, 1e-6f)
    }

    @Test
    fun `an unrecorded tilt is omitted rather than written as zero`() {
        val json = CaptureConditions(tiltDeg = null).encodeToString()
        assertTrue("tiltDeg key should be omitted, got $json", !json.contains("tiltDeg"))
        assertNull(CaptureConditions.parse(json).tiltDeg)
    }

    @Test
    fun `one malformed field does not discard the other`() {
        // The document comes from another agent; a bad tilt must not take the subject
        // down with it, or a writer-side bug would silently disable two features.
        val json = """{"tiltDeg":{"nested":1},"subject":{"left":0.1,"top":0.2,"right":0.6,"bottom":0.8}}"""
        val read = CaptureConditions.parse(json)
        assertNull(read.tiltDeg)
        assertEquals(SubjectBox(0.1f, 0.2f, 0.6f, 0.8f), read.subject)
    }

    @Test
    fun `malformed json degrades instead of throwing`() {
        // This runs on every photo the user opens; a broken document is a reason to
        // skip levelling, not to fail the result screen.
        assertEquals(CaptureConditions.NONE, CaptureConditions.parse("not json at all"))
        assertEquals(CaptureConditions.NONE, CaptureConditions.parse("[1,2,3]"))
        assertEquals(CaptureConditions.NONE, CaptureConditions.parse("{\"tiltDeg\":"))
    }

    @Test
    fun `a non-numeric tilt reads as absent rather than throwing`() {
        assertNull(CaptureConditions.parse("""{"tiltDeg":"steep"}""").tiltDeg)
        assertEquals(0f, CaptureConditions.parse("""{"tiltDeg":"steep"}""").tiltDegOrZero, 1e-6f)
    }

    @Test
    fun `a non-finite tilt is rejected`() {
        // NaN would propagate through the rotation maths as NaN; levelingRotationDeg
        // guards it too, but a value this broken should not survive parsing.
        assertNull(CaptureConditions.parse("""{"tiltDeg":"NaN"}""").tiltDeg)
    }

    @Test
    fun `unknown keys are ignored so KPI fields can share the document`() {
        // guide-capture writes brightnessMean / shake / flags for KPI; the editor
        // reads none of them and must not choke on them.
        val json = """
            {"tiltDeg":-3.0,"brightnessMean":0.42,"shake":0.013,
             "lowLightFlag":false,"backlightFlag":true,
             "subject":{"left":0.1,"top":0.2,"right":0.6,"bottom":0.8}}
        """.trimIndent()
        val read = CaptureConditions.parse(json)
        assertEquals(-3.0f, read.tiltDegOrZero, 1e-4f)
        assertEquals(SubjectBox(0.1f, 0.2f, 0.6f, 0.8f), read.subject)
    }

    @Test
    fun `a degenerate subject box is rejected rather than clamped`() {
        // Clamping would invent a plausible region out of a broken one; dropping it
        // keeps null meaning "we do not know".
        assertNull(CaptureConditions.parse(subjectJson(0f, 0f, 0f, 0f)).subject)
        assertNull(CaptureConditions.parse(subjectJson(0.8f, 0.1f, 0.2f, 0.9f)).subject)
        assertNull(CaptureConditions.parse(subjectJson(0.1f, 0.9f, 0.9f, 0.2f)).subject)
    }

    @Test
    fun `a subject missing a field is rejected`() {
        assertNull(CaptureConditions.parse("""{"subject":{"left":0.1,"top":0.2}}""").subject)
    }

    @Test
    fun `a valid subject survives`() {
        val box = CaptureConditions.parse(subjectJson(0.2f, 0.1f, 0.8f, 0.95f)).subject
        assertNotNull(box)
        assertEquals(0.5f, box!!.centerX, 1e-4f)
        assertEquals(0.525f, box.centerY, 1e-4f)
    }

    @Test
    fun `the subject actually moves the crop`() {
        // The reason the box is worth carrying at all: §4-1 "인물 중심 유지".
        val offCentre = SubjectBox(0.55f, 0.1f, 0.95f, 0.9f)
        val centred = planGeometry(2000, 2000, 0f, EditAspect.RATIO_4_5)
        val biased = planGeometry(
            sourceWidth = 2000,
            sourceHeight = 2000,
            tiltDeg = 0f,
            aspect = EditAspect.RATIO_4_5,
            subjectCenterX = offCentre.centerX,
            subjectCenterY = offCentre.centerY,
        )
        assertTrue(
            "crop should follow the subject right, ${centred.crop.x} -> ${biased.crop.x}",
            biased.crop.x > centred.crop.x,
        )
    }

    @Test
    fun `a subject at the frame edge cannot push the crop off the image`() {
        for (cx in floatArrayOf(0f, 0.02f, 0.98f, 1f)) {
            val plan = planGeometry(2000, 2000, 6f, EditAspect.RATIO_4_5, cx, cx)
            assertTrue("x negative at $cx", plan.crop.x >= 0)
            assertTrue("y negative at $cx", plan.crop.y >= 0)
            assertTrue("overruns width at $cx", plan.crop.right <= plan.rotatedWidth)
            assertTrue("overruns height at $cx", plan.crop.bottom <= plan.rotatedHeight)
        }
    }

    private fun subjectJson(l: Float, t: Float, r: Float, b: Float): String =
        """{"subject":{"left":$l,"top":$t,"right":$r,"bottom":$b}}"""
}
