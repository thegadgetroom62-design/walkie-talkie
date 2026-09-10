package com.example.apkautomation.global

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.apkautomation.ui.BentoCard
import com.example.apkautomation.ui.ProTheme

@Composable
fun DirectIpSetupScreen(
    directIpManager: DirectIpCommsManager
) {
    val context = LocalContext.current
    val connectionState by directIpManager.connectionState.collectAsState()
    val roomMode by directIpManager.roomMode.collectAsState()
    val activeRoomCode by directIpManager.activeRoomCode.collectAsState()
    val lastRoomCode by directIpManager.lastRoomCode.collectAsState()
    val onlinePeers by directIpManager.onlinePeers.collectAsState()
    val savedContacts by directIpManager.savedContacts.collectAsState()
    val myCallSign by directIpManager.myCallSign.collectAsState()
    val myPhoneId = directIpManager.myPhoneId
    val localIp by directIpManager.localIp.collectAsState()
    val publicAddress by directIpManager.publicAddress.collectAsState()
    val statusMessage by directIpManager.statusMessage.collectAsState()
    val latencyMs by directIpManager.latencyMs.collectAsState()

    var joinCodeInput by remember { mutableStateOf("") }
    var targetIpInput by remember { mutableStateOf("") }
    var showManualIp by remember { mutableStateOf(false) }

    var showEditCallSign by remember { mutableStateOf(false) }
    var editCallSignText by remember { mutableStateOf(myCallSign) }

    var showAddContact by remember { mutableStateOf(false) }
    var addContactIdText by remember { mutableStateOf("") }
    var addContactNameText by remember { mutableStateOf("") }

    // Dialog: Edit My Call Sign
    if (showEditCallSign) {
        AlertDialog(
            onDismissRequest = { showEditCallSign = false },
            title = { Text("Edit Your Call Sign", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This name will appear on friends' phones when you call or wake them:", fontSize = 12.sp, color = ProTheme.TextSecondary)
                    OutlinedTextField(
                        value = editCallSignText,
                        onValueChange = { editCallSignText = it },
                        label = { Text("Call Sign / Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        directIpManager.updateCallSign(editCallSignText)
                        showEditCallSign = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ProTheme.Emerald, contentColor = Color.Black)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditCallSign = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Add Contact by ID
    if (showAddContact) {
        AlertDialog(
            onDismissRequest = { showAddContact = false },
            title = { Text("Add Contact by Phone ID", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ask your friend for their 6-digit Phone ID shown at the top of their screen:", fontSize = 12.sp, color = ProTheme.TextSecondary)
                    OutlinedTextField(
                        value = addContactIdText,
                        onValueChange = { if (it.length <= 6) addContactIdText = it.filter { c -> c.isDigit() } },
                        label = { Text("6-Digit Phone ID") },
                        placeholder = { Text("e.g. 582914") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = addContactNameText,
                        onValueChange = { addContactNameText = it },
                        label = { Text("Friend's Name") },
                        placeholder = { Text("e.g. Dave") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idInt = addContactIdText.toIntOrNull()
                        if (idInt != null && addContactNameText.isNotBlank()) {
                            directIpManager.saveContact(idInt, addContactNameText)
                            addContactIdText = ""
                            addContactNameText = ""
                            showAddContact = false
                            Toast.makeText(context, "Contact saved!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ProTheme.Emerald, contentColor = Color.Black)
                ) {
                    Text("Add Contact", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddContact = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // App Version Confirmation Pill
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = ProTheme.EmeraldDark.copy(alpha = 0.35f),
            border = BorderStroke(0.8.dp, ProTheme.Emerald)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(ProTheme.Emerald)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Walkie-Talkie Pro v2.3.0",
                        color = ProTheme.Emerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Zero-Config Direct Link Active",
                    color = ProTheme.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // 1. MY IDENTITY & CALL SIGN BAR
        BentoCard(
            title = "MY TACTICAL IDENTITY",
            subtitle = "Your unique address & call sign seen by other phones"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Phone ID", myPhoneId.toString()))
                        Toast.makeText(context, "Phone ID #$myPhoneId copied!", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = ProTheme.SurfaceCardElevated,
                    border = BorderStroke(1.dp, ProTheme.Emerald.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("YOUR PHONE ID", fontSize = 9.sp, color = ProTheme.TextSecondary, fontWeight = FontWeight.Bold)
                            Text("#$myPhoneId", fontSize = 15.sp, color = ProTheme.Emerald, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = ProTheme.Emerald, modifier = Modifier.size(16.dp))
                    }
                }

                Surface(
                    onClick = {
                        editCallSignText = myCallSign
                        showEditCallSign = true
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = ProTheme.SurfaceCardElevated,
                    border = BorderStroke(1.dp, ProTheme.SkyBlue.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("CALL SIGN", fontSize = 9.sp, color = ProTheme.TextSecondary, fontWeight = FontWeight.Bold)
                            Text(myCallSign, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = ProTheme.SkyBlue, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // 2. SAVED CONTACTS & REMOTE WAKE
        BentoCard(
            title = "⭐ SAVED CONTACTS & REMOTE WAKE",
            subtitle = "Ring the other phone loudly & wake their screen even when locked"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (savedContacts.isEmpty()) {
                    Text(
                        text = "No saved contacts yet.\nTap '+ Add Contact by ID' or tap 'Save' next to online phones below.",
                        color = ProTheme.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        savedContacts.forEach { contact ->
                            val isOnline = onlinePeers.any { it.id == contact.id }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ProTheme.SurfaceCardElevated,
                                border = BorderStroke(1.dp, if (isOnline) ProTheme.Emerald.copy(alpha = 0.6f) else ProTheme.BorderSubtle),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isOnline) ProTheme.Emerald else Color.Gray)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = contact.name,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    fontSize = 14.sp
                                                )
                                                Text(
                                                    text = "ID: #${contact.id} • ${if (isOnline) "🟢 Online" else "⚪ Screen-Off / Idle"}",
                                                    fontSize = 11.sp,
                                                    color = if (isOnline) ProTheme.Emerald else ProTheme.TextSecondary
                                                )
                                            }
                                        }

                                        IconButton(
                                            onClick = { directIpManager.removeContact(contact.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = ProTheme.TextMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                directIpManager.sendWakeAlert(contact)
                                                Toast.makeText(context, "🚨 Paging ${contact.name}! Alarm triggered.", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .weight(1.2f)
                                                .height(42.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFD32F2F),
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("🚨 WAKE / RING", fontWeight = FontWeight.Black, fontSize = 11.sp)
                                        }

                                        Button(
                                            onClick = { directIpManager.callContact(contact) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(42.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ProTheme.Emerald,
                                                contentColor = Color.Black
                                            )
                                        ) {
                                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("CALL", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        addContactIdText = ""
                        addContactNameText = ""
                        showAddContact = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ProTheme.SkyBlue),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ProTheme.SkyBlue)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("+ Add Contact by ID", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        // 3. QUICK 1-TAP CHANNELS (FASTEST & EASIEST)
        BentoCard(
            title = "📻 TACTICAL PRESET CHANNELS",
            subtitle = "Tap the same channel on both phones to connect immediately — zero typing!"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (ch in 1..4) {
                        val chCode = (1000 + ch).toString()
                        val isCurrent = (activeRoomCode == chCode)
                        Button(
                            onClick = { directIpManager.joinChannel(ch) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCurrent) ProTheme.Emerald else ProTheme.SurfaceCardElevated,
                                contentColor = if (isCurrent) Color.Black else Color.White
                            ),
                            border = BorderStroke(1.dp, if (isCurrent) ProTheme.Emerald else ProTheme.BorderSubtle),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = "CH $ch",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                if (lastRoomCode != null && lastRoomCode != activeRoomCode) {
                    Surface(
                        onClick = { directIpManager.reconnectLastRoom() },
                        shape = RoundedCornerShape(10.dp),
                        color = ProTheme.SkyBlue.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, ProTheme.SkyBlue),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = ProTheme.SkyBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "⚡ Reconnect to Last Room ($lastRoomCode)",
                                color = ProTheme.SkyBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // 4. LIVE ONLINE PHONES (DISCOVERY & 1-TAP CONNECT)
        BentoCard(
            title = "🟢 ONLINE PHONES (AUTO-DISCOVERY)",
            subtitle = "Phones detected on Global Call link"
        ) {
            if (onlinePeers.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ProTheme.Emerald
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Searching for other phones online... Or tap a Channel above.",
                        color = ProTheme.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    onlinePeers.forEach { peer ->
                        val isAlreadySaved = savedContacts.any { it.id == peer.id }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ProTheme.SurfaceCardElevated,
                            border = BorderStroke(1.dp, ProTheme.BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(ProTheme.Emerald)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = peer.name,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "ID: #${peer.id} • Online",
                                            color = ProTheme.Emerald,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (!isAlreadySaved) {
                                        OutlinedButton(
                                            onClick = {
                                                directIpManager.saveContact(peer.id, peer.name)
                                                Toast.makeText(context, "Saved ${peer.name} to Contacts!", Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, ProTheme.SkyBlue),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ProTheme.SkyBlue),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Icon(Icons.Default.StarBorder, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Button(
                                        onClick = { directIpManager.callPeer(peer) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ProTheme.Emerald,
                                            contentColor = Color.Black
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.PhoneInTalk, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("CALL", fontWeight = FontWeight.Black, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. MANUAL 4-DIGIT ROOM CODE (OR HOST CUSTOM ROOM)
        BentoCard(
            title = "CUSTOM 4-DIGIT ROOM CALLING",
            subtitle = "Host a private custom room or enter friend's 4-digit code"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ACTIVE ROOM BANNER (If host has created a room)
                if (roomMode == RoomMode.WAITING_FOR_PEER || (activeRoomCode != null && connectionState != DirectIpState.CONNECTED)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ProTheme.EmeraldDark.copy(alpha = 0.45f),
                        border = BorderStroke(1.5.dp, ProTheme.Emerald)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "YOUR ROOM CODE",
                                color = ProTheme.TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = activeRoomCode ?: "----",
                                color = ProTheme.Emerald,
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )

                            Text(
                                text = "Tell your friend to enter this 4-digit code in their app",
                                color = Color.White,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val code = activeRoomCode ?: ""
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "Join my Walkie-Talkie call! Room Code: $code")
                                            type = "text/plain"
                                        }
                                        val shareIntent = Intent.createChooser(sendIntent, "Share Room Code")
                                        context.startActivity(shareIntent)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ProTheme.Emerald,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share Code", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { directIpManager.disconnect() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                                    border = BorderStroke(1.dp, Color(0xFFFF5252)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Cancel", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    // STEP 1: CREATE ROOM BUTTON
                    Button(
                        onClick = { directIpManager.createRoom() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ProTheme.Emerald,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CREATE 4-DIGIT ROOM (HOST)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                HorizontalDivider(color = ProTheme.BorderSubtle, thickness = 0.8.dp)

                // STEP 2: JOIN ROOM INPUT
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Or enter a code from a friend:",
                        color = ProTheme.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = joinCodeInput,
                            onValueChange = { if (it.length <= 6) joinCodeInput = it.filter { c -> c.isDigit() } },
                            label = { Text("4-Digit Code") },
                            placeholder = { Text("e.g. 7421") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clip.isNullOrBlank()) {
                                            joinCodeInput = clip.filter { it.isDigit() }.take(6)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = ProTheme.SkyBlue)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ProTheme.SkyBlue,
                                unfocusedBorderColor = ProTheme.BorderGlow,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = ProTheme.SkyBlue
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = { directIpManager.joinRoom(joinCodeInput) },
                            modifier = Modifier.height(54.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProTheme.SkyBlue,
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("JOIN & CALL", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // 2. STATUS & ENCRYPTION CARD
        BentoCard(title = "CALL STATUS & ENCRYPTION", subtitle = "AES-256 CTR Encrypted • Zero Cloud Recording") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusMessage,
                        color = when (connectionState) {
                            DirectIpState.CONNECTED -> ProTheme.Emerald
                            DirectIpState.CONNECTING, DirectIpState.LISTENING -> ProTheme.SkyBlue
                            DirectIpState.IDLE -> ProTheme.TextSecondary
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (roomMode) {
                            RoomMode.DIRECT_P2P -> "Direct UDP P2P Active (<20ms)"
                            RoomMode.ENCRYPTED_RELAY -> "Encrypted Cloud Relay Fallback Active"
                            else -> "Dual Transport (P2P + Relay Fallback)"
                        },
                        color = ProTheme.TextMuted,
                        fontSize = 11.sp
                    )
                }

                if (connectionState == DirectIpState.CONNECTED && latencyMs > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ProTheme.SurfaceCardElevated,
                        border = BorderStroke(1.dp, ProTheme.BorderGlow)
                    ) {
                        Text(
                            text = "${latencyMs} ms",
                            color = ProTheme.Emerald,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // 3. ADVANCED MANUAL IP DIALER (OPTIONAL / COLLAPSIBLE)
        BentoCard(
            title = "ADVANCED: MANUAL IP DIALING",
            subtitle = "For Tailscale or Local LAN connections"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show manual IP dialer and STUN address",
                        color = ProTheme.TextSecondary,
                        fontSize = 12.sp
                    )
                    Switch(
                        checked = showManualIp,
                        onCheckedChange = { showManualIp = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ProTheme.Emerald,
                            checkedTrackColor = ProTheme.EmeraldDark
                        )
                    )
                }

                if (showManualIp) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Public STUN: ${publicAddress ?: "Resolving..."}",
                        color = ProTheme.SkyBlue,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Local LAN IP: $localIp:${DirectIpCommsManager.DEFAULT_PORT}",
                        color = ProTheme.TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    OutlinedTextField(
                        value = targetIpInput,
                        onValueChange = { targetIpInput = it },
                        label = { Text("Peer IP:Port") },
                        placeholder = { Text("e.g. 100.85.12.34:8895") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { directIpManager.startHosting() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Host / Listen", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { directIpManager.connectToPeer(targetIpInput) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ProTheme.Emerald, contentColor = Color.Black)
                        ) {
                            Text("Dial IP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
