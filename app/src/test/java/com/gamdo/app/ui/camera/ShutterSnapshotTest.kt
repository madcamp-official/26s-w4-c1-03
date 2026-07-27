package com.gamdo.app.ui.camera

import com.gamdo.app.detect.FrameFeatures
import com.gamdo.app.detect.NormalizedBox
import com.gamdo.app.detect.PointN
import com.gamdo.app.detect.SideMargins
import com.gamdo.app.edit.CaptureConditions
import com.gamdo.app.guide.StyleTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3-3 → §4-1 round trip.
 *
 * The failure this guards against is silent: a wrong `conditions_json` parses
 * cleanly into "level, no subject", which turns levelling into an identity
 * transform and keeps three diagnosis chips from ever firing. Nothing throws.
 */
class ShutterSnapshotTest {

    private val pane = 1080f / 1500f

    private fun features(
        person: NormalizedBox? = NormalizedBox(0.3f, 0.2f, 0.7f, 0.9f),
        face: NormalizedBox? = NormalizedBox(0.42f, 0.22f, 0.58f, 0.38f),
        tilt: Float = -4.5f,
        pitch: Float = 2.5f,
    ) = FrameFeatures(
        personBox = person,
        faceBox = face,
        personCenter = person?.let { PointN(it.centerX, it.centerY) },
        personAreaRatio = 0.28f,
        headroom = 0.22f,
        sideMargins = SideMargins(left = 0.3f, right = 0.3f),
        tiltDeg = tilt,
        pitchDeg = pitch,
        brightnessMean = 0.41f,
        backlightFlag = true,
        lowLightFlag = false,
        poseConfidence = 0.82f,
        shake = 0.03f,
    )

    private fun frame(f: FrameFeatures = features()) =
        ShutterFrame(features = f, target = StyleTarget(), aligned = true, visible = true)

    private fun snapshot(
        f: ShutterFrame? = frame(),
        matchScore: Float? = 0.73f,
        paneRatio: Float = pane,
        target: Float = 0.8f,
        mirror: Boolean = false,
        tiltRecorded: Boolean = true,
    ) = buildCaptureSnapshot(
        frame = f,
        matchScore = matchScore,
        sessionId = "ses_TEST",
        paneRatioWtoH = paneRatio,
        targetRatioWtoH = target,
        mirror = mirror,
        tiltRecorded = tiltRecorded,
    )

    @Test
    fun `the document the editor reads back carries tilt and a subject`() {
        val parsed = CaptureConditions.parse(snapshot().conditionsJson)
        assertEquals(-4.5f, parsed.tiltDeg!!, 1e-5f)
        assertNotNull("subject must survive the projection", parsed.subject)
        assertTrue(parsed.subject!!.right > parsed.subject!!.left)
        assertTrue(parsed.subject!!.bottom > parsed.subject!!.top)
    }

    /**
     * The regression that motivated all of this: `saveCameraCapture(bitmap)` with
     * no snapshot wrote `{}`, which parses to NONE and disables levelling.
     */
    @Test
    fun `an empty document is exactly the broken state, so ours must not be empty`() {
        assertEquals(CaptureConditions.NONE, CaptureConditions.parse("{}"))
        assertFalse(snapshot().conditionsJson == "{}")
    }

    @Test
    fun `no analyzed frame writes nothing rather than a plausible default`() {
        val s = snapshot(f = null, matchScore = null)
        assertEquals("{}", s.conditionsJson)
        assertEquals("{}", s.analysisJson)
        assertEquals("ses_TEST", s.sessionId)
        // The point: absent must not be readable as "measured level".
        assertNull(CaptureConditions.parse(s.conditionsJson).tiltDeg)
    }

    @Test
    fun `an unmeasured pane drops the subject but keeps the tilt`() {
        val parsed = CaptureConditions.parse(snapshot(paneRatio = 0f).conditionsJson)
        assertNull("a bogus ratio must not produce a plausible box", parsed.subject)
        assertEquals(-4.5f, parsed.tiltDeg!!, 1e-5f)
    }

    @Test
    fun `face box is used when pose found no person`() {
        val parsed = CaptureConditions.parse(
            snapshot(f = frame(features(person = null))).conditionsJson,
        )
        assertNotNull("a detected face is still a subject", parsed.subject)
    }

    @Test
    fun `no detection at all leaves the subject absent`() {
        val parsed = CaptureConditions.parse(
            snapshot(f = frame(features(person = null, face = null))).conditionsJson,
        )
        assertNull(parsed.subject)
        assertEquals(-4.5f, parsed.tiltDeg!!, 1e-5f)
    }

