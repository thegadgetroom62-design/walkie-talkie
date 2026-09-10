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
    val localIp by directIpManager.localIp.collectAsState()
    val publicAddress by directIpManager.publicAddress.collectAsState()
    val statusMessage by directIpManager.statusMessage.collectAsState()
    val latencyMs by directIpManager.latencyMs.collectAsState()

    var joinCodeInput by remember { mutableStateOf("") }
    var targetIpInput by remember { mutableStateOf("") }
    var showManualIp by remember { mutableStateOf(false) }

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
                        text = "Walkie-Talkie Pro v2.0.0",
                        color = ProTheme.Emerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Zero-Config Room P2P",
                    color = ProTheme.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // 1. HERO CARD: 4-DIGIT ROOM CODE (RECOMMENDED)
        BentoCard(
            title = "AUTOMATIC 4-DIGIT ROOM CALLING",
            subtitle = "Zero configuration • Guaranteed to connect across 100+ km on 4G/5G or Wi-Fi"
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
