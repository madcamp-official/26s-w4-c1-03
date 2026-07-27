package com.gamdo.app.ui.camera

import com.gamdo.app.data.CaptureSnapshot
import com.gamdo.app.edit.CaptureConditions

/**
 * Assembles the §3-3 shutter record from the last analyzed frame.
 *
 * Pure — no Android, no CameraX — so the document that unblocks §4-1 levelling and
 * §4-3's TILT / EXCESS_MARGIN / BACKLIGHT chips can be checked on the JVM. That
 * matters more than usual here: a wrong document does not throw. It reads back as
 * "level, no subject" and silently turns levelling into an identity transform,
 * which is exactly the shape of the bug this function exists to end.
 *
 * @param frame the analysis state at the moment of the press, or null when the
 *   analyzer has not produced one yet (cold start, or straight after a rebind).
 * @param matchScore the §4.2 weighted score for [frame], or null with it.
 * @param tiltRecorded `TiltSensor.hasReading` — false when the sensor has never
 *   fired, in which case `FrameFeatures.tiltDeg` is the *default* 0f rather than a
 *   measurement, and writing it would say "perfectly level" about a phone nobody
 *   measured.
 * @param paneRatioWtoH preview pane aspect — the viewport crop. Zero/negative when
 *   the pane has not been measured, which drops the subject box rather than
 *   projecting through a bogus ratio.
 * @param targetRatioWtoH the saved file's aspect (D9: 0.8 or 1.0).
 * @param mirror true for the front lens.
 */
fun buildCaptureSnapshot(
    frame: ShutterFrame?,
    matchScore: Float?,
    sessionId: String?,
    paneRatioWtoH: Float,
    targetRatioWtoH: Float,
    mirror: Boolean,
    tiltRecorded: Boolean,
): CaptureSnapshot {
    // No frame means no measurement. Writing defaults would be worse than writing
    // nothing: `tiltDeg: 0` is indistinguishable from a photo measured as level.
    if (frame == null) return CaptureSnapshot(sessionId = sessionId)

    val conditions = CaptureConditions(
        tiltDeg = frame.features.tiltDeg.takeIf { tiltRecorded && it.isFinite() },
        subject = SubjectProjection.project(
            box = frame.features.personBox ?: frame.features.faceBox,
            paneRatioWtoH = paneRatioWtoH,
            targetRatioWtoH = targetRatioWtoH,
            mirror = mirror,
        ),
    )

    return CaptureSnapshot(
        sessionId = sessionId,
        analysisJson = CaptureAnalysisJson.encodeToString(
            features = frame.features,
            matchScore = matchScore ?: 0f,
            aligned = frame.aligned,
        ),
        conditionsJson = conditions.encodeToString(),
    )
}
