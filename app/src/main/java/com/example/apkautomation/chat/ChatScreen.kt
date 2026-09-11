package com.example.apkautomation.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.apkautomation.crypto.ChatMessage
import com.example.apkautomation.global.SavedContact
import com.example.apkautomation.ui.BentoCard
import com.example.apkautomation.ui.ProTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatManager: ChatManager,
    savedContacts: List<SavedContact>,
    onOpenVault: () -> Unit
) {
    val activeKey by chatManager.activeConversationKey.collectAsState()
    val activeTitle by chatManager.activeConversationTitle.collectAsState()
    val allMessages by chatManager.messages.collectAsState()
    val filteredMessages = remember(allMessages, activeKey) {
        chatManager.getMessagesForConversation(activeKey)
    }

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Mark chat screen active for notifications suppression
    DisposableEffect(Unit) {
        chatManager.isChatScreenVisible = true
        onDispose {
            chatManager.isChatScreenVisible = false
        }
    }

    // Auto-scroll to bottom when messages update
    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ProTheme.Background)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Cockpit Header
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ProTheme.EmeraldDark.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubble,
                            contentDescription = null,
                            tint = ProTheme.Emerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = activeTitle,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            color = ProTheme.TextPrimary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = ProTheme.Emerald,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "AES-256 E2EE • ZERO CLOUD PLAINTEXT",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = ProTheme.Emerald
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenVault,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ProTheme.SurfaceCardElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "PIN Vault",
                            tint = ProTheme.Emerald,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = { chatManager.clearHistory() },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ProTheme.SurfaceCardElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear Chat",
                            tint = ProTheme.TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Channel & Contact Quick Switcher Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    val isSelected = activeKey == "room_1001"
                    FilterChip(
                        selected = isSelected,
                        onClick = { chatManager.openRoomChat("1001", "Channel 1 (Broadcast)") },
                        label = { Text("📢 CH 1", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ProTheme.Emerald,
                            selectedLabelColor = Color.Black,
                            containerColor = ProTheme.SurfaceCardElevated,
                            labelColor = ProTheme.TextSecondary
                        )
                    )
                }

                item {
                    val isSelected = activeKey == "room_1002"
                    FilterChip(
                        selected = isSelected,
                        onClick = { chatManager.openRoomChat("1002", "Channel 2 (Broadcast)") },
                        label = { Text("📢 CH 2", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ProTheme.Emerald,
                            selectedLabelColor = Color.Black,
                            containerColor = ProTheme.SurfaceCardElevated,
                            labelColor = ProTheme.TextSecondary
                        )
                    )
                }

                items(savedContacts) { contact ->
                    val isSelected = activeKey == "direct_${contact.id}"
                    FilterChip(
                        selected = isSelected,
                        onClick = { chatManager.openDirectChatWithContact(contact) },
                        label = { Text("👤 ${contact.name}", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF0284C7),
                            selectedLabelColor = Color.White,
                            containerColor = ProTheme.SurfaceCardElevated,
                            labelColor = ProTheme.TextSecondary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Message Thread
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (filteredMessages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = ProTheme.TextMuted,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "End-to-End Encrypted Channel",
                        color = ProTheme.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Messages in this thread are encrypted with AES-256 using your Vault PIN before transmission over HiveMQ.\nNo plaintext is ever stored on any server.",
                        color = ProTheme.TextSecondary,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredMessages, key = { it.id }) { msg ->
                        MessageBubble(msg = msg)
                    }
                }
            }
        }

        // Tactical Quick Phrases Row
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(ChatManager.QUICK_TACTICAL_PHRASES) { phrase ->
                Surface(
                    onClick = {
                        val isDirect = activeKey.startsWith("direct_")
                        val recipientId = if (isDirect) activeKey.removePrefix("direct_").toIntOrNull() else null
                        val roomCode = if (!isDirect) activeKey.removePrefix("room_") else null
                        chatManager.sendMessage(phrase, recipientId, roomCode)
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = ProTheme.SurfaceCardElevated,
                    border = BorderStroke(0.8.dp, ProTheme.BorderSubtle)
                ) {
                    Text(
                        text = phrase,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ProTheme.Emerald
                    )
                }
            }
        }

        // Input Field & Send Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 100.dp),
                placeholder = {
                    Text(
                        text = "Type encrypted message...",
                        fontSize = 13.sp,
                        color = ProTheme.TextMuted
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ProTheme.Emerald,
                    unfocusedBorderColor = ProTheme.BorderSubtle,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = ProTheme.Emerald,
                    focusedContainerColor = ProTheme.SurfaceCard,
                    unfocusedContainerColor = ProTheme.SurfaceCard
                ),
                shape = RoundedCornerShape(20.dp),
                maxLines = 3
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        val textToSend = inputText.trim()
                        inputText = ""
                        focusManager.clearFocus()
                        val isDirect = activeKey.startsWith("direct_")
                        val recipientId = if (isDirect) activeKey.removePrefix("direct_").toIntOrNull() else null
                        val roomCode = if (!isDirect) activeKey.removePrefix("room_") else null
                        chatManager.sendMessage(textToSend, recipientId, roomCode)
                    }
                },
                enabled = inputText.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (inputText.isNotBlank()) ProTheme.Emerald else ProTheme.SurfaceCardElevated)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (inputText.isNotBlank()) Color.Black else ProTheme.TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(msg.timestamp) { timeFormat.format(Date(msg.timestamp)) }

    val alignment = if (msg.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (msg.isOutgoing) Color(0xFF047857) else ProTheme.SurfaceCardElevated
    val textColor = Color.White

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (msg.isOutgoing) 16.dp else 2.dp,
                        bottomEnd = if (msg.isOutgoing) 2.dp else 16.dp
                    )
                )
                .background(bubbleColor)
                .border(
                    BorderStroke(0.6.dp, if (msg.isOutgoing) Color(0xFF10B981) else ProTheme.BorderSubtle),
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (msg.isOutgoing) 16.dp else 2.dp,
                        bottomEnd = if (msg.isOutgoing) 2.dp else 16.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!msg.isOutgoing) {
                Text(
                    text = msg.senderCallSign,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ProTheme.Emerald
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            Text(
                text = msg.text,
                fontSize = 13.5.sp,
                color = textColor,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedTime,
                    fontSize = 9.5.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace
                )
                if (msg.isOutgoing) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Sent",
                        tint = ProTheme.Emerald,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}
