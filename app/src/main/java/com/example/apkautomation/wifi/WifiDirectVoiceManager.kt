package com.example.apkautomation.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.apkautomation.bluetooth.ConnectionState
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
import kotlin.random.Random

class WifiDirectVoiceManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "WifiDirectVoiceManager"
        const val VOICE_PORT = 8888
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Packet type identifiers
        private const val PKT_PING: Byte = 1
        private const val PKT_PONG: Byte = 2
        private const val PKT_AUDIO: Byte = 3
    }

    // Unique random ID for this session to filter out self-broadcast echo
    private val localSenderId: Int = Random.nextInt(100000, 999999)

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting = _isTransmitting.asStateFlow()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving = _isReceiving.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to scan Wi-Fi Direct")
    val statusMessage = _statusMessage.asStateFlow()

    private val _isSpeakerphone = MutableStateFlow(true)
    val isSpeakerphone = _isSpeakerphone.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredPeers = _discoveredPeers.asStateFlow()

    private var targetPeerAddress: InetAddress? = null
    private var isGroupOwner = false

    private var udpSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var receiveJob: Job? = null
    private var transmitJob: Job? = null
    private var handshakeJob: Job? = null

    private var toneGenerator: ToneGenerator? = null

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager?.requestPeers(channel) { peers ->
                        _discoveredPeers.value = peers.deviceList.toList()
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        wifiP2pManager?.requestConnectionInfo(channel) { info ->
                            handleConnectionEstablished(info)
                        }
                    } else {
                        if (_connectionState.value == ConnectionState.CONNECTED) {
                            disconnect()
                        }
                    }
                }
            }
        }
    }

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator error", e)
        }
    }

    fun initialize() {
        if (wifiP2pManager != null && channel == null) {
            channel = wifiP2pManager.initialize(context, context.mainLooper, null)
            try {
                context.registerReceiver(receiver, intentFilter)
            } catch (e: Exception) {
                Log.e(TAG, "Receiver register error", e)
            }
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Ignored if not registered
        }
    }

    @SuppressLint("MissingPermission")
    fun startPeerDiscovery() {
        initialize()
        _statusMessage.value = "Scanning for nearby Wi-Fi Direct devices..."
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _statusMessage.value = "Scanning... Select a phone below"
            }

            override fun onFailure(reason: Int) {
                _statusMessage.value = "Wi-Fi Direct scan failed (Code: $reason)"
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice) {
        initialize()
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Connecting to ${device.deviceName}..."
        _connectedDeviceName.value = device.deviceName

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _statusMessage.value = "Connected! Negotiating audio channel..."
            }

            override fun onFailure(reason: Int) {
                _connectionState.value = ConnectionState.IDLE
                _statusMessage.value = "Connection failed (Code: $reason)"
            }
        })
    }

    private fun handleConnectionEstablished(info: WifiP2pInfo) {
        if (!info.groupFormed) return

        isGroupOwner = info.isGroupOwner
        _connectionState.value = ConnectionState.CONNECTED

        if (!isGroupOwner) {
            targetPeerAddress = info.groupOwnerAddress
            _statusMessage.value = "Connected (Peer: ${info.groupOwnerAddress.hostAddress})"
        } else {
            _statusMessage.value = "Connected as Host (Listening for peer IP...)"
        }

        startUdpReceiver()
        startHandshakeLoop()
    }

    /**
     * Periodically send handshake pings to learn peer IP and confirm bidirectional routing
     */
    private fun startHandshakeLoop() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch(Dispatchers.IO) {
            for (i in 1..20) {
                if (!isActive) break
                try {
                    val target = targetPeerAddress ?: InetAddress.getByName("192.168.49.255")
                    sendPacket(PKT_PING, target, ByteArray(0))
                } catch (e: Exception) {
                    // Ignore
                }
                delay(600)
            }
        }
    }

    private fun sendPacket(type: Byte, address: InetAddress, payload: ByteArray, payloadLength: Int = payload.size) {
        val socket = udpSocket ?: return
        val buffer = ByteArray(5 + payloadLength)
        buffer[0] = type
        buffer[1] = ((localSenderId shr 24) and 0xFF).toByte()
        buffer[2] = ((localSenderId shr 16) and 0xFF).toByte()
        buffer[3] = ((localSenderId shr 8) and 0xFF).toByte()
        buffer[4] = (localSenderId and 0xFF).toByte()

        if (payloadLength > 0) {
            System.arraycopy(payload, 0, buffer, 5, payloadLength)
        }

        try {
            val packet = DatagramPacket(buffer, buffer.size, address, VOICE_PORT)
            socket.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Send packet error", e)
        }
    }

    private fun startUdpReceiver() {
        try {
            udpSocket?.close()
            udpSocket = DatagramSocket(VOICE_PORT).apply {
                broadcast = true
            }
        } catch (e: SocketException) {
            Log.e(TAG, "Socket creation failed", e)
            return
        }

        applySpeakerphoneRouting(_isSpeakerphone.value)

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 2048)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
            Log.e(TAG, "AudioTrack failed", e)
            return
        }

        receiveJob = scope.launch(Dispatchers.IO) {
            val socket = udpSocket ?: return@launch
            val buffer = ByteArray(bufferSize + 16)
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

                    // 1. FILTER OUT SELF-ECHO (Ignore broadcast packets reflected back to ourselves)
                    if (senderId == localSenderId) {
                        continue
                    }

                    // 2. Lock onto the peer's exact IP address
                    if (targetPeerAddress == null || targetPeerAddress != packet.address) {
                        targetPeerAddress = packet.address
                        _statusMessage.value = "Channel Active (${packet.address.hostAddress})"
                    }

                    when (packetType) {
                        PKT_PING -> {
                            // Peer is looking for us; send PONG reply directly to their IP
                            sendPacket(PKT_PONG, packet.address, ByteArray(0))
                        }
                        PKT_PONG -> {
                            // Handshake confirmed
                        }
                        PKT_AUDIO -> {
                            val audioLength = packet.length - 5
                            if (audioLength > 0) {
                                _isReceiving.value = true
                                audioTrack?.write(packet.data, 5, audioLength)
                            }
                        }
                    }
                } catch (e: IOException) {
                    break
                }
            }
            _isReceiving.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startTalking() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        if (_isTransmitting.value) return

        triggerHapticFeedback(60)
        _isTransmitting.value = true
        _statusMessage.value = "Transmitting (Wi-Fi Direct)..."

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT,
                bufferSize
            )
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e)
            _isTransmitting.value = false
            return
        }

        transmitJob = scope.launch(Dispatchers.IO) {
            val record = audioRecord ?: return@launch
            val buffer = ByteArray(bufferSize)

            while (isActive && _isTransmitting.value) {
                val bytesRead = record.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    val target = targetPeerAddress ?: InetAddress.getByName("192.168.49.255")
                    sendPacket(PKT_AUDIO, target, buffer, bytesRead)
                }
            }
        }
    }

    fun stopTalking() {
        if (!_isTransmitting.value) return

        _isTransmitting.value = false
        _statusMessage.value = "Connected (${_connectedDeviceName.value ?: "Wi-Fi Direct"})"

        transmitJob?.cancel()
        transmitJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord stop error", e)
        } finally {
            audioRecord = null
        }

        triggerHapticFeedback(40)
        playRogerBeep()
    }

    fun playRogerBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {
            Log.e(TAG, "Roger beep error", e)
        }
    }

    private fun triggerHapticFeedback(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Ignored if device lacks vibrator
        }
    }

    fun toggleSpeakerphone() {
        val newState = !_isSpeakerphone.value
        _isSpeakerphone.value = newState
        applySpeakerphoneRouting(newState)
    }

    private fun applySpeakerphoneRouting(enabled: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speakerphone routing error", e)
        }
    }

    fun disconnect() {
        stopTalking()

        handshakeJob?.cancel()
        handshakeJob = null

        receiveJob?.cancel()
        receiveJob = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignored
        } finally {
            audioTrack = null
        }

        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // Ignored
        } finally {
            udpSocket = null
        }

        wifiP2pManager?.removeGroup(channel, null)

        targetPeerAddress = null
        _connectedDeviceName.value = null
        _connectionState.value = ConnectionState.IDLE
        _isReceiving.value = false
        _statusMessage.value = "Wi-Fi Direct disconnected"
    }
}
