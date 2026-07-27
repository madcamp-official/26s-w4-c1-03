package com.gamdo.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Device tilt in degrees. roll = horizon tilt (0 = level), pitch = forward lean.
 *
 * **Sign convention for [rollDeg] — derived, not yet confirmed on a device.**
 *
 * `TYPE_GRAVITY` reports world-up expressed in device axes (flat on a table,
 * screen up ⇒ `(0, 0, +9.81)`). Held upright in portrait, world-up lies along
 * device `+Y`, so `roll = atan2(gx, gy) = 0`. Rotate the phone clockwise by θ as
 * the user sees it and world-up becomes `(−sin θ, cos θ, 0)`, giving
 * `roll = −θ`. Cross-check at θ = 90° (phone's top edge swung to the right):
 * world-up is along `−X`, so `atan2(−1, 0) = −90°`.
 *
 * Therefore:
 * - `rollDeg < 0` ⇒ device tilted **clockwise** (top of the phone leans right)
 * - `rollDeg > 0` ⇒ device tilted **counter-clockwise**
 *
 * A photo taken with the device tilted clockwise by θ shows the world rotated
 * counter-clockwise by θ, so **levelling rotation = −rollDeg**, positive meaning
 * clockwise. That "positive = clockwise" half is *settled*, not assumed:
 * `Matrix.setSinCos` lays out `[cos, −sin; sin, cos]`, mapping `(1, 0)` to
 * `(cos, sin)`, which is clockwise in the +Y-down screen frame — and the edit
 * pipeline's hand-built affine matrix was checked against that same layout.
 *
 * So the **only** unverified link left is the `TYPE_GRAVITY` sign above.
 *
 * ## The two consumers need OPPOSITE signs — resolved, W3
 *
 * Let `A` be the angle of the true horizon as it appears in the frame, clockwise
 * positive. Whatever the gravity convention turns out to be:
 * - the indicator must lie **along** that line, so it draws at `rotate(A)`;
 * - levelling must **cancel** it, so it rotates by `rotate(-A)`.
 *
 * Those are negatives of each other by definition. Both sites used to return
 * `-roll`, i.e. the *same* rotation, so exactly one had to be wrong — a conclusion
 * that needed no device. The derivation above yields `roll = -θ` and a horizon that
 * appears at `-θ`, so `A == rollDeg` and the **indicator** was the inverted one.
 * Fixed in `ui/camera/HorizonGeometry.kt`; `levelingRotationDeg` was left alone.
 *
 * `HorizonGeometryTest` now pins the invariant across both verticals, so a future
 * one-sided flip fails immediately. Do not "resynchronise" these two call sites —
 * making them agree is what breaks the correct one.
 *
 * Consumers: `ui/camera/CameraOverlay` (horizon indicator) and the local edit
 * pipeline, which reads the shutter-time value out of `captures.conditions_json`.
 *
 * §3-3 must write that document by constructing
 * `com.gamdo.app.edit.CaptureConditions(tiltDeg = …, subject = …)` and calling
 * `encodeToString()` — **never by typing the JSON keys**, so a misspelling is a
 * compile error rather than a silent 0°.
 *
 * `CaptureConditions.tiltDeg` is `Float?`: pass `null` when the sensor has not
 * reported yet, which omits the key entirely and keeps "unrecorded" distinct from
 * "measured level". Both still level by 0°, so this is about not lying, not about
 * behaviour. Note that a device with neither `TYPE_GRAVITY` nor
 * `TYPE_ACCELEROMETER` never leaves the initial `(0f, 0f)`, so §3-3 needs a
 * "has this ever reported" signal off this class to tell the two apart.
 *
 * DONE-DEVICE — the two sites are now consistent with each other, so one
 * observation no longer picks a broken site: it confirms or refutes the
 * `TYPE_GRAVITY` premise for **both at once**. Point the camera at a real horizon
 * (window frame, desk edge) and tilt the phone clockwise ~10°:
 * - indicator stays **on** the true horizon (on screen it leans opposite to the
 *   device) ⇒ premise confirmed, both signs correct, nothing to change;
 * - indicator leans **with** the device, off the true horizon ⇒ `TYPE_GRAVITY` is
 *   the opposite of the derivation, `A == -rollDeg`, and **both** sites flip
 *   together (plus the expectation in `HorizonGeometryTest`).
 *
 * Never flip one alone — the invariant test exists to stop that.
 *
 * ⚠️ Do **not** judge this by colour. The sage/red swap keys off `abs(rollDeg)`
 * and is blind to sign, so an inverted indicator turns sage at exactly the same
 * moments as a correct one. The §2-5 "실기기 확인" history almost certainly passed
 * for that reason.
 */
data class TiltReading(val rollDeg: Float, val pitchDeg: Float)

/**
 * Horizon tilt from the **gravity vector** (§2-3). Computed as roll = atan2(gx, gy)
 * so it stays correct in the upright shooting posture — unlike ROTATION_VECTOR +
 * getOrientation, whose roll hits gimbal lock (pitch ≈ 90°) when the phone stands up.
 *
 * Low-pass filtered (α, default 0.2) at SENSOR_DELAY_GAME (≥10Hz). Feeds the horizon
 * guide (AlignmentEngine on Day 3).
 */
class TiltSensor(
    context: Context,
    private val alpha: Float = 0.2f,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _reading = MutableStateFlow(TiltReading(0f, 0f))
    val reading: StateFlow<TiltReading> = _reading

    /**
     * Whether the sensor has ever reported, exposed because §3-3 needs to tell
     * "not recorded" from "measured level".
     *
     * [reading]'s initial value is `TiltReading(0f, 0f)` — the same numbers a
     * perfectly level phone produces. A device with neither TYPE_GRAVITY nor
     * TYPE_ACCELEROMETER, and a shutter pressed before the first
     * `onSensorChanged`, both hand the shutter that zero. Writing it into
     * `conditions_json` as a measurement is exactly the information loss
     * `CaptureConditions`' nullable `tiltDeg` exists to prevent, and it is silent:
     * the editor would simply never level a photo and never say why.
     */
    val hasReading: Boolean get() = initialized

    private var filteredRoll = 0f
    private var filteredPitch = 0f

    @Volatile
    private var initialized = false

    fun start() {
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Gravity (or accelerometer at rest ≈ gravity) in device coordinates.
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]

        // roll: rotation of the phone around the viewing axis — the horizon tilt.
        // 0 when the phone is held upright and level.
        val roll = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        // pitch: lean from vertical (0 upright, ~90 flat).
        val pitch = Math.toDegrees(atan2(gz.toDouble(), hypot(gx.toDouble(), gy.toDouble()))).toFloat()

        if (!initialized) {
            filteredRoll = roll
            filteredPitch = pitch
            initialized = true
        } else {
            // Roll wraps at ±180° (upside-down posture): filter along the shortest
            // arc or the line sweeps across the whole screen. Pitch is bounded
            // (−90..90) and needs no wrapping.
            filteredRoll = wrapDegrees(filteredRoll + alpha * angleDelta(roll, filteredRoll))
            filteredPitch += alpha * (pitch - filteredPitch)
        }
        _reading.value = TiltReading(filteredRoll, filteredPitch)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Signed shortest angular difference target−current in (−180, 180]. */
        fun angleDelta(target: Float, current: Float): Float = wrapDegrees(target - current)

        fun wrapDegrees(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}
