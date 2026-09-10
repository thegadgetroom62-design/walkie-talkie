package com.example.apkautomation.video

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.apkautomation.ui.BentoCard
import com.example.apkautomation.ui.ProTheme
import java.net.InetAddress

@Composable
fun VideoCallScreen(
    videoManager: WifiVideoManager,
    peerName: String,
    peerIp: InetAddress?,
    isGroupOwner: Boolean,
    isSpeakerphone: Boolean,
    isMuted: Boolean,
    onToggleSpeaker: () -> Unit,
    onToggleMute: () -> Unit,
    onEndCall: () -> Unit
) {
    val isVideoActive by videoManager.isVideoActive.collectAsState()
    val isFacingFront by videoManager.isCameraFacingFront.collectAsState()
    val isTorchOn by videoManager.isTorchEnabled.collectAsState()
    val fps by videoManager.fps.collectAsState()
    val statusText by videoManager.statusText.collectAsState()

    var remoteSurfaceHolder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var localSurfaceHolder by remember { mutableStateOf<SurfaceHolder?>(null) }

    // Start video call once surfaces are ready
    LaunchedEffect(remoteSurfaceHolder, localSurfaceHolder, peerIp) {
        val remoteHolder = remoteSurfaceHolder
        val localHolder = localSurfaceHolder
        if (remoteHolder != null && localHolder != null && !isVideoActive) {
            videoManager.startVideoCall(
                peerIp = peerIp,
                isHost = isGroupOwner,
                localSurface = localHolder.surface,
                remoteSurface = remoteHolder.surface
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            videoManager.stopVideoCall()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Full Screen: Remote Peer's Incoming Video Feed
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            remoteSurfaceHolder = holder
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            remoteSurfaceHolder = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Inset Card: Local Self / Tactical Camera Preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Surface(
                modifier = Modifier
                    .width(110.dp)
                    .height(150.dp)
                    .padding(top = 40.dp)
                    .shadow(12.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = Color.DarkGray,
                border = BorderStroke(1.5.dp, ProTheme.Emerald)
            ) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).apply {
                            setZOrderMediaOverlay(true)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    localSurfaceHolder = holder
                                }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    localSurfaceHolder = null
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 3. Top HUD: Cryptography & Telemetry Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.TopStart
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .padding(top = 40.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ProTheme.SurfaceCard.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, ProTheme.BorderGlow)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = ProTheme.Emerald,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "AES-256 CTR VIDEO",
                                color = ProTheme.Emerald,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${fps} FPS",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = peerName,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "$statusText • < 25ms P2P",
                            color = ProTheme.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 4. Bottom Floating Action Pill: Camera Controls & Call Termination
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .shadow(16.dp, RoundedCornerShape(32.dp)),
                shape = RoundedCornerShape(32.dp),
                color = ProTheme.SurfaceCard.copy(alpha = 0.92f),
                border = BorderStroke(1.2.dp, ProTheme.BorderGlow)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Flip Camera (Front / Rear)
                    IconButton(
                        onClick = { videoManager.flipCamera() },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(ProTheme.SurfaceCardElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Flip Lens",
                            tint = ProTheme.Emerald
                        )
                    }

                    // Torch Flashlight Toggle (Rear camera only)
                    if (!isFacingFront) {
                        IconButton(
                            onClick = { videoManager.toggleTorch() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (isTorchOn) ProTheme.Amber.copy(alpha = 0.3f) else ProTheme.SurfaceCardElevated)
                        ) {
                            Icon(
                                imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Torch",
                                tint = if (isTorchOn) ProTheme.Amber else Color.Gray
                            )
                        }
                    }

                    // Mute / Unmute Microphone
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isMuted) ProTheme.CrimsonDark.copy(alpha = 0.4f) else ProTheme.SurfaceCardElevated)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute",
                            tint = if (isMuted) ProTheme.Crimson else Color.White
                        )
                    }

                    // Speakerphone Toggle
                    IconButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakerphone) ProTheme.EmeraldDark else ProTheme.SurfaceCardElevated)
                    ) {
                        Icon(
                            imageVector = if (isSpeakerphone) Icons.Default.VolumeUp else Icons.Default.Hearing,
                            contentDescription = "Speaker",
                            tint = if (isSpeakerphone) ProTheme.Emerald else Color.Gray
                        )
                    }

                    // End Video Call
                    IconButton(
                        onClick = onEndCall,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(ProTheme.Crimson)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "End Call",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
