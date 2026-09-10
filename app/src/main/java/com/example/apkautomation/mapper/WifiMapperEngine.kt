package com.example.apkautomation.mapper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class MapPoint(
    val x: Float, // Relative X in meters
    val y: Float, // Relative Y in meters
    val rssi: Int, // Wi-Fi signal in dBm
    val isWallBoundary: Boolean = false
)

data class WallMarker(
    val x: Float,
    val y: Float
)

class WifiMapperEngine(
    private val context: Context,
    private val scope: CoroutineScope
) : SensorEventListener {

    companion object {
        private const val TAG = "WifiMapperEngine"
        private const val DEFAULT_STEP_LENGTH_METERS = 0.72f
        private const val WALL_RSSI_DROP_THRESHOLD = 5 // dBm drop indicating wall attenuation
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ORIENTATION)
    private val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _isMapping = MutableStateFlow(false)
    val isMapping = _isMapping.asStateFlow()

    private val _currentHeadingDegrees = MutableStateFlow(0f)
    val currentHeadingDegrees = _currentHeadingDegrees.asStateFlow()

    private val _currentPosition = MutableStateFlow(Pair(0f, 0f))
    val currentPosition = _currentPosition.asStateFlow()

    private val _currentRssi = MutableStateFlow(-60)
    val currentRssi = _currentRssi.asStateFlow()

    private val _totalSteps = MutableStateFlow(0)
    val totalSteps = _totalSteps.asStateFlow()

    private val _totalDistanceMeters = MutableStateFlow(0f)
    val totalDistanceMeters = _totalDistanceMeters.asStateFlow()

    private val _points = MutableStateFlow<List<MapPoint>>(emptyList())
    val points = _points.asStateFlow()

    private val _walls = MutableStateFlow<List<WallMarker>>(emptyList())
    val walls = _walls.asStateFlow()

    private var currentX = 0f
    private var currentY = 0f
    private var lastRssi = -60

    // Accelerometer step detector filter
    private var lastAccelMagnitude = 9.8f
    private var lastStepTime = 0L

    private var pollJob: Job? = null

    fun startMapping() {
        if (_isMapping.value) return
        _isMapping.value = true

        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Register both step sensor AND accelerometer so all phones (Samsung, Huawei, Pixel) work reliably
        if (stepSensor != null) {
            sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        accelSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive && _isMapping.value) {
                updateWifiRssi()
                delay(500)
            }
        }

        // Add initial starting point
        val initialRssi = getWifiRssi()
        _currentRssi.value = initialRssi
        lastRssi = initialRssi
        if (_points.value.isEmpty()) {
            _points.value = listOf(MapPoint(0f, 0f, initialRssi))
        }
    }

    fun stopMapping() {
        _isMapping.value = false
        sensorManager?.unregisterListener(this)
        pollJob?.cancel()
        pollJob = null
    }

    fun resetMap() {
        currentX = 0f
        currentY = 0f
        _currentPosition.value = Pair(0f, 0f)
        _totalSteps.value = 0
        _totalDistanceMeters.value = 0f
        _points.value = emptyList()
        _walls.value = emptyList()

        val rssi = getWifiRssi()
        _currentRssi.value = rssi
        lastRssi = rssi
        _points.value = listOf(MapPoint(0f, 0f, rssi))
    }

    /**
     * Allows user to manually log a step/point if their phone's step sensor is asleep
     */
    fun manualStep() {
        recordStep()
    }

    private fun updateWifiRssi() {
        val rssi = getWifiRssi()
        _currentRssi.value = rssi
    }

    @Suppress("DEPRECATION")
    private fun getWifiRssi(): Int {
        return try {
            val info = wifiManager?.connectionInfo
            val rawRssi = info?.rssi ?: -60
            if (rawRssi in -110..-10) rawRssi else -60
        } catch (e: Exception) {
            -60
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!_isMapping.value || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)

                var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (degrees < 0) degrees += 360f
                _currentHeadingDegrees.value = degrees
            }

            Sensor.TYPE_ORIENTATION -> {
                _currentHeadingDegrees.value = event.values[0]
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                recordStep()
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                val now = SystemClock.elapsedRealtime()

                // Universal step peak detection (tuned to 1.7f delta above baseline)
                val delta = kotlin.math.abs(magnitude - lastAccelMagnitude)
                if (delta > 1.7f && (now - lastStepTime > 320)) {
                    lastStepTime = now
                    recordStep()
                }
                lastAccelMagnitude = magnitude
            }
        }
    }

    private fun recordStep() {
        val headingRad = Math.toRadians(_currentHeadingDegrees.value.toDouble())
        val dx = (sin(headingRad) * DEFAULT_STEP_LENGTH_METERS).toFloat()
        val dy = (cos(headingRad) * DEFAULT_STEP_LENGTH_METERS).toFloat()

        currentX += dx
        currentY += dy

        _currentPosition.value = Pair(currentX, currentY)
        _totalSteps.value += 1
        _totalDistanceMeters.value += DEFAULT_STEP_LENGTH_METERS

        val rssi = getWifiRssi()
        _currentRssi.value = rssi

        // Detect sharp signal attenuation drop -> likely physical wall boundary!
        val isWallDrop = (lastRssi - rssi) >= WALL_RSSI_DROP_THRESHOLD
        if (isWallDrop) {
            val updatedWalls = _walls.value.toMutableList()
            updatedWalls.add(WallMarker(currentX, currentY))
            _walls.value = updatedWalls
        }
        lastRssi = rssi

        val updatedPoints = _points.value.toMutableList()
        updatedPoints.add(MapPoint(currentX, currentY, rssi, isWallDrop))
        _points.value = updatedPoints
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
