package com.example.bleapp.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.example.bleapp.data.NearbyDevice

class BleLocationScanner(
    private val onDeviceFound: (NearbyDevice) -> Unit,
    /** Named onScanError — avoids clash with ScanCallback.onScanFailed(). */
    private val onScanError: (String) -> Unit,
) {

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceUuids = BleConstants.ALL_SERVICE_UUIDS.map { ParcelUuid(it) }

    fun start(leScanner: BluetoothLeScanner) {
        stop()
        logScanInfo("Starting BLE scan (serviceUuids=${BleConstants.ALL_SERVICE_UUIDS})")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val name = result.device.name ?: result.scanRecord?.deviceName ?: "null"
                logScanDebug(
                    "onScanResult: addr=$address name=$name rssi=${result.rssi} " +
                        "callbackType=$callbackType (${callbackTypeName(callbackType)})",
                )

                val device = result.toNearbyDevice()
                if (device == null) {
                    logScanDebug("Ignored (not BleApp): addr=$address ${result.scanRecord?.advertisementSummary()}")
                    return
                }

                logScanInfo(
                    "Matched BleApp device: addr=${device.address} name=${device.name} rssi=${device.rssi} " +
                        "hasLocation=${device.location != null}",
                )
                mainHandler.post { onDeviceFound(device) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                logScanDebug("onBatchScanResults: count=${results.size}")
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                val message = scanErrorMessage(errorCode)
                logScanError("onScanFailed: errorCode=$errorCode (${scanErrorCodeName(errorCode)}) message=$message")
                mainHandler.post { this@BleLocationScanner.onScanError(message) }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            leScanner.startScan(null, settings, callback)
            logScanInfo("startScan() called successfully")
        } catch (securityException: SecurityException) {
            logScanError("startScan() SecurityException — missing Bluetooth permissions?", securityException)
            onScanError("Bluetooth scan permission denied. Grant all permissions and retry.")
            return
        } catch (exception: Exception) {
            logScanError("startScan() failed with exception", exception)
            onScanError("Scan start failed: ${exception.message}")
            return
        }

        scanner = leScanner
        scanCallback = callback
    }

    fun stop() {
        val leScanner = scanner
        val callback = scanCallback
        if (leScanner == null || callback == null) {
            logScanDebug("stop() skipped — scan not running")
            return
        }
        try {
            leScanner.stopScan(callback)
            logScanInfo("BLE scan stopped")
        } catch (securityException: SecurityException) {
            logScanError("stopScan() SecurityException", securityException)
        } catch (exception: Exception) {
            logScanError("stopScan() failed", exception)
        }
        scanner = null
        scanCallback = null
    }

    private fun ScanResult.toNearbyDevice(): NearbyDevice? {
        val record = scanRecord
        if (record == null) {
            logScanDebug("scanRecord is null for ${device.address}")
            return null
        }

        if (!record.hasOurService()) return null

        val serviceData = record.getOurServiceData()
        logScanDebug(
            "BleApp service: addr=${device.address} serviceDataBytes=${serviceData?.size ?: 0} " +
                record.advertisementSummary(),
        )

        val location = serviceData?.let { LocationBleCodec.decode(it) }
        if (serviceData != null && location == null) {
            logScanError(
                "Failed to decode location: addr=${device.address} " +
                    "bytes=${serviceData.joinToString { "%02X".format(it) }}",
            )
        }

        val displayName = record.deviceName?.takeIf { it.isNotBlank() }
            ?: device.name?.takeIf { it.isNotBlank() }
            ?: "BleApp device"

        return NearbyDevice(
            address = device.address,
            name = displayName,
            rssi = rssi,
            location = location,
            lastSeenMillis = System.currentTimeMillis(),
        )
    }

    private fun ScanRecord.hasOurService(): Boolean {
        val advertised = this.serviceUuids?.map { parcel -> parcel.uuid } ?: emptyList()
        return BleConstants.ALL_SERVICE_UUIDS.any { it in advertised }
    }

    private fun ScanRecord.getOurServiceData(): ByteArray? {
        for (uuid in serviceUuids) {
            getServiceData(uuid)?.let { return it }
        }
        return null
    }

    private fun ScanRecord.advertisementSummary(): String {
        val uuids = this.serviceUuids?.joinToString { parcel -> parcel.uuid.toString() } ?: "none"
        return "advertisedUuids=[$uuids]"
    }

    private fun callbackTypeName(type: Int): String = when (type) {
        ScanSettings.CALLBACK_TYPE_ALL_MATCHES -> "ALL_MATCHES"
        ScanSettings.CALLBACK_TYPE_FIRST_MATCH -> "FIRST_MATCH"
        ScanSettings.CALLBACK_TYPE_MATCH_LOST -> "MATCH_LOST"
        else -> "UNKNOWN"
    }

    private fun scanErrorCodeName(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
        else -> "UNKNOWN"
    }

    private fun scanErrorMessage(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED ->
            "Scan already running. Tap Stop scan, wait 2 seconds, then try again."
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Scan failed. Turn ON Location/GPS in quick settings, grant all permissions, then retry."
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
            "BLE scan is not supported on this device."
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
            "BLE scan internal error. Toggle Bluetooth off/on and retry."
        else -> "Scan failed (code $code). Turn ON GPS and Bluetooth, then retry."
    }
}
