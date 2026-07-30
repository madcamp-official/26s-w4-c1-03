package com.gamdo.app.ui.camera

import com.gamdo.app.camera.CaptureGeometry
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
 * @param geometry the framing plan the shutter actually applied, straight from
 *   `CameraController.capture`. Null drops the subject box rather than inventing one.
 *   This used to be `paneRatioWtoH` + `targetRatioWtoH` + `mirror`, from which
 *   [SubjectProjection] inferred the crops; the inference was measurably wrong on
 *   SM-G970N and the plan is the thing that is not a guess. See that file's KDoc.
 * @param bufferWidth the decoded capture buffer's width, which [geometry] is
 *   expressed against; likewise [bufferHeight].
 */
fun buildCaptureSnapshot(
    frame: ShutterFrame?,
    matchScore: Float?,
    sessionId: String?,
    geometry: CaptureGeometry?,
    bufferWidth: Int,
    bufferHeight: Int,
    tiltRecorded: Boolean,
): CaptureSnapshot {
    // No frame means no measurement. Writing defaults would be worse than writing
    // nothing: `tiltDeg: 0` is indistinguishable from a photo measured as level.
    if (frame == null) return CaptureSnapshot(sessionId = sessionId)

    val conditions = CaptureConditions(
        // Posture-gated, the same rule the horizon indicator draws under. Roll is
        // meaningless near face-up/face-down, and recording it there is not a
        // harmless extra number: §4-3 reads it back and tells the user their photo
        // is crooked. Measured on device — a shot taken with the phone flat on a
        // desk wrote tiltDeg 93.4 and produced exactly that false chip.
        tiltDeg = frame.features.tiltDeg.takeIf {
            tiltRecorded && it.isFinite() && isRollMeaningful(frame.features.pitchDeg)
        },
        subject = SubjectProjection.project(
            box = frame.features.personBox ?: frame.features.faceBox,
            geometry = geometry,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
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
