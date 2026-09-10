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
import androidx.core.content.ContextCompat
import com.example.apkautomation.bluetooth.ConnectionState
import com.example.apkautomation.crypto.VoiceEncryptor
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.random.Random

class WifiDirectVoiceManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "WifiDirectVoiceManager"
        const val VOICE_PORT = 8888
        const val TCP_HANDSHAKE_PORT = 8889
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Packet type identifiers
        private const val PKT_PING: Byte = 1
        private const val PKT_PONG: Byte = 2
        private const val PKT_AUDIO: Byte = 3
        private const val PKT_VIDEO_CALL_REQ: Byte = 4
        private const val PKT_VIDEO_CALL_END: Byte = 5
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

    private val _peerAddressFlow = MutableStateFlow<InetAddress?>(null)
    val peerAddressFlow = _peerAddressFlow.asStateFlow()

    private val _isGroupOwnerFlow = MutableStateFlow(false)
    val isGroupOwnerFlow = _isGroupOwnerFlow.asStateFlow()

    private val _isFullDuplexVoice = MutableStateFlow(false)
    val isFullDuplexVoice = _isFullDuplexVoice.asStateFlow()

    private val _isVideoCallActive = MutableStateFlow(false)
    val isVideoCallActive = _isVideoCallActive.asStateFlow()

    val meshRouter = WifiMeshRouter(localSenderId, "Node-${localSenderId % 1000}")
    val activeMeshNodes = meshRouter.meshNodes

    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var receiveJob: Job? = null
    private var transmitJob: Job? = null
    private var heartbeatJob: Job? = null
    private var tcpHandshakeJob: Job? = null

    private var toneGenerator: ToneGenerator? = null
    private var voiceEncryptor: VoiceEncryptor = VoiceEncryptor("1234")

    fun updateSecurityPin(pin: String) {
        val activePin = if (pin.isBlank()) "1234" else pin
        voiceEncryptor = VoiceEncryptor(activePin)
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var pollConnectionJob: Job? = null
    private var passiveWatcherJob: Job? = null

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
                    wifiP2pManager?.requestConnectionInfo(channel) { info ->
                        if (info != null && info.groupFormed) {
                            handleConnectionEstablished(info)
                        } else if (_connectionState.value == ConnectionState.CONNECTED) {
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        intentFilter,
                        ContextCompat.RECEIVER_EXPORTED
                    )
                } else {
                    context.registerReceiver(receiver, intentFilter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Receiver register error", e)
            }
            startPassiveWatcher()
        }
    }

    private fun startPassiveWatcher() {
        passiveWatcherJob?.cancel()
        passiveWatcherJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1200)
                if (_connectionState.value != ConnectionState.CONNECTED && channel != null) {
                    wifiP2pManager?.requestConnectionInfo(channel) { info ->
                        if (info != null && info.groupFormed) {
                            handleConnectionEstablished(info)
                        }
                    }
                }
            }
        }
    }

    fun unregister() {
        passiveWatcherJob?.cancel()
        pollConnectionJob?.cancel()
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
                _statusMessage.value = "Connected! Establishing audio link..."
            }

            override fun onFailure(reason: Int) {
                _connectionState.value = ConnectionState.IDLE
                _statusMessage.value = "Connection failed (Code: $reason)"
            }
        })

        // Active Watchdog: Poll connection info every 500ms
        pollConnectionJob?.cancel()
        pollConnectionJob = scope.launch(Dispatchers.IO) {
            for (i in 1..40) {
                delay(500)
                if (_connectionState.value == ConnectionState.CONNECTED) break
                wifiP2pManager?.requestConnectionInfo(channel) { info ->
                    if (info != null && info.groupFormed) {
                        handleConnectionEstablished(info)
                    }
                }
            }
        }
    }

    private fun handleConnectionEstablished(info: WifiP2pInfo) {
        if (!info.groupFormed) return

        isGroupOwner = info.isGroupOwner
        _isGroupOwnerFlow.value = isGroupOwner
        _connectionState.value = ConnectionState.CONNECTED

        // Ensure Audio subsystem is in Communication mode so mic + speaker are active
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            applySpeakerphoneRouting(_isSpeakerphone.value)
        } catch (e: Exception) {
            Log.e(TAG, "Audio mode error", e)
        }

        if (!isGroupOwner) {
            targetPeerAddress = info.groupOwnerAddress
            _peerAddressFlow.value = info.groupOwnerAddress
            _statusMessage.value = "Connected (Peer: ${info.groupOwnerAddress.hostAddress})"
            startTcpClientHandshake(info.groupOwnerAddress)
        } else {
            _statusMessage.value = "Connected as Host (Awaiting peer handshake...)"
            startTcpServerHandshake()
        }

        startUdpReceiver()
        startContinuousHeartbeat()
    }

    /**
     * Group Owner TCP Server: Waits for Client connection to discover Client's exact IP address
     */
    private fun startTcpServerHandshake() {
        tcpHandshakeJob?.cancel()
        tcpHandshakeJob = scope.launch(Dispatchers.IO) {
            try {
                tcpServerSocket?.close()
                tcpServerSocket = ServerSocket(TCP_HANDSHAKE_PORT).apply {
                    reuseAddress = true
                }
                while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                    try {
                        val clientSocket = tcpServerSocket?.accept() ?: break
                        val clientIp = clientSocket.inetAddress
                        targetPeerAddress = clientIp
                        _peerAddressFlow.value = clientIp
                        _statusMessage.value = "Channel Active (${clientIp.hostAddress})"
                        val out = clientSocket.getOutputStream()
                        out.write(byteArrayOf(1))
                        out.flush()
                        clientSocket.close()
                        break
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP Server error", e)
            }
        }
    }

    /**
     * Client TCP Connect: Pings the Group Owner to exchange and lock IP addresses
     */
    private fun startTcpClientHandshake(hostAddress: InetAddress) {
        tcpHandshakeJob?.cancel()
        tcpHandshakeJob = scope.launch(Dispatchers.IO) {
            for (attempt in 1..40) {
                if (!isActive || _connectionState.value != ConnectionState.CONNECTED) break
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(hostAddress, TCP_HANDSHAKE_PORT), 1500)
                    val input = socket.getInputStream()
                    input.read()
                    socket.close()
                    targetPeerAddress = hostAddress
                    _peerAddressFlow.value = hostAddress
                    _statusMessage.value = "Channel Active (${hostAddress.hostAddress})"
                    break
                } catch (e: Exception) {
                    delay(600)
                }
            }
        }
    }

    /**
     * Continuous 1-second ping to keep the UDP channel, IP lock, and Wi-Fi radio alive
     */
    private fun startContinuousHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                try {
                    meshRouter.pruneStaleNodes()
                    targetPeerAddress?.let { target ->
                        sendPacket(PKT_PING, target, ByteArray(0))
                    }
                } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    private fun sendPacket(
        type: Byte,
        address: InetAddress,
        payload: ByteArray,
        payloadLength: Int = payload.size,
        originId: Int = localSenderId,
        seq: Int = meshRouter.nextSequenceNumber(),
        hops: Byte = WifiMeshRouter.MAX_HOPS
    ) {
        val socket = udpSocket ?: return
        val buffer = ByteArray(10 + payloadLength)
        buffer[0] = type
        buffer[1] = ((originId shr 24) and 0xFF).toByte()
        buffer[2] = ((originId shr 16) and 0xFF).toByte()
        buffer[3] = ((originId shr 8) and 0xFF).toByte()
        buffer[4] = (originId and 0xFF).toByte()
        buffer[5] = ((seq shr 24) and 0xFF).toByte()
        buffer[6] = ((seq shr 16) and 0xFF).toByte()
        buffer[7] = ((seq shr 8) and 0xFF).toByte()
        buffer[8] = (seq and 0xFF).toByte()
        buffer[9] = hops

        if (payloadLength > 0) {
            System.arraycopy(payload, 0, buffer, 10, payloadLength)
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
            udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(VOICE_PORT))
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
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            val buffer = ByteArray(bufferSize + 32)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive && !socket.isClosed) {
                try {
                    socket.receive(packet)

                    if (packet.length < 5) continue

                    val packetType = buffer[0]
                    val originSenderId = ((buffer[1].toInt() and 0xFF) shl 24) or
                            ((buffer[2].toInt() and 0xFF) shl 16) or
                            ((buffer[3].toInt() and 0xFF) shl 8) or
                            (buffer[4].toInt() and 0xFF)

                    // 1. FILTER OUT SELF-ECHO
                    if (originSenderId == localSenderId) {
                        continue
                    }

                    val isMesh = (packet.length >= 10)
                    val seqNum = if (isMesh) {
                        ((buffer[5].toInt() and 0xFF) shl 24) or
                        ((buffer[6].toInt() and 0xFF) shl 16) or
                        ((buffer[7].toInt() and 0xFF) shl 8) or
                        (buffer[8].toInt() and 0xFF)
                    } else 0
                    val hopCount = if (isMesh) buffer[9] else 1.toByte()
                    val payloadOffset = if (isMesh) 10 else 5
                    val payloadLength = packet.length - payloadOffset

                    // 2. Loop prevention & de-duplication
                    if (isMesh && !meshRouter.checkAndRecordPacket(originSenderId, seqNum)) {
                        continue
                    }

                    // 3. Register node in mesh topology
                    meshRouter.registerNode(originSenderId, "Node-${originSenderId % 1000}", (WifiMeshRouter.MAX_HOPS - hopCount + 1), packet.address)

                    // 4. Lock onto the peer's exact IP address
                    if (targetPeerAddress == null || targetPeerAddress != packet.address) {
                        targetPeerAddress = packet.address
                        _peerAddressFlow.value = packet.address
                        _statusMessage.value = "Mesh Active (${packet.address.hostAddress})"
                    }

                    when (packetType) {
                        PKT_PING -> {
                            // Peer is checking in; reply with PONG
                            sendPacket(PKT_PONG, packet.address, ByteArray(0))
                        }
                        PKT_PONG -> {
                            // Heartbeat confirmed
                        }
                        PKT_AUDIO -> {
                            if (payloadLength > 16) {
                                var audioBytes: ByteArray? = null
                                try {
                                    audioBytes = voiceEncryptor.decrypt(packet.data, payloadOffset, payloadLength)
                                } catch (_: Exception) {}

                                // Fallback: If decryption fails, attempt raw audio pass to avoid silent failure
                                if (audioBytes == null && payloadLength > 0) {
                                    audioBytes = ByteArray(payloadLength)
                                    System.arraycopy(packet.data, payloadOffset, audioBytes, 0, payloadLength)
                                }

                                if (audioBytes != null && audioBytes.isNotEmpty()) {
                                    _isReceiving.value = true
                                    audioTrack?.write(audioBytes, 0, audioBytes.size)
                                }
                            }
                        }
                        PKT_VIDEO_CALL_REQ -> {
                            _isVideoCallActive.value = true
                            startFullDuplexVoice()
                        }
                        PKT_VIDEO_CALL_END -> {
                            _isVideoCallActive.value = false
                            stopFullDuplexVoice()
                        }
                    }

                    // 5. Multi-Hop Forwarding across daisy chain
                    if (isMesh && hopCount > 1) {
                        relayMeshPacket(buffer, packet.length, (hopCount - 1).toByte(), packet.address)
                    }
                } catch (e: IOException) {
                    break
                }
            }
            _isReceiving.value = false
        }
    }

    private fun relayMeshPacket(data: ByteArray, length: Int, nextHops: Byte, incomingAddr: InetAddress) {
        val socket = udpSocket ?: return
        val relayBuffer = ByteArray(length)
        System.arraycopy(data, 0, relayBuffer, 0, length)
        relayBuffer[9] = nextHops

        scope.launch(Dispatchers.IO) {
            try {
                val broadcastAddr = InetAddress.getByName("192.168.49.255")
                if (broadcastAddr != incomingAddr) {
                    val p = DatagramPacket(relayBuffer, length, broadcastAddr, VOICE_PORT)
                    socket.send(p)
                }
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    fun startTalking() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        if (_isTransmitting.value) return

        triggerHapticFeedback(60)
        _isTransmitting.value = true
        _statusMessage.value = "Transmitting (Wi-Fi)..."

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
        } catch (_: Exception) {}

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record?.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (_: Exception) {}
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord could not initialize")
            _isTransmitting.value = false
            _statusMessage.value = "Microphone error (Check permission)"
            return
        }

        audioRecord = record
        audioRecord?.startRecording()

        transmitJob = scope.launch(Dispatchers.IO) {
            val rec = audioRecord ?: return@launch
            val buffer = ByteArray(bufferSize)

            while (isActive && _isTransmitting.value) {
                val bytesRead = rec.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    val encrypted = voiceEncryptor.encrypt(buffer, 0, bytesRead)
                    targetPeerAddress?.let { target ->
                        sendPacket(PKT_AUDIO, target, encrypted, encrypted.size)
                    }
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
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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

            // Default to 100% maximum volume for walkie-talkie loudspeaker
            if (enabled) {
                try {
                    val maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCallVol, 0)
                    val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0)
                } catch (se: Exception) {
                    Log.w(TAG, "Cannot override system volume (e.g. DND mode enabled)", se)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speakerphone routing error", e)
        }
    }

    fun disconnect() {
        stopTalking()

        heartbeatJob?.cancel()
        heartbeatJob = null

        tcpHandshakeJob?.cancel()
        tcpHandshakeJob = null

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

        try {
            tcpServerSocket?.close()
        } catch (e: Exception) {
            // Ignored
        } finally {
            tcpServerSocket = null
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            // Ignored
        }

        wifiP2pManager?.removeGroup(channel, null)

        targetPeerAddress = null
        meshRouter.clear()
        _peerAddressFlow.value = null
        _isGroupOwnerFlow.value = false
        _isFullDuplexVoice.value = false
        _isVideoCallActive.value = false
        _connectedDeviceName.value = null
        _connectionState.value = ConnectionState.IDLE
        _isReceiving.value = false
        _statusMessage.value = "Wi-Fi Direct disconnected"
    }

    fun startFullDuplexVoice() {
        if (_isFullDuplexVoice.value) return
        _isFullDuplexVoice.value = true
        startTalking()
    }

    fun stopFullDuplexVoice() {
        if (!_isFullDuplexVoice.value) return
        _isFullDuplexVoice.value = false
        stopTalking()
    }

    fun requestStartVideoCall() {
        _isVideoCallActive.value = true
        startFullDuplexVoice()
        scope.launch(Dispatchers.IO) {
            for (i in 1..4) {
                val peer = targetPeerAddress ?: (if (!isGroupOwner) InetAddress.getByName("192.168.49.1") else null)
                peer?.let { sendPacket(PKT_VIDEO_CALL_REQ, it, ByteArray(0)) }
                delay(120)
            }
        }
    }

    fun requestEndVideoCall() {
        _isVideoCallActive.value = false
        stopFullDuplexVoice()
        scope.launch(Dispatchers.IO) {
            for (i in 1..4) {
                val peer = targetPeerAddress ?: (if (!isGroupOwner) InetAddress.getByName("192.168.49.1") else null)
                peer?.let { sendPacket(PKT_VIDEO_CALL_END, it, ByteArray(0)) }
                delay(120)
            }
        }
    }
}
