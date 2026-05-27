package com.example.bleapp.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.bleapp.data.LocationPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceLocationProvider(private val context: Context) {

    suspend fun getCurrentLocation(): LocationPayload? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            return@withContext null
        }

        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }.filter { manager.isProviderEnabled(it) }

        val best = providers
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        best?.toPayload()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Location.toPayload() = LocationPayload(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        timestampMillis = time,
    )
}
