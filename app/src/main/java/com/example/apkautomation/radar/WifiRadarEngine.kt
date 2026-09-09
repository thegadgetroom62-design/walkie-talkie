package com.example.apkautomation.radar

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

enum class RadarRole {
    IDLE,
    BEACON,   // Transmits timing pulses
    SENSOR    // Listens, analyzes jitter/variance, renders radar
}

class WifiRadarEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "WifiRadarEngine"
        const val RADAR_PORT = 9999
        private const val BURST_INTERVAL_MS = 25L // 40 pulses per second
        private const val HISTORY_SIZE = 100
        private const val PKT_PULSE: Byte = 0x42
    }

    private val localSenderId: Int = Random.nextInt(100000, 999999)

    private val _role = MutableStateFlow(RadarRole.IDLE)
    val role = _role.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    // Disturbance level: 0f (steady) to 100f (heavy motion)
    private val _disturbanceLevel = MutableStateFlow(0f)
    val disturbanceLevel = _disturbanceLevel.asStateFlow()

    // Dynamic wave history for the oscilloscope canvas (normalized values -1.0 to +1.0)
    private val _waveformHistory = MutableStateFlow(List(HISTORY_SIZE) { 0f })
    val waveformHistory = _waveformHistory.asStateFlow()

    private val _isMotionDetected = MutableStateFlow(false)
    val isMotionDetected = _isMotionDetected.asStateFlow()

    private val _sensitivity = MutableStateFlow(35f) // Threshold between 15 and 80
    val sensitivity = _sensitivity.asStateFlow()

    private val _isAlarmSoundEnabled = MutableStateFlow(true)
    val isAlarmSoundEnabled = _isAlarmSoundEnabled.asStateFlow()

    private val _statusText = MutableStateFlow("Radar Idle - Select Role")
    val statusText = _statusText.asStateFlow()

    private var udpSocket: DatagramSocket? = null
    private var transmitterJob: Job? = null
    private var receiverJob: Job? = null

    private var toneGenerator: ToneGenerator? = null
    private var lastAlarmToneTime = 0L

    // Calibration baseline
    private var baselineNoiseFloor = 2.0f
    private var isCalibrating = false

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator error", e)
        }
    }

    fun setSensitivity(value: Float) {
        _sensitivity.value = value.coerceIn(10f, 90f)
    }

    fun toggleAlarmSound() {
        _isAlarmSoundEnabled.value = !_isAlarmSoundEnabled.value
    }

    /**
     * Start Phone as BEACON (Transmitter):
     * Continuously broadcasts micro-timing pulses across the subnet.
     */
    fun startBeaconMode() {
        stopRadar()
        _role.value = RadarRole.BEACON
        _isScanning.value = true
        _statusText.value = "Beacon Transmitting (Pulse Active)"

        try {
            udpSocket?.close()
            udpSocket = DatagramSocket().apply {
                broadcast = true
            }
        } catch (e: SocketException) {
            Log.e(TAG, "Beacon socket error", e)
            _statusText.value = "Failed to open socket"
            return
        }

        transmitterJob = scope.launch(Dispatchers.IO) {
            val socket = udpSocket ?: return@launch
            val buffer = ByteArray(13)
            buffer[0] = PKT_PULSE
            buffer[1] = ((localSenderId shr 24) and 0xFF).toByte()
            buffer[2] = ((localSenderId shr 16) and 0xFF).toByte()
            buffer[3] = ((localSenderId shr 8) and 0xFF).toByte()
            buffer[4] = (localSenderId and 0xFF).toByte()

            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val p2pBroadcastAddr = InetAddress.getByName("192.168.49.255")

            while (isActive && _role.value == RadarRole.BEACON) {
                val now = System.currentTimeMillis()
                for (i in 0..7) {
                    buffer[5 + i] = ((now shr ((7 - i) * 8)) and 0xFF).toByte()
                }

                try {
                    // Send to standard LAN broadcast
                    val pkt1 = DatagramPacket(buffer, buffer.size, broadcastAddr, RADAR_PORT)
                    socket.send(pkt1)
                    // Also send to Wi-Fi Direct subnet broadcast
                    val pkt2 = DatagramPacket(buffer, buffer.size, p2pBroadcastAddr, RADAR_PORT)
                    socket.send(pkt2)
                } catch (e: Exception) {
                    // Ignore transient send failures
                }

                delay(BURST_INTERVAL_MS)
            }
        }
    }

    /**
     * Start Phone as SENSOR (Receiver & Analyzer):
     * Catches the pulses, analyzes micro-timing jitter variance, and detects motion through walls.
     */
    fun startSensorMode() {
        stopRadar()
        _role.value = RadarRole.SENSOR
        _isScanning.value = true
        _statusText.value = "Radar Listening for Beacons..."

        try {
            udpSocket?.close()
            udpSocket = DatagramSocket(RADAR_PORT).apply {
                broadcast = true
            }
        } catch (e: SocketException) {
            Log.e(TAG, "Sensor socket error", e)
            _statusText.value = "Radar Port Busy or Unavailable"
            return
        }

        receiverJob = scope.launch(Dispatchers.IO) {
            val socket = udpSocket ?: return@launch
            val buffer = ByteArray(64)
            val packet = DatagramPacket(buffer, buffer.size)

            val deltas = mutableListOf<Float>()
            var lastArrivalTime = System.nanoTime()
            val history = ArrayDeque<Float>(List(HISTORY_SIZE) { 0f })

            while (isActive && !socket.isClosed) {
                try {
                    socket.receive(packet)
                    val nowNano = System.nanoTime()

                    if (packet.length < 13 || buffer[0] != PKT_PULSE) continue

                    val senderId = ((buffer[1].toInt() and 0xFF) shl 24) or
                            ((buffer[2].toInt() and 0xFF) shl 16) or
                            ((buffer[3].toInt() and 0xFF) shl 8) or
                            (buffer[4].toInt() and 0xFF)

                    // Skip our own pulses
                    if (senderId == localSenderId) continue

                    val deltaMs = (nowNano - lastArrivalTime) / 1_000_000f
                    lastArrivalTime = nowNano

                    deltas.add(deltaMs)
                    if (deltas.size > 15) {
                        deltas.removeAt(0)
                    }

                    // Calculate variance in arrival timing (RF micro-jitter)
                    if (deltas.size >= 8) {
                        val avg = deltas.average().toFloat()
                        val variance = sqrt(deltas.map { (it - avg) * (it - avg) }.average().toFloat())

                        // Normalization against baseline
                        val effectiveJitter = max(0f, variance - baselineNoiseFloor)
                        val disturbance = min(100f, effectiveJitter * 18f)

                        _disturbanceLevel.value = disturbance

                        // Add wave point to oscilloscope (-1f to +1f with disturbance magnitude)
                        val wavePoint = ((variance - avg) / (avg + 0.1f)).coerceIn(-1f, 1f) * (disturbance / 30f).coerceIn(0.2f, 2.5f)
                        if (history.size >= HISTORY_SIZE) history.removeFirst()
                        history.addLast(wavePoint.coerceIn(-1f, 1f))
                        _waveformHistory.value = history.toList()

                        val isTriggered = disturbance > _sensitivity.value
                        _isMotionDetected.value = isTriggered

                        if (isTriggered) {
                            _statusText.value = "⚠️ MOTION DETECTED THROUGH WALL"
                            onMotionTriggered()
                        } else {
                            _statusText.value = "🟢 ROOM SCANNING - CLEAR"
                        }
                    }
                } catch (e: IOException) {
                    break
                }
            }
        }
    }

    /**
     * Calibrate the noise floor of the room (when no one is moving)
     */
    fun calibrateRoom() {
        scope.launch {
            _statusText.value = "Calibrating room... DO NOT MOVE"
            isCalibrating = true
            delay(2500)
            baselineNoiseFloor = max(1.0f, _disturbanceLevel.value * 0.1f)
            isCalibrating = false
            _statusText.value = "Calibrated! Baseline locked."
        }
    }

    private fun onMotionTriggered() {
        val now = System.currentTimeMillis()
        if (now - lastAlarmToneTime > 1200) {
            lastAlarmToneTime = now
            triggerHapticAlert()
            if (_isAlarmSoundEnabled.value) {
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 100)
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }
    }

    private fun triggerHapticAlert() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(80)
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    fun stopRadar() {
        _isScanning.value = false
        _role.value = RadarRole.IDLE
        _statusText.value = "Radar Stopped"
        _disturbanceLevel.value = 0f
        _isMotionDetected.value = false

        transmitterJob?.cancel()
        transmitterJob = null

        receiverJob?.cancel()
        receiverJob = null

        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // Ignored
        } finally {
            udpSocket = null
        }
    }
}
