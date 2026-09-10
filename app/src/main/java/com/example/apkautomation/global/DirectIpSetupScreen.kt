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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Global Public Address Card (STUN Resolved)
        BentoCard(
            title = "GLOBAL PUBLIC ADDRESS (STUN / ZERO-APP)",
            subtitle = "Share this address for calls over 100+ km (Traverses 4G/5G & Wi-Fi NAT)"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = publicAddress ?: "Resolving STUN...",
                            color = ProTheme.SkyBlue,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = natStatus,
                            color = ProTheme.Emerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
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
                                val addressToShare = publicAddress ?: localIp
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Call me on Walkie-Talkie Pro! My Global Calling Address is: $addressToShare")
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
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val addressToCopy = publicAddress ?: localIp
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Global Address", addressToCopy))
                                Toast.makeText(context, "Global Address Copied", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProTheme.SurfaceCardElevated,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Local Wi-Fi IP Card
        BentoCard(title = "LOCAL WI-FI ADDRESS", subtitle = "Use when both phones are on the same local router") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = localIp,
                        color = ProTheme.Emerald,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Port: ${DirectIpCommsManager.DEFAULT_PORT} • LAN Only",
                        color = ProTheme.TextMuted,
                        fontSize = 11.sp
                    )
                }

                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Local IP", localIp))
                        Toast.makeText(context, "Local IP Copied", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Copy LAN IP", fontSize = 12.sp, color = ProTheme.Emerald)
                }
            }
        }

        // 3. Direct Dial / Connect Card
        BentoCard(title = "DIAL PEER GLOBALLY", subtitle = "Paste your peer's Global Address (e.g. 174.56.23.90:8895)") {
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

        // 4. Status & Telemetry Card
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
                        text = "UDP Hole-Punching Tunnel • Zero Cloud Relay",
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

        // 5. Recent Peers
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
