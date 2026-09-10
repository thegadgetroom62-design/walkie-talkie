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
    val publicAddress by directIpManager.publicAddress.collectAsState()
    val natStatus by directIpManager.natStatus.collectAsState()
    val statusMessage by directIpManager.statusMessage.collectAsState()
    val latencyMs by directIpManager.latencyMs.collectAsState()
    val recentPeers by directIpManager.recentPeers.collectAsState()

    var targetIpInput by remember { mutableStateOf("") }

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
                        text = "Walkie-Talkie Pro v1.9.1",
                        color = ProTheme.Emerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Zero-App Direct P2P",
                    color = ProTheme.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // 1. HOST / LISTEN CARD (RECEIVE CALLS)
        BentoCard(
            title = "1. RECEIVE CALLS (HOST / LISTEN)",
            subtitle = "For the phone waiting to receive a call"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (connectionState == DirectIpState.LISTENING) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ProTheme.EmeraldDark.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, ProTheme.Emerald)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🟢 LISTENING FOR INCOMING CALLS",
                                    color = ProTheme.Emerald,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Port ${DirectIpCommsManager.DEFAULT_PORT} • Waiting for peer to dial...",
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                            OutlinedButton(
                                onClick = { directIpManager.disconnect() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                                border = BorderStroke(1.dp, Color(0xFFFF5252)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Stop", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = { directIpManager.startHosting() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ProTheme.Emerald,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Default.Hearing, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "START LISTENING (HOST CALL)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // Global Public Address
                Text(
                    text = "Your Global Public Address (Share this with peer):",
                    color = ProTheme.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = publicAddress ?: "Resolving STUN...",
                            color = ProTheme.SkyBlue,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = natStatus,
                            color = ProTheme.Emerald,
                            fontSize = 11.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { directIpManager.resolvePublicAddress() },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(ProTheme.SurfaceCardElevated)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh STUN",
                                tint = ProTheme.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Button(
                            onClick = {
                                val addressToShare = publicAddress ?: "${localIp}:${DirectIpCommsManager.DEFAULT_PORT}"
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Call me on Walkie-Talkie Pro! My Global Address is: $addressToShare")
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Share Global Calling Code")
                                context.startActivity(shareIntent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProTheme.SkyBlue,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val addressToCopy = publicAddress ?: "${localIp}:${DirectIpCommsManager.DEFAULT_PORT}"
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Global Address", addressToCopy))
                                Toast.makeText(context, "Address Copied to Clipboard", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProTheme.SurfaceCardElevated,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(
                    text = "Local Wi-Fi LAN Address: $localIp:${DirectIpCommsManager.DEFAULT_PORT}",
                    color = ProTheme.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 2. DIAL PEER CARD (MAKE A CALL)
        BentoCard(
            title = "2. MAKE A CALL (DIAL PEER)",
            subtitle = "For the phone initiating the call (100+ km away or local)"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = targetIpInput,
                    onValueChange = { targetIpInput = it },
                    label = { Text("Peer Global Address") },
                    placeholder = { Text("e.g. 174.56.23.90:8895") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) {
                                    targetIpInput = clip.trim()
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = ProTheme.SkyBlue)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ProTheme.Emerald,
                        unfocusedBorderColor = ProTheme.BorderGlow,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = ProTheme.Emerald
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { directIpManager.connectToPeer(targetIpInput) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProTheme.Emerald,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("DIAL DIRECT-IP NOW", fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        // 3. STATUS & TELEMETRY CARD
        BentoCard(title = "CONNECTION STATUS & ENCRYPTION", subtitle = "Zero centralized servers • AES-256 CTR Encrypted") {
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
                        text = "UDP Direct Hole-Punching • No Cloud Intermediaries",
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

        // 4. RECENT CONNECTIONS CARD
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
