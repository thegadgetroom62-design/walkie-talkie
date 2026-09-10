package com.example.apkautomation.global

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.apkautomation.crypto.VoiceEncryptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.*
import java.util.Collections
import kotlin.random.Random

/**
 * Direct-IP Global P2P Engine
 * Enables serverless, direct peer-to-peer calling over any IP network
 * (Satellite cellular, Wi-Fi, mobile hotspot, or home internet) with AES-256 CTR encryption.
 */
class DirectIpCommsManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DirectIpCommsManager"
        const val DEFAULT_PORT = 8895
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val PKT_PING: Byte = 1
        private const val PKT_PONG: Byte = 2
        private const val PKT_AUDIO: Byte = 3
        private const val PKT_DISCONNECT: Byte = 4
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val localSenderId: Int = Random.nextInt(100000, 999999)

    private val _connectionState = MutableStateFlow(DirectIpState.IDLE)
    val connectionState = _connectionState.asStateFlow()

    private val _localIp = MutableStateFlow(detectLocalIp())
    val localIp = _localIp.asStateFlow()

    private val _peerIp = MutableStateFlow<String?>(null)
    val peerIp = _peerIp.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting = _isTransmitting.asStateFlow()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving = _isReceiving.asStateFlow()

    private val _isSpeakerphone = MutableStateFlow(true)
    val isSpeakerphone = _isSpeakerphone.asStateFlow()

    private val _statusMessage = MutableStateFlow("Direct-IP Standby (Zero Cloud)")
    val statusMessage = _statusMessage.asStateFlow()

    private val _latencyMs = MutableStateFlow(0)
    val latencyMs = _latencyMs.asStateFlow()

    private val _recentPeers = MutableStateFlow<List<String>>(emptyList())
    val recentPeers = _recentPeers.asStateFlow()

    private var targetInetAddress: InetAddress? = null
    private var targetPort: Int = DEFAULT_PORT

    private var udpSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var receiveJob: Job? = null
    private var transmitJob: Job? = null
    private var heartbeatJob: Job? = null

    private var voiceEncryptor: VoiceEncryptor = VoiceEncryptor("1234")
    private var lastPingSentTime = 0L

    init {
        refreshLocalIp()
    }

    fun updateSecurityPin(pin: String) {
        val activePin = if (pin.isBlank()) "1234" else pin
        voiceEncryptor = VoiceEncryptor(activePin)
    }

    fun refreshLocalIp() {
        _localIp.value = detectLocalIp()
    }

    private fun detectLocalIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun startHosting() {
        disconnect()
        refreshLocalIp()
        _connectionState.value = DirectIpState.LISTENING
        _statusMessage.value = "Listening on port $DEFAULT_PORT (Share your IP)"

        initUdpSocket(DEFAULT_PORT)
        startHeartbeat()
    }

    fun connectToPeer(ipString: String, port: Int = DEFAULT_PORT) {
        val cleanIp = ipString.trim()
        if (cleanIp.isBlank()) {
            _statusMessage.value = "Please enter a valid target IP"
            return
        }

        disconnect()
        refreshLocalIp()
        _connectionState.value = DirectIpState.CONNECTING
        _statusMessage.value = "Pinging peer $cleanIp..."
        _peerIp.value = cleanIp

        saveRecentPeer(cleanIp)

        scope.launch(Dispatchers.IO) {
            try {
                targetInetAddress = InetAddress.getByName(cleanIp)
                targetPort = port
                initUdpSocket(0) // Bind to any available local port
                startHeartbeat()

                // Send immediate ping
                sendPacket(PKT_PING, targetInetAddress!!, targetPort, ByteArray(0))
            } catch (e: Exception) {
                Log.e(TAG, "Connect error", e)
                _connectionState.value = DirectIpState.IDLE
                _statusMessage.value = "Invalid IP address: ${e.message}"
            }
        }
    }

    private fun saveRecentPeer(ip: String) {
        val current = _recentPeers.value.toMutableList()
        current.remove(ip)
        current.add(0, ip)
        _recentPeers.value = current.take(5)
    }

    private fun initUdpSocket(port: Int) {
        try {
            udpSocket?.close()
            udpSocket = if (port > 0) {
                DatagramSocket(port).apply { reuseAddress = true }
            } else {
                DatagramSocket().apply { reuseAddress = true }
            }
            startReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Socket bind error", e)
            _statusMessage.value = "Port $port unavailable"
        }
    }

    private fun startReceiver() {
        receiveJob?.cancel()

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 2048)

        try {
            audioTrack?.release()
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init error", e)
            return
        }

        receiveJob = scope.launch(Dispatchers.IO) {
            val socket = udpSocket ?: return@launch
            val buffer = ByteArray(bufferSize + 64)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive && !socket.isClosed) {
                try {
                    socket.receive(packet)
                    if (packet.length < 5) continue

                    val packetType = buffer[0]
                    val senderId = ((buffer[1].toInt() and 0xFF) shl 24) or
                            ((buffer[2].toInt() and 0xFF) shl 16) or
                            ((buffer[3].toInt() and 0xFF) shl 8) or
                            (buffer[4].toInt() and 0xFF)

                    if (senderId == localSenderId) continue

                    // Automatically lock target address on first incoming packet if listening
                    if (targetInetAddress == null) {
                        targetInetAddress = packet.address
                        targetPort = packet.port
                        _peerIp.value = packet.address.hostAddress
                        _connectionState.value = DirectIpState.CONNECTED
                        _statusMessage.value = "Direct-IP Channel Locked (${packet.address.hostAddress})"
                    }

                    when (packetType) {
                        PKT_PING -> {
                            sendPacket(PKT_PONG, packet.address, packet.port, ByteArray(0))
                            if (_connectionState.value != DirectIpState.CONNECTED) {
                                _connectionState.value = DirectIpState.CONNECTED
                                _statusMessage.value = "Connected to ${packet.address.hostAddress}"
                            }
                        }
                        PKT_PONG -> {
                            if (_connectionState.value != DirectIpState.CONNECTED) {
                                _connectionState.value = DirectIpState.CONNECTED
                                _statusMessage.value = "Connected to ${packet.address.hostAddress}"
                            }
                            if (lastPingSentTime > 0) {
                                val roundTrip = (System.currentTimeMillis() - lastPingSentTime).toInt()
                                _latencyMs.value = roundTrip
                            }
                        }
                        PKT_AUDIO -> {
                            val payloadLen = packet.length - 5
                            if (payloadLen > 16) {
                                val audioBytes = voiceEncryptor.decrypt(packet.data, 5, payloadLen)
                                    ?: ByteArray(payloadLen).also {
                                        System.arraycopy(packet.data, 5, it, 0, payloadLen)
                                    }

                                if (audioBytes.isNotEmpty()) {
                                    _isReceiving.value = true
                                    audioTrack?.write(audioBytes, 0, audioBytes.size)
                                }
                            }
                        }
                        PKT_DISCONNECT -> {
                            disconnect()
                        }
                    }
                } catch (e: IOException) {
                    break
                }
            }
            _isReceiving.value = false
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000)
                val peer = targetInetAddress
                if (peer != null) {
                    lastPingSentTime = System.currentTimeMillis()
                    sendPacket(PKT_PING, peer, targetPort, ByteArray(0))
                }
            }
        }
    }

    private fun sendPacket(type: Byte, dest: InetAddress, port: Int, payload: ByteArray) {
        val socket = udpSocket ?: return
        try {
            val packetData = ByteArray(5 + payload.size)
            packetData[0] = type
            packetData[1] = ((localSenderId shr 24) and 0xFF).toByte()
            packetData[2] = ((localSenderId shr 16) and 0xFF).toByte()
            packetData[3] = ((localSenderId shr 8) and 0xFF).toByte()
            packetData[4] = (localSenderId and 0xFF).toByte()

            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, packetData, 5, payload.size)
            }

            val packet = DatagramPacket(packetData, packetData.size, dest, port)
            socket.send(packet)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun startTalking() {
        if (_connectionState.value != DirectIpState.CONNECTED && targetInetAddress == null) return
        if (_isTransmitting.value) return

        triggerHapticFeedback(50)
        _isTransmitting.value = true
        _statusMessage.value = "Transmitting (Global Direct-IP)..."

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 2048)

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            _isTransmitting.value = false
            return
        }

        audioRecord = record

        transmitJob = scope.launch(Dispatchers.IO) {
            try {
                record.startRecording()
                val rawBuffer = ByteArray(bufferSize)

                while (isActive && _isTransmitting.value) {
                    val read = record.read(rawBuffer, 0, rawBuffer.size)
                    if (read > 0) {
                        val encryptedAudio = voiceEncryptor.encrypt(rawBuffer, 0, read)
                        val target = targetInetAddress
                        if (target != null) {
                            sendPacket(PKT_AUDIO, target, targetPort, encryptedAudio)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transmit error", e)
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {}
                audioRecord = null
                _isTransmitting.value = false
            }
        }
    }

    fun stopTalking() {
        _isTransmitting.value = false
        transmitJob?.cancel()
        transmitJob = null
        triggerHapticFeedback(30)
        if (_connectionState.value == DirectIpState.CONNECTED) {
            _statusMessage.value = "Channel Active (${_peerIp.value})"
        }
    }

    fun toggleSpeakerphone() {
        val newSpeaker = !_isSpeakerphone.value
        _isSpeakerphone.value = newSpeaker
        try {
            audioManager.isSpeakerphoneOn = newSpeaker
        } catch (_: Exception) {}
    }

    fun disconnect() {
        val peer = targetInetAddress
        if (peer != null) {
            scope.launch(Dispatchers.IO) {
                sendPacket(PKT_DISCONNECT, peer, targetPort, ByteArray(0))
            }
        }

        heartbeatJob?.cancel()
        heartbeatJob = null

        receiveJob?.cancel()
        receiveJob = null

        transmitJob?.cancel()
        transmitJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        targetInetAddress = null
        _peerIp.value = null
        _connectionState.value = DirectIpState.IDLE
        _isReceiving.value = false
        _isTransmitting.value = false
        _latencyMs.value = 0
        _statusMessage.value = "Direct-IP Link Disconnected"
    }

    private fun triggerHapticFeedback(millis: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }
}

enum class DirectIpState {
    IDLE,
    LISTENING,
    CONNECTING,
    CONNECTED
}
