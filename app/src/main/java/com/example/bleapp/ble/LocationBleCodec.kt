package com.example.bleapp.ble

import com.example.bleapp.data.LocationPayload
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LocationBleCodec {

    private const val PAYLOAD_SIZE = 16

    fun encode(payload: LocationPayload): ByteArray {
        val latMicro = (payload.latitude * 1_000_000).toInt()
        val lngMicro = (payload.longitude * 1_000_000).toInt()
        val accuracy = payload.accuracyMeters.coerceIn(0f, 65535f).toInt()
        val timestampSec = (payload.timestampMillis / 1000).toInt()

        return ByteBuffer.allocate(PAYLOAD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(latMicro)
            .putInt(lngMicro)
            .putShort(accuracy.toShort())
            .putInt(timestampSec)
            .array()
    }

    fun decode(bytes: ByteArray): LocationPayload? {
        if (bytes.size < PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val latMicro = buffer.int
        val lngMicro = buffer.int
        val accuracy = buffer.short.toInt() and 0xFFFF
        val timestampSec = buffer.int.toLong() and 0xFFFFFFFFL

        return LocationPayload(
            latitude = latMicro / 1_000_000.0,
            longitude = lngMicro / 1_000_000.0,
            accuracyMeters = accuracy.toFloat(),
            timestampMillis = timestampSec * 1000,
        )
    }
}
