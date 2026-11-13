package com.smartbackpack.display.model

import android.graphics.Bitmap
import com.google.gson.Gson

data class MusicTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: Long = 0,    // seconds
    val position: Long = 0,    // seconds
    val playing: Boolean = false,
    @Transient val artwork: Bitmap? = null
) {
    /**
     * Converts track data to the BLE JSON format expected by the ESP32
     */
    fun toJson(): String {
        val map = mapOf(
            "title" to title,
            "artist" to artist,
            "album" to album,
            "duration" to duration,
            "position" to position,
            "playing" to playing
        )
        return Gson().toJson(map)
    }

    fun toPlaybackJson(): String {
        val map = mapOf(
            "position" to position,
            "playing" to playing
        )
        return Gson().toJson(map)
    }
}
