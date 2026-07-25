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

/** Device tilt in degrees. roll = horizon tilt (0 = level), pitch = forward lean. */
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

    private var filteredRoll = 0f
    private var filteredPitch = 0f
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
