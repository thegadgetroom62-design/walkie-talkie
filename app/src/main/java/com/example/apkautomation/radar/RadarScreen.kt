package com.example.apkautomation.radar

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RadarScreen(
    radarEngine: WifiRadarEngine
) {
    val role by radarEngine.role.collectAsState()
    val isScanning by radarEngine.isScanning.collectAsState()
    val disturbance by radarEngine.disturbanceLevel.collectAsState()
    val waveform by radarEngine.waveformHistory.collectAsState()
    val isMotion by radarEngine.isMotionDetected.collectAsState()
    val sensitivity by radarEngine.sensitivity.collectAsState()
    val isAlarmSound by radarEngine.isAlarmSoundEnabled.collectAsState()
    val statusText by radarEngine.statusText.collectAsState()

    val radarColor by animateColorAsState(
        targetValue = when {
            isMotion -> Color(0xFFEF4444) // Bright Red on Motion
            disturbance > sensitivity * 0.6f -> Color(0xFFF59E0B) // Amber on Minor movement
            else -> Color(0xFF10B981) // Emerald Green on Still/Quiet
        },
        label = "radarColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            tint = radarColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Wi-Fi RF Motion Radar",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 17.sp
                        )
                    }

                    if (role == RadarRole.SENSOR) {
                        OutlinedButton(
                            onClick = { radarEngine.calibrateRoom() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Calibrate", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = statusText,
                    fontWeight = FontWeight.SemiBold,
                    color = radarColor,
                    fontSize = 14.sp
                )

                if (role == RadarRole.SENSOR) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Disturbance: ${disturbance.toInt()}%",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (isMotion) "TRIPPED" else "CLEAR",
                            fontWeight = FontWeight.Bold,
                            color = radarColor,
                            fontSize = 13.sp
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (disturbance / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = radarColor,
                        trackColor = Color(0xFF334155),
                    )
                }
            }
        }

        // Center: Live Oscilloscope Canvas
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1120)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (role == RadarRole.BEACON) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.WifiTethering,
                            contentDescription = null,
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "BEACON PULSING ACTIVE",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Place this phone in Room A.\nOpen 'Sensor Mode' on your other phone in Room B to detect movement through the wall.",
                            textAlign = TextAlign.Center,
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                } else if (role == RadarRole.SENSOR) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val width = size.width
                        val height = size.height
                        val midY = height / 2

                        // Draw Grid Lines
                        val gridPaint = Color(0xFF1E293B)
                        drawLine(gridPaint, Offset(0f, midY), Offset(width, midY), strokeWidth = 1f)
                        drawLine(gridPaint, Offset(0f, height * 0.25f), Offset(width, height * 0.25f), strokeWidth = 1f)
                        drawLine(gridPaint, Offset(0f, height * 0.75f), Offset(width, height * 0.75f), strokeWidth = 1f)

                        if (waveform.size > 1) {
                            val path = Path()
                            val stepX = width / (waveform.size - 1)

                            waveform.forEachIndexed { index, value ->
                                val x = index * stepX
                                val y = midY - (value * (height * 0.4f))
                                if (index == 0) {
                                    path.moveTo(x, y)
                                } else {
                                    path.lineTo(x, y)
                                }
                            }

                            drawPath(
                                path = path,
                                color = radarColor,
                                style = Stroke(width = 3.5f)
                            )
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Invisible Wi-Fi Radar",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Set one phone as BEACON in one room,\nand the other as SENSOR in the other room.",
                            textAlign = TextAlign.Center,
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Bottom Controls Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (role == RadarRole.SENSOR) {
                    // Sensitivity Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sensitivity (${sensitivity.toInt()}%)",
                            fontSize = 13.sp,
                            color = Color.LightGray
                        )
                        IconButton(onClick = { radarEngine.toggleAlarmSound() }) {
                            Icon(
                                imageVector = if (isAlarmSound) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = "Sound Alert",
                                tint = if (isAlarmSound) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }
                    }
                    Slider(
                        value = sensitivity,
                        onValueChange = { radarEngine.setSensitivity(it) },
                        valueRange = 15f..80f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isScanning) {
                        Button(
                            onClick = { radarEngine.startBeaconMode() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Set Beacon")
                        }

                        Button(
                            onClick = { radarEngine.startSensorMode() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Set Sensor", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { radarEngine.stopRadar() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Radar Scan", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
