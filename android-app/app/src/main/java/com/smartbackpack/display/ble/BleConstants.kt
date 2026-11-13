package com.smartbackpack.display.ble

import java.util.UUID

object BleConstants {
    const val DEVICE_NAME = "SMART_BACKPACK_DISPLAY"

    // Matches firmware/src/config.h exactly
    val SERVICE_UUID: UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    val METADATA_CHAR_UUID: UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
    val ARTWORK_CHAR_UUID: UUID = UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")
    val PLAYBACK_CHAR_UUID: UUID = UUID.fromString("19B10003-E8F2-537E-4F6C-D104768A1214")

    const val CHUNK_PAYLOAD_SIZE = 480 // Bytes per packet (leaves headroom for 512 MTU)
    const val ARTWORK_DIMENSION = 80   // 80x80 px RGB565
}
