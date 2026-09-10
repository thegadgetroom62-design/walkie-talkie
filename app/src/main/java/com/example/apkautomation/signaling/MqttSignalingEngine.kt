package com.example.apkautomation.signaling

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Random

/**
 * Lightweight Zero-Dependency MQTT v3.1.1 Signaling Engine
 * Connects to public MQTT brokers (e.g. broker.hivemq.com) for instantaneous,
 * serverless room handshakes and encrypted audio relay fallback.
 */
class MqttSignalingEngine(
    private val candidateHosts: List<String> = listOf("broker.emqx.io", "test.mosquitto.org", "broker.hivemq.com"),
    private val port: Int = 1883
) {
    private var activeHost: String = candidateHosts.first()
    companion object {
        private const val TAG = "MqttSignaling"
        private const val PKT_CONNECT: Byte = 0x10
        private const val PKT_CONNACK = 2
        private const val PKT_PUBLISH = 3
        private const val PKT_PUBACK = 4
        private const val PKT_SUBSCRIBE: Byte = 0x82.toByte()
        private const val PKT_SUBACK = 9
        private const val PKT_PINGREQ: Byte = 0xC0.toByte()
        private const val PKT_PINGRESP = 13
        private const val PKT_DISCONNECT: Byte = 0xE0.toByte()
    }

    private var socket: Socket? = null
    private var outputStream: BufferedOutputStream? = null
    private var inputStream: BufferedInputStream? = null
    private var listenerJob: Job? = null
    private var pingJob: Job? = null

    var isConnected: Boolean = false
        private set

    var onMessageListener: ((topic: String, payload: ByteArray) -> Unit)? = null
    var onConnectionStateListener: ((connected: Boolean, error: String?) -> Unit)? = null

    private val clientId: String = "WalkiePro_" + Random().nextInt(100000, 999999)

    suspend fun connect(scope: CoroutineScope): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        var lastError: String? = null
        for (host in candidateHosts) {
            try {
                Log.i(TAG, "Attempting signaling connection to $host:$port...")
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                s.soTimeout = 0

                socket = s
                outputStream = BufferedOutputStream(s.getOutputStream())
                inputStream = BufferedInputStream(s.getInputStream())

                sendConnectPacket()

                // Read CONNACK
                val input = inputStream ?: throw EOFException("Input stream null")
                val header = input.read()
                if (header == -1) throw EOFException("Broker closed connection")
                val len = readRemainingLength(input)
                val connackPayload = ByteArray(len)
                var read = 0
                while (read < len) {
                    val r = input.read(connackPayload, read, len - read)
                    if (r == -1) break
                    read += r
                }

                if ((header ushr 4) != PKT_CONNACK || (connackPayload.size >= 2 && connackPayload[1].toInt() != 0)) {
                    throw Exception("CONNACK rejected by $host")
                }

                activeHost = host
                isConnected = true
                Log.i(TAG, "Connected to signaling broker $host successfully as $clientId")
                onConnectionStateListener?.invoke(true, null)

                startListener(scope)
                startPingLoop(scope)
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "Broker $host failed: ${e.message}")
                lastError = e.message
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                outputStream = null
                inputStream = null
            }
        }
        Log.e(TAG, "All signaling brokers failed. Last error: $lastError")
        disconnect()
        onConnectionStateListener?.invoke(false, lastError)
        false
    }

    private fun startListener(scope: CoroutineScope) {
        listenerJob?.cancel()
        listenerJob = scope.launch(Dispatchers.IO) {
            val input = inputStream ?: return@launch
            try {
                while (isActive && isConnected) {
                    val header = input.read()
                    if (header == -1) break
                    val pktType = header ushr 4
                    val remainingLength = readRemainingLength(input)

                    val payload = ByteArray(remainingLength)
                    var read = 0
                    while (read < remainingLength) {
                        val r = input.read(payload, read, remainingLength - read)
                        if (r == -1) break
                        read += r
                    }

                    when (pktType) {
                        PKT_PUBLISH -> {
                            if (payload.size >= 2) {
                                val topicLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                                if (payload.size >= 2 + topicLen) {
                                    val topic = String(payload, 2, topicLen, StandardCharsets.UTF_8)
                                    val dataOffset = 2 + topicLen
                                    val data = payload.copyOfRange(dataOffset, payload.size)
                                    onMessageListener?.invoke(topic, data)
                                }
                            }
                        }
                        PKT_PINGRESP -> {
                            // Heartbeat response received
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Listener exception: ${e.message}")
            } finally {
                disconnect()
            }
        }
    }

    private fun startPingLoop(scope: CoroutineScope) {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected) {
                delay(25000)
                try {
                    synchronized(this@MqttSignalingEngine) {
                        val out = outputStream ?: return@synchronized
                        out.write(byteArrayOf(PKT_PINGREQ, 0x00))
                        out.flush()
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    suspend fun subscribe(topic: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext false
        try {
            synchronized(this@MqttSignalingEngine) {
                val out = outputStream ?: return@withContext false
                val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)

                // Variable header (Packet ID = 1) + payload (Topic len + topic + QoS 0)
                val body = ByteArrayOutputStream()
                body.write(0x00)
                body.write(0x01) // Packet ID = 1
                body.write((topicBytes.size ushr 8) and 0xFF)
                body.write(topicBytes.size and 0xFF)
                body.write(topicBytes)
                body.write(0x00) // Requested QoS = 0

                val bodyBytes = body.toByteArray()
                out.write(PKT_SUBSCRIBE.toInt())
                writeRemainingLength(out, bodyBytes.size)
                out.write(bodyBytes)
                out.flush()
            }
            Log.i(TAG, "Subscribed to topic: $topic")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to $topic", e)
            false
        }
    }

    suspend fun publish(topic: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext false
        try {
            synchronized(this@MqttSignalingEngine) {
                val out = outputStream ?: return@withContext false
                val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)

                val body = ByteArrayOutputStream()
                body.write((topicBytes.size ushr 8) and 0xFF)
                body.write(topicBytes.size and 0xFF)
                body.write(topicBytes)
                body.write(data)

                val bodyBytes = body.toByteArray()
                out.write(0x30) // PUBLISH QoS 0
                writeRemainingLength(out, bodyBytes.size)
                out.write(bodyBytes)
                out.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Publish to $topic failed", e)
            false
        }
    }

    fun disconnect() {
        isConnected = false
        listenerJob?.cancel()
        listenerJob = null
        pingJob?.cancel()
        pingJob = null

        try {
            val out = outputStream
            if (out != null) {
                out.write(byteArrayOf(PKT_DISCONNECT, 0x00))
                out.flush()
            }
        } catch (_: Exception) {}

        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
        onConnectionStateListener?.invoke(false, null)
    }

    private fun sendConnectPacket() {
        val out = outputStream ?: return
        val clientIdBytes = clientId.toByteArray(StandardCharsets.UTF_8)

        val varHeaderAndPayload = ByteArrayOutputStream()
        // Protocol Name: "MQTT"
        varHeaderAndPayload.write(0x00)
        varHeaderAndPayload.write(0x04)
        varHeaderAndPayload.write("MQTT".toByteArray(StandardCharsets.UTF_8))
        // Protocol Level: 4 (v3.1.1)
        varHeaderAndPayload.write(0x04)
        // Connect Flags: Clean Session (0x02)
        varHeaderAndPayload.write(0x02)
        // Keep Alive: 60s
        varHeaderAndPayload.write(0x00)
        varHeaderAndPayload.write(0x3C)
        // Client ID
        varHeaderAndPayload.write((clientIdBytes.size ushr 8) and 0xFF)
        varHeaderAndPayload.write(clientIdBytes.size and 0xFF)
        varHeaderAndPayload.write(clientIdBytes)

        val bytes = varHeaderAndPayload.toByteArray()
        out.write(PKT_CONNECT.toInt())
        writeRemainingLength(out, bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun writeRemainingLength(out: BufferedOutputStream, length: Int) {
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) {
                encodedByte = encodedByte or 0x80
            }
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun readRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        var digit: Int
        do {
            digit = input.read()
            if (digit == -1) throw EOFException("Connection closed while reading length")
            value += (digit and 0x7F) * multiplier
            multiplier *= 128
        } while ((digit and 0x80) != 0)
        return value
    }
}
