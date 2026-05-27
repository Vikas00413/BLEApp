package com.example.bleapp.data

import java.util.Locale

/** BLE MAC addresses must use one casing for Room lookups. */
fun String.normalizeBleAddress(): String = uppercase(Locale.US)
