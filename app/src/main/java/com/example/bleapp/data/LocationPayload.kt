package com.example.bleapp.data

data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long,
)

data class NearbyDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    /** Location from BLE advertisement (no connect). */
    val location: LocationPayload?,
    /** Location read after GATT connect + characteristic read. */
    val gattLocation: LocationPayload? = null,
    val lastSeenMillis: Long,
)
