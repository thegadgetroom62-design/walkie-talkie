package com.example.apkautomation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.apkautomation.bluetooth.BluetoothVoiceManager
import com.example.apkautomation.bluetooth.ConnectionState
import com.example.apkautomation.global.DirectIpCommsManager
import com.example.apkautomation.global.DirectIpSetupScreen
import com.example.apkautomation.global.DirectIpState
import com.example.apkautomation.global.RoomMode
import com.example.apkautomation.service.WalkieTalkieService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.example.apkautomation.mapper.FloorPlanMapScreen
import com.example.apkautomation.mapper.WifiMapperEngine
import com.example.apkautomation.radar.RadarScreen
import com.example.apkautomation.radar.WifiRadarEngine
import com.example.apkautomation.ui.*
import com.example.apkautomation.video.VideoCallScreen
import com.example.apkautomation.video.WifiVideoManager
import com.example.apkautomation.wifi.WifiDirectVoiceManager

enum class CommsTransport(val label: String) {
    BLUETOOTH("Bluetooth"),
    WIFI_DIRECT("Wi-Fi Mesh"),
    DIRECT_IP("Direct-IP Global")
}

class MainActivity : ComponentActivity() {

    private lateinit var btVoiceManager: BluetoothVoiceManager
    private lateinit var wifiVoiceManager: WifiDirectVoiceManager
    private lateinit var directIpManager: DirectIpCommsManager
    private lateinit var videoManager: WifiVideoManager
    private lateinit var radarEngine: WifiRadarEngine
    private lateinit var mapperEngine: WifiMapperEngine
    private var bluetoothAdapter: BluetoothAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable screen-on and show-when-locked so walkie-talkie is accessible when phone is locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        btVoiceManager = BluetoothVoiceManager(this, bluetoothAdapter, lifecycleScope)
        wifiVoiceManager = WifiDirectVoiceManager(this, lifecycleScope)
        wifiVoiceManager.initialize()
        directIpManager = DirectIpCommsManager(this, lifecycleScope)
        videoManager = WifiVideoManager(this, lifecycleScope)
        radarEngine = WifiRadarEngine(this, lifecycleScope)
        mapperEngine = WifiMapperEngine(this, lifecycleScope)

