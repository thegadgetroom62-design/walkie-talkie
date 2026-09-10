package com.example.apkautomation.video

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
    val localRotation by videoManager.localCameraRotation.collectAsState()
    val remoteRotation by videoManager.remoteRotation.collectAsState()

    var remoteSurface by remember { mutableStateOf<Surface?>(null) }
    var localSurface by remember { mutableStateOf<Surface?>(null) }
    var isAspectFill by remember { mutableStateOf(false) }

    // Start video call once surfaces are ready
    LaunchedEffect(remoteSurface, localSurface, peerIp) {
        val remote = remoteSurface
        val local = localSurface
        if (remote != null && local != null && !isVideoActive) {
            videoManager.startVideoCall(
                peerIp = peerIp,
                isHost = isGroupOwner,
                localSurface = local,
                remoteSurface = remote
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
        // 1. Full Screen: Remote Peer's Incoming Video Feed (Aspect-Fit default, tap to toggle Fill)
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            st.setDefaultBufferSize(WifiVideoManager.VIDEO_WIDTH, WifiVideoManager.VIDEO_HEIGHT)
                            applyTextureTransform(
                                view = this@apply,
                                viewWidth = width,
                                viewHeight = height,
                                rotationDegrees = remoteRotation,
                                isMirror = false,
                                aspectFill = isAspectFill
                            )
                            val surface = Surface(st)
                            remoteSurface = surface
                            videoManager.setRemoteDisplaySurface(surface)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                            st.setDefaultBufferSize(WifiVideoManager.VIDEO_WIDTH, WifiVideoManager.VIDEO_HEIGHT)
                            applyTextureTransform(
                                view = this@apply,
                                viewWidth = width,
                                viewHeight = height,
                                rotationDegrees = remoteRotation,
                                isMirror = false,
                                aspectFill = isAspectFill
                            )
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            videoManager.setRemoteDisplaySurface(null)
                            remoteSurface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            update = { textureView ->
                if (textureView.isAvailable && textureView.width > 0 && textureView.height > 0) {
                    applyTextureTransform(
                        view = textureView,
                        viewWidth = textureView.width,
                        viewHeight = textureView.height,
                        rotationDegrees = remoteRotation,
                        isMirror = false,
                        aspectFill = isAspectFill
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable { isAspectFill = !isAspectFill }
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
                    .padding(top = 40.dp)
                    .width(115.dp)
                    .height(155.dp)
                    .shadow(12.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1E1E1E),
                border = BorderStroke(1.5.dp, ProTheme.Emerald)
            ) {
                AndroidView(
                    factory = { context ->
                        TextureView(context).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                    st.setDefaultBufferSize(WifiVideoManager.VIDEO_WIDTH, WifiVideoManager.VIDEO_HEIGHT)
                                    applyTextureTransform(
                                        view = this@apply,
                                        viewWidth = width,
                                        viewHeight = height,
                                        rotationDegrees = localRotation,
                                        isMirror = isFacingFront,
                                        aspectFill = true
                                    )
                                    val surface = Surface(st)
                                    localSurface = surface
                                    videoManager.setLocalPreviewSurface(surface)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                                    st.setDefaultBufferSize(WifiVideoManager.VIDEO_WIDTH, WifiVideoManager.VIDEO_HEIGHT)
                                    applyTextureTransform(
                                        view = this@apply,
                                        viewWidth = width,
                                        viewHeight = height,
                                        rotationDegrees = localRotation,
                                        isMirror = isFacingFront,
                                        aspectFill = true
                                    )
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    videoManager.setLocalPreviewSurface(null)
                                    localSurface = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    update = { textureView ->
                        if (textureView.isAvailable && textureView.width > 0 && textureView.height > 0) {
                            applyTextureTransform(
                                view = textureView,
                                viewWidth = textureView.width,
                                viewHeight = textureView.height,
                                rotationDegrees = localRotation,
                                isMirror = isFacingFront,
                                aspectFill = true
                            )
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
                    color = ProTheme.SurfaceCard.copy(alpha = 0.88f),
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
                                text = "AES-256 CTR 640x480",
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
                            text = "$statusText • < 30ms P2P",
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

/**
 * Transforms camera and video frames to render in true upright portrait orientation
 * with 1:1 pixel square aspect ratio and no stretching or unintended zoom.
 */
private fun applyTextureTransform(
    view: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    bufWidth: Int = WifiVideoManager.VIDEO_WIDTH,
    bufHeight: Int = WifiVideoManager.VIDEO_HEIGHT,
    rotationDegrees: Int = 270,
    isMirror: Boolean = false,
    aspectFill: Boolean = false
) {
    if (viewWidth <= 0 || viewHeight <= 0 || bufWidth <= 0 || bufHeight <= 0) return

    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    // 1. TextureView default stretches [0, bufWidth] to viewWidth, and [0, bufHeight] to viewHeight.
    // Scale by (bufWidth / viewWidth, bufHeight / viewHeight) to restore native 1:1 pixel square aspect ratio:
    matrix.setScale(bufWidth.toFloat() / viewWidth, bufHeight.toFloat() / viewHeight, centerX, centerY)

    // 2. Rotate to upright portrait orientation:
    matrix.postRotate(rotationDegrees.toFloat(), centerX, centerY)

    // 3. Selfie mirror if requested (front camera preview):
    if (isMirror) {
        matrix.postScale(-1f, 1f, centerX, centerY)
    }

    // 4. Calculate proper scaling to fit or fill the view without distortion:
    val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
    val effectiveBufWidth = if (isRotated) bufHeight.toFloat() else bufWidth.toFloat()
    val effectiveBufHeight = if (isRotated) bufWidth.toFloat() else bufHeight.toFloat()

    val scaleX = viewWidth.toFloat() / effectiveBufWidth
    val scaleY = viewHeight.toFloat() / effectiveBufHeight
    val finalScale = if (aspectFill) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)

    matrix.postScale(finalScale, finalScale, centerX, centerY)

    view.setTransform(matrix)
}


