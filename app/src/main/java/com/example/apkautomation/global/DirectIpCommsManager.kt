package com.example.apkautomation.global

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.apkautomation.crypto.VoiceEncryptor
import com.example.apkautomation.service.WalkieTalkieService
import com.example.apkautomation.signaling.MqttSignalingEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.random.Random

data class LobbyPeer(
    val id: Int,
    val name: String,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Direct-IP Global P2P & 4-Digit Room Code Signaling Engine
 * Supports:
 * 1. Automatic 4-Digit Room Code P2P with Coordinated Hole Punching & Encrypted Relay Fallback.
 * 2. Standalone Serverless Direct-IP dialing (with STUN NAT Traversal & LAN fallback).
 * 3. 1-Tap Preset Channels & Live Online Lobby Calling.
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
        private const val PKT_KEEPALIVE: Byte = 5
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences("walkie_prefs", Context.MODE_PRIVATE)

    // Permanent 6-digit Phone ID per device
    val myPhoneId: Int = run {
        var id = prefs.getInt("my_phone_id", 0)
        if (id == 0) {
            id = Random.nextInt(100000, 999999)
            prefs.edit().putInt("my_phone_id", id).apply()
        }
        id
    }
    private val localSenderId: Int = myPhoneId

    // Customizable User Call Sign
    private val _myCallSign = MutableStateFlow(
        prefs.getString("my_call_sign", null) ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
    )
    val myCallSign = _myCallSign.asStateFlow()

    fun updateCallSign(newSign: String) {
        val trimmed = newSign.trim().ifBlank { "Phone $myPhoneId" }
        _myCallSign.value = trimmed
        prefs.edit().putString("my_call_sign", trimmed).apply()
    }

    // Saved Contacts
    private val _savedContacts = MutableStateFlow<List<SavedContact>>(loadSavedContacts())
    val savedContacts = _savedContacts.asStateFlow()

    private val _incomingWakeAlert = MutableStateFlow<WakeAlert?>(null)
    val incomingWakeAlert = _incomingWakeAlert.asStateFlow()

    private var toneGenerator: ToneGenerator? = null

    private fun loadSavedContacts(): List<SavedContact> {
        val raw = prefs.getString("saved_contacts_list", "") ?: ""
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<SavedContact>()
        raw.split(";").forEach { item ->
            val parts = item.split(",")
            if (parts.size >= 2) {
                val id = parts[0].toIntOrNull()
                val name = parts[1]
                val callSign = if (parts.size >= 3) parts[2] else ""
                if (id != null && name.isNotBlank()) {
                    list.add(SavedContact(id = id, name = name, callSign = callSign))
                }
            }
        }
        return list
    }

    private fun persistContacts(list: List<SavedContact>) {
        val str = list.joinToString(";") { "${it.id},${it.name},${it.callSign}" }
        prefs.edit().putString("saved_contacts_list", str).apply()
        _savedContacts.value = list
    }

    fun saveContact(id: Int, name: String, callSign: String = "") {
        val current = _savedContacts.value.toMutableList()
        current.removeAll { it.id == id }
        current.add(0, SavedContact(id = id, name = name.trim().ifBlank { "Phone $id" }, callSign = callSign.trim()))
        persistContacts(current)
    }

    fun removeContact(id: Int) {
        val current = _savedContacts.value.toMutableList()
        current.removeAll { it.id == id }
        persistContacts(current)
    }

    private val _connectionState = MutableStateFlow(DirectIpState.IDLE)
    val connectionState = _connectionState.asStateFlow()

    private val _roomMode = MutableStateFlow(RoomMode.DISCONNECTED)
    val roomMode = _roomMode.asStateFlow()

    private val _activeRoomCode = MutableStateFlow<String?>(null)
    val activeRoomCode = _activeRoomCode.asStateFlow()

    private val _lastRoomCode = MutableStateFlow<String?>(prefs.getString("last_room_code", null))
    val lastRoomCode = _lastRoomCode.asStateFlow()

    private val _onlinePeers = MutableStateFlow<List<LobbyPeer>>(emptyList())
    val onlinePeers = _onlinePeers.asStateFlow()

    private val _activeCallLabel = MutableStateFlow("Tactical Channel")
    val activeCallLabel = _activeCallLabel.asStateFlow()

    private val _localIp = MutableStateFlow(detectLocalIp())
    val localIp = _localIp.asStateFlow()

    private val _publicAddress = MutableStateFlow<String?>("Resolving STUN...")
    val publicAddress = _publicAddress.asStateFlow()

    private val _natStatus = MutableStateFlow("Querying STUN...")
    val natStatus = _natStatus.asStateFlow()

    private val _peerIp = MutableStateFlow<String?>(null)
    val peerIp = _peerIp.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting = _isTransmitting.asStateFlow()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving = _isReceiving.asStateFlow()

    private val _isSpeakerphone = MutableStateFlow(true)
    val isSpeakerphone = _isSpeakerphone.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready (Room Code or Direct IP)")
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
    private var stunJob: Job? = null
    private var fallbackTimerJob: Job? = null
    private var joinAnnounceJob: Job? = null
    private var lobbyJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var voiceEncryptor: VoiceEncryptor = VoiceEncryptor("1234")
    private var lastPingSentTime = 0L

    var chatManager: com.example.apkautomation.chat.ChatManager? = null

    fun getVoiceEncryptor(): VoiceEncryptor = voiceEncryptor

    fun publishChatMessage(topic: String, payload: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                if (!signalingEngine.isConnected) {
                    signalingEngine.connect(scope)
                }
                signalingEngine.publish(topic, payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish chat message to $topic", e)
            }
        }
    }

    private val signalingEngine = MqttSignalingEngine()

    init {
        try {
            refreshLocalIp()
        } catch (t: Throwable) {
            Log.e(TAG, "refreshLocalIp error", t)
        }
        try {
            initAudioTrack()
        } catch (t: Throwable) {
            Log.e(TAG, "initAudioTrack error", t)
        }

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Throwable) {}

        signalingEngine.onMessageListener = { topic, payload ->
            handleSignalingMessage(topic, payload)
        }

        try {
            startLobbyDiscovery()
        } catch (t: Throwable) {
            Log.e(TAG, "startLobbyDiscovery error", t)
        }
    }

    fun updateSecurityPin(pin: String) {
        val activePin = if (pin.isBlank()) "1234" else pin
        voiceEncryptor = VoiceEncryptor(activePin)
    }

    fun refreshLocalIp() {
        _localIp.value = detectLocalIp()
        resolvePublicAddress()
    }

    fun resolvePublicAddress() {
        stunJob?.cancel()
        stunJob = scope.launch(Dispatchers.IO) {
            try {
                _natStatus.value = "Contacting Google STUN..."
                val result = StunClient.resolvePublicAddress(udpSocket)
                if (result.isSuccessful) {
                    _publicAddress.value = result.addressString
                    _natStatus.value = "NAT Traversal Ready (${result.serverUsed})"
                    Log.i(TAG, "STUN mapped: ${result.addressString}")
                } else {
                    val fallback = "${_localIp.value}:$DEFAULT_PORT"
                    _publicAddress.value = fallback
                    _natStatus.value = "Local Wi-Fi Only"
                }
            } catch (e: Exception) {
                Log.w(TAG, "STUN lookup caught exception: ${e.message}")
                val fallback = "${_localIp.value}:$DEFAULT_PORT"
                _publicAddress.value = fallback
                _natStatus.value = "Local Wi-Fi Only"
            }
        }
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

    // ==========================================
    // ONLINE LOBBY AUTO-DISCOVERY & 1-TAP CALL
    // ==========================================

    fun startLobbyDiscovery() {
        lobbyJob?.cancel()
        lobbyJob = scope.launch(Dispatchers.IO) {
            try {
                if (!signalingEngine.isConnected) {
                    signalingEngine.connect(scope)
                }
                signalingEngine.subscribe("walkie_p2p/lobby/#")
                signalingEngine.subscribe("walkie_p2p/alerts/$myPhoneId/#")
                signalingEngine.subscribe("walkie_p2p/chat/direct/$myPhoneId/#")
                signalingEngine.subscribe("walkie_p2p/chat/room/#")
            } catch (e: Exception) {
                Log.w(TAG, "Lobby connect initial warning: ${e.message}")
            }

            while (isActive) {
                try {
                    if (!signalingEngine.isConnected) {
                        signalingEngine.connect(scope)
                        signalingEngine.subscribe("walkie_p2p/lobby/#")
                        signalingEngine.subscribe("walkie_p2p/alerts/$myPhoneId/#")
                        signalingEngine.subscribe("walkie_p2p/chat/direct/$myPhoneId/#")
                        signalingEngine.subscribe("walkie_p2p/chat/room/#")
                    }

                    val pingMsg = "LOBBY_PING|$myPhoneId|${_myCallSign.value}"
                    signalingEngine.publish("walkie_p2p/lobby/ping", pingMsg.toByteArray(StandardCharsets.UTF_8))

                    // Clean up stale peers older than 10 seconds
                    val now = System.currentTimeMillis()
                    _onlinePeers.value = _onlinePeers.value.filter { (now - it.lastSeen) < 10000 }
                } catch (_: Exception) {}
                delay(3500)
            }
        }
    }

    fun callPeer(peer: LobbyPeer) {
        val code = Random.nextInt(1000, 9999).toString()
        _activeCallLabel.value = "Call with ${peer.name}"
        scope.launch(Dispatchers.IO) {
            val msg = "LOBBY_CALL|${peer.id}|$myPhoneId|$code|${_myCallSign.value}"
            signalingEngine.publish("walkie_p2p/lobby/call", msg.toByteArray(StandardCharsets.UTF_8))
            withContext(Dispatchers.Main) {
                joinRoom(code)
            }
        }
    }

    fun pageChannel1(contact: SavedContact) {
        val code = "1001"
        _activeCallLabel.value = "Channel 1"
        _statusMessage.value = "📢 Paging ${contact.name} to Channel 1..."
        scope.launch(Dispatchers.IO) {
            val alertMsg = "PAGE_ALERT|$myPhoneId|${_myCallSign.value}|$code|${System.currentTimeMillis()}"
            signalingEngine.publish("walkie_p2p/alerts/${contact.id}", alertMsg.toByteArray(StandardCharsets.UTF_8))
            withContext(Dispatchers.Main) {
                joinRoom(code)
            }
        }
    }

    fun sendWakeAlert(contact: SavedContact) {
        val code = Random.nextInt(1000, 9999).toString()
        _activeCallLabel.value = "Call with ${contact.name}"
        _statusMessage.value = "🚨 Paging ${contact.name}... Joining Room $code"
        scope.launch(Dispatchers.IO) {
            val alertMsg = "PAGE_ALERT|$myPhoneId|${_myCallSign.value}|$code|${System.currentTimeMillis()}"
            signalingEngine.publish("walkie_p2p/alerts/${contact.id}", alertMsg.toByteArray(StandardCharsets.UTF_8))
            withContext(Dispatchers.Main) {
                joinRoom(code)
            }
        }
    }

    fun callContact(contact: SavedContact) {
        val code = Random.nextInt(1000, 9999).toString()
        _activeCallLabel.value = "Call with ${contact.name}"
        scope.launch(Dispatchers.IO) {
            val msg = "LOBBY_CALL|${contact.id}|$myPhoneId|$code|${_myCallSign.value}"
            signalingEngine.publish("walkie_p2p/lobby/call", msg.toByteArray(StandardCharsets.UTF_8))
            val alertMsg = "PAGE_ALERT|$myPhoneId|${_myCallSign.value}|$code|${System.currentTimeMillis()}"
            signalingEngine.publish("walkie_p2p/alerts/${contact.id}", alertMsg.toByteArray(StandardCharsets.UTF_8))
            withContext(Dispatchers.Main) {
                joinRoom(code)
            }
        }
    }

    fun joinChannel(channelNumber: Int) {
        val channelCode = (1000 + channelNumber).toString()
        _activeCallLabel.value = "Channel $channelNumber"
        joinRoom(channelCode)
    }

    fun reconnectLastRoom() {
        val code = _lastRoomCode.value ?: return
        joinRoom(code)
    }

    // ==========================================
    // 4-DIGIT ROOM CODE SIGNALING & CALLING
    // ==========================================

    fun createRoom() {
        disconnectInternal()
        val code = Random.nextInt(1000, 9999).toString()
        prefs.edit().putString("last_room_code", code).apply()
        _lastRoomCode.value = code
        _activeRoomCode.value = code
        _roomMode.value = RoomMode.CREATING
        _statusMessage.value = "Creating Room $code..."

        scope.launch(Dispatchers.IO) {
            initUdpSocket(DEFAULT_PORT)
            val stunRes = StunClient.resolvePublicAddress(udpSocket)
            val myPubIp = if (stunRes.isSuccessful) stunRes.publicIp else _localIp.value
            val myPubPort = if (stunRes.isSuccessful) stunRes.publicPort else DEFAULT_PORT

            val connected = signalingEngine.connect(scope)
            if (connected) {
                signalingEngine.subscribe("walkie_p2p/$code/#")
                _roomMode.value = RoomMode.WAITING_FOR_PEER
                _statusMessage.value = "Room $code Ready • Waiting for friend to join..."

                // Announce host endpoint
                val msg = "HOST_READY|$localSenderId|$myPubIp|$myPubPort|${_localIp.value}"
                signalingEngine.publish("walkie_p2p/$code/signal", msg.toByteArray(StandardCharsets.UTF_8))
            } else {
                _roomMode.value = RoomMode.DISCONNECTED
                _statusMessage.value = "Failed to connect to room server. Check internet."
            }
        }
    }

    fun joinRoom(codeRaw: String) {
        val code = codeRaw.trim()
        if (code.length < 4) {
            _statusMessage.value = "Please enter a valid 4-digit code"
            return
        }

        prefs.edit().putString("last_room_code", code).apply()
        _lastRoomCode.value = code

        disconnectInternal()
        _activeRoomCode.value = code
        _roomMode.value = RoomMode.ENCRYPTED_RELAY
        _connectionState.value = DirectIpState.CONNECTED
        _statusMessage.value = "Channel Active • Ready to Talk"
        acquireWakeLocks()
        applySpeakerphoneRouting(true)

        scope.launch(Dispatchers.IO) {
            initUdpSocket(0)
            val stunRes = StunClient.resolvePublicAddress(udpSocket)
            val myPubIp = if (stunRes.isSuccessful) stunRes.publicIp else _localIp.value
            val myPubPort = if (stunRes.isSuccessful) stunRes.publicPort else DEFAULT_PORT

            val connected = if (signalingEngine.isConnected) true else signalingEngine.connect(scope)
            if (connected) {
                signalingEngine.subscribe("walkie_p2p/$code/#")
                signalingEngine.subscribe("walkie_p2p/alerts/$myPhoneId/#")
                signalingEngine.subscribe("walkie_p2p/chat/direct/$myPhoneId/#")
                signalingEngine.subscribe("walkie_p2p/chat/room/#")
                _statusMessage.value = "Channel Active • Ready to Talk"

                // Continuous announcement until connected so both users connect seamlessly
                joinAnnounceJob?.cancel()
                joinAnnounceJob = scope.launch(Dispatchers.IO) {
                    val msg = "JOIN_REQ|$localSenderId|$myPubIp|$myPubPort|${_localIp.value}"
                    for (attempt in 1..10) {
                        if (!isActive) break
                        signalingEngine.publish("walkie_p2p/$code/signal", msg.toByteArray(StandardCharsets.UTF_8))
                        delay(2000)
                    }
                }
            } else {
                _statusMessage.value = "Reconnecting to server..."
            }
        }
    }

    private fun handleSignalingMessage(topic: String, payload: ByteArray) {
        // Handle Global Lobby
        if (topic.startsWith("walkie_p2p/lobby")) {
            val text = String(payload, StandardCharsets.UTF_8)
            val parts = text.split("|")
            if (parts.size >= 3 && parts[0] == "LOBBY_PING") {
                val peerId = parts[1].toIntOrNull() ?: return
                if (peerId != localSenderId) {
                    val name = parts[2].ifBlank { "Nearby Phone" }
                    val current = _onlinePeers.value.toMutableList()
                    current.removeAll { it.id == peerId }
                    current.add(0, LobbyPeer(id = peerId, name = name, lastSeen = System.currentTimeMillis()))
                    _onlinePeers.value = current
                }
            } else if (parts.size >= 4 && parts[0] == "LOBBY_CALL") {
                val targetId = parts[1].toIntOrNull() ?: return
                if (targetId == localSenderId) {
                    val roomCode = parts[3]
                    val callerName = if (parts.size > 4) parts[4] else "Direct Call"
                    Log.i(TAG, "Incoming direct call from $callerName, joining room $roomCode")
                    triggerHapticFeedback(80)
                    _activeCallLabel.value = "Call with $callerName"
                    scope.launch(Dispatchers.Main) {
                        joinRoom(roomCode)
                    }
                }
            }
            return
        }

        // Handle Personal Wake Alerts
        if (topic.startsWith("walkie_p2p/alerts/$myPhoneId")) {
            val text = String(payload, StandardCharsets.UTF_8)
            val parts = text.split("|")
            if (parts.size >= 4 && parts[0] == "PAGE_ALERT") {
                val fromId = parts[1].toIntOrNull() ?: return
                val callerName = parts[2].ifBlank { "Remote Walkie-Talkie" }
                val roomCode = parts[3]
                Log.i(TAG, "🚨 RECEIVED WAKE ALERT from $callerName ($fromId) for Room $roomCode")

                playTacticalWakeTone()
                triggerWakeVibration()
                WalkieTalkieService.showWakeNotification(context, callerName, roomCode)
                _incomingWakeAlert.value = WakeAlert(fromId, callerName, roomCode)
                _activeCallLabel.value = "Call with $callerName"

                scope.launch(Dispatchers.Main) {
                    joinRoom(roomCode)
                }
            }
            return
        }

        // Handle End-to-End Encrypted Chat Messages
        if (topic.startsWith("walkie_p2p/chat/")) {
            chatManager?.handleIncomingMessage(topic, payload)
            return
        }

        val room = _activeRoomCode.value ?: return

        if (topic.endsWith("/audio")) {
            // Audio Relay Packet received over cloud fallback
            if (_roomMode.value == RoomMode.ENCRYPTED_RELAY && payload.size > 4) {
                val senderId = ((payload[0].toInt() and 0xFF) shl 24) or
                        ((payload[1].toInt() and 0xFF) shl 16) or
                        ((payload[2].toInt() and 0xFF) shl 8) or
                        (payload[3].toInt() and 0xFF)

                if (senderId != localSenderId) {
                    val encryptedAudioLen = payload.size - 4
                    val audioBytes = voiceEncryptor.decrypt(payload, 4, encryptedAudioLen)
                        ?: ByteArray(encryptedAudioLen).also {
                            System.arraycopy(payload, 4, it, 0, encryptedAudioLen)
                        }

                    if (audioBytes.isNotEmpty()) {
                        _isReceiving.value = true
                        audioTrack?.write(audioBytes, 0, audioBytes.size)
                        scope.launch {
                            delay(200)
                            _isReceiving.value = false
                        }
                    }
                }
            }
            return
        }

        if (topic.endsWith("/signal")) {
            val text = String(payload, StandardCharsets.UTF_8)
            val parts = text.split("|")
            if (parts.size < 4) return

            val msgType = parts[0]
            val senderId = parts[1].toIntOrNull() ?: return
            if (senderId == localSenderId) return

            val peerPubIp = parts[2]
            val peerPubPort = parts[3].toIntOrNull() ?: DEFAULT_PORT

            _peerIp.value = "$peerPubIp:$peerPubPort"
            _statusMessage.value = "Synchronizing with peer..."

            scope.launch(Dispatchers.IO) {
                try {
                    targetInetAddress = InetAddress.getByName(peerPubIp)
                    targetPort = peerPubPort
                    startHeartbeat()

                    // Coordinated Simultaneous UDP Hole Punching
                    for (i in 1..8) {
                        if (!isActive || _connectionState.value == DirectIpState.CONNECTED) break
                        sendPacket(PKT_PING, targetInetAddress!!, targetPort, ByteArray(0))
                        delay(120)
                    }

                    // If host receiving JOIN_REQ, reply with HOST_ACK
                    if (msgType == "JOIN_REQ") {
                        val stunRes = StunClient.resolvePublicAddress(udpSocket)
                        val myPubIp = if (stunRes.isSuccessful) stunRes.publicIp else _localIp.value
                        val myPubPort = if (stunRes.isSuccessful) stunRes.publicPort else DEFAULT_PORT
                        val ackMsg = "HOST_ACK|$localSenderId|$myPubIp|$myPubPort|${_localIp.value}"
                        signalingEngine.publish("walkie_p2p/$room/signal", ackMsg.toByteArray(StandardCharsets.UTF_8))
                        scheduleFallbackTimer(room)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Hole punch coordination error", e)
                }
            }
        }
    }

    private fun scheduleFallbackTimer(room: String) {
        fallbackTimerJob?.cancel()
        fallbackTimerJob = scope.launch(Dispatchers.IO) {
            delay(2800)
            if (_connectionState.value != DirectIpState.CONNECTED) {
                Log.i(TAG, "Direct UDP hole-punching timed out (Strict CGNAT). Engaging Encrypted Relay Fallback!")
                _roomMode.value = RoomMode.ENCRYPTED_RELAY
                _connectionState.value = DirectIpState.CONNECTED
                _statusMessage.value = "Connected (Encrypted Cloud Relay • Zero-Drop)"
                acquireWakeLocks()
            }
        }
    }

    // ==========================================
    // MANUAL DIRECT-IP DIALING
    // ==========================================

    fun startHosting() {
        disconnectInternal()
        refreshLocalIp()
        _connectionState.value = DirectIpState.LISTENING
        _statusMessage.value = "Listening on port $DEFAULT_PORT (Share your Global Address)"

        initUdpSocket(DEFAULT_PORT)
        startHeartbeat()
        resolvePublicAddress()
    }

    fun connectToPeer(addressInput: String, defaultPortFallback: Int = DEFAULT_PORT) {
        val trimmed = addressInput.trim().removePrefix("CALL-").removePrefix("call-")
        if (trimmed.isBlank()) {
            _statusMessage.value = "Please enter a target address"
            return
        }

        val parts = trimmed.split(":")
        val targetIp = parts[0].trim()
        val port = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: defaultPortFallback else defaultPortFallback

        disconnectInternal()
        refreshLocalIp()
        _connectionState.value = DirectIpState.CONNECTING
        _statusMessage.value = "Punching NAT Hole to $targetIp:$port..."
        _peerIp.value = "$targetIp:$port"

        saveRecentPeer("$targetIp:$port")

        scope.launch(Dispatchers.IO) {
            try {
                targetInetAddress = InetAddress.getByName(targetIp)
                targetPort = port
                initUdpSocket(0)
                startHeartbeat()

                for (i in 1..8) {
                    if (!isActive || _connectionState.value == DirectIpState.CONNECTED) break
                    sendPacket(PKT_PING, targetInetAddress!!, targetPort, ByteArray(0))
                    delay(150)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connect error", e)
                _connectionState.value = DirectIpState.IDLE
                _statusMessage.value = "Invalid Address: ${e.message}"
            }
        }
    }

    private fun saveRecentPeer(address: String) {
        val current = _recentPeers.value.toMutableList()
        current.remove(address)
        current.add(0, address)
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

    private fun initAudioTrack() {
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
            applySpeakerphoneRouting(_isSpeakerphone.value)
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack init error", e)
        }
    }

    private fun startReceiver() {
        receiveJob?.cancel()
        initAudioTrack()

        receiveJob = scope.launch(Dispatchers.IO) {
            val socket = udpSocket ?: return@launch
            val buffer = ByteArray(2048 + 64)
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

                    // Direct UDP packet verified! Cancel relay fallback timer
                    fallbackTimerJob?.cancel()
                    if (_roomMode.value != RoomMode.DISCONNECTED) {
                        _roomMode.value = RoomMode.DIRECT_P2P
                    }

                    if (targetInetAddress == null) {
                        targetInetAddress = packet.address
                        targetPort = packet.port
                        _peerIp.value = "${packet.address.hostAddress}:${packet.port}"
                        _connectionState.value = DirectIpState.CONNECTED
                        _statusMessage.value = "Direct-IP Channel Locked (${packet.address.hostAddress})"
                    }

                    when (packetType) {
                        PKT_PING -> {
                            sendPacket(PKT_PONG, packet.address, packet.port, ByteArray(0))
                            if (_connectionState.value != DirectIpState.CONNECTED) {
                                _connectionState.value = DirectIpState.CONNECTED
                                _statusMessage.value = "Connected to ${packet.address.hostAddress}:${packet.port}"
                            }
                        }
                        PKT_PONG -> {
                            if (_connectionState.value != DirectIpState.CONNECTED) {
                                _connectionState.value = DirectIpState.CONNECTED
                                _statusMessage.value = "Connected to ${packet.address.hostAddress}:${packet.port}"
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
                        PKT_KEEPALIVE -> {}
                        PKT_DISCONNECT -> { disconnect() }
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
            var keepaliveCounter = 0
            while (isActive) {
                delay(2000)
                val peer = targetInetAddress
                if (peer != null) {
                    lastPingSentTime = System.currentTimeMillis()
                    sendPacket(PKT_PING, peer, targetPort, ByteArray(0))
                }

                keepaliveCounter++
                if (keepaliveCounter % 6 == 0 && peer != null) {
                    sendPacket(PKT_KEEPALIVE, peer, targetPort, ByteArray(0))
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
        if (_connectionState.value != DirectIpState.CONNECTED && targetInetAddress == null && _roomMode.value != RoomMode.ENCRYPTED_RELAY) return
        if (_isTransmitting.value) return

        triggerHapticFeedback(50)
        _isTransmitting.value = true
        _statusMessage.value = "Transmitting Voice..."

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

                        if (_roomMode.value == RoomMode.ENCRYPTED_RELAY) {
                            val room = _activeRoomCode.value
                            if (room != null) {
                                val relayPacket = ByteArray(4 + encryptedAudio.size)
                                relayPacket[0] = ((localSenderId shr 24) and 0xFF).toByte()
                                relayPacket[1] = ((localSenderId shr 16) and 0xFF).toByte()
                                relayPacket[2] = ((localSenderId shr 8) and 0xFF).toByte()
                                relayPacket[3] = (localSenderId and 0xFF).toByte()
                                System.arraycopy(encryptedAudio, 0, relayPacket, 4, encryptedAudio.size)
                                signalingEngine.publish("walkie_p2p/$room/audio", relayPacket)
                            }
                        } else {
                            val target = targetInetAddress
                            if (target != null) {
                                sendPacket(PKT_AUDIO, target, targetPort, encryptedAudio)
                            }
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
            _statusMessage.value = if (_roomMode.value == RoomMode.ENCRYPTED_RELAY)
                "Channel Active (Encrypted Relay)"
            else
                "Channel Active (${_peerIp.value})"
        }
    }

    fun toggleSpeakerphone() {
        val newSpeaker = !_isSpeakerphone.value
        _isSpeakerphone.value = newSpeaker
        applySpeakerphoneRouting(newSpeaker)
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
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled

            // Default to 100% maximum volume for walkie-talkie loudspeaker
            if (enabled) {
                try {
                    val maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCallVol, 0)
                    val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0)
                } catch (se: Throwable) {
                    Log.w(TAG, "Audio volume override restricted by system (e.g. DND)", se)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Speakerphone routing error", e)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WalkieTalkiePro::DirectIpWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
            }
            if (wifiLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "WalkieTalkiePro::DirectIpWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            Log.d(TAG, "DirectIp WakeLock and WifiLock acquired for call")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquisition warning", e)
        }
    }

    private fun releaseWakeLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (_: Exception) {}
    }

    fun disconnectInternal() {
        joinAnnounceJob?.cancel()
        joinAnnounceJob = null

        fallbackTimerJob?.cancel()
        fallbackTimerJob = null

        heartbeatJob?.cancel()
        heartbeatJob = null

        receiveJob?.cancel()
        receiveJob = null

        transmitJob?.cancel()
        transmitJob = null

        releaseWakeLocks()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        targetInetAddress = null
        _peerIp.value = null
        _activeRoomCode.value = null
        _roomMode.value = RoomMode.DISCONNECTED
        _connectionState.value = DirectIpState.IDLE
        _isReceiving.value = false
        _isTransmitting.value = false
        _latencyMs.value = 0
    }

    fun disconnect() {
        val peer = targetInetAddress
        if (peer != null) {
            scope.launch(Dispatchers.IO) {
                sendPacket(PKT_DISCONNECT, peer, targetPort, ByteArray(0))
            }
        }

        disconnectInternal()
        _statusMessage.value = "Link Disconnected"
    }

    private fun playTacticalWakeTone() {
        scope.launch(Dispatchers.IO) {
            try {
                applySpeakerphoneRouting(true)
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 450)
                delay(500)
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 450)
                delay(500)
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 700)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play wake tone", e)
            }
        }
    }

    private fun triggerWakeVibration() {
        try {
            val pattern = longArrayOf(0, 300, 200, 300, 200, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
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

enum class RoomMode {
    DISCONNECTED,
    CREATING,
    WAITING_FOR_PEER,
    CONNECTING,
    DIRECT_P2P,
    ENCRYPTED_RELAY
}
