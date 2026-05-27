package com.example.bleapp.ble

/**
 * Tracks negotiated ATT MTU so packets stay within [maxAttPayload] (MTU − 3).
 * Client updates from [android.bluetooth.BluetoothGattCallback.onMtuChanged];
 * GATT host assumes the client requested a high MTU when it connects.
 */
object BleMtu {

    const val REQUEST_MTU = 517
    private const val ATT_HEADER = 3
    private const val DEFAULT_MTU = 23
    /** Safe when MTU exchange did not run yet (payload = 20 bytes). */
    private const val MIN_PAYLOAD = 20

    @Volatile
    var negotiatedMtu: Int = DEFAULT_MTU
        private set

    fun updateNegotiated(mtu: Int) {
        negotiatedMtu = mtu.coerceIn(DEFAULT_MTU, REQUEST_MTU)
    }

    fun onClientConnected() {
        // Link MTU is chosen by the client; assume a modern size for host → client notifies.
        updateNegotiated(REQUEST_MTU)
    }

    fun reset() {
        negotiatedMtu = DEFAULT_MTU
    }

    fun maxAttPayload(): Int =
        (negotiatedMtu - ATT_HEADER).coerceAtLeast(MIN_PAYLOAD)
}
