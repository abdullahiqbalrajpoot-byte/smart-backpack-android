package com.smartbackpack.display

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smartbackpack.display.databinding.ActivityMainBinding
import com.smartbackpack.display.model.MusicTrack
import com.smartbackpack.display.service.BleService
import com.smartbackpack.display.service.MediaNotificationListener
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private var bleService: BleService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as BleService.LocalBinder
                bleService = binder.getService()
                isBound = true

                bleService?.bleManager?.onConnectionStateChanged = { connected ->
                    runOnUiThread { notifyWebBleState(connected) }
                }

                bleService?.bleManager?.onLogMessage = { log ->
                    Log.d("BleLog", log)
                }

                val connected = bleService?.bleManager?.isConnected?.get() == true
                notifyWebBleState(connected)
            } catch (e: Exception) {
                Log.e(tag, "Service connection error: ${e.message}")
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
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            connectToServiceSafely()
        } else {
            Toast.makeText(this, "Bluetooth permissions required to connect to display", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupBackNavigation()
        requestAppPermissions()
        checkNotificationAccess()

        // Handle live track changes from background media players (Spotify, YouTube Music, etc.)
        MediaNotificationListener.onTrackChanged = { track ->
            runOnUiThread {
                notifyWebTrackChanged(track)
                bleService?.bleManager?.sendMetadata(track)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        webView.setBackgroundColor(Color.parseColor("#06060F"))
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        val assetLoader = androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("WebConsole", "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                return request?.url?.let { assetLoader.shouldInterceptRequest(it) }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                val isConnected = bleService?.bleManager?.isConnected?.get() == true
                notifyWebBleState(isConnected)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e(tag, "WebView error: ${error?.description} on ${request?.url}")
            }
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun notifyWebBleState(connected: Boolean) {
        val js = "if (window.onNativeBleState) { window.onNativeBleState($connected, 'SMART_BACKPACK_DISPLAY'); }"
        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun notifyWebTrackChanged(track: MusicTrack) {
        val titleSafe = escapeJs(track.title)
        val artistSafe = escapeJs(track.artist)
        val albumSafe = escapeJs(track.album)
        val duration = track.duration
        val js = "if (window.onNativeTrackChanged) { window.onNativeTrackChanged('$titleSafe', '$artistSafe', '$albumSafe', $duration); }"
        binding.webView.post {
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun escapeJs(str: String?): String {
        return (str ?: "").replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
    }

    // ─────────────────────────────────────────────────────────────
    // JAVASCRIPT INTERFACE (BRIDGES REACT UI TO NATIVE HARDWARE)
    // ─────────────────────────────────────────────────────────────
    inner class AndroidBridge {

        @JavascriptInterface
        fun isBleConnected(): Boolean {
            return bleService?.bleManager?.isConnected?.get() ?: false
        }

        @JavascriptInterface
        fun startScan() {
            runOnUiThread {
                connectToServiceSafely()
                bleService?.bleManager?.startScanAndConnect()
                Toast.makeText(this@MainActivity, "Scanning for Smart Backpack...", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                bleService?.bleManager?.disconnect()
                Toast.makeText(this@MainActivity, "Disconnected from Backpack", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun sendTrack(title: String, artist: String, album: String, duration: Long, artworkUrl: String?) {
            runOnUiThread {
                val bitmap = resolveArtworkBitmap(artworkUrl, title)
                val track = MusicTrack(
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    position = 0,
                    playing = true,
                    artwork = bitmap
                )
                bleService?.bleManager?.sendMetadata(track)
            }
        }

        @JavascriptInterface
        fun sendCustomText(text: String) {
            runOnUiThread {
                val banner = text.trim()
                if (banner.isNotEmpty()) {
                    val art = createGradientArtwork(banner, Color.parseColor("#10B981"), Color.parseColor("#064E3B"))
                    val track = MusicTrack(
                        title = banner,
                        artist = "BACKPACK MARQUEE",
                        album = "Custom Message",
                        duration = 0,
                        position = 0,
                        playing = false,
                        artwork = art
                    )
                    bleService?.bleManager?.sendMetadata(track)
                    Toast.makeText(this@MainActivity, "Sent to Backpack Display!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun getBatteryLevel(): Int {
            return try {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (e: Exception) {
                85
            }
        }
    }

    private fun resolveArtworkBitmap(artworkUrl: String?, title: String): Bitmap {
        if (!artworkUrl.isNullOrEmpty()) {
            try {
                // If Base64 image
                if (artworkUrl.startsWith("data:image")) {
                    val base64Data = artworkUrl.substringAfter("base64,")
                    val decoded = Base64.decode(base64Data, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                    if (bmp != null) return bmp
                }

                // If local asset path (e.g. "./thumbnails/track1.jpg" or "/thumbnails/track1.jpg")
                val cleanPath = artworkUrl.removePrefix(".").removePrefix("/")
                val assetPath = if (cleanPath.startsWith("thumbnails/")) "web/$cleanPath" else "web/thumbnails/$cleanPath"
                var inputStream: InputStream? = null
                try {
                    inputStream = assets.open(assetPath)
                    val bmp = BitmapFactory.decodeStream(inputStream)
                    if (bmp != null) return bmp
                } catch (ignored: Exception) {
                    // Try fallback without prefix
                    try {
                        val fallbackStream = assets.open("web/thumbnails/track1.jpg")
                        val bmp = BitmapFactory.decodeStream(fallbackStream)
                        if (bmp != null) return bmp
                    } catch (e2: Exception) {
                        Log.e(tag, "Asset load error: ${e2.message}")
                    }
                } finally {
                    inputStream?.close()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to resolve artwork: ${e.message}")
            }
        }

        // Fallback: Generates high-tech gradient album art with track initials
        return createGradientArtwork(title, Color.parseColor("#10B981"), Color.parseColor("#064E3B"))
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
                    .setTitle("Grant Media Notification Access")
                    .setMessage("To automatically send live songs and artwork from Spotify or YouTube Music to your backpack display, please grant notification access.")
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
                Log.e(tag, "Failed to bind BleService: ${e.message}")
            }
        }
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
        super.onDestroy()
    }
}
