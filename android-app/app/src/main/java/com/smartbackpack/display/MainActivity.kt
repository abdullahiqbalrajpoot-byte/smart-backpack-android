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

    private lateinit var binding: ActivityMainBinding
    private var bleService: BleService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            isBound = true

            // Attach listeners
            bleService?.bleManager?.onConnectionStateChanged = { connected ->
                runOnUiThread {
                    updateConnectionUi(connected)
                }
            }

            bleService?.bleManager?.onLogMessage = { log ->
                runOnUiThread {
                    binding.tvLogs.append("\n$log")
                    binding.scrollLogs.post { binding.scrollLogs.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }

            updateConnectionUi(bleService?.bleManager?.isConnected?.get() == true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startBleService()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to connect", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        checkNotificationAccess()
        requestAppPermissions()
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            bleService?.bleManager?.startScanAndConnect()
        }

        binding.btnDisconnect.setOnClickListener {
            bleService?.bleManager?.disconnect()
        }

        // Test sending "Starboy" by The Weeknd
        binding.btnSendTest.setOnClickListener {
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
            bleService?.bleManager?.sendMetadata(testTrack)
            updateTrackPreview(testTrack)
        }

        // Live track listener
        MediaNotificationListener.onTrackChanged = { track ->
            runOnUiThread {
                updateTrackPreview(track)
                bleService?.bleManager?.sendMetadata(track)
            }
        }
    }

    private fun updateConnectionUi(connected: Boolean) {
        if (connected) {
            binding.tvBleStatus.text = "CONNECTED"
            binding.tvBleStatus.setTextColor(Color.parseColor("#10B981"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#10B981"))
            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
        } else {
            binding.tvBleStatus.text = "DISCONNECTED"
            binding.tvBleStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#EF4444"))
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
        }
    }

    private fun updateTrackPreview(track: MusicTrack) {
        binding.tvTrackTitle.text = track.title
        binding.tvTrackArtist.text = track.artist
        binding.tvPlayState.text = if (track.playing) "Playing ▶" else "Paused ⏸"

        track.artwork?.let {
            binding.ivArtwork.setImageBitmap(it)
        } ?: run {
            binding.ivArtwork.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun checkNotificationAccess() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val packageName = packageName

        if (enabledListeners == null || !enabledListeners.contains(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Grant Notification Access")
                .setMessage("To read your current song title and album artwork from Spotify or YouTube Music, the app needs Notification Listener access.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
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
            startBleService()
        }
    }

    private fun startBleService() {
        BleService.start(this)
        val intent = Intent(this, BleService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun createPlaceholderBitmap(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#E50914")) // Netflix/Starboy red
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
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
