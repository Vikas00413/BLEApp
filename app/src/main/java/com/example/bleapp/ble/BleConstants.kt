package com.example.bleapp.ble

import java.util.UUID

object BleConstants {
    /**
     * 16-bit style UUID (advertised compactly as 0xFE01).
     * Keeps BLE advertisement under the 31-byte limit.
     */
    val SERVICE_UUID: UUID = UUID.fromString("0000FE01-0000-1000-8000-00805F9B34FB")

    /** Older app builds used this UUID — still accepted when scanning. */
    val LEGACY_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    val ALL_SERVICE_UUIDS: List<UUID> = listOf(SERVICE_UUID, LEGACY_SERVICE_UUID)

    /** GATT characteristic — client reads location bytes from here after connect. */
    val LOCATION_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("0000FE02-0000-1000-8000-00805F9B34FB")

    /** UTF-8 text chat — write from client, notify to receive. */
    val MESSAGE_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("0000FE03-0000-1000-8000-00805F9B34FB")

    /** Max bytes per ATT packet (use [BleMtu.maxAttPayload] for live MTU). */
    fun maxChatBytes(): Int = BleMtu.maxAttPayload()
}
