package com.example.bleapp.ble

import android.content.Context
import com.example.bleapp.data.ChatPacket
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

object BleImageTransfer {

    const val TYPE_IMAGE_CHUNK: Byte = 4
    const val HEADER_BYTES = 9

    fun chunkDataSize(): Int = (BleMtu.maxAttPayload() - HEADER_BYTES).coerceAtLeast(1)

    private val assemblies = ConcurrentHashMap<Int, Assembly>()

    fun encodeChunks(jpeg: ByteArray, transferId: Int): List<ByteArray> {
        val dataSize = chunkDataSize()
        val totalChunks = (jpeg.size + dataSize - 1) / dataSize
        require(totalChunks in 1..512) { "Image too large for BLE (${jpeg.size} bytes)" }
        return (0 until totalChunks).map { index ->
            val start = index * dataSize
            val end = minOf(start + dataSize, jpeg.size)
            encodeChunk(transferId, index, totalChunks, jpeg, start, end)
        }
    }

    fun decodeChunk(bytes: ByteArray): ChatPacket.ImageChunk? {
        if (bytes.isEmpty() || bytes[0] != TYPE_IMAGE_CHUNK) return null
        if (bytes.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.get() // type
        val transferId = buffer.int
        val index = buffer.short.toInt() and 0xFFFF
        val total = buffer.short.toInt() and 0xFFFF
        if (total <= 0 || index < 0 || index >= total) return null
        val data = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        if (data.isEmpty()) return null
        return ChatPacket.ImageChunk(
            transferId = transferId,
            index = index,
            totalChunks = total,
            data = data,
        )
    }

    fun onChunk(
        context: Context,
        chunk: ChatPacket.ImageChunk,
        onComplete: (localUri: String) -> Unit,
    ) {
        val assembly = assemblies.compute(chunk.transferId) { _, existing ->
            if (existing != null && existing.totalChunks != chunk.totalChunks) {
                Assembly(chunk.totalChunks, mutableMapOf(), System.currentTimeMillis())
            } else {
                existing ?: Assembly(chunk.totalChunks, mutableMapOf(), System.currentTimeMillis())
            }
        } ?: return

        assembly.chunks[chunk.index] = chunk.data
        if (assembly.chunks.size < chunk.totalChunks) return

        assemblies.remove(chunk.transferId)
        val jpeg = ByteArray(assembly.chunks.values.sumOf { it.size })
        var offset = 0
        for (i in 0 until chunk.totalChunks) {
            val part = assembly.chunks[i] ?: return
            System.arraycopy(part, 0, jpeg, offset, part.size)
            offset += part.size
        }

        val dir = File(context.cacheDir, "ble_chat_images").apply { mkdirs() }
        val file = File(dir, "recv_${chunk.transferId}_${System.currentTimeMillis()}.jpg")
        file.writeBytes(jpeg)
        onComplete(file.toURI().toString())
    }

    fun pruneStaleAssemblies(maxAgeMs: Long = 120_000L) {
        val now = System.currentTimeMillis()
        assemblies.entries.removeIf { now - it.value.startedAtMs > maxAgeMs }
    }

    private fun encodeChunk(
        transferId: Int,
        index: Int,
        totalChunks: Int,
        jpeg: ByteArray,
        start: Int,
        end: Int,
    ): ByteArray {
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        header.put(TYPE_IMAGE_CHUNK)
        header.putInt(transferId)
        header.putShort(index.toShort())
        header.putShort(totalChunks.toShort())
        val payload = jpeg.copyOfRange(start, end)
        return header.array() + payload
    }

    private data class Assembly(
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray>,
        val startedAtMs: Long,
    )
}
