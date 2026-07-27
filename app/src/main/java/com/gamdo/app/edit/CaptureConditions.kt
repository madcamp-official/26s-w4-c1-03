package com.gamdo.app.edit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The shutter-moment context this vertical reads out of `captures.conditions_json`
 * — **platform-free, and the executable form of a cross-agent contract**.
 *
 * guide-capture-agent writes the document at the shutter (§3-3); the local editor
 * reads it here. Rather than agreeing on key spellings in prose, both sides call
 * [parse] / [encode], so a divergence becomes a compile error instead of a silent
 * default. That matters more than usual: the reader accepts no alternative
 * spellings, so a mistyped key would not fail — it would quietly read 0 and disable
 * levelling on every photo, with no device available to notice.
 *
 * ## Only what is actually consumed
 *
 * Two fields, deliberately. The document may carry more (`brightnessMean`, `shake`
 * and friends are written for KPI) and unknown keys are ignored — but this type
 * exposes only what the edit pipeline reads:
 *
 *  - [tiltDeg] — a sensor reading, unrecoverable from the saved pixels once the
 *    shutter has fired. Sign per `camera/TiltSensor.kt`'s `TiltReading` KDoc
 *    (negative = clockwise device tilt). **Nullable**: a device with no gravity or
 *    accelerometer sensor, and a shutter that beats the first `onSensorChanged`,
 *    both produce no reading at all — which is not the same fact as "level". Read
 *    it through [tiltDegOrZero] unless the distinction matters to you.
 *  - [subject] — where the person is, in **stored-file coordinates**. Unlocks
 *    §4-1's "인물 중심 유지" crop plus the margin and backlight measurements.
 *
 * Everything else the pipeline needs is measured from the stored JPEG itself
 * (`ImageStats.kt`). Importing a shutter-time brightness would be less accurate —
 * the preview frame differs from the saved file in exposure, resolution and, after
 * the aspect crop, field of view — and would give one number two sources of truth.
 *
 * ## Absent means absent
 *
 * Both fields are nullable and both are **omitted** from [encode] when null, rather
 * than written as `null` or as a neutral-looking value. A missing `subject` must not
 * become `0,0,0,0`, which looks like a valid box in the top-left corner and would
 * drag the crop into it; a missing `tiltDeg` must not become `0f`, which is
 * indistinguishable from a photo measured as perfectly level.
 *
 * That second one is not hypothetical: the writer intends to omit `tiltDeg` when the
 * sensor never reported, and a non-null default here would silently discard that
 * distinction on arrival.
 */
@Serializable
data class CaptureConditions(
    val tiltDeg: Float? = null,
    val subject: SubjectBox? = null,
) {

    /**
     * [tiltDeg] with "not recorded" collapsed to "do not rotate".
     *
     * The collapse happens **here and nowhere else**, on purpose. `ImageMetrics`
     * types tilt as a non-null Float, so a number has to be chosen somewhere; making
     * it explicit at one named accessor keeps the distinction alive everywhere above
     * it. Zero is the safe choice — no reading means no correction.
     *
     * **Do not read `tiltDeg == 0f` as "confirmed level".** It may mean the device
     * has no gravity or accelerometer sensor, or that the shutter beat the first
     * `onSensorChanged`. Both produce a genuine null, not a measurement.
     */
    val tiltDegOrZero: Float get() = tiltDeg ?: 0f

    /**
     * The `conditions_json` document for these conditions.
     *
     * The writer's half of the contract. [subject] is **omitted** rather than
     * emitted as null when there is no detection, so the absent case is expressed by
     * the key not existing — see the class note.
     */
    fun encode(json: Json = contractJson): JsonObject = buildJsonObject {
        tiltDeg?.let { put(KEY_TILT_DEG, JsonPrimitive(it)) }
        subject?.let { put(KEY_SUBJECT, json.encodeToJsonElement(SubjectBox.serializer(), it)) }
    }

    /** [encode] as a string, ready for `CaptureSnapshot.conditionsJson`. */
    fun encodeToString(json: Json = contractJson): String = encode(json).toString()

    companion object {
        /** Nothing was recorded: no levelling, no subject-aware crop. */
        val NONE = CaptureConditions()

        /**
         * Key names. Public so the writer can reference them if it builds the
         * document by hand, though [encode] is the intended path.
         */
        const val KEY_TILT_DEG = "tiltDeg"
        const val KEY_SUBJECT = "subject"

        private val contractJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Reads [conditionsJson], degrading to absent values rather than throwing.
         *
         * Total by design. This runs on every photo the user opens, and a malformed
         * document is a reason to skip levelling — not to fail the result screen.
         *
         * The two fields are parsed **independently**: the document is written by
         * another agent, so one malformed value must not discard the other. A subject
         * box that is empty or inverted is dropped rather than clamped, because
         * clamping would invent a plausible-looking region out of a broken one.
         */
        fun parse(conditionsJson: String?, json: Json = contractJson): CaptureConditions {
            if (conditionsJson.isNullOrBlank()) return NONE
            val root = runCatching {
                json.parseToJsonElement(conditionsJson).jsonObject
            }.getOrNull() ?: return NONE
            return CaptureConditions(
                tiltDeg = runCatching {
                    root[KEY_TILT_DEG]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() }
                }.getOrNull(),
                subject = runCatching {
                    root[KEY_SUBJECT]?.let { parseSubject(it.jsonObject, json) }
                }.getOrNull(),
            )
        }

        private fun parseSubject(obj: JsonObject, json: Json): SubjectBox? {
            val box = runCatching {
                json.decodeFromJsonElement(SubjectBox.serializer(), obj)
            }.getOrNull() ?: return null
            val valid = box.left.isFinite() && box.top.isFinite() &&
                box.right.isFinite() && box.bottom.isFinite() &&
                box.right > box.left && box.bottom > box.top
            return box.takeIf { valid }
        }
    }
}
