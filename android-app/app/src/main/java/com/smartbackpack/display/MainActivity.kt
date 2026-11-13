package com.smartbackpack.display

import android.Manifest
import android.animation.ValueAnimator
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smartbackpack.display.databinding.ActivityMainBinding
import com.smartbackpack.display.model.MusicTrack
import com.smartbackpack.display.service.BleService
import com.smartbackpack.display.service.MediaNotificationListener
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bleService: BleService? = null
    private var isBound = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVisualizerRunning = false
    private var currentCustomBanner = "🎒 MY BACKPACK"
    private var currentPlayingTrack: MusicTrack? = null

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
                        binding.tvLogs.append("\n$log")
                        binding.scrollLogs.post { 
                            binding.scrollLogs.fullScroll(View.FOCUS_DOWN) 
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

    // Animation runnable for the 8 visualizer bars
    private val visualizerRunnable = object : Runnable {
        override fun run() {
            if (!isVisualizerRunning) return
            try {
                val heights = listOf(14, 22, 28, 18, 26, 16, 24, 12)
                val bars = listOf(
                    binding.bar1, binding.bar2, binding.bar3, binding.bar4,
                    binding.bar5, binding.bar6, binding.bar7, binding.bar8
                )

                for (i in bars.indices) {
                    val factor = 0.4f + (Random.nextFloat() * 0.8f)
                    val baseHeight = heights[i]
                    val newHeightPx = ((baseHeight * factor) * resources.displayMetrics.density).toInt().coerceAtLeast(8)
                    bars[i].layoutParams.height = newHeightPx
                    bars[i].requestLayout()
                }
            } catch (e: Exception) {
                // Ignore transient layout updates
            }
            mainHandler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        setupDemoTracks()
        startVisualizerAnimation()
        checkNotificationAccess()
        requestAppPermissions()

        // Initialize with default demo track
        loadTrack("Starboy", "The Weeknd", "Starboy", Color.parseColor("#E50914"), Color.parseColor("#1E1B4B"))
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            connectToServiceSafely()
            bleService?.bleManager?.startScanAndConnect()
        }

        binding.btnDisconnect.setOnClickListener {
            bleService?.bleManager?.disconnect()
        }

        binding.tvClearLogs.setOnClickListener {
            binding.tvLogs.text = "[LOG] Console cleared."
        }

        binding.btnUpdateBanner.setOnClickListener {
            val text = binding.etCustomBanner.text.toString().trim()
            if (text.isNotEmpty()) {
                currentCustomBanner = text.uppercase()
                binding.tvScreenCustomBanner.text = "💬 $currentCustomBanner"
                Toast.makeText(this, "Updated Backpack Banner!", Toast.LENGTH_SHORT).show()

                // Re-send current track with updated banner
                currentPlayingTrack?.let { bleService?.bleManager?.sendMetadata(it) }
            }
        }

        binding.btnSendTest.setOnClickListener {
            currentPlayingTrack?.let {
                bleService?.bleManager?.sendMetadata(it)
                Toast.makeText(this, "Re-synced metadata with display!", Toast.LENGTH_SHORT).show()
            }
        }

        // Live track listener from Spotify / YouTube Music / Apple Music
        MediaNotificationListener.onTrackChanged = { track ->
            runOnUiThread {
                updateFullUiWithTrack(track)
                bleService?.bleManager?.sendMetadata(track)
            }
        }
    }

    private fun setupDemoTracks() {
        // Song 1: Me, Myself & I
        binding.btnDemoSong1.setOnClickListener {
            loadTrack("Me, Myself & I", "G-Eazy, Bebe Rexha", "When It's Dark Out", Color.parseColor("#10B981"), Color.parseColor("#064E3B"))
        }

        // Song 2: Starboy
        binding.btnDemoSong2.setOnClickListener {
            loadTrack("Starboy", "The Weeknd, Daft Punk", "Starboy", Color.parseColor("#E50914"), Color.parseColor("#31102B"))
        }

        // Song 3: Blinding Lights
        binding.btnDemoSong3.setOnClickListener {
            loadTrack("Blinding Lights", "The Weeknd", "After Hours", Color.parseColor("#8B5CF6"), Color.parseColor("#06B6D4"))
        }
    }

    private fun loadTrack(title: String, artist: String, album: String, primaryColor: Int, secondaryColor: Int) {
        val art = createGradientArtwork(title, primaryColor, secondaryColor)
        val track = MusicTrack(
            title = title,
            artist = artist,
            album = album,
            duration = 215,
            position = 45,
            playing = true,
            artwork = art
        )
        updateFullUiWithTrack(track)
        bleService?.bleManager?.sendMetadata(track)
    }

    private fun updateFullUiWithTrack(track: MusicTrack) {
        currentPlayingTrack = track

        // Update Phone Player Card
        binding.tvTrackTitle.text = track.title
        binding.tvTrackArtist.text = track.artist
        binding.tvPlayState.text = if (track.playing) "Streaming Live ▶" else "Paused ⏸"
        track.artwork?.let { binding.ivArtwork.setImageBitmap(it) }

        // Update Simulated Physical Backpack Display (TFT Screen Preview)
        binding.tvScreenTrackTitle.text = track.title
        binding.tvScreenTrackArtist.text = track.artist
        binding.tvScreenDuration.text = formatDuration(track.duration)
        binding.pbScreenProgress.progress = 45
        binding.tvScreenCustomBanner.text = currentCustomBanner
        track.artwork?.let { binding.ivScreenArtwork.setImageBitmap(it) }

        // Animate visualizer state
        if (track.playing) {
            startVisualizerAnimation()
        } else {
            stopVisualizerAnimation()
        }
    }

    private fun updateConnectionUi(connected: Boolean) {
        if (connected) {
            binding.tvBleStatus.text = "CONNECTED"
            binding.tvBleStatus.setTextColor(Color.parseColor("#10B981"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#10B981"))
            binding.tvScreenModeTag.text = "BLE ONLINE"
            binding.tvScreenModeTag.setTextColor(Color.parseColor("#10B981"))
            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
        } else {
            binding.tvBleStatus.text = "STANDBY"
            binding.tvBleStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#EF4444"))
            binding.tvScreenModeTag.text = "NOW PLAYING"
            binding.tvScreenModeTag.setTextColor(Color.parseColor("#9E9EB8"))
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
        }
    }

    private fun startVisualizerAnimation() {
        if (!isVisualizerRunning) {
            isVisualizerRunning = true
            mainHandler.post(visualizerRunnable)
        }
    }

    private fun stopVisualizerAnimation() {
        isVisualizerRunning = false
        mainHandler.removeCallbacks(visualizerRunnable)
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun createGradientArtwork(title: String, color1: Int, color2: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val gradient = LinearGradient(
            0f, 0f, 80f, 80f,
            color1, color2,
            Shader.TileMode.CLAMP
        )
        val paint = Paint().apply { shader = gradient }
        canvas.drawRect(0f, 0f, 80f, 80f, paint)

        // Draw dynamic vinyl circle motif
        val circlePaint = Paint().apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawCircle(40f, 40f, 24f, circlePaint)
        canvas.drawCircle(40f, 40f, 12f, circlePaint)

        // Overlay text initials
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 12f
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val initials = if (title.length >= 2) title.substring(0, 2).uppercase() else title.uppercase()
        canvas.drawText(initials, 40f, 44f, textPaint)

        return bitmap
    }

    private fun checkNotificationAccess() {
        try {
            val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            val pkg = packageName

            if (enabledListeners == null || !enabledListeners.contains(pkg)) {
                AlertDialog.Builder(this)
                    .setTitle("Grant Notification Access")
                    .setMessage("To display live song titles and album artwork on your backpack, please allow Notification Access.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    .setNegativeButton("Later", null)
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

    override fun onDestroy() {
        stopVisualizerAnimation()
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
        }
        super.onDestroy()
    }
}
