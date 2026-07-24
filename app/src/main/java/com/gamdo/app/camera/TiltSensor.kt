package com.gamdo.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Device tilt in degrees. roll = horizon tilt, pitch = forward/back lean. */
data class TiltReading(val rollDeg: Float, val pitchDeg: Float)

/**
 * Device tilt from ROTATION_VECTOR with a low-pass filter (α, default 0.2) to
 * smooth jitter. Runs at SENSOR_DELAY_GAME (≥10Hz). Feeds the horizon guide (§2-3,
 * consumed by AlignmentEngine on Day 3).
 */
class TiltSensor(
    context: Context,
    private val alpha: Float = 0.2f,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _reading = MutableStateFlow(TiltReading(0f, 0f))
    val reading: StateFlow<TiltReading> = _reading

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
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
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        if (!initialized) {
            filteredRoll = roll
            filteredPitch = pitch
            initialized = true
        } else {
            filteredRoll += alpha * (roll - filteredRoll)
            filteredPitch += alpha * (pitch - filteredPitch)
        }
        _reading.value = TiltReading(filteredRoll, filteredPitch)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