        // Automatically start background WakeLock service when any call/room is active
        lifecycleScope.launch {
            combine(
                directIpManager.connectionState,
                directIpManager.roomMode,
                directIpManager.activeRoomCode,
                wifiVoiceManager.connectionState,
                btVoiceManager.connectionState
            ) { values ->
                val ipState = values[0] as DirectIpState
                val roomMode = values[1] as RoomMode
                val roomCode = values[2] as? String
                val wifiState = values[3] as ConnectionState
                val btState = values[4] as ConnectionState

                ipState == DirectIpState.CONNECTED ||
                roomMode != RoomMode.DISCONNECTED ||
                roomCode != null ||
                wifiState == ConnectionState.CONNECTED ||
                btState == ConnectionState.CONNECTED
            }.collect { isCallActive ->
                if (isCallActive) {
                    WalkieTalkieService.start(this@MainActivity)
                } else {
                    WalkieTalkieService.stop(this@MainActivity)
                }
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = ProTheme.Emerald,
                    primaryContainer = ProTheme.EmeraldDark,
                    surface = ProTheme.SurfaceCard,
                    surfaceVariant = ProTheme.SurfaceCardElevated,
                    background = ProTheme.Background,
                    onBackground = ProTheme.TextPrimary,
                    onSurface = ProTheme.TextPrimary
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ProTheme.Background
                ) {
                    WalkieTalkieApp(
                        btManager = btVoiceManager,
                        wifiManager = wifiVoiceManager,
                        directIpManager = directIpManager,
                        videoManager = videoManager,
                        radarEngine = radarEngine,
                        mapperEngine = mapperEngine,
                        bluetoothAdapter = bluetoothAdapter,
                        checkPermissions = { hasRequiredPermissions() }
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) {
                val handled = triggerPttDown()
                if (handled) return true
            } else {
                if (isAnyCallActive()) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val handled = triggerPttUp()
            if (handled) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isAnyCallActive(): Boolean {
        return directIpManager.connectionState.value == DirectIpState.CONNECTED ||
               directIpManager.roomMode.value != RoomMode.DISCONNECTED ||
               directIpManager.activeRoomCode.value != null ||
               wifiVoiceManager.connectionState.value == ConnectionState.CONNECTED ||
               btVoiceManager.connectionState.value == ConnectionState.CONNECTED
    }

    private fun triggerPttDown(): Boolean {
        if (directIpManager.connectionState.value == DirectIpState.CONNECTED ||
            directIpManager.roomMode.value != RoomMode.DISCONNECTED ||
            directIpManager.activeRoomCode.value != null) {
            directIpManager.startTalking()
            return true
        }
        if (wifiVoiceManager.connectionState.value == ConnectionState.CONNECTED) {
            wifiVoiceManager.startTalking()
            return true
        }
        if (btVoiceManager.connectionState.value == ConnectionState.CONNECTED) {
            btVoiceManager.startTalking()
            return true
        }
        return false
    }

    private fun triggerPttUp(): Boolean {
        if (directIpManager.connectionState.value == DirectIpState.CONNECTED ||
            directIpManager.roomMode.value != RoomMode.DISCONNECTED ||
            directIpManager.activeRoomCode.value != null) {
            directIpManager.stopTalking()
            return true
        }
        if (wifiVoiceManager.connectionState.value == ConnectionState.CONNECTED) {
            wifiVoiceManager.stopTalking()
            return true
        }
        if (btVoiceManager.connectionState.value == ConnectionState.CONNECTED) {
            btVoiceManager.stopTalking()
            return true
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        WalkieTalkieService.stop(this)
        btVoiceManager.disconnect()
        wifiVoiceManager.disconnect()
        wifiVoiceManager.unregister()
        directIpManager.disconnect()
        videoManager.stopVideoCall()
        radarEngine.stopRadar()
        mapperEngine.stopMapping()
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieTalkieApp(
    btManager: BluetoothVoiceManager,
    wifiManager: WifiDirectVoiceManager,
    directIpManager: DirectIpCommsManager,
    videoManager: WifiVideoManager,
    radarEngine: WifiRadarEngine,
    mapperEngine: WifiMapperEngine,
    bluetoothAdapter: BluetoothAdapter?,
    checkPermissions: () -> Boolean
) {
    var hasPermissions by remember { mutableStateOf(checkPermissions()) }
    var activeNavTab by remember { mutableStateOf(NavDestination.COMMS) }
    var commsTransport by remember { mutableStateOf(CommsTransport.BLUETOOTH) }
    val isVideoCallActive by wifiManager.isVideoCallActive.collectAsState()
    var isMicMuted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    val requestPermissions = {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val isBtConnected = (btManager.connectionState.collectAsState().value == ConnectionState.CONNECTED)
    val isWifiConnected = (wifiManager.connectionState.collectAsState().value == ConnectionState.CONNECTED)
    val isDirectIpConnected = (directIpManager.connectionState.collectAsState().value == DirectIpState.CONNECTED)
    val isAnyConnected = isBtConnected || isWifiConnected || isDirectIpConnected

    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("walkie_prefs", Context.MODE_PRIVATE) }
    var securityPin by remember { mutableStateOf(sharedPrefs.getString("security_pin", "1234") ?: "1234") }

    LaunchedEffect(securityPin) {
        btManager.updateSecurityPin(securityPin)
        wifiManager.updateSecurityPin(securityPin)
        directIpManager.updateSecurityPin(securityPin)
        videoManager.updateSecurityPin(securityPin)
    }

    Scaffold(
        topBar = {
            Surface(
                color = ProTheme.Background,
                border = BorderStroke(0.8.dp, ProTheme.BorderSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ProTheme.EmeraldDark.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneInTalk,
                                contentDescription = null,
                                tint = ProTheme.Emerald,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "WALKIE-TALKIE PRO",
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                letterSpacing = 1.sp,
                                color = ProTheme.TextPrimary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isAnyConnected) ProTheme.Emerald else ProTheme.SkyBlue)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isAnyConnected) "SECURE DIRECT LINK" else "STANDBY (ZERO-CLOUD)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isAnyConnected) ProTheme.Emerald else ProTheme.TextMuted
                                )
                            }
                        }
                    }

                    // Quick Vault button
                    Surface(
                        onClick = { activeNavTab = NavDestination.VAULT },
                        shape = RoundedCornerShape(12.dp),
                        color = ProTheme.SurfaceCardElevated,
                        border = BorderStroke(1.dp, ProTheme.BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Vault",
                                tint = ProTheme.Emerald,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "PIN: $securityPin",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = ProTheme.Emerald
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            FloatingBottomDock(
                currentDestination = activeNavTab,
                onNavigate = { destination ->
                    if (destination != NavDestination.RADAR) radarEngine.stopRadar()
                    if (destination != NavDestination.MAPPER) mapperEngine.stopMapping()
                    activeNavTab = destination
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (activeNavTab) {
                NavDestination.COMMS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!hasPermissions) {
                            PermissionRequestCard(onRequest = requestPermissions)
                        } else if (isWifiConnected && isVideoCallActive) {
                            val peerIp by wifiManager.peerAddressFlow.collectAsState()
                            val isGroupOwner by wifiManager.isGroupOwnerFlow.collectAsState()
                            val devName by wifiManager.connectedDeviceName.collectAsState()
                            val isSpk by wifiManager.isSpeakerphone.collectAsState()

                            VideoCallScreen(
                                videoManager = videoManager,
                                peerName = devName ?: "Peer Phone",
                                peerIp = peerIp,
                                isGroupOwner = isGroupOwner,
                                isSpeakerphone = isSpk,
                                isMuted = isMicMuted,
                                onToggleSpeaker = { wifiManager.toggleSpeakerphone() },
                                onToggleMute = {
                                    isMicMuted = !isMicMuted
                                    if (isMicMuted) {
                                        wifiManager.stopFullDuplexVoice()
                                    } else {
                                        wifiManager.startFullDuplexVoice()
                                    }
                                },
                                onEndCall = {
                                    wifiManager.requestEndVideoCall()
                                    videoManager.stopVideoCall()
                                }
                            )
                        } else if (isBtConnected) {
                            val devName by btManager.connectedDeviceName.collectAsState()
                            val isTx by btManager.isTransmitting.collectAsState()
                            val isRx by btManager.isReceiving.collectAsState()
                            val isSpk by btManager.isSpeakerphone.collectAsState()
                            val status by btManager.statusMessage.collectAsState()

                            ActiveCallScreen(
                                modeLabel = "Bluetooth 2.4 GHz RF Link",
                                deviceName = devName ?: "Peer Device",
                                isTransmitting = isTx,
                                isReceiving = isRx,
                                isSpeakerphone = isSpk,
                                statusMessage = status,
                                onStartTalking = { btManager.startTalking() },
                                onStopTalking = { btManager.stopTalking() },
                                onToggleSpeaker = { btManager.toggleSpeakerphone() },
                                onOpenSecuritySettings = { activeNavTab = NavDestination.VAULT },
                                onDisconnect = { btManager.disconnect() }
                            )
                        } else if (isWifiConnected) {
                            val devName by wifiManager.connectedDeviceName.collectAsState()
                            val isTx by wifiManager.isTransmitting.collectAsState()
                            val isRx by wifiManager.isReceiving.collectAsState()
                            val isSpk by wifiManager.isSpeakerphone.collectAsState()
                            val status by wifiManager.statusMessage.collectAsState()

                            ActiveCallScreen(
                                modeLabel = "Wi-Fi Direct P2P (Long Range)",
                                deviceName = devName ?: "Peer Device",
                                isTransmitting = isTx,
                                isReceiving = isRx,
                                isSpeakerphone = isSpk,
                                statusMessage = status,
                                onStartTalking = { wifiManager.startTalking() },
                                onStopTalking = { wifiManager.stopTalking() },
                                onToggleSpeaker = { wifiManager.toggleSpeakerphone() },
                                onOpenSecuritySettings = { activeNavTab = NavDestination.VAULT },
                                onStartVideoCall = {
                                    wifiManager.requestStartVideoCall()
                                },
                                onDisconnect = {
                                    wifiManager.requestEndVideoCall()
                                    videoManager.stopVideoCall()
                                    wifiManager.disconnect()
                                }
                            )
                        } else if (isDirectIpConnected) {
                            val peerIp by directIpManager.peerIp.collectAsState()
                            val isTx by directIpManager.isTransmitting.collectAsState()
                            val isRx by directIpManager.isReceiving.collectAsState()
                            val isSpk by directIpManager.isSpeakerphone.collectAsState()
                            val status by directIpManager.statusMessage.collectAsState()

                            ActiveCallScreen(
                                modeLabel = "Global Direct-IP (Serverless P2P)",
                                deviceName = peerIp ?: "Remote Node",
                                isTransmitting = isTx,
                                isReceiving = isRx,
                                isSpeakerphone = isSpk,
                                statusMessage = status,
                                onStartTalking = { directIpManager.startTalking() },
                                onStopTalking = { directIpManager.stopTalking() },
                                onToggleSpeaker = { directIpManager.toggleSpeakerphone() },
                                onOpenSecuritySettings = { activeNavTab = NavDestination.VAULT },
                                onDisconnect = { directIpManager.disconnect() }
                            )
                        } else {
                            // Scrollable Setup Screen with bounded constraints
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Sleek segmented pill switcher
                                SegmentedPillSwitcher(
                                    options = listOf(CommsTransport.BLUETOOTH, CommsTransport.WIFI_DIRECT, CommsTransport.DIRECT_IP),
                                    selectedOption = commsTransport,
                                    onOptionSelected = { transport ->
                                        when (transport) {
                                            CommsTransport.BLUETOOTH -> {
                                                wifiManager.disconnect()
                                                directIpManager.disconnect()
                                            }
                                            CommsTransport.WIFI_DIRECT -> {
                                                btManager.disconnect()
                                                directIpManager.disconnect()
                                                wifiManager.startPeerDiscovery()
                                            }
                                            CommsTransport.DIRECT_IP -> {
                                                btManager.disconnect()
                                                wifiManager.disconnect()
                                                directIpManager.refreshLocalIp()
                                            }
                                        }
                                        commsTransport = transport
                                    },
                                    labelProvider = { it.label },
                                    iconProvider = {
                                        when (it) {
                                            CommsTransport.BLUETOOTH -> Icons.Default.Bluetooth
                                            CommsTransport.WIFI_DIRECT -> Icons.Default.Wifi
                                            CommsTransport.DIRECT_IP -> Icons.Default.Language
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                when (commsTransport) {
                                    CommsTransport.BLUETOOTH -> {
                                        val btState by btManager.connectionState.collectAsState()
                                        val btStatus by btManager.statusMessage.collectAsState()

                                        SetupScreen(
                                            connectionState = btState,
                                            statusMessage = btStatus,
                                            bluetoothAdapter = bluetoothAdapter,
                                            onHostCall = { btManager.startHosting() },
                                            onConnectDevice = { device -> btManager.connectToDevice(device) },
                                            onCancel = { btManager.disconnect() }
                                        )
                                    }
                                    CommsTransport.WIFI_DIRECT -> {
                                        WifiDirectSetupScreen(wifiManager = wifiManager)
                                    }
                                    CommsTransport.DIRECT_IP -> {
                                        DirectIpSetupScreen(directIpManager = directIpManager)
                                    }
                                }

                                Spacer(modifier = Modifier.height(110.dp))
                            }
                        }
                    }
                }
                NavDestination.RADAR -> {
                    RadarScreen(radarEngine = radarEngine)
                }
                NavDestination.MAPPER -> {
                    FloorPlanMapScreen(mapperEngine = mapperEngine)
                }
                NavDestination.VAULT -> {
                    VaultScreen(
                        currentPin = securityPin,
                        onSavePin = { newPin ->
                            securityPin = newPin
                            sharedPrefs.edit().putString("security_pin", newPin).apply()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun WifiDirectSetupScreen(
    wifiManager: WifiDirectVoiceManager
) {
    val statusMessage by wifiManager.statusMessage.collectAsState()
    val discoveredPeers by wifiManager.discoveredPeers.collectAsState()
    val connState by wifiManager.connectionState.collectAsState()
    val activeMeshNodes by wifiManager.activeMeshNodes.collectAsState()

    BentoCard(title = "TACTICAL WI-FI DIRECT MESH", subtitle = "Multi-peer off-grid daisy chain relay") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when (connState) {
                                ConnectionState.CONNECTING -> ProTheme.SkyBlue
                                ConnectionState.CONNECTED -> ProTheme.Emerald
                                else -> ProTheme.TextSecondary
                            }
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusMessage,
                    color = ProTheme.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            }

            if (activeMeshNodes.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ProTheme.SurfaceCardElevated,
                    border = BorderStroke(0.8.dp, ProTheme.Emerald)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.WifiTethering, contentDescription = null, tint = ProTheme.Emerald, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tactical Mesh: ${activeMeshNodes.size + 1} Nodes Connected (Daisy-Chain Ready)",
                            color = ProTheme.Emerald,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    Button(
        onClick = { wifiManager.startPeerDiscovery() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ProTheme.Emerald)
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Scan for Nearby Phones",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }

    Spacer(modifier = Modifier.height(20.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.WifiTethering, contentDescription = null, tint = ProTheme.TextSecondary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Discovered Wi-Fi Direct Phones",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ProTheme.TextPrimary
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    if (discoveredPeers.isEmpty()) {
        BentoCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "No Wi-Fi Direct devices found yet.\nMake sure Wi-Fi is ON and tap 'Scan for Nearby Phones' on both devices.",
                color = ProTheme.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            discoveredPeers.forEach { device ->
                BentoCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = device.deviceName.ifEmpty { "Android Phone" },
                                fontWeight = FontWeight.Bold,
                                color = ProTheme.TextPrimary,
                                fontSize = 15.sp
                            )
                            Text(
                                text = device.deviceAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = ProTheme.TextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Button(
                            onClick = { wifiManager.connectToPeer(device) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ProTheme.SkyBlue)
                        ) {
                            Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRequestCard(onRequest: () -> Unit) {
    BentoCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(ProTheme.Amber.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = ProTheme.Amber,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Off-Grid Permissions Required",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ProTheme.TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Microphone, Bluetooth, Wi-Fi, and Physical Activity permissions are needed for off-grid PTT, through-wall radar, and floor-plan mapping.",
                textAlign = TextAlign.Center,
                color = ProTheme.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRequest,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ProTheme.Emerald)
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

    BentoCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when (connectionState) {
                            ConnectionState.LISTENING -> ProTheme.Amber
                            ConnectionState.CONNECTING -> ProTheme.SkyBlue
                            else -> ProTheme.TextMuted
                        }
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = statusMessage,
                color = ProTheme.TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (connectionState == ConnectionState.LISTENING || connectionState == ConnectionState.CONNECTING) {
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = ProTheme.EmeraldDark.copy(alpha = 0.35f),
            borderColor = ProTheme.Emerald
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = ProTheme.Emerald, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (connectionState == ConnectionState.LISTENING)
                        "Listening for Incoming Calls..."
                    else
                        "Connecting to Device...",
                    fontWeight = FontWeight.Bold,
                    color = ProTheme.TextPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel", color = ProTheme.TextPrimary)
                }
            }
        }
    } else {
        Button(
            onClick = onHostCall,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ProTheme.Emerald)
        ) {
            Icon(Icons.Default.Call, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Host a Call (Wait for Peer)",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.SettingsBluetooth, contentDescription = null, tint = ProTheme.TextSecondary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Paired Devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ProTheme.TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (bondedDevices.isEmpty()) {
            BentoCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No paired Bluetooth devices found.\nPlease pair your friend's phone in Android Bluetooth Settings first.",
                    color = ProTheme.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bondedDevices.forEach { device ->
                    BentoCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = device.name ?: "Unknown Device",
                                    fontWeight = FontWeight.Bold,
                                    color = ProTheme.TextPrimary,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ProTheme.TextMuted,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Button(
                                onClick = { onConnectDevice(device) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ProTheme.SkyBlue)
                            ) {
                                Text("Call", color = Color.Black, fontWeight = FontWeight.Bold)
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
    modeLabel: String = "Active Call",
    deviceName: String,
    isTransmitting: Boolean,
    isReceiving: Boolean,
    isSpeakerphone: Boolean,
    statusMessage: String,
    onStartTalking: () -> Unit,
    onStopTalking: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onStartVideoCall: (() -> Unit)? = null,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Cockpit Status Bento Card
        BentoCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Encrypted",
                            tint = ProTheme.Emerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AES-256 CTR LINK ACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProTheme.Emerald,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = ProTheme.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$modeLabel • $statusMessage",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isTransmitting -> ProTheme.Crimson
                            isReceiving -> ProTheme.SkyBlue
                            else -> ProTheme.Emerald
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenSecuritySettings,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ProTheme.SurfaceCardElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Vault PIN",
                            tint = ProTheme.Emerald
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakerphone) ProTheme.EmeraldDark else ProTheme.SurfaceCardElevated)
                    ) {
                        Icon(
                            imageVector = if (isSpeakerphone) Icons.Default.VolumeUp else Icons.Default.Hearing,
                            contentDescription = "Speaker Toggle",
                            tint = if (isSpeakerphone) ProTheme.Emerald else ProTheme.TextMuted
                        )
                    }
                }
            }
        }

        // Concentric Audio-Reactive Pulsing PTT Button
        PulsingPttButton(
            isTransmitting = isTransmitting,
            isReceiving = isReceiving,
            onStartTalking = onStartTalking,
            onStopTalking = onStopTalking
        )

        // Bottom Controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (onStartVideoCall != null) {
                Button(
                    onClick = onStartVideoCall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ProTheme.SkyBlue),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Full-Duplex Video Call", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Button(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ProTheme.CrimsonDark.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, ProTheme.Crimson),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = ProTheme.Crimson)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Disconnect Link", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

