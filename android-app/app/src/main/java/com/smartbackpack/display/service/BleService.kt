package com.smartbackpack.display.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.smartbackpack.display.MainActivity
import com.smartbackpack.display.R
import com.smartbackpack.display.ble.BleManager
import com.smartbackpack.display.model.MusicTrack

class BleService : Service() {

    private val binder = LocalBinder()
    lateinit var bleManager: BleManager
        private set

    companion object {
        const val CHANNEL_ID = "smart_backpack_ble_channel"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, BleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ready to connect to backpack display"))

        // Listen for media changes from MediaNotificationListener
        MediaNotificationListener.onTrackChanged = { track ->
            updateNotification("Playing: ${track.title} - ${track.artist}")
            bleManager.sendMetadata(track)
        }

        bleManager.onConnectionStateChanged = { connected ->
            val status = if (connected) "Connected to Backpack Display" else "Searching for Display..."
            updateNotification(status)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bleManager.startScanAndConnect()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart Backpack Display Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BLE connected to your backpack display while screen is off"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Backpack Display")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        bleManager.disconnect()
        super.onDestroy()
    }
}
