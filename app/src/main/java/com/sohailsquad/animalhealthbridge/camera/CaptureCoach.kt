package com.sohailsquad.animalhealthbridge.camera

import android.hardware.Sensor
import android.os.SystemClock

class CaptureCoach(private val sensors: SensorMonitor) {
    private var shakingSince: Long? = null
    fun checkSteadiness(): String {
        if (Sensor.TYPE_GYROSCOPE !in sensors.availableSensors.value) return "UNAVAILABLE"
        if (sensors.gyroscopeMagnitude.value <= 0.5f) {
            shakingSince = null
            return "GOOD"
        }
        val now = SystemClock.elapsedRealtime()
        if (shakingSince == null) shakingSince = now
        return if (now - requireNotNull(shakingSince) >= 1000) "HOLD_STEADY" else "GOOD"
    }
    fun checkLighting(): String = when {
        Sensor.TYPE_LIGHT !in sensors.availableSensors.value -> "UNAVAILABLE"
        sensors.lightLevel.value < 50f -> "TOO_DARK"
        else -> "GOOD"
    }
    fun checkProximity(): String = when {
        Sensor.TYPE_PROXIMITY !in sensors.availableSensors.value -> "UNAVAILABLE"
        sensors.proximity.value < 1f -> "COVERED"
        else -> "OK"
    }
}
