package com.example.apkautomation.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.apkautomation.MainActivity

/**
 * High-reliability background audio service that holds CPU WakeLock and high-performance
 * WifiLock to keep audio playback running through the loudspeaker even when the phone
 * is locked or the app is minimized.
 */
class WalkieTalkieService : Service() {

    companion object {
        private const val TAG = "WalkieTalkieService"
        const val CHANNEL_ID = "walkie_talkie_background_audio"
        const val NOTIFICATION_ID = 8821
        const val ACTION_START = "com.example.apkautomation.START_SERVICE"
        const val ACTION_STOP = "com.example.apkautomation.STOP_SERVICE"
        const val ALERT_CHANNEL_ID = "walkie_talkie_alert_channel"
        const val ALERT_NOTIFICATION_ID = 8822
        const val EXTRA_AUTO_JOIN_ROOM = "extra_auto_join_room"

        fun start(context: Context, status: String = "Listening on Loudspeaker • Screen-Off Active") {
            val intent = Intent(context, WalkieTalkieService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STATUS, status)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, WalkieTalkieService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop foreground service", e)
            }
        }

        fun showWakeNotification(context: Context, callerName: String, roomCode: String) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val alertChannel = NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "Incoming Walkie-Talkie Transmissions",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Urgent incoming walkie-talkie calls and wake alerts"
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 300, 200, 300, 200, 400)
                    }
                    notificationManager.createNotificationChannel(alertChannel)
                }

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_AUTO_JOIN_ROOM, roomCode)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    roomCode.hashCode(),
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                    .setContentTitle("🚨 INCOMING WALKIE-TALKIE CALL")
                    .setContentText("$callerName is paging you on Room $roomCode! Tap to answer.")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setAutoCancel(true)
                    .setFullScreenIntent(pendingIntent, true)
                    .setContentIntent(pendingIntent)
                    .addAction(
                        android.R.drawable.ic_menu_call,
                        "ANSWER & TALK",
                        pendingIntent
                    )
                    .build()

                notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show wake notification", e)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Listening on Loudspeaker • Screen-Off Active"
        startForegroundWithNotification(status)
        return START_STICKY
    }

    private fun startForegroundWithNotification(status: String) {
        val notification = buildNotification(status)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(status: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Walkie-Talkie Pro Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Walkie-Talkie Background Audio Link",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps voice and video audio playing smoothly through the loudspeaker when the screen is locked."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WalkieTalkiePro::BackgroundAudioWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
            }

            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "WalkieTalkiePro::BackgroundWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            Log.d(TAG, "WakeLock and WifiLock acquired for screen-off audio")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake locks", e)
        }
    }

    private fun releaseWakeLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null

            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
            Log.d(TAG, "WakeLock and WifiLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake locks", e)
        }
    }

    private fun stopForegroundService() {
        releaseWakeLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLocks()
        super.onDestroy()
    }
}
