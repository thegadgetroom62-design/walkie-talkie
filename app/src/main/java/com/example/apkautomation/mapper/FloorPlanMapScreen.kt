package com.example.apkautomation.mapper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FloorPlanMapScreen(
    mapperEngine: WifiMapperEngine
) {
    val isMapping by mapperEngine.isMapping.collectAsState()
    val points by mapperEngine.points.collectAsState()
    val walls by mapperEngine.walls.collectAsState()
    val currentPos by mapperEngine.currentPosition.collectAsState()
    val heading by mapperEngine.currentHeadingDegrees.collectAsState()
    val currentRssi by mapperEngine.currentRssi.collectAsState()
    val totalSteps by mapperEngine.totalSteps.collectAsState()
    val distance by mapperEngine.totalDistanceMeters.collectAsState()

    // Pan & Zoom gestures for blueprint canvas
    var scale by remember { mutableStateOf(18f) } // Pixels per meter
    var offset by remember { mutableStateOf(Offset.Zero) }

    val signalColor = remember(currentRssi) {
        when {
            currentRssi >= -50 -> Color(0xFFEF4444) // Very close to router (Strong red)
            currentRssi >= -65 -> Color(0xFFF59E0B) // Intermediate living area (Amber)
            currentRssi >= -75 -> Color(0xFF10B981) // Bedrooms through drywall (Green)
            currentRssi >= -82 -> Color(0xFF06B6D4) // Attenuated cyan
            else -> Color(0xFF3B82F6)               // Far perimeter / Deep blue
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top HUD
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Wi-Fi Floor Plan SLAM",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }

                    Row {
                        IconButton(
                            onClick = { mapperEngine.resetMap() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset",
                                tint = Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Wi-Fi Signal",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = "$currentRssi dBm",
                            fontWeight = FontWeight.Bold,
                            color = signalColor,
                            fontSize = 15.sp
                        )
                    }

                    Column {
                        Text(
                            text = "Distance Walked",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = String.format("%.1f m (%d steps)", distance, totalSteps),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }

                    Column {
                        Text(
                            text = "Walls Detected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = "${walls.size} boundaries",
                            fontWeight = FontWeight.Bold,
                            color = if (walls.isNotEmpty()) Color(0xFFEF4444) else Color.LightGray,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Center: 2D Blueprint Canvas with Pan & Zoom
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF030712)), // Pitch black blueprint
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(8f, 50f)
                            offset += pan
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val originX = canvasWidth / 2 + offset.x
                    val originY = canvasHeight / 2 + offset.y

                    // 1. Draw Architectural Grid Lines (1 meter intervals)
                    val gridSpacingPx = scale
                    val gridColor = Color(0xFF1E293B).copy(alpha = 0.6f)

                    var curX = originX % gridSpacingPx
                    while (curX < canvasWidth) {
                        drawLine(gridColor, Offset(curX, 0f), Offset(curX, canvasHeight), strokeWidth = 1f)
                        curX += gridSpacingPx
                    }
                    var curY = originY % gridSpacingPx
                    while (curY < canvasHeight) {
                        drawLine(gridColor, Offset(0f, curY), Offset(canvasWidth, curY), strokeWidth = 1f)
                        curY += gridSpacingPx
                    }

                    // 2. Draw Start Point (Origin 0,0)
                    drawCircle(
                        color = Color(0xFF38BDF8),
                        radius = 6f,
                        center = Offset(originX, originY)
                    )

                    // 3. Draw Heat-map Trail Path
                    if (points.size > 1) {
                        val path = Path()
                        points.forEachIndexed { index, pt ->
                            val px = originX + (pt.x * scale)
                            val py = originY - (pt.y * scale) // Canvas Y is inverted
                            if (index == 0) {
                                path.moveTo(px, py)
                            } else {
                                path.lineTo(px, py)
                            }

                            // Heat-map colored node for each step
                            val nodeColor = when {
                                pt.rssi >= -50 -> Color(0xFFEF4444)
                                pt.rssi >= -65 -> Color(0xFFF59E0B)
                                pt.rssi >= -75 -> Color(0xFF10B981)
                                pt.rssi >= -82 -> Color(0xFF06B6D4)
                                else -> Color(0xFF3B82F6)
                            }

                            drawCircle(
                                color = nodeColor,
                                radius = 7f,
                                center = Offset(px, py)
                            )
                        }

                        drawPath(
                            path = path,
                            color = Color.White.copy(alpha = 0.5f),
                            style = Stroke(width = 2.5f)
                        )
                    }

                    // 4. Draw Detected Wall Boundary Markers
                    walls.forEach { wall ->
                        val wx = originX + (wall.x * scale)
                        val wy = originY - (wall.y * scale)

                        // Red X marker for physical wall drop
                        val size = 9f
                        drawLine(
                            Color(0xFFEF4444),
                            Offset(wx - size, wy - size),
                            Offset(wx + size, wy + size),
                            strokeWidth = 3f
                        )
                        drawLine(
                            Color(0xFFEF4444),
                            Offset(wx - size, wy + size),
                            Offset(wx + size, wy - size),
                            strokeWidth = 3f
                        )
                    }

                    // 5. Draw User's Current Position & Compass Heading Cursor
                    val userPx = originX + (currentPos.first * scale)
                    val userPy = originY - (currentPos.second * scale)

                    // Pulsing outer ring
                    drawCircle(
                        color = Color(0xFF38BDF8).copy(alpha = 0.35f),
                        radius = 16f,
                        center = Offset(userPx, userPy)
                    )
                    // Inner dot
                    drawCircle(
                        color = Color(0xFF38BDF8),
                        radius = 8f,
                        center = Offset(userPx, userPy)
                    )

                    // Directional Pointer Arrow (Heading)
                    val headingRad = Math.toRadians(heading.toDouble())
                    val arrowLen = 22f
                    val arrowEndX = userPx + (sin(headingRad) * arrowLen).toFloat()
                    val arrowEndY = userPy - (cos(headingRad) * arrowLen).toFloat()

                    drawLine(
                        color = Color(0xFF38BDF8),
                        start = Offset(userPx, userPy),
                        end = Offset(arrowEndX, arrowEndY),
                        strokeWidth = 4f
                    )
                }

                // Overlay legend at top right
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(Color(0xFF1E293B).copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text("Signal Heatmap", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    LegendRow(Color(0xFFEF4444), "> -50 dBm (Router)")
                    LegendRow(Color(0xFFF59E0B), "-65 dBm (Hall)")
                    LegendRow(Color(0xFF10B981), "-75 dBm (Room)")
                    LegendRow(Color(0xFF3B82F6), "< -82 dBm (Outer)")
                    LegendRow(Color(0xFFEF4444), "✖ Wall Boundary", isMarker = true)
                }

                // Canvas instructions at bottom
                if (points.size <= 1) {
                    Text(
                        text = "Walk naturally from room to room with phone in hand.\nPinch to zoom • Drag to pan map.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    )
                }
            }
        }

        // Bottom Start / Stop Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isMapping) {
                    Button(
                        onClick = { mapperEngine.startMapping() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start Floor Plan Scan", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { mapperEngine.stopMapping() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pause Mapping", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, isMarker: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = label, color = Color.LightGray, fontSize = 9.sp)
    }
}
