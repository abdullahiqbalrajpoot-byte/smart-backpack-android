package com.smartbackpack.display.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.smartbackpack.display.model.MusicTrack
import com.smartbackpack.display.util.ImageUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val tag = "BleManager"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var metaChar: BluetoothGattCharacteristic? = null
    private var artChar: BluetoothGattCharacteristic? = null
    private var playChar: BluetoothGattCharacteristic? = null

    val isConnected = AtomicBoolean(false)
    val isScanning = AtomicBoolean(false)

    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Scanning & Connection ────────────────────────────────────────────────
    fun startScanAndConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            log("Bluetooth is turned off")
            return
        }

        if (isConnected.get()) {
            log("Already connected to display")
            return
        }

        val filter = ScanFilter.Builder()
            .setDeviceName(BleConstants.DEVICE_NAME)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning.set(true)
        log("Scanning for ${BleConstants.DEVICE_NAME}...")

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            log("Found target device: ${device.name ?: "Unknown"} [${device.address}]")
            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed with error code: $errorCode")
            isScanning.set(false)
        }
    }

    fun stopScan() {
        if (isScanning.get()) {
            scanner?.stopScan(scanCallback)
            isScanning.set(false)
            log("Stopped scanning")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        log("Connecting to GATT server...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected.set(false)
        onConnectionStateChanged?.invoke(false)
        log("Disconnected from device")
    }

    // ── GATT Callbacks ───────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT connected. Requesting 512 MTU...")
                gatt.requestMtu(512) // Request maximum MTU for fast artwork transfer
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT disconnected. Triggering auto-reconnect...")
                isConnected.set(false)
                onConnectionStateChanged?.invoke(false)
                scope.launch {
                    delay(3000)
                    startScanAndConnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU negotiated to: $mtu bytes. Discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BleConstants.SERVICE_UUID)
                if (service != null) {
                    metaChar = service.getCharacteristic(BleConstants.METADATA_CHAR_UUID)
                    artChar = service.getCharacteristic(BleConstants.ARTWORK_CHAR_UUID)
                    playChar = service.getCharacteristic(BleConstants.PLAYBACK_CHAR_UUID)

                    isConnected.set(true)
                    onConnectionStateChanged?.invoke(true)
                    log("Services discovered! Display is READY.")
                } else {
                    log("Service UUID not found on device")
                }
            }
        }
    }

    // ── Send Methods ─────────────────────────────────────────────────────────
    fun sendMetadata(track: MusicTrack) {
        if (!isConnected.get() || gatt == null || metaChar == null) return

        val json = track.toJson()
        scope.launch {
            try {
                metaChar?.value = json.toByteArray(Charsets.UTF_8)
                metaChar?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                gatt?.writeCharacteristic(metaChar)
                log("Sent metadata: ${track.title} - ${track.artist}")

                // If artwork is present, send chunks
                track.artwork?.let { bitmap ->
                    delay(100)
                    sendArtwork(bitmap)
                }
            } catch (e: Exception) {
                log("Error sending metadata: ${e.message}")
            }
        }
    }

    private suspend fun sendArtwork(bitmap: android.graphics.Bitmap) {
        if (artChar == null || gatt == null) return

        val rawBytes = ImageUtils.convertToRgb565(bitmap)
        val chunks = ImageUtils.createChunks(rawBytes)
        log("Sending artwork: ${chunks.size} chunks (${rawBytes.size} bytes)...")

        for ((index, chunk) in chunks.withIndex()) {
            artChar?.value = chunk
            artChar?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt?.writeCharacteristic(artChar)
            delay(25) // Small 25ms pace to prevent BLE buffer overflow
        }
        log("Artwork transfer complete!")
    }

    fun sendPlaybackTick(positionSec: Long, isPlaying: Boolean) {
        if (!isConnected.get() || gatt == null || playChar == null) return
        val json = """{"position":$positionSec,"playing":$isPlaying}"""
        playChar?.value = json.toByteArray(Charsets.UTF_8)
        playChar?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(playChar)
    }

    private fun log(message: String) {
        Log.d(tag, message)
        onLogMessage?.invoke(message)
    }
}
