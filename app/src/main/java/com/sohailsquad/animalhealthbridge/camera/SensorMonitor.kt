package com.sohailsquad.animalhealthbridge.camera

import android.content.Context
import android.hardware.*
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class SensorMonitor(context: Context) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val mutableGyro = MutableStateFlow(0f)
    private val mutableLight = MutableStateFlow(0f)
    private val mutableProximity = MutableStateFlow(0f)
    private val mutableAvailability = MutableStateFlow(emptySet<Int>())
    val gyroscopeMagnitude = mutableGyro.asStateFlow()
    val lightLevel = mutableLight.asStateFlow()
    val proximity = mutableProximity.asStateFlow()
    val availableSensors = mutableAvailability.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        mutableAvailability.value = listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY)
            .filter { type ->
                try {
                    manager?.getDefaultSensor(type)?.let {
                        manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    } == true
                } catch (failure: Throwable) {
                    Log.w("AhbSensor", "Sensor unavailable type=$type", failure)
                    false
                }
            }.toSet()
        Log.i("AhbSensor", "Monitoring started sensors=${mutableAvailability.value}")
    }

    fun stop() {
        runCatching { manager?.unregisterListener(this) }.onFailure { Log.w("AhbSensor", "Stop failed", it) }
        mutableAvailability.value = emptySet()
        started = false
        Log.i("AhbSensor", "Monitoring stopped")
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> mutableGyro.value = sqrt(event.values.take(3).sumOf { (it * it).toDouble() }).toFloat()
            Sensor.TYPE_LIGHT -> mutableLight.value = event.values[0]
            Sensor.TYPE_PROXIMITY -> mutableProximity.value = event.values[0]
        }
    }
}
