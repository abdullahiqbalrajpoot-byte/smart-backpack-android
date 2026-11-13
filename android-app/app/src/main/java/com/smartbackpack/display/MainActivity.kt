package com.smartbackpack.display

import android.Manifest
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
    private var isAnimating = false
    private var currentAccentColor = Color.parseColor("#06B6D4") // Default Cyber Cyan
    private var currentCustomBanner = "🎒 MY BACKPACK"
    private var currentTrack: MusicTrack? = null

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
            Toast.makeText(this, "Bluetooth permissions required to connect to display", Toast.LENGTH_SHORT).show()
        }
    }

    // Mini Equalizer animation runnable
    private val equalizerRunnable = object : Runnable {
        override fun run() {
            if (!isAnimating) return
            try {
                val bars = listOf(binding.miniBar1, binding.miniBar2, binding.miniBar3, binding.miniBar4)
                for (b in bars) {
                    val h = Random.nextInt(4, 11)
                    val px = (h * resources.displayMetrics.density).toInt()
                    b.layoutParams.height = px
                    b.requestLayout()
                }
            } catch (e: Exception) {
                // Ignore transient layout
            }
            mainHandler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        setupThemeButtons()
        setupDemoTracks()
        startEqualizer()
        checkNotificationAccess()
        requestAppPermissions()

        // Load initial track (Me, Myself and I) matching Vercel prototype
        loadDemoTrack("Me, Myself and I", "G-Eazy, Bebe Rexha", "When It's Dark Out", Color.parseColor("#06B6D4"), Color.parseColor("#0B192C"))
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            connectToServiceSafely()
            bleService?.bleManager?.startScanAndConnect()
            updateConnectionUi(false, isScanning = true)
        }

        binding.btnDisconnect.setOnClickListener {
            bleService?.bleManager?.disconnect()
            updateConnectionUi(false)
        }

        binding.tvClearLogs.setOnClickListener {
            binding.tvLogs.text = "[BLE] Console cleared."
        }

        binding.btnUpdateBanner.setOnClickListener {
            val text = binding.etCustomBanner.text.toString().trim()
            if (text.isNotEmpty()) {
                currentCustomBanner = text.uppercase()
                binding.tvScreenCustomBanner.text = "💬 $currentCustomBanner"
                Toast.makeText(this, "Updated Marquee Banner!", Toast.LENGTH_SHORT).show()
                currentTrack?.let { bleService?.bleManager?.sendMetadata(it) }
            }
        }

        // Live automatic music detection from Spotify / YouTube Music / Apple Music
        MediaNotificationListener.onTrackChanged = { track ->
            runOnUiThread {
                updateTrackDisplay(track)
                bleService?.bleManager?.sendMetadata(track)
            }
        }
    }

    private fun setupThemeButtons() {
        binding.btnThemeCyber.setOnClickListener { applyTheme(Color.parseColor("#06B6D4")) }
        binding.btnThemePurple.setOnClickListener { applyTheme(Color.parseColor("#8B5CF6")) }
        binding.btnThemeGreen.setOnClickListener { applyTheme(Color.parseColor("#22C55E")) }
        binding.btnThemeOrange.setOnClickListener { applyTheme(Color.parseColor("#F97316")) }
    }

    private fun applyTheme(accentColor: Int) {
        currentAccentColor = accentColor
        binding.themeAccentDot.setBackgroundColor(accentColor)
        binding.btnConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        binding.btnUpdateBanner.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        binding.pbScreenProgress.progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
        binding.tvScreenCustomBanner.setTextColor(accentColor)

        val bars = listOf(binding.miniBar1, binding.miniBar2, binding.miniBar3, binding.miniBar4)
        for (b in bars) {
            b.setBackgroundColor(accentColor)
        }
    }

    private fun setupDemoTracks() {
        binding.btnDemoSong1.setOnClickListener {
            loadDemoTrack("Me, Myself and I", "G-Eazy, Bebe Rexha", "When It's Dark Out", Color.parseColor("#06B6D4"), Color.parseColor("#0A2540"))
        }

        binding.btnDemoSong2.setOnClickListener {
            loadDemoTrack("Starboy", "The Weeknd, Daft Punk", "Starboy", Color.parseColor("#E50914"), Color.parseColor("#280914"))
        }

        binding.btnDemoSong3.setOnClickListener {
            loadDemoTrack("Blinding Lights", "The Weeknd", "After Hours", Color.parseColor("#8B5CF6"), Color.parseColor("#06B6D4"))
        }
    }

    private fun loadDemoTrack(title: String, artist: String, album: String, c1: Int, c2: Int) {
        val art = createGradientArtwork(title, c1, c2)
        val track = MusicTrack(
            title = title,
            artist = artist,
            album = album,
            duration = 251,
            position = 42,
            playing = true,
            artwork = art
        )
        updateTrackDisplay(track)
        bleService?.bleManager?.sendMetadata(track)
    }

    private fun updateTrackDisplay(track: MusicTrack) {
        currentTrack = track

        binding.tvScreenTrackTitle.text = track.title
        binding.tvScreenTrackArtist.text = track.artist
        binding.tvScreenCustomBanner.text = currentCustomBanner

        track.artwork?.let {
            binding.ivScreenArtwork.setImageBitmap(it)
        } ?: run {
            binding.ivScreenArtwork.setImageResource(android.R.drawable.ic_media_play)
        }

        val elapsed = track.position
        val remaining = (track.duration - track.position).coerceAtLeast(0)
        binding.tvScreenCurrentTime.text = formatTime(elapsed)
        binding.tvScreenRemainingTime.text = "-${formatTime(remaining)}"

        val pct = if (track.duration > 0) ((elapsed.toFloat() / track.duration.toFloat()) * 100).toInt() else 0
        binding.pbScreenProgress.progress = pct

        if (track.playing) {
            startEqualizer()
        } else {
            stopEqualizer()
        }
    }

    private fun updateConnectionUi(connected: Boolean, isScanning: Boolean = false) {
        if (connected) {
            binding.tvBleStatus.text = "CONNECTED"
            binding.tvBleStatus.setTextColor(Color.parseColor("#10B981"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#10B981"))
            binding.hardwareLed.setBackgroundColor(Color.parseColor("#10B981"))
            binding.screenBleIndicator.setBackgroundColor(Color.parseColor("#10B981"))

            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
        } else if (isScanning) {
            binding.tvBleStatus.text = "SCANNING..."
            binding.tvBleStatus.setTextColor(Color.parseColor("#F59E0B"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#F59E0B"))
            binding.hardwareLed.setBackgroundColor(Color.parseColor("#F59E0B"))
            binding.screenBleIndicator.setBackgroundColor(Color.parseColor("#F59E0B"))

            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
        } else {
            binding.tvBleStatus.text = "DISCONNECTED"
            binding.tvBleStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#EF4444"))
            binding.hardwareLed.setBackgroundColor(Color.parseColor("#EF4444"))
            binding.screenBleIndicator.setBackgroundColor(Color.parseColor("#6B7280"))

            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
        }
    }

    private fun startEqualizer() {
        if (!isAnimating) {
            isAnimating = true
            mainHandler.post(equalizerRunnable)
        }
    }

    private fun stopEqualizer() {
        isAnimating = false
        mainHandler.removeCallbacks(equalizerRunnable)
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%d:%02d", m, s)
    }

    private fun createGradientArtwork(title: String, c1: Int, c2: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val gradient = LinearGradient(0f, 0f, 80f, 80f, c1, c2, Shader.TileMode.CLAMP)
        val paint = Paint().apply { shader = gradient }
        canvas.drawRect(0f, 0f, 80f, 80f, paint)

        // Vinyl concentric grooves
        val circlePaint = Paint().apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawCircle(40f, 40f, 26f, circlePaint)
        canvas.drawCircle(40f, 40f, 13f, circlePaint)

        // Title initial
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 14f
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val initials = if (title.length >= 2) title.substring(0, 2).uppercase() else title.uppercase()
        canvas.drawText(initials, 40f, 45f, textPaint)

        return bitmap
    }

    private fun checkNotificationAccess() {
        try {
            val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            val pkg = packageName

            if (enabledListeners == null || !enabledListeners.contains(pkg)) {
                AlertDialog.Builder(this)
                    .setTitle("Grant Notification Access")
                    .setMessage("To detect live song titles and album artwork from Spotify or YouTube Music, please enable Notification Access.")
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
        stopEqualizer()
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
