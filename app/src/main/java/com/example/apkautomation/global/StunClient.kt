package com.example.apkautomation.global

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Random

/**
 * Embedded RFC 5389 / RFC 3489 STUN Client.
 * Queries public STUN servers to discover the device's public internet IP
 * and NAT-mapped port without requiring any external accounts or VPNs.
 */
object StunClient {
    private const val TAG = "StunClient"

    // Google's high-availability public STUN servers
    private val STUN_SERVERS = listOf(
        Pair("stun.l.google.com", 19302),
        Pair("stun1.l.google.com", 19302),
        Pair("stun2.l.google.com", 19302),
        Pair("stun3.l.google.com", 19302),
        Pair("stun4.l.google.com", 19302)
    )

    private const val MAGIC_COOKIE = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS = 0x0101
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020

    data class StunResult(
        val publicIp: String,
        val publicPort: Int,
        val serverUsed: String,
        val isSuccessful: Boolean = true,
        val errorMessage: String? = null
    ) {
        val addressString: String
            get() = "$publicIp:$publicPort"
    }

    /**
     * Resolves the device's public IP and mapped port.
     * Can query using an existing DatagramSocket (to preserve port mapping) or create a fresh socket.
     */
    suspend fun resolvePublicAddress(existingSocket: DatagramSocket? = null): StunResult = withContext(Dispatchers.IO) {
        val ownsSocket = (existingSocket == null)
        val socket = existingSocket ?: try {
            DatagramSocket().apply {
                soTimeout = 2000
                reuseAddress = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create STUN socket", e)
            return@withContext StunResult("0.0.0.0", 0, "None", false, e.message)
        }

        val originalTimeout = try { socket.soTimeout } catch (_: Exception) { 0 }
        try {
            socket.soTimeout = 2000
        } catch (_: Exception) {}

        var result: StunResult? = null

        for ((serverHost, serverPort) in STUN_SERVERS) {
            try {
                val serverAddress = InetAddress.getByName(serverHost)
                val transactionId = ByteArray(12).apply { Random().nextBytes(this) }

                // Build 20-byte STUN Binding Request Header
                val request = ByteArray(20)
                // Message Type: 0x0001
                request[0] = ((BINDING_REQUEST ushr 8) and 0xFF).toByte()
                request[1] = (BINDING_REQUEST and 0xFF).toByte()
                // Message Length: 0x0000
                request[2] = 0x00
                request[3] = 0x00
                // Magic Cookie: 0x2112A442
                request[4] = 0x21.toByte()
                request[5] = 0x12.toByte()
                request[6] = 0xA4.toByte()
                request[7] = 0x42.toByte()
                // Transaction ID (12 bytes)
                System.arraycopy(transactionId, 0, request, 8, 12)

                val sendPacket = DatagramPacket(request, request.size, serverAddress, serverPort)
                socket.send(sendPacket)

                // Receive response
                val buffer = ByteArray(512)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(receivePacket)

                val parsed = parseStunResponse(buffer, receivePacket.length, transactionId, serverHost)
                if (parsed != null) {
                    result = parsed
                    break
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "STUN timeout contacting $serverHost:$serverPort")
            } catch (e: Exception) {
                Log.w(TAG, "STUN query failed on $serverHost: ${e.message}")
            }
        }

        // Restore socket timeout
        try {
            socket.soTimeout = originalTimeout
        } catch (_: Exception) {}

        if (ownsSocket) {
            try {
                socket.close()
            } catch (_: Exception) {}
        }

        result ?: StunResult("0.0.0.0", 0, "All Servers", false, "Unable to reach public STUN servers")
    }

    private fun parseStunResponse(
        buf: ByteArray,
        length: Int,
        expectedTxId: ByteArray,
        serverHost: String
    ): StunResult? {
        if (length < 20) return null

        val msgType = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (msgType != BINDING_SUCCESS) {
            return null
        }

        val msgLength = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)

        // Verify transaction ID matches
        for (i in 0 until 12) {
            if (buf[8 + i] != expectedTxId[i]) return null
        }

        // Parse attributes
        var offset = 20
        val maxOffset = minOf(length, 20 + msgLength)

        var xorMappedResult: StunResult? = null
        var mappedResult: StunResult? = null

        while (offset + 4 <= maxOffset) {
            val attrType = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
            val attrLen = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
            val valOffset = offset + 4

            if (valOffset + attrLen > maxOffset) break

            if (attrType == ATTR_XOR_MAPPED_ADDRESS && attrLen >= 8) {
                val family = buf[valOffset + 1].toInt() and 0xFF
                if (family == 0x01) { // IPv4
                    val xorPort = ((buf[valOffset + 2].toInt() and 0xFF) shl 8) or (buf[valOffset + 3].toInt() and 0xFF)
                    val port = xorPort xor (MAGIC_COOKIE ushr 16)

                    val b0 = (buf[valOffset + 4].toInt() xor (0x21)) and 0xFF
                    val b1 = (buf[valOffset + 5].toInt() xor (0x12)) and 0xFF
                    val b2 = (buf[valOffset + 6].toInt() xor (0xA4)) and 0xFF
                    val b3 = (buf[valOffset + 7].toInt() xor (0x42)) and 0xFF
                    val ip = "$b0.$b1.$b2.$b3"

                    xorMappedResult = StunResult(ip, port, serverHost, true)
                }
            } else if (attrType == ATTR_MAPPED_ADDRESS && attrLen >= 8) {
                val family = buf[valOffset + 1].toInt() and 0xFF
                if (family == 0x01) { // IPv4
                    val port = ((buf[valOffset + 2].toInt() and 0xFF) shl 8) or (buf[valOffset + 3].toInt() and 0xFF)
                    val b0 = buf[valOffset + 4].toInt() and 0xFF
                    val b1 = buf[valOffset + 5].toInt() and 0xFF
                    val b2 = buf[valOffset + 6].toInt() and 0xFF
                    val b3 = buf[valOffset + 7].toInt() and 0xFF
                    val ip = "$b0.$b1.$b2.$b3"

                    mappedResult = StunResult(ip, port, serverHost, true)
                }
            }

            // STUN attributes are padded to 4-byte boundaries
            val padding = (4 - (attrLen % 4)) % 4
            offset = valOffset + attrLen + padding
        }

        // Prefer XOR-MAPPED-ADDRESS as per RFC 5389
        return xorMappedResult ?: mappedResult
    }
}
