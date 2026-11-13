package com.smartbackpack.display.util

import android.graphics.Bitmap
import com.smartbackpack.display.ble.BleConstants
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageUtils {

    /**
     * Resizes a Bitmap to 80x80 and converts to 16-bit RGB565 binary format
     */
    fun convertToRgb565(bitmap: Bitmap, targetSize: Int = BleConstants.ARTWORK_DIMENSION): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        val byteBuffer = ByteBuffer.allocate(targetSize * targetSize * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val pixels = IntArray(targetSize * targetSize)
        scaled.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF) shr 3 // 5 bits red
            val g = (pixel shr 8 and 0xFF) shr 2  // 6 bits green
            val b = (pixel and 0xFF) shr 3        // 5 bits blue
            val rgb565 = (r shl 11) or (g shl 5) or b
            byteBuffer.putShort(rgb565.toShort())
        }

        return byteBuffer.array()
    }

    /**
     * Splits raw RGB565 data into chunk packets with headers:
     * Header: [chunkIndex: uint16 LE] [totalChunks: uint16 LE] [payload bytes]
     */
    fun createChunks(rawData: ByteArray, chunkSize: Int = BleConstants.CHUNK_PAYLOAD_SIZE): List<ByteArray> {
        val totalChunks = ((rawData.size + chunkSize - 1) / chunkSize)
        val chunks = mutableListOf<ByteArray>()

        for (i in 0 until totalChunks) {
            val offset = i * chunkSize
            val length = minOf(chunkSize, rawData.size - offset)

            val packet = ByteBuffer.allocate(4 + length).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putShort(i.toShort())           // Chunk index
                putShort(totalChunks.toShort())  // Total chunks
                put(rawData, offset, length)    // Payload
            }
            chunks.add(packet.array())
        }

        return chunks
    }
}
