package com.example.bleapp.ble

import com.example.bleapp.data.LocationPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationBleCodecTest {

    @Test
    fun encodeAndDecode_roundTrip() {
        val original = LocationPayload(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracyMeters = 12f,
            timestampMillis = 1_715_000_000_000L,
        )
        val decoded = LocationBleCodec.decode(LocationBleCodec.encode(original))!!
        assertEquals(original.latitude, decoded.latitude, 0.000001)
        assertEquals(original.longitude, decoded.longitude, 0.000001)
        assertEquals(original.accuracyMeters, decoded.accuracyMeters, 0.1f)
    }
}
