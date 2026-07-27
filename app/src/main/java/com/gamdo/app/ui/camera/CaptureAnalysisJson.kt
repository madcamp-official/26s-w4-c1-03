package com.gamdo.app.ui.camera

import com.gamdo.app.detect.FrameFeatures
import com.gamdo.app.detect.NormalizedBox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the `captures.analysis_json` document for §3-3 — the FrameFeatures the
 * guide saw at the shutter, plus the §4.2 weighted match score.
 *
 * Written by hand rather than with `@Serializable`. [FrameFeatures] belongs to
 * 담당 B's frozen `detect/` module, which this vertical uses call-site only, so
 * annotating it is not ours to do; and the KPI document is a *record format* that
 * should stay stable even if the in-memory class is refactored. Hand-encoding
 * makes that coupling explicit and testable.
 *
 * Absent boxes are omitted rather than written as zeros, for the same reason
 * `CaptureConditions` omits them: `0,0,0,0` reads as a valid region in the corner.
 *
 * Coordinates here are **analysis-frame** normalized values, unlike
 * `conditions_json`'s subject box which is projected into stored-file space by
 * [SubjectProjection]. The two documents answer different questions — this one is
 * "what did the guide see", that one is "where is the person in this file" — and
 * conflating them would make the KPI depend on the aspect the user happened to pick.
 */
object CaptureAnalysisJson {

    fun encode(features: FrameFeatures, matchScore: Float, aligned: Boolean): JsonObject =
        buildJsonObject {
            features.personBox?.let { put(KEY_PERSON_BOX, boxOf(it)) }
            features.faceBox?.let { put(KEY_FACE_BOX, boxOf(it)) }
            features.personCenter?.let {
                put(KEY_PERSON_CENTER, buildJsonObject { put("x", it.x); put("y", it.y) })
            }
            put(KEY_PERSON_AREA_RATIO, features.personAreaRatio)
            put(KEY_HEADROOM, features.headroom)
            put(
                KEY_SIDE_MARGINS,
                buildJsonObject {
                    put("left", features.sideMargins.left)
                    put("right", features.sideMargins.right)
                },
            )
            put(KEY_TILT_DEG, features.tiltDeg)
            put(KEY_PITCH_DEG, features.pitchDeg)
            put(KEY_BRIGHTNESS_MEAN, features.brightnessMean)
            put(KEY_BACKLIGHT, features.backlightFlag)
            put(KEY_LOW_LIGHT, features.lowLightFlag)
            put(KEY_POSE_CONFIDENCE, features.poseConfidence)
            put(KEY_SHAKE, features.shake)
            put(KEY_MATCH_SCORE, matchScore)
            put(KEY_ALIGNED, aligned)
        }

    fun encodeToString(features: FrameFeatures, matchScore: Float, aligned: Boolean): String =
        encode(features, matchScore, aligned).toString()

    private fun boxOf(b: NormalizedBox): JsonObject = buildJsonObject {
        put("left", b.left)
        put("top", b.top)
        put("right", b.right)
        put("bottom", b.bottom)
    }

    const val KEY_PERSON_BOX = "personBox"
    const val KEY_FACE_BOX = "faceBox"
    const val KEY_PERSON_CENTER = "personCenter"
    const val KEY_PERSON_AREA_RATIO = "personAreaRatio"
    const val KEY_HEADROOM = "headroom"
    const val KEY_SIDE_MARGINS = "sideMargins"
    const val KEY_TILT_DEG = "tiltDeg"
    const val KEY_PITCH_DEG = "pitchDeg"
    const val KEY_BRIGHTNESS_MEAN = "brightnessMean"
    const val KEY_BACKLIGHT = "backlightFlag"
    const val KEY_LOW_LIGHT = "lowLightFlag"
    const val KEY_POSE_CONFIDENCE = "poseConfidence"
    const val KEY_SHAKE = "shake"
    const val KEY_MATCH_SCORE = "matchScore"
    const val KEY_ALIGNED = "aligned"
}
