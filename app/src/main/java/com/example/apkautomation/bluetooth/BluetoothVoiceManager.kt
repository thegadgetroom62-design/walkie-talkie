package com.example.apkautomation.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class ConnectionState {
    IDLE,
    LISTENING,
    CONNECTING,
    CONNECTED
}

class BluetoothVoiceManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothVoiceManager"
        private const val APP_NAME = "WalkieTalkie"
        // Standard Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val SAMPLE_RATE = 16000 // 16 kHz HD Voice
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting = _isTransmitting.asStateFlow()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving = _isReceiving.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to connect")
    val statusMessage = _statusMessage.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isSpeakerphone = MutableStateFlow(true)
    val isSpeakerphone = _isSpeakerphone.asStateFlow()

    fun toggleSpeakerphone() {
        val newState = !_isSpeakerphone.value
        _isSpeakerphone.value = newState
        applySpeakerphoneRouting(newState)
    }

    private fun applySpeakerphoneRouting(enabled: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route audio to speaker", e)
        }
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var acceptJob: Job? = null
    private var connectJob: Job? = null
    private var receiveJob: Job? = null
    private var transmitJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    /**
     * Start hosting an incoming call session
     */
    @SuppressLint("MissingPermission")
    fun startHosting() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _statusMessage.value = "Bluetooth is disabled"
            return
        }

        disconnect()
        _connectionState.value = ConnectionState.LISTENING
        _statusMessage.value = "Waiting for incoming connection..."

        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, SPP_UUID)
                val socket = serverSocket?.accept()
                serverSocket?.close()
                serverSocket = null

                if (socket != null) {
                    handleConnectedSocket(socket, socket.remoteDevice.name ?: "Peer Device")
                }
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Accept error", e)
                    _connectionState.value = ConnectionState.IDLE
                    _statusMessage.value = "Hosting canceled or failed"
                }
            }
        }
    }

    /**
     * Connect to a specific paired Bluetooth device
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _statusMessage.value = "Bluetooth is disabled"
            return
        }

        disconnect()
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Connecting to ${device.name ?: device.address}..."

        connectJob = scope.launch(Dispatchers.IO) {
            // Cancel discovery to avoid slowing down the connection
            bluetoothAdapter.cancelDiscovery()

            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                handleConnectedSocket(socket, device.name ?: device.address)
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = ConnectionState.IDLE
                _statusMessage.value = "Could not connect to ${device.name ?: "device"}"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedSocket(socket: BluetoothSocket, deviceName: String) {
        activeSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream

        _connectedDeviceName.value = deviceName
        _connectionState.value = ConnectionState.CONNECTED
        _statusMessage.value = "Connected to $deviceName"

        startAudioReceiver()
    }

    /**
     * Background listener for incoming voice packets
     */
    private fun startAudioReceiver() {
        applySpeakerphoneRouting(_isSpeakerphone.value)

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 2048)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            return
        }

        receiveJob = scope.launch(Dispatchers.IO) {
            val stream = inputStream ?: return@launch
            val buffer = ByteArray(bufferSize)

            while (isActive && activeSocket?.isConnected == true) {
                try {
                    val bytesRead = stream.read(buffer)
                    if (bytesRead > 0) {
                        _isReceiving.value = true
                        audioTrack?.write(buffer, 0, bytesRead)
                    } else if (bytesRead == -1) {
                        break
                    }
                } catch (e: IOException) {
                    break
                }
            }

            _isReceiving.value = false
            if (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                disconnect()
                _statusMessage.value = "Call disconnected by peer"
            }
        }
    }

    /**
     * Start transmitting microphone audio (Push-to-Talk pressed)
     */
    @SuppressLint("MissingPermission")
    fun startTalking() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        if (_isTransmitting.value) return

        _isTransmitting.value = true
        _statusMessage.value = "Transmitting voice..."

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT,
                bufferSize
            )
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            _isTransmitting.value = false
            return
        }

        transmitJob = scope.launch(Dispatchers.IO) {
            val stream = outputStream ?: return@launch
            val record = audioRecord ?: return@launch
            val buffer = ByteArray(bufferSize)

            while (isActive && _isTransmitting.value) {
                val bytesRead = record.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    try {
                        stream.write(buffer, 0, bytesRead)
                        stream.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "Transmission write failed", e)
                        break
                    }
                }
            }
        }
    }

    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator init failed", e)
        }
    }

    /**
     * Stop transmitting microphone audio (Push-to-Talk released)
     */
    fun stopTalking() {
        if (!_isTransmitting.value) return

        _isTransmitting.value = false
        _statusMessage.value = "Connected to ${_connectedDeviceName.value}"

        transmitJob?.cancel()
        transmitJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }

        triggerHapticFeedback(40)
        playRogerBeep()
    }

    fun playRogerBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {
            Log.e(TAG, "Roger beep error", e)
        }
    }

    fun triggerHapticFeedback(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    /**
     * Disconnect the call and reset state
     */
    fun disconnect() {
        stopTalking()

        acceptJob?.cancel()
        connectJob?.cancel()
        receiveJob?.cancel()

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        } finally {
            audioTrack = null
        }

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        } finally {
            serverSocket = null
        }

        try {
            inputStream?.close()
            outputStream?.close()
            activeSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing active socket", e)
        } finally {
            inputStream = null
            outputStream = null
            activeSocket = null
        }

        _connectedDeviceName.value = null
        _connectionState.value = ConnectionState.IDLE
        _isReceiving.value = false
        _statusMessage.value = "Disconnected"
    }
}
