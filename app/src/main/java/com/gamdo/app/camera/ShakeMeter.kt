package com.gamdo.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * Camera-shake level = variance of angular-speed magnitude (gyroscope) over the
 * last [windowMs] (default 0.5s). Higher = shakier. Feeds capture conditions and
 * overlay stabilization (§2-3).
 */
class ShakeMeter(
    context: Context,
    private val windowMs: Long = 500L,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _shake = MutableStateFlow(0f)
    val shake: StateFlow<Float> = _shake

    private val timestamps = ArrayDeque<Long>()
    private val magnitudes = ArrayDeque<Float>()

    fun start() {
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        val now = System.currentTimeMillis()
        timestamps.addLast(now)
        magnitudes.addLast(magnitude)
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
            timestamps.removeFirst()
            magnitudes.removeFirst()
        }
        _shake.value = variance(magnitudes)
    }

    private fun variance(values: Collection<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        val sumSq = values.sumOf { (it - mean) * (it - mean) }
        return (sumSq / values.size).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
