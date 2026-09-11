package com.example.apkautomation.crypto

import java.util.UUID

/**
 * Represents an End-to-End Encrypted chat message in Walkie-Talkie Pro.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: Int,
    val senderCallSign: String,
    val recipientId: Int? = null,
    val roomCode: String? = null,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false
) {
    val isDirectMessage: Boolean
        get() = recipientId != null

    /**
     * Serializes this message for over-the-air MQTT transmission with encrypted payload.
     */
    fun serializeWire(encryptedPayloadBase64: String): String {
        val recIdStr = recipientId?.toString() ?: ""
        val rmStr = roomCode ?: ""
        return "CHAT_MSG|$id|$senderId|$senderCallSign|$recIdStr|$rmStr|$timestamp|$encryptedPayloadBase64"
    }

    companion object {
        /**
         * Parses wire string format:
         * CHAT_MSG|id|senderId|senderCallSign|recipientId|roomCode|timestamp|encryptedBase64
         */
        fun parseWire(wire: String): WireChatMessage? {
            val parts = wire.split("|")
            if (parts.size < 8 || parts[0] != "CHAT_MSG") return null

            val id = parts[1]
            val senderId = parts[2].toIntOrNull() ?: return null
            val senderCallSign = parts[3]
            val recipientId = parts[4].toIntOrNull()
            val roomCode = parts[5].ifBlank { null }
            val timestamp = parts[6].toLongOrNull() ?: System.currentTimeMillis()
            val encryptedBase64 = parts[7]

            return WireChatMessage(
                id = id,
                senderId = senderId,
                senderCallSign = senderCallSign,
                recipientId = recipientId,
                roomCode = roomCode,
                timestamp = timestamp,
                encryptedBase64 = encryptedBase64
            )
        }
    }
}

data class WireChatMessage(
    val id: String,
    val senderId: Int,
    val senderCallSign: String,
    val recipientId: Int?,
    val roomCode: String?,
    val timestamp: Long,
    val encryptedBase64: String
)

data class ChatConversation(
    val key: String, // e.g. "direct_123456" or "room_1001"
    val title: String,
    val subtitle: String,
    val isDirect: Boolean,
    val targetId: Int? = null,
    val roomCode: String? = null,
    val lastMessage: ChatMessage? = null,
    val unreadCount: Int = 0
)
