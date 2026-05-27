package com.example.bleapp.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import androidx.annotation.RequiresPermission
import com.example.bleapp.data.LocationPayload

/**
 * GATT client example: connect → discover service → read location characteristic.
 */
class BleGattClient(private val context: Context) {

    private var gatt: BluetoothGatt? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectAndRead(
        device: BluetoothDevice,
        onStatus: (String) -> Unit,
        onLocation: (LocationPayload) -> Unit,
        onError: (String) -> Unit,
    ) {
        disconnect()
        onStatus("Connecting to ${device.address}…")
        logGatt("connectGatt → ${device.address}")

        gatt = device.connectGatt(
            context,
            false,
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    when (newState) {
                        BluetoothGatt.STATE_CONNECTED -> {
                            logGatt("Connected, discovering services…")
                            onStatus("Connected — discovering services…")
                            gatt.discoverServices()
                        }
                        BluetoothGatt.STATE_DISCONNECTED -> {
                            logGatt("Disconnected (status=$status)")
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                onError("Disconnected (status=$status)")
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        onError("Service discovery failed (status=$status)")
                        disconnect()
                        return
                    }
                    val service = gatt.getService(BleConstants.SERVICE_UUID)
                    if (service == null) {
                        onError("BleApp GATT service not found on device.")
                        disconnect()
                        return
                    }
                    val characteristic = service.getCharacteristic(
                        BleConstants.LOCATION_CHARACTERISTIC_UUID,
                    )
                    if (characteristic == null) {
                        onError("Location characteristic not found.")
                        disconnect()
                        return
                    }
                    logGatt("Reading characteristic ${BleConstants.LOCATION_CHARACTERISTIC_UUID}")
                    onStatus("Reading location characteristic…")
                    gatt.readCharacteristic(characteristic)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (characteristic.uuid != BleConstants.LOCATION_CHARACTERISTIC_UUID) return
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        onError("Characteristic read failed (status=$status)")
                        disconnect()
                        return
                    }
                    val bytes = characteristic.value
                    val location = bytes?.let { LocationBleCodec.decode(it) }
                    if (location == null) {
                        onError("Could not decode location from GATT.")
                        disconnect()
                        return
                    }
                    logGatt("GATT read OK: ${location.latitude}, ${location.longitude}")
                    onStatus("Location received via GATT")
                    onLocation(location)
                    disconnect()
                }
            },
        )
    }

    fun disconnect() {
        gatt?.close()
        gatt = null
    }
}
