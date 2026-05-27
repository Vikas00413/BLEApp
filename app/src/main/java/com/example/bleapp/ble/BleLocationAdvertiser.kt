package com.example.bleapp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import com.example.bleapp.data.LocationPayload

class BleLocationAdvertiser {

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    fun start(
        adapter: BluetoothAdapter,
        payload: LocationPayload,
        onStarted: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        stop()
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            onFailed("BLE advertising is not supported on this device.")
            return
        }

        val encoded = LocationBleCodec.encode(payload)
        val serviceUuid = ParcelUuid(BleConstants.SERVICE_UUID)
        logScanInfo("Advertising location bytes (${encoded.size}): ${encoded.joinToString { "%02X".format(it) }}")

        // UUID + location in main packet so scanners see data on first callback (type=1).
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, encoded)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                onStarted()
            }

            override fun onStartFailure(errorCode: Int) {
                onFailed(advertiseErrorMessage(errorCode))
            }
        }

        leAdvertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        advertiser = leAdvertiser
        callback = advertiseCallback
    }

    fun stop() {
        val leAdvertiser = advertiser ?: return
        callback?.let { leAdvertiser.stopAdvertising(it) }
        advertiser = null
        callback = null
    }

    private fun advertiseErrorMessage(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "Advertisement too large. Restart the app and try again."
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "Too many BLE advertisers. Close other BLE apps."
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
            "Advertising already running."
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
            "This phone cannot BLE-advertise."
        else -> "Advertising failed (code $code)."
    }
}
