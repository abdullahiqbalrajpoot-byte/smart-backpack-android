package com.smartbackpack.display

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smartbackpack.display.databinding.ActivityMainBinding
import com.smartbackpack.display.model.MusicTrack
import com.smartbackpack.display.service.BleService
import com.smartbackpack.display.service.MediaNotificationListener

class MainActivity : AppCompatActivity() {

    private var binding: ActivityMainBinding? = null
    private var bleService: BleService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as BleService.LocalBinder
                bleService = binder.getService()
                isBound = true

                bleService?.bleManager?.onConnectionStateChanged = { connected ->
                    runOnUiThread { updateConnectionUi(connected) }
                }

                bleService?.bleManager?.onLogMessage = { log ->
                    runOnUiThread {
                        binding?.tvLogs?.append("\n$log")
                        binding?.scrollLogs?.post { 
                            binding?.scrollLogs?.fullScroll(android.view.View.FOCUS_DOWN) 
                        }
                    }
                }

                updateConnectionUi(bleService?.bleManager?.isConnected?.get() == true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            connectToServiceSafely()
        } else {
            Toast.makeText(this, "Permissions needed to scan for backpack", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding?.root)

            setupListeners()
            checkNotificationAccess()
            requestAppPermissions()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupListeners() {
        val b = binding ?: return

        b.btnConnect.setOnClickListener {
            connectToServiceSafely()
            bleService?.bleManager?.startScanAndConnect()
        }

        b.btnDisconnect.setOnClickListener {
            bleService?.bleManager?.disconnect()
        }

        // Test sending a track to preview on screen
        b.btnSendTest.setOnClickListener {
            val testBitmap = createPlaceholderBitmap("Starboy")
            val testTrack = MusicTrack(
                title = "Starboy",
                artist = "The Weeknd",
                album = "Starboy",
                duration = 230,
                position = 45,
                playing = true,
                artwork = testBitmap
            )
            updateTrackPreview(testTrack)
            bleService?.bleManager?.sendMetadata(testTrack)
        }

        // Live track listener from Spotify / YouTube Music
        MediaNotificationListener.onTrackChanged = { track ->
            runOnUiThread {
                updateTrackPreview(track)
                bleService?.bleManager?.sendMetadata(track)
            }
        }
    }

    private fun updateConnectionUi(connected: Boolean) {
        val b = binding ?: return
        if (connected) {
            b.tvBleStatus.text = "CONNECTED"
            b.tvBleStatus.setTextColor(Color.parseColor("#10B981"))
            b.dotStatus.setBackgroundColor(Color.parseColor("#10B981"))
            b.btnConnect.isEnabled = false
            b.btnDisconnect.isEnabled = true
        } else {
            b.tvBleStatus.text = "DISCONNECTED"
            b.tvBleStatus.setTextColor(Color.parseColor("#EF4444"))
            b.dotStatus.setBackgroundColor(Color.parseColor("#EF4444"))
            b.btnConnect.isEnabled = true
            b.btnDisconnect.isEnabled = false
        }
    }

    private fun updateTrackPreview(track: MusicTrack) {
        val b = binding ?: return
        b.tvTrackTitle.text = track.title
        b.tvTrackArtist.text = track.artist
        b.tvPlayState.text = if (track.playing) "Playing ▶" else "Paused ⏸"

        track.artwork?.let {
            b.ivArtwork.setImageBitmap(it)
        } ?: run {
            b.ivArtwork.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun checkNotificationAccess() {
        try {
            val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            val pkg = packageName

            if (enabledListeners == null || !enabledListeners.contains(pkg)) {
                AlertDialog.Builder(this)
                    .setTitle("Grant Notification Access")
                    .setMessage("To display the song title and album artwork on your backpack display, please enable Notification Access.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            connectToServiceSafely()
        }
    }

    private fun connectToServiceSafely() {
        if (!isBound) {
            try {
                val intent = Intent(this, BleService::class.java)
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createPlaceholderBitmap(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#E50914"))
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 14f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, 40f, 45f, paint)
        return bitmap
    }

    override fun onDestroy() {
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
        }
        binding = null
        super.onDestroy()
    }
}
