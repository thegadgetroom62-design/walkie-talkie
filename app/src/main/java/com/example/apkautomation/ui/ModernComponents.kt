package com.example.apkautomation.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Modern Pro Color Palette
object ProTheme {
    val Background = Color(0xFF0B0F19)
    val SurfaceCard = Color(0xFF131C2E)
    val SurfaceCardElevated = Color(0xFF1E293B)
    val BorderSubtle = Color(0xFF26354A)
    val BorderGlow = Color(0xFF334155)

    val Emerald = Color(0xFF10B981)
    val EmeraldDark = Color(0xFF065F46)
    val SkyBlue = Color(0xFF38BDF8)
    val Crimson = Color(0xFFEF4444)
    val CrimsonDark = Color(0xFF991B1B)
    val Amber = Color(0xFFF59E0B)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

enum class NavDestination(val label: String, val icon: ImageVector) {
    COMMS("Comms", Icons.Default.PhoneInTalk),
    CHAT("Chat", Icons.Default.ChatBubble),
    RADAR("Radar", Icons.Default.Sensors),
    MAPPER("Mapper", Icons.Default.Map),
    VAULT("Vault", Icons.Default.Security)
}

/**
 * Bento Grid Card with subtle borders and smooth glassmorphism styling
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = ProTheme.SurfaceCard,
    borderColor: Color = ProTheme.BorderSubtle,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun BentoCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    backgroundColor: Color = ProTheme.SurfaceCard,
    borderColor: Color = ProTheme.BorderSubtle,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding)
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = ProTheme.Emerald
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = ProTheme.TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }
            content()
        }
    }
}

/**
 * Floating glassmorphic bottom navigation dock inspired by iOS and Nothing OS
 */
@Composable
fun FloatingBottomDock(
    currentDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(32.dp),
                    spotColor = ProTheme.Emerald.copy(alpha = 0.25f)
                ),
            shape = RoundedCornerShape(32.dp),
            color = ProTheme.SurfaceCard.copy(alpha = 0.95f),
            border = BorderStroke(1.2.dp, ProTheme.BorderGlow)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavDestination.values().forEach { destination ->
                    val isSelected = currentDestination == destination

                    val animatedBgColor by animateColorAsState(
                        targetValue = if (isSelected) ProTheme.EmeraldDark.copy(alpha = 0.6f) else Color.Transparent,
                        animationSpec = tween(250),
                        label = "tabBg"
                    )

                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) ProTheme.Emerald else ProTheme.TextMuted,
                        animationSpec = tween(200),
                        label = "tabContent"
                    )

                    Surface(
                        onClick = { onNavigate(destination) },
                        shape = RoundedCornerShape(24.dp),
                        color = animatedBgColor,
                        border = if (isSelected) BorderStroke(1.dp, ProTheme.Emerald.copy(alpha = 0.4f)) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = destination.label,
                                    color = contentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Audio-reactive pulsating circular PTT button with concentric shockwave rings
 */
@Composable
fun PulsingPttButton(
    isTransmitting: Boolean,
    isReceiving: Boolean,
    onStartTalking: () -> Unit,
    onStopTalking: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pttPulse")

    // Ring 1 Animation
    val ring1Scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1Scale"
    )
    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1Alpha"
    )

    // Ring 2 Animation
    val ring2Scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring2Scale"
    )

    // Breathing pulse for idle
    val idleBreathingScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleScale"
    )

    val isActive = isTransmitting || isReceiving

    val baseColor = when {
        isTransmitting -> ProTheme.Crimson
        isReceiving -> ProTheme.SkyBlue
        else -> ProTheme.Emerald
    }

    val gradientBrush = when {
        isTransmitting -> Brush.radialGradient(
            colors = listOf(Color(0xFFFF5252), ProTheme.CrimsonDark)
        )
        isReceiving -> Brush.radialGradient(
            colors = listOf(Color(0xFF60A5FA), Color(0xFF1D4ED8))
        )
        else -> Brush.radialGradient(
            colors = listOf(Color(0xFF34D399), ProTheme.EmeraldDark)
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(280.dp)
    ) {
        // Outer shockwave 1
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .scale(ring1Scale)
                    .clip(CircleShape)
                    .background(baseColor.copy(alpha = ring1Alpha))
            )
            // Outer shockwave 2
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .scale(ring2Scale)
                    .clip(CircleShape)
                    .background(baseColor.copy(alpha = 0.20f))
            )
        } else {
            // Ambient idle breathing glow
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .scale(idleBreathingScale)
                    .clip(CircleShape)
                    .background(ProTheme.Emerald.copy(alpha = 0.08f))
            )
        }

        // Tactile outer ring
        Box(
            modifier = Modifier
                .size(214.dp)
                .clip(CircleShape)
                .background(Color(0xFF0F172A))
                .padding(6.dp)
        ) {
            // Main Touch Target
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(gradientBrush)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onStartTalking()
                                tryAwaitRelease()
                                onStopTalking()
                            }
                        )
                    }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isReceiving) Icons.Default.VolumeUp else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            isTransmitting -> "TRANSMITTING"
                            isReceiving -> "RECEIVING..."
                            else -> "HOLD TO TALK"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 1.2.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = when {
                            isTransmitting -> "AES-256 MIC ACTIVE"
                            isReceiving -> "STREAMING AUDIO"
                            else -> "RELEASE TO LISTEN"
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Segmented Pill Switcher (e.g. for Bluetooth vs Wi-Fi Direct)
 */
@Composable
fun <T> SegmentedPillSwitcher(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    labelProvider: (T) -> String,
    iconProvider: (T) -> ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ProTheme.SurfaceCard,
        border = BorderStroke(1.dp, ProTheme.BorderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) ProTheme.SurfaceCardElevated else Color.Transparent,
                    label = "pillBg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) ProTheme.Emerald else ProTheme.TextSecondary,
                    label = "pillText"
                )

                Surface(
                    onClick = { onOptionSelected(option) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = bgColor,
                    border = if (isSelected) BorderStroke(1.dp, ProTheme.BorderGlow) else null
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = iconProvider(option),
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = labelProvider(option),
                            color = textColor,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}