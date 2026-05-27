package com.example.bleapp.ble

import android.util.Log

object BleLog {
    const val TAG_SCAN = "BleApp/Scan"
    const val TAG_ADVERTISE = "BleApp/Advertise"
    const val TAG_GATT = "BleApp/GATT"
    const val TAG_VIEWMODEL = "BleApp/ViewModel"
}

internal fun logGatt(message: String) {
    android.util.Log.i(BleLog.TAG_GATT, message)
}

internal fun logGattError(message: String, throwable: Throwable? = null) {
    if (throwable != null) {
        android.util.Log.e(BleLog.TAG_GATT, message, throwable)
    } else {
        android.util.Log.e(BleLog.TAG_GATT, message)
    }
}

internal fun logScanError(message: String, throwable: Throwable? = null) {
    if (throwable != null) {
        Log.e(BleLog.TAG_SCAN, message, throwable)
    } else {
        Log.e(BleLog.TAG_SCAN, message)
    }
}

internal fun logScanDebug(message: String) {
    Log.d(BleLog.TAG_SCAN, message)
}

internal fun logScanInfo(message: String) {
    Log.i(BleLog.TAG_SCAN, message)
}
