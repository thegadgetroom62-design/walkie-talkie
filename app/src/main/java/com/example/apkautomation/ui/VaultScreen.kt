package com.example.apkautomation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

@Composable
fun VaultScreen(
    currentPin: String,
    onSavePin: (String) -> Unit
) {
    var pinInput by remember { mutableStateOf(currentPin) }
    var isPinVisible by remember { mutableStateOf(false) }
    var statusFeedback by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Vault Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(ProTheme.EmeraldDark.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = ProTheme.Emerald,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Security Vault",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = ProTheme.TextPrimary
                )
                Text(
                    text = "Off-grid hardware cryptography & radio diagnostics",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProTheme.TextSecondary
                )
            }
        }

        // Status Feedback Snackbar / Banner if modified
        statusFeedback?.let { msg ->
            BentoCard(
                backgroundColor = ProTheme.EmeraldDark.copy(alpha = 0.4f),
                borderColor = ProTheme.Emerald
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ProTheme.Emerald)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(msg, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        // Bento 1: Cryptographic Engine Status
        BentoCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CIPHER SUITE",
                    color = ProTheme.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ProTheme.EmeraldDark.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ProTheme.Emerald)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ARMv8 HW ACCELERATED",
                            color = ProTheme.Emerald,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecuritySpecRow(label = "Algorithm", value = "AES-256-CTR (Stream Cipher)")
                SecuritySpecRow(label = "Key Derivation", value = "PBKDF2 with HMAC-SHA256")
                SecuritySpecRow(label = "KDF Work Factor", value = "10,000 Key Stretching Cycles")
                SecuritySpecRow(label = "Frame Counter", value = "Synchronized 16-byte Nonce IV")
                SecuritySpecRow(label = "Cloud Exposure", value = "0% (Strict Peer-to-Peer)")
            }
        }

        // Bento 2: Shared Secret PIN Key Management
        BentoCard {
            Text(
                text = "ENCRYPTION KEY CONFIGURATION",
                color = ProTheme.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Both walkie-talkies must have the identical secret PIN configured. Mismatched PINs result in unintelligible static.",
                color = ProTheme.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = pinInput,
                onValueChange = { if (it.length <= 12) pinInput = it },
                label = { Text("Shared Secret PIN") },
                singleLine = true,
                visualTransformation = if (isPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                trailingIcon = {
                    IconButton(onClick = { isPinVisible = !isPinVisible }) {
                        Icon(
                            imageVector = if (isPinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle PIN Visibility",
                            tint = ProTheme.TextSecondary
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ProTheme.Emerald,
                    unfocusedBorderColor = ProTheme.BorderSubtle,
                    focusedLabelColor = ProTheme.Emerald,
                    cursorColor = ProTheme.Emerald
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Preset Quick Sync buttons
            Text(
                text = "Quick Sync Presets:",
                color = ProTheme.TextMuted,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("1234", "7777", "9090", "0000").forEach { preset ->
                    Surface(
                        onClick = {
                            pinInput = preset
                            onSavePin(preset)
                            statusFeedback = "Updated & Activated PIN: $preset"
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (pinInput == preset) ProTheme.EmeraldDark.copy(alpha = 0.6f) else ProTheme.SurfaceCardElevated,
                        border = if (pinInput == preset) androidx.compose.foundation.BorderStroke(1.dp, ProTheme.Emerald) else null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = preset,
                            color = if (pinInput == preset) ProTheme.Emerald else ProTheme.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (pinInput.isNotBlank()) {
                        onSavePin(pinInput)
                        statusFeedback = "Encryption Key Saved & Re-keyed Successfully!"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ProTheme.Emerald)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Apply & Derive AES Key",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bento 3: Radio Telemetry & Audio Engine
        BentoCard {
            Text(
                text = "RADIO & AUDIO ENGINE TELEMETRY",
                color = ProTheme.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecuritySpecRow(label = "Bluetooth RF", value = "2.402 - 2.480 GHz FHSS")
                SecuritySpecRow(label = "Wi-Fi Direct Port", value = "TCP Socket 8888 (High-BW)")
                SecuritySpecRow(label = "Sampling Rate", value = "16,000 Hz (16 kHz Wideband)")
                SecuritySpecRow(label = "PCM Encoding", value = "16-Bit Signed Linear PCM")
                SecuritySpecRow(label = "Packet Frame Size", value = "640 Bytes (20 ms latency)")
                SecuritySpecRow(label = "Network Latency", value = "< 25 ms Off-Grid Direct")
            }
        }

        // Bento 4: Security Utilities
        BentoCard {
            Text(
                text = "EMERGENCY ACTIONS",
                color = ProTheme.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val randomPin = Random.nextInt(1000, 9999).toString()
                        pinInput = randomPin
                        onSavePin(randomPin)
                        statusFeedback = "Generated Random Secure PIN: $randomPin"
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Random PIN", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        pinInput = "1234"
                        onSavePin("1234")
                        statusFeedback = "Reset to Default PIN (1234)"
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset 1234", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp)) // Extra clearance for floating dock
    }
}

@Composable
fun SecuritySpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = ProTheme.TextSecondary,
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = ProTheme.TextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}