    /**
     * The trap this closes: `TiltSensor.reading` starts at `TiltReading(0f, 0f)`,
     * which is byte-identical to a perfectly level phone. A device with no gravity
     * sensor, or a shutter that beats the first `onSensorChanged`, must record
     * *absent* — not a measurement of zero that quietly disables levelling forever.
     */
    @Test
    fun `an unreported sensor omits tilt instead of claiming level`() {
        val parsed = CaptureConditions.parse(
            snapshot(f = frame(features(tilt = 0f)), tiltRecorded = false).conditionsJson,
        )
        assertNull(parsed.tiltDeg)
        assertEquals("and 0f collapses only at the named accessor", 0f, parsed.tiltDegOrZero, 1e-6f)
        // A recorded zero is a different fact and must survive.
        val recorded = CaptureConditions.parse(
            snapshot(f = frame(features(tilt = 0f)), tiltRecorded = true).conditionsJson,
        )
        assertEquals(0f, recorded.tiltDeg!!, 1e-6f)
    }

    /**
     * Found on device. The phone was flat on a desk (pitch 88.6°), roll read 93.4°,
     * §3-3 stored it, and §4-3 told the user their photo was crooked. Roll is
     * `atan2(gx, gy)` and near face-up both components are noise, so the number was
     * never about the horizon.
     */
    @Test
    fun `a face-up phone records no tilt, because roll means nothing there`() {
        val flat = CaptureConditions.parse(
            snapshot(f = frame(features(tilt = 93.4f, pitch = 88.6f))).conditionsJson,
        )
        assertNull(flat.tiltDeg)

        // The same roll in a shooting posture is a real measurement and survives.
        val upright = CaptureConditions.parse(
            snapshot(f = frame(features(tilt = 12.0f, pitch = 3.0f))).conditionsJson,
        )
        assertEquals(12.0f, upright.tiltDeg!!, 1e-5f)
    }

    @Test
    fun `the posture gate matches the one the horizon indicator draws under`() {
        // Just inside: recorded. Just outside: not. The overlay hides its indicator
        // at the same boundary, so the app never stores a horizon it will not draw.
        val inside = CaptureConditions.parse(
            snapshot(f = frame(features(tilt = 5f, pitch = MAX_MEANINGFUL_PITCH_DEG - 1f))).conditionsJson,
        )
        val outside = CaptureConditions.parse(
            snapshot(f = frame(features(tilt = 5f, pitch = MAX_MEANINGFUL_PITCH_DEG + 1f))).conditionsJson,
        )
        assertEquals(5f, inside.tiltDeg!!, 1e-5f)
        assertNull(outside.tiltDeg)
    }

    @Test
    fun `non-finite tilt is omitted, not written as NaN`() {
        val parsed = CaptureConditions.parse(
            snapshot(f = frame(features(tilt = Float.NaN))).conditionsJson,
        )
        assertNull(parsed.tiltDeg)
    }

    @Test
    fun `analysis json carries the KPI fields the metric script reads`() {
        val root = Json.parseToJsonElement(snapshot().analysisJson).jsonObject
        assertEquals(0.73f, root[CaptureAnalysisJson.KEY_MATCH_SCORE]!!.jsonPrimitive.content.toFloat(), 1e-5f)
        assertEquals(-4.5f, root[CaptureAnalysisJson.KEY_TILT_DEG]!!.jsonPrimitive.content.toFloat(), 1e-5f)
        assertEquals(true, root[CaptureAnalysisJson.KEY_BACKLIGHT]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, root[CaptureAnalysisJson.KEY_LOW_LIGHT]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, root[CaptureAnalysisJson.KEY_ALIGNED]!!.jsonPrimitive.content.toBoolean())
        assertNotNull(root[CaptureAnalysisJson.KEY_PERSON_BOX])
        assertNotNull(root[CaptureAnalysisJson.KEY_SIDE_MARGINS])
    }

    /**
     * `analysis_json` stays in analysis space while `conditions_json` is projected
     * into file space. Conflating them would make the KPI depend on which aspect
     * the user happened to pick.
     */
    @Test
    fun `KPI coordinates do not move with the chosen aspect but the subject does`() {
        val four = snapshot(target = 0.8f)
        val square = snapshot(target = 1.0f)

        val fourKpi = Json.parseToJsonElement(four.analysisJson).jsonObject[CaptureAnalysisJson.KEY_PERSON_BOX]
        val squareKpi = Json.parseToJsonElement(square.analysisJson).jsonObject[CaptureAnalysisJson.KEY_PERSON_BOX]
        assertEquals("KPI box must be aspect-independent", fourKpi, squareKpi)

        val fourSubject = CaptureConditions.parse(four.conditionsJson).subject!!
        val squareSubject = CaptureConditions.parse(square.conditionsJson).subject!!
        assertTrue(
            "file-space box must follow the crop",
            kotlin.math.abs(fourSubject.top - squareSubject.top) > 1e-4f,
        )
    }

    @Test
    fun `front lens mirrors the stored subject box`() {
        val back = CaptureConditions.parse(snapshot(mirror = false).conditionsJson).subject!!
        val front = CaptureConditions.parse(snapshot(mirror = true).conditionsJson).subject!!
        assertEquals(1f - back.right, front.left, 1e-5f)
        assertEquals(1f - back.left, front.right, 1e-5f)
    }
}
