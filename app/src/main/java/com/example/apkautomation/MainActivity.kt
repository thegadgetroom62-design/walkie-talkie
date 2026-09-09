package com.example.apkautomation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.apkautomation.bluetooth.BluetoothVoiceManager
import com.example.apkautomation.bluetooth.ConnectionState

class MainActivity : ComponentActivity() {

    private lateinit var voiceManager: BluetoothVoiceManager
    private var bluetoothAdapter: BluetoothAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        voiceManager = BluetoothVoiceManager(bluetoothAdapter, lifecycleScope)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981),
                    primaryContainer = Color(0xFF064E3B),
                    surface = Color(0xFF111827),
                    background = Color(0xFF0F172A)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WalkieTalkieApp(
                        voiceManager = voiceManager,
                        bluetoothAdapter = bluetoothAdapter,
                        checkPermissions = { hasRequiredPermissions() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.disconnect()
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieTalkieApp(
    voiceManager: BluetoothVoiceManager,
    bluetoothAdapter: BluetoothAdapter?,
    checkPermissions: () -> Boolean
) {
    var hasPermissions by remember { mutableStateOf(checkPermissions()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    val requestPermissions = {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val connectionState by voiceManager.connectionState.collectAsState()
    val connectedDeviceName by voiceManager.connectedDeviceName.collectAsState()
    val isTransmitting by voiceManager.isTransmitting.collectAsState()
    val isReceiving by voiceManager.isReceiving.collectAsState()
    val statusMessage by voiceManager.statusMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PhoneInTalk,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Bluetooth Walkie-Talkie",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E293B)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!hasPermissions) {
                PermissionRequestCard(onRequest = requestPermissions)
            } else if (connectionState == ConnectionState.CONNECTED) {
                ActiveCallScreen(
                    deviceName = connectedDeviceName ?: "Peer Device",
                    isTransmitting = isTransmitting,
                    isReceiving = isReceiving,
                    statusMessage = statusMessage,
                    onStartTalking = { voiceManager.startTalking() },
                    onStopTalking = { voiceManager.stopTalking() },
                    onDisconnect = { voiceManager.disconnect() }
                )
            } else {
                SetupScreen(
                    connectionState = connectionState,
                    statusMessage = statusMessage,
                    bluetoothAdapter = bluetoothAdapter,
                    onHostCall = { voiceManager.startHosting() },
                    onConnectDevice = { device -> voiceManager.connectToDevice(device) },
                    onCancel = { voiceManager.disconnect() }
                )
            }
        }
    }
}

@Composable
fun PermissionRequestCard(onRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Permissions Required",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This app requires Bluetooth and Microphone permissions to connect devices and stream live voice.",
                textAlign = TextAlign.Center,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Grant Permissions", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun SetupScreen(
    connectionState: ConnectionState,
    statusMessage: String,
    bluetoothAdapter: BluetoothAdapter?,
    onHostCall: () -> Unit,
    onConnectDevice: (BluetoothDevice) -> Unit,
    onCancel: () -> Unit
) {
    val bondedDevices = remember(bluetoothAdapter) {
        try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when (connectionState) {
                            ConnectionState.LISTENING -> Color(0xFFF59E0B)
                            ConnectionState.CONNECTING -> Color(0xFF3B82F6)
                            else -> Color.Gray
                        }
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = statusMessage,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    if (connectionState == ConnectionState.LISTENING || connectionState == ConnectionState.CONNECTING) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F291E))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (connectionState == ConnectionState.LISTENING)
                        "Listening for Incoming Calls..."
                    else
                        "Connecting to Device...",
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    } else {
        // Host button
        Button(
            onClick = onHostCall,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Call, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Host a Call (Wait for Peer)",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.SettingsBluetooth, contentDescription = null, tint = Color.Gray)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Paired Devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (bondedDevices.isEmpty()) {
            Text(
                text = "No paired Bluetooth devices found.\nPlease pair your friend's phone in Android Bluetooth Settings first.",
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(bondedDevices) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = device.name ?: "Unknown Device",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                            Button(
                                onClick = { onConnectDevice(device) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                            ) {
                                Text("Call")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveCallScreen(
    deviceName: String,
    isTransmitting: Boolean,
    isReceiving: Boolean,
    statusMessage: String,
    onStartTalking: () -> Unit,
    onStopTalking: () -> Unit,
    onDisconnect: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonBgColor by animateColorAsState(
        targetValue = when {
            isTransmitting -> Color(0xFFEF4444) // Red while transmitting
            isReceiving -> Color(0xFF3B82F6)    // Blue while receiving
            else -> Color(0xFF10B981)           // Green idle
        },
        label = "buttonBg"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Connected to",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isTransmitting -> Color(0xFFEF4444)
                        isReceiving -> Color(0xFF3B82F6)
                        else -> Color(0xFF10B981)
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Center: Massive Push-To-Talk Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            // Pulse ring while active
            if (isTransmitting || isReceiving) {
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(buttonBgColor.copy(alpha = 0.25f))
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(buttonBgColor)
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            isTransmitting -> "TRANSMITTING"
                            isReceiving -> "RECEIVING..."
                            else -> "HOLD TO TALK"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Bottom: End Call Button
        Button(
            onClick = onDisconnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFEF4444))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Disconnect Call", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
