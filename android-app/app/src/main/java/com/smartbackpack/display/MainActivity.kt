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
    private var isPlaying = true
    private var currentAccentColor = Color.parseColor("#10B981") // Default Vercel Emerald
    private var currentCustomBanner = "🎒 MY BACKPACK"
    private var currentTrack: MusicTrack? = null
    private var currentTrackIndex = 0

    // Curated catalogue matching Vercel website
    private val playlist = listOf(
        Triple("Me, Myself and I", "G-Eazy, Bebe Rexha", Color.parseColor("#10B981")),
        Triple("Starboy", "The Weeknd, Daft Punk", Color.parseColor("#E50914")),
        Triple("Blinding Lights", "The Weeknd", Color.parseColor("#8B5CF6"))
    )

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
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    // Mini animated equalizer bars inside screen
    private val equalizerRunnable = object : Runnable {
        override fun run() {
            if (!isAnimating) return
            try {
                val bars = listOf(binding.miniBar1, binding.miniBar2, binding.miniBar3, binding.miniBar4)
                for (b in bars) {
                    val h = Random.nextInt(3, 9)
                    val px = (h * resources.displayMetrics.density).toInt()
                    b.layoutParams.height = px
                    b.requestLayout()
                }
            } catch (e: Exception) {
                // Ignore transient layout
            }
            mainHandler.postDelayed(this, 140)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()
        setupOrientationToggle()
        setupDisplayModePills()
        setupPlayerControls()
        setupThemeButtons()
        setupBluetoothButtons()
        startEqualizer()
        checkNotificationAccess()
        requestAppPermissions()

        // Load default song
        loadSongByIndex(0)
    }

    // -------------------------------------------------------------
    // 1. EXACT VERCEL BOTTOM NAVIGATION TABS
    // -------------------------------------------------------------
    private fun setupBottomNavigation() {
        binding.tabHome.setOnClickListener { switchTab("dashboard", binding.tvTabHome, binding.sectionDashboard) }
        binding.tabDisplay.setOnClickListener { switchTab("display", binding.tvTabDisplay, binding.sectionDisplay) }
        binding.tabBt.setOnClickListener { switchTab("bluetooth", binding.tvTabBt, binding.sectionBluetooth) }
        binding.tabSettings.setOnClickListener { switchTab("settings", binding.tvTabSettings, binding.sectionSettings) }
    }

    private fun switchTab(sectionName: String, activeLabel: android.widget.TextView, activeSectionView: View) {
        val labels = listOf(binding.tvTabHome, binding.tvTabDisplay, binding.tvTabBt, binding.tvTabSettings)
        val sections = listOf(binding.sectionDashboard, binding.sectionDisplay, binding.sectionBluetooth, binding.sectionSettings)

        // Reset all tabs
        for (lbl in labels) lbl.setTextColor(Color.parseColor("#6B7280"))
        for (sec in sections) sec.visibility = View.GONE

        // Activate selected tab
        activeLabel.setTextColor(currentAccentColor)
        activeSectionView.visibility = View.VISIBLE
        binding.tvTopSectionTag.text = sectionName.uppercase()
    }

    // -------------------------------------------------------------
    // 2. DISPLAY ORIENTATION SWITCHER
    // -------------------------------------------------------------
    private fun setupOrientationToggle() {
        binding.btnOrientLandscape.setOnClickListener {
            binding.btnOrientLandscape.setBackgroundColor(Color.parseColor("#24243B"))
            binding.btnOrientLandscape.setTextColor(Color.WHITE)
            binding.btnOrientPortrait.setBackgroundColor(Color.TRANSPARENT)
            binding.btnOrientPortrait.setTextColor(Color.parseColor("#9E9EB8"))

            // Adjust bezel dimensions to Landscape
            binding.innerScreenBezel.layoutParams.height = (190 * resources.displayMetrics.density).toInt()
            binding.innerScreenBezel.requestLayout()
        }

        binding.btnOrientPortrait.setOnClickListener {
            binding.btnOrientPortrait.setBackgroundColor(Color.parseColor("#24243B"))
            binding.btnOrientPortrait.setTextColor(Color.WHITE)
            binding.btnOrientLandscape.setBackgroundColor(Color.TRANSPARENT)
            binding.btnOrientLandscape.setTextColor(Color.parseColor("#9E9EB8"))

            // Adjust bezel dimensions to Portrait
            binding.innerScreenBezel.layoutParams.height = (250 * resources.displayMetrics.density).toInt()
            binding.innerScreenBezel.requestLayout()
        }
    }

    // -------------------------------------------------------------
    // 3. DISPLAY MODE PILLS (Directly under screen)
    // -------------------------------------------------------------
    private fun setupDisplayModePills() {
        val pills = listOf(
            Pair(binding.btnModeNowPlaying, "NOW PLAYING"),
            Pair(binding.btnModeVisualizer, "VISUALIZER"),
            Pair(binding.btnModeMinimal, "MINIMAL"),
            Pair(binding.btnModeCustomText, "CUSTOM TEXT"),
            Pair(binding.btnModeStandby, "STANDBY")
        )

        for ((btn, modeName) in pills) {
            btn.setOnClickListener {
                for ((b, _) in pills) {
                    b.setBackgroundColor(Color.parseColor("#12121E"))
                    b.setTextColor(Color.parseColor("#9E9EB8"))
                }
                btn.setBackgroundColor(Color.parseColor("#152B1E"))
                btn.setTextColor(currentAccentColor)

                binding.tvScreenBannerTag.text = "MODE: $modeName"
                Toast.makeText(this, "Display Mode: $modeName", Toast.LENGTH_SHORT).show()

                currentTrack?.let { bleService?.bleManager?.sendMetadata(it) }
            }
        }
    }

    // -------------------------------------------------------------
    // 4. MUSIC PLAYER & PLAYLIST CONTROLS
    // -------------------------------------------------------------
    private fun setupPlayerControls() {
        binding.btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            binding.btnPlayPause.text = if (isPlaying) "⏸" else "▶"
            currentTrack = currentTrack?.copy(playing = isPlaying)

            if (isPlaying) startEqualizer() else stopEqualizer()
            currentTrack?.let { bleService?.bleManager?.sendMetadata(it) }
        }

        binding.btnNextTrack.setOnClickListener {
            currentTrackIndex = (currentTrackIndex + 1) % playlist.size
            loadSongByIndex(currentTrackIndex)
        }

        binding.btnPrevTrack.setOnClickListener {
            currentTrackIndex = if (currentTrackIndex - 1 < 0) playlist.size - 1 else currentTrackIndex - 1
            loadSongByIndex(currentTrackIndex)
        }

        binding.trackItem1.setOnClickListener { loadSongByIndex(0) }
        binding.trackItem2.setOnClickListener { loadSongByIndex(1) }
        binding.trackItem3.setOnClickListener { loadSongByIndex(2) }

        // Custom Banner Send Button
        binding.btnUpdateBanner.setOnClickListener {
            val text = binding.etCustomBanner.text.toString().trim()
            if (text.isNotEmpty()) {
                currentCustomBanner = text.uppercase()
                binding.tvScreenBannerTag.text = "💬 $currentCustomBanner"
                Toast.makeText(this, "Updated Backpack Marquee!", Toast.LENGTH_SHORT).show()
                currentTrack?.let { bleService?.bleManager?.sendMetadata(it) }
            }
        }

        // Live automatic music detection from Spotify / YouTube Music / Apple Music
        MediaNotificationListener.onTrackChanged = { track ->
            runOnUiThread {
                updateFullUi(track)
                bleService?.bleManager?.sendMetadata(track)
            }
        }
    }

    private fun loadSongByIndex(index: Int) {
        currentTrackIndex = index
        val item = playlist[index]
        val art = createGradientArtwork(item.first, item.third, Color.parseColor("#080814"))
        val track = MusicTrack(
            title = item.first,
            artist = item.second,
            album = item.first,
            duration = 240,
            position = 45,
            playing = true,
            artwork = art
        )
        updateFullUi(track)
        bleService?.bleManager?.sendMetadata(track)
    }

    private fun updateFullUi(track: MusicTrack) {
        currentTrack = track
        isPlaying = track.playing

        // Phone player controls
        binding.tvPlayerTitle.text = track.title
        binding.tvPlayerArtist.text = track.artist
        binding.btnPlayPause.text = if (isPlaying) "⏸" else "▶"

        // Simulated screen display
        binding.tvScreenTrackTitle.text = track.title
        binding.tvScreenTrackArtist.text = track.artist
        binding.tvScreenBannerTag.text = currentCustomBanner

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

        if (isPlaying) startEqualizer() else stopEqualizer()
    }

    // -------------------------------------------------------------
    // 5. THEME SWITCHER
    // -------------------------------------------------------------
    private fun setupThemeButtons() {
        binding.btnThemeEmerald.setOnClickListener { applyAccent(Color.parseColor("#10B981")) }
        binding.btnThemeCyan.setOnClickListener { applyAccent(Color.parseColor("#06B6D4")) }
        binding.btnThemePurple.setOnClickListener { applyAccent(Color.parseColor("#8B5CF6")) }
        binding.btnThemeOrange.setOnClickListener { applyAccent(Color.parseColor("#F97316")) }
    }

    private fun applyAccent(color: Int) {
        currentAccentColor = color
        binding.tvLogo.setTextColor(color)
        binding.btnPlayPause.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        binding.btnConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        binding.btnUpdateBanner.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        binding.pbScreenProgress.progressTintList = android.content.res.ColorStateList.valueOf(color)
        binding.tvTabHome.setTextColor(color)

        val bars = listOf(binding.miniBar1, binding.miniBar2, binding.miniBar3, binding.miniBar4)
        for (b in bars) b.setBackgroundColor(color)
    }

    // -------------------------------------------------------------
    // 6. BLUETOOTH ENGINE
    // -------------------------------------------------------------
    private fun setupBluetoothButtons() {
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
            binding.tvLogs.text = "[BLE Console] Cleared."
        }
    }

    private fun updateConnectionUi(connected: Boolean, isScanning: Boolean = false) {
        if (connected) {
            binding.tvBleStatus.text = "CONNECTED"
            binding.tvBleStatus.setTextColor(Color.parseColor("#10B981"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#10B981"))
            binding.hardwareLed.setBackgroundColor(Color.parseColor("#10B981"))
            binding.screenBleDot.setBackgroundColor(Color.parseColor("#10B981"))

            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
        } else if (isScanning) {
            binding.tvBleStatus.text = "SCANNING..."
            binding.tvBleStatus.setTextColor(Color.parseColor("#F59E0B"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#F59E0B"))
            binding.hardwareLed.setBackgroundColor(Color.parseColor("#F59E0B"))
            binding.screenBleDot.setBackgroundColor(Color.parseColor("#F59E0B"))

            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
        } else {
            binding.tvBleStatus.text = "DISCONNECTED"
            binding.tvBleStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.dotStatus.setBackgroundColor(Color.parseColor("#EF4444"))
            binding.hardwareLed.setBackgroundColor(Color.parseColor("#EF4444"))
            binding.screenBleDot.setBackgroundColor(Color.parseColor("#EF4444"))

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

        val circlePaint = Paint().apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawCircle(40f, 40f, 26f, circlePaint)
        canvas.drawCircle(40f, 40f, 13f, circlePaint)

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
