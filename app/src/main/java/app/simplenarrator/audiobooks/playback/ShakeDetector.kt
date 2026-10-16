package app.simplenarrator.audiobooks.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Erkennt kräftiges Schütteln über den Beschleunigungssensor. */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var active = false
    private var lastShake = 0L
    private var peaks = 0
    private var firstPeak = 0L

    fun start() {
        if (active || accel == null) return
        active = sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!active) return
        sensors.unregisterListener(this)
        active = false
        peaks = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < 2.2f) return
        val now = SystemClock.elapsedRealtime()
        if (now - firstPeak > 800) {
            firstPeak = now
            peaks = 0
        }
        peaks++
        if (peaks >= 3 && now - lastShake > 1500) {
            lastShake = now
            peaks = 0
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
