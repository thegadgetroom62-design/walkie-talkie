package com.example.apkautomation.chat

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.apkautomation.crypto.ChatMessage
import com.example.apkautomation.crypto.ChatConversation
import com.example.apkautomation.crypto.VoiceEncryptor
import com.example.apkautomation.global.SavedContact
import com.example.apkautomation.service.WalkieTalkieService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * End-to-End Encrypted (AES-256) Chat and Messaging Manager.
 * Operates over HiveMQ MQTT with zero plaintext broker transmission.
 */
class ChatManager(
    private val context: Context,
    val myPhoneId: Int,
    private val myCallSign: StateFlow<String>,
    private val getVoiceEncryptor: () -> VoiceEncryptor,
    private val onPublishWireMessage: (topic: String, payload: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "ChatManager"
        private const val PREFS_NAME = "walkie_chat_prefs"
        private const val KEY_MESSAGES = "chat_messages_history_json"

        val QUICK_TACTICAL_PHRASES = listOf(
            "Roger that",
            "Copy that",
            "What is your 20?",
            "Stand by",
            "Switch to CH 1",
            "All clear",
            "En route"
        )
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _messages = MutableStateFlow<List<ChatMessage>>(loadMessages())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Currently selected conversation (e.g. room_1001 or direct_123456)
    private val _activeConversationKey = MutableStateFlow<String>("room_1001")
    val activeConversationKey: StateFlow<String> = _activeConversationKey.asStateFlow()

    private val _activeConversationTitle = MutableStateFlow<String>("Channel 1 (Broadcast)")
    val activeConversationTitle: StateFlow<String> = _activeConversationTitle.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    var isChatScreenVisible: Boolean = false

    fun setActiveConversation(key: String, title: String) {
        _activeConversationKey.value = key
        _activeConversationTitle.value = title
    }

    fun openDirectChatWithContact(contact: SavedContact) {
        val key = "direct_${contact.id}"
        setActiveConversation(key, contact.name)
    }

    fun openRoomChat(roomCode: String, roomTitle: String = "Channel $roomCode") {
        val key = "room_$roomCode"
        setActiveConversation(key, roomTitle)
    }

    /**
     * Send an end-to-end encrypted message.
     */
    fun sendMessage(
        text: String,
        recipientId: Int? = null,
        roomCode: String? = null
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val encryptor = getVoiceEncryptor()
        val encryptedBase64 = encryptor.encryptText(trimmed)

        val chatMsg = ChatMessage(
            senderId = myPhoneId,
            senderCallSign = myCallSign.value,
            recipientId = recipientId,
            roomCode = roomCode,
            text = trimmed,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true
        )

        // Store locally immediately
        addMessage(chatMsg)

        val wireString = chatMsg.serializeWire(encryptedBase64)
        val payload = wireString.toByteArray(StandardCharsets.UTF_8)

        val topic = if (recipientId != null) {
            "walkie_p2p/chat/direct/$recipientId"
        } else {
            val code = roomCode ?: "1001"
            "walkie_p2p/chat/room/$code"
        }

        Log.i(TAG, "Publishing encrypted message to $topic (length: ${trimmed.length} chars)")
        onPublishWireMessage(topic, payload)
    }

    /**
     * Handle incoming wire message from MQTT signaling engine.
     */
    fun handleIncomingMessage(topic: String, payload: ByteArray) {
        try {
            val raw = String(payload, StandardCharsets.UTF_8)
            val wire = ChatMessage.parseWire(raw) ?: return

            // Ignore our own echo
            if (wire.senderId == myPhoneId) return

            // Decrypt message content using AES-256
            val encryptor = getVoiceEncryptor()
            val decryptedText = encryptor.decryptText(wire.encryptedBase64) ?: "[🔒 Encrypted - PIN Mismatch]"

            val incomingMessage = ChatMessage(
                id = wire.id,
                senderId = wire.senderId,
                senderCallSign = wire.senderCallSign,
                recipientId = wire.recipientId,
                roomCode = wire.roomCode,
                text = decryptedText,
                timestamp = wire.timestamp,
                isOutgoing = false
            )

            scope.launch(Dispatchers.Main) {
                addMessage(incomingMessage)

                val conversationKey = if (incomingMessage.isDirectMessage) {
                    "direct_${incomingMessage.senderId}"
                } else {
                    "room_${incomingMessage.roomCode ?: "1001"}"
                }

                val isViewingCurrent = isChatScreenVisible && (_activeConversationKey.value == conversationKey)

                if (!isViewingCurrent) {
                    _unreadCount.value = _unreadCount.value + 1
                    triggerTacticalHaptic()
                    WalkieTalkieService.showChatMessageNotification(
                        context = context,
                        senderName = incomingMessage.senderCallSign,
                        senderId = incomingMessage.senderId,
                        messageText = decryptedText,
                        roomCode = incomingMessage.roomCode
                    )
                } else {
                    triggerTacticalHaptic()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming chat message", e)
        }
    }

    private fun addMessage(msg: ChatMessage) {
        val current = _messages.value.toMutableList()
        // Prevent duplicates
        if (current.none { it.id == msg.id }) {
            current.add(msg)
            _messages.value = current
            persistMessages(current)
        }
    }

    fun getMessagesForConversation(key: String): List<ChatMessage> {
        return _messages.value.filter { msg ->
            if (key.startsWith("direct_")) {
                val contactId = key.removePrefix("direct_").toIntOrNull()
                msg.isDirectMessage && (
                    (msg.recipientId == contactId && msg.isOutgoing) ||
                    (msg.senderId == contactId && !msg.isOutgoing)
                )
            } else if (key.startsWith("room_")) {
                val code = key.removePrefix("room_")
                !msg.isDirectMessage && (msg.roomCode == code || (code == "1001" && msg.roomCode == null))
            } else {
                false
            }
        }
    }

    fun clearHistory() {
        _messages.value = emptyList()
        prefs.edit().remove(KEY_MESSAGES).apply()
    }

    private fun triggerTacticalHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 60, 120), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (_: Exception) {}
    }

    private fun loadMessages(): List<ChatMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        val list = mutableListOf<ChatMessage>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ChatMessage(
                        id = obj.getString("id"),
                        senderId = obj.getInt("senderId"),
                        senderCallSign = obj.getString("senderCallSign"),
                        recipientId = if (obj.has("recipientId") && !obj.isNull("recipientId")) obj.getInt("recipientId") else null,
                        roomCode = if (obj.has("roomCode") && !obj.isNull("roomCode")) obj.getString("roomCode") else null,
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp"),
                        isOutgoing = obj.getBoolean("isOutgoing")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading messages", e)
        }
        return list
    }

    private fun persistMessages(list: List<ChatMessage>) {
        try {
            val recent = if (list.size > 200) list.takeLast(200) else list
            val array = JSONArray()
            for (m in recent) {
                val obj = JSONObject().apply {
                    put("id", m.id)
                    put("senderId", m.senderId)
                    put("senderCallSign", m.senderCallSign)
                    put("recipientId", m.recipientId)
                    put("roomCode", m.roomCode)
                    put("text", m.text)
                    put("timestamp", m.timestamp)
                    put("isOutgoing", m.isOutgoing)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving messages", e)
        }
    }
}
