package com.example.apkautomation.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.apkautomation.crypto.VoiceEncryptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Off-Grid Hardware-Accelerated Video Calling Manager
 * Streams 480x640 @ 30fps H.264 encrypted with AES-256 CTR over Wi-Fi Direct (Port 8890)
 */
class WifiVideoManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "WifiVideoManager"
        const val VIDEO_PORT = 8890
        const val VIDEO_WIDTH = 480
        const val VIDEO_HEIGHT = 640
        private const val FRAME_RATE = 30
        private const val BIT_RATE = 1_200_000 // 1.2 Mbps
        private const val I_FRAME_INTERVAL = 1 // 1 sec keyframe interval
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var videoEncoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var videoDecoder: MediaCodec? = null

    private var videoServerSocket: ServerSocket? = null
    private var videoSocket: Socket? = null
    private var sendStreamJob: Job? = null
    private var receiveStreamJob: Job? = null

    private var voiceEncryptor: VoiceEncryptor = VoiceEncryptor("1234")

    private val _isVideoActive = MutableStateFlow(false)
    val isVideoActive = _isVideoActive.asStateFlow()

    private val _isCameraFacingFront = MutableStateFlow(true)
    val isCameraFacingFront = _isCameraFacingFront.asStateFlow()

    private val _isTorchEnabled = MutableStateFlow(false)
    val isTorchEnabled = _isTorchEnabled.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps = _fps.asStateFlow()

    private val _statusText = MutableStateFlow("Video Standby")
    val statusText = _statusText.asStateFlow()

    private var localPreviewSurface: Surface? = null
    private var remoteDisplaySurface: Surface? = null
    private var targetPeerAddress: InetAddress? = null
    private var isGroupOwner: Boolean = false

    fun updateSecurityPin(pin: String) {
        val activePin = if (pin.isBlank()) "1234" else pin
        voiceEncryptor = VoiceEncryptor(activePin)
    }

    fun startVideoCall(
        peerIp: InetAddress?,
        isHost: Boolean,
        localSurface: Surface?,
        remoteSurface: Surface?
    ) {
        if (_isVideoActive.value) return
        targetPeerAddress = peerIp
        isGroupOwner = isHost
        localPreviewSurface = localSurface
        remoteDisplaySurface = remoteSurface

        _isVideoActive.value = true
        _statusText.value = if (isHost) "Hosting Video Stream..." else "Connecting to Peer Video..."

        startCameraBackgroundThread()

        // 1. Initialize Decoder if remote surface available
        remoteSurface?.let { initDecoder(it) }

        // 2. Initialize Encoder & Camera
        initEncoder()
        startCamera()

        // 3. Connect Video Socket
        startVideoSocket()
    }

    fun stopVideoCall() {
        if (!_isVideoActive.value) return
        _isVideoActive.value = false
        _statusText.value = "Video Link Closed"
        _fps.value = 0

        sendStreamJob?.cancel()
        sendStreamJob = null

        receiveStreamJob?.cancel()
        receiveStreamJob = null

        try {
            videoSocket?.close()
        } catch (_: Exception) {}
        videoSocket = null

        try {
            videoServerSocket?.close()
        } catch (_: Exception) {}
        videoServerSocket = null

        stopCamera()
        stopEncoder()
        stopDecoder()
        stopCameraBackgroundThread()
    }

    private fun startCameraBackgroundThread() {
        cameraThread = HandlerThread("CameraBackground").also { it.start() }
        cameraHandler = Handler(cameraThread?.looper!!)
    }

    private fun stopCameraBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
            cameraThread = null
            cameraHandler = null
        } catch (_: Exception) {}
    }

    private fun initEncoder() {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderInputSurface = createInputSurface()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder initialization failed", e)
            _statusText.value = "Encoder Error: ${e.message}"
        }
    }

    private fun stopEncoder() {
        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (_: Exception) {}
        videoEncoder = null
        encoderInputSurface = null
    }

    private fun initDecoder(surface: Surface) {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT)
            videoDecoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, surface, null, 0)
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decoder initialization failed", e)
            _statusText.value = "Decoder Error: ${e.message}"
        }
    }

    private fun stopDecoder() {
        try {
            videoDecoder?.stop()
            videoDecoder?.release()
        } catch (_: Exception) {}
        videoDecoder = null
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        val handler = cameraHandler ?: return
        try {
            val targetFacing = if (_isCameraFacingFront.value) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }

            var selectedCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    selectedCameraId = id
                    break
                }
            }

            if (selectedCameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                selectedCameraId = cameraManager.cameraIdList[0]
            }

            selectedCameraId?.let { id ->
                cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCameraCaptureSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        Log.e(TAG, "Camera error: $error")
                    }
                }, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera", e)
        }
    }

    private fun createCameraCaptureSession() {
        val camera = cameraDevice ?: return
        val handler = cameraHandler ?: return
        val encoderSurface = encoderInputSurface

        val surfaces = mutableListOf<Surface>()
        encoderSurface?.let { surfaces.add(it) }
        localPreviewSurface?.let { surfaces.add(it) }

        if (surfaces.isEmpty()) return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (surface in surfaces) {
                requestBuilder.addTarget(surface)
            }

            // Apply torch if back camera and enabled
            if (!_isCameraFacingFront.value && _isTorchEnabled.value) {
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start repeating request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera session configuration failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "createCameraCaptureSession failed", e)
        }
    }

    private fun stopCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null
    }

    fun flipCamera() {
        _isCameraFacingFront.value = !_isCameraFacingFront.value
        _isTorchEnabled.value = false
        stopCamera()
        startCamera()
    }

    fun toggleTorch() {
        if (_isCameraFacingFront.value) return // Front camera has no physical flash
        val newTorch = !_isTorchEnabled.value
        _isTorchEnabled.value = newTorch

        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val handler = cameraHandler ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            encoderInputSurface?.let { requestBuilder.addTarget(it) }
            localPreviewSurface?.let { requestBuilder.addTarget(it) }

            if (newTorch) {
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            session.setRepeatingRequest(requestBuilder.build(), null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "toggleTorch failed", e)
        }
    }

    private fun startVideoSocket() {
        scope.launch(Dispatchers.IO) {
            try {
                if (isGroupOwner) {
                    videoServerSocket?.close()
                    videoServerSocket = ServerSocket(VIDEO_PORT).apply {
                        reuseAddress = true
                    }
                    val client = videoServerSocket?.accept()
                    videoSocket = client
                } else {
                    val peer = targetPeerAddress
                    if (peer != null) {
                        for (attempt in 1..25) {
                            if (!_isVideoActive.value) break
                            try {
                                val s = Socket()
                                s.connect(InetSocketAddress(peer, VIDEO_PORT), 1500)
                                videoSocket = s
                                break
                            } catch (_: Exception) {
                                delay(600)
                            }
                        }
                    }
                }

                val socket = videoSocket
                if (socket != null && socket.isConnected) {
                    _statusText.value = "Direct HD Video Active"
                    startTransmissionPipelines(socket)
                } else {
                    _statusText.value = "Video Link Failed to Connect"
                }
            } catch (e: Exception) {
                Log.e(TAG, "startVideoSocket error", e)
                _statusText.value = "Video Socket Error"
            }
        }
    }

    private fun startTransmissionPipelines(socket: Socket) {
        val outStream = DataOutputStream(socket.getOutputStream())
        val inStream = DataInputStream(socket.getInputStream())

        // 1. Send Loop (Encode -> AES-256 -> Socket)
        sendStreamJob = scope.launch(Dispatchers.IO) {
            val encoder = videoEncoder ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()
            var framesCount = 0
            var lastFpsTime = System.currentTimeMillis()

            while (isActive && _isVideoActive.value && !socket.isClosed) {
                try {
                    val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val rawData = ByteArray(bufferInfo.size)
                            outputBuffer.get(rawData)

                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            // Encrypt with AES-256 CTR using Vault PIN
                            val encryptedData = voiceEncryptor.encrypt(rawData)

                            // Frame wire format: [4-byte length][1-byte flags][payload]
                            synchronized(outStream) {
                                outStream.writeInt(encryptedData.size)
                                outStream.writeByte(if (isKeyFrame) 1 else 0)
                                outStream.write(encryptedData)
                                outStream.flush()
                            }

                            framesCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTime >= 1000) {
                                _fps.value = framesCount
                                framesCount = 0
                                lastFpsTime = now
                            }
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                } catch (e: Exception) {
                    if (!isActive || socket.isClosed) break
                    Log.e(TAG, "Send video frame error", e)
                }
            }
        }

        // 2. Receive Loop (Socket -> AES-256 Decrypt -> Decode -> Remote Display Surface)
        receiveStreamJob = scope.launch(Dispatchers.IO) {
            val decoder = videoDecoder ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && _isVideoActive.value && !socket.isClosed) {
                try {
                    val frameLength = inStream.readInt()
                    if (frameLength <= 0 || frameLength > 500_000) continue

                    val isKeyFrame = inStream.readByte() == 1.toByte()

                    val encryptedFrame = ByteArray(frameLength)
                    inStream.readFully(encryptedFrame)

                    // Decrypt with AES-256 CTR using Vault PIN
                    val decryptedFrame = voiceEncryptor.decrypt(encryptedFrame) ?: continue

                    // Feed decrypted NAL into hardware decoder
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        inBuffer?.clear()
                        inBuffer?.put(decryptedFrame)
                        decoder.queueInputBuffer(
                            inIndex,
                            0,
                            decryptedFrame.size,
                            System.nanoTime() / 1000,
                            if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        )
                    }

                    // Release decoded frame to surface for rendering
                    var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                    while (outIndex >= 0) {
                        decoder.releaseOutputBuffer(outIndex, true) // true renders to surface!
                        outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                    }
                } catch (e: EOFException) {
                    break
                } catch (e: Exception) {
                    if (!isActive || socket.isClosed) break
                    Log.e(TAG, "Receive video frame error", e)
                }
            }
        }
    }
}
