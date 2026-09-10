package com.example.apkautomation.global

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val localIp by directIpManager.localIp.collectAsState()
    val statusMessage by directIpManager.statusMessage.collectAsState()
    val latencyMs by directIpManager.latencyMs.collectAsState()
    val recentPeers by directIpManager.recentPeers.collectAsState()

    var targetIpInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. My IP Card with Copy
        BentoCard(title = "MY LOCAL IP ADDRESS", subtitle = "Share this IP with your peer to receive calls") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = localIp,
                        color = ProTheme.Emerald,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Port: ${DirectIpCommsManager.DEFAULT_PORT} • UDP Direct",
                        color = ProTheme.TextSecondary,
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { directIpManager.refreshLocalIp() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(ProTheme.SurfaceCardElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh IP",
                            tint = ProTheme.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("My IP", localIp))
                            Toast.makeText(context, "IP Copied to Clipboard", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ProTheme.EmeraldDark,
                            contentColor = ProTheme.Emerald
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. Direct Dial / Connect Card
        BentoCard(title = "DIAL PEER BY IP", subtitle = "Enter your peer's IP address (Satellite, Wi-Fi or Cellular)") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = targetIpInput,
                    onValueChange = { targetIpInput = it },
                    label = { Text("Peer IP Address") },
                    placeholder = { Text("e.g. 192.168.1.45") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ProTheme.Emerald,
                        unfocusedBorderColor = ProTheme.BorderGlow,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = ProTheme.Emerald
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Host / Listen Mode button
                    OutlinedButton(
                        onClick = { directIpManager.startHosting() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (connectionState == DirectIpState.LISTENING) ProTheme.Emerald else ProTheme.BorderGlow),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (connectionState == DirectIpState.LISTENING) ProTheme.Emerald else Color.White
                        )
                    ) {
                        Icon(Icons.Default.Hearing, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (connectionState == DirectIpState.LISTENING) "Listening..." else "Host / Listen", fontSize = 12.sp)
                    }

                    // Direct Connect Button
                    Button(
                        onClick = { directIpManager.connectToPeer(targetIpInput) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ProTheme.Emerald,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Dial Direct-IP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 3. Status & Telemetry Card
        BentoCard(title = "GLOBAL P2P STATUS", subtitle = "Zero centralized servers • AES-256 CTR Encrypted") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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
                        text = "Direct UDP Tunnel • No Carrier Interception",
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

        // 4. Recent Peers
        if (recentPeers.isNotEmpty()) {
            BentoCard(title = "RECENT CONNECTIONS", subtitle = "Tap to quickly redial") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentPeers.forEach { peer ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    targetIpInput = peer
                                    directIpManager.connectToPeer(peer)
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = ProTheme.SurfaceCardElevated,
                            border = BorderStroke(0.6.dp, ProTheme.BorderSubtle)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, contentDescription = null, tint = ProTheme.TextSecondary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(peer, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                }
                                Icon(Icons.Default.PhoneForwarded, contentDescription = null, tint = ProTheme.Emerald, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
