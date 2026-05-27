package com.example.bleapp.ui.location

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bleapp.R
import com.example.bleapp.data.LocationPayload
import com.example.bleapp.data.NearbyDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationShareScreen(
    modifier: Modifier = Modifier,
    viewModel: LocationShareViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.all { it }
        viewModel.onIntent(LocationShareIntent.PermissionsResult(granted))
    }

    LaunchedEffect(Unit) {
        viewModel.onIntent(LocationShareIntent.OnScreenStarted)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is LocationShareEffect.RequestPermissions -> {
                    permissionLauncher.launch(effect.permissions)
                }
            }
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.onIntent(LocationShareIntent.DismissError)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val enabled = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR,
                    ) == BluetoothAdapter.STATE_ON
                    viewModel.onIntent(LocationShareIntent.BluetoothStateChanged(enabled))
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        val adapter = (context.getSystemService(BluetoothManager::class.java) as BluetoothManager).adapter
        viewModel.onIntent(LocationShareIntent.BluetoothStateChanged(adapter?.isEnabled == true))
        onDispose { context.unregisterReceiver(receiver) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.location_share_title)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    bluetoothEnabled = state.bluetoothEnabled,
                    locationEnabled = state.locationEnabled,
                    permissionsGranted = state.permissionsGranted,
                    isAdvertising = state.isAdvertising,
                    isScanning = state.isScanning,
                )
            }

            item {
                MyLocationCard(
                    location = state.myLocation,
                    isLoading = state.isLocationLoading,
                    isAdvertising = state.isAdvertising,
                    onRefresh = { viewModel.onIntent(LocationShareIntent.RefreshMyLocation) },
                    onToggleAdvertise = { viewModel.onIntent(LocationShareIntent.ToggleAdvertising) },
                )
            }

            item {
                GattServerCard(
                    isRunning = state.isGattServerRunning,
                    statusMessage = state.gattStatusMessage,
                    enabled = state.myLocation != null && !state.isLocationLoading,
                    onToggle = { viewModel.onIntent(LocationShareIntent.ToggleGattServer) },
                    onOpenChat = { viewModel.onIntent(LocationShareIntent.OpenChatAsServer) },
                )
            }

            state.gattStatusMessage?.let { status ->
                if (state.isGattClientBusy || state.isGattServerRunning) {
                    item {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.nearby_devices_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FilledTonalButton(
                        onClick = { viewModel.onIntent(LocationShareIntent.ToggleScanning) },
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Text(
                            if (state.isScanning) {
                                stringResource(R.string.stop_scan)
                            } else {
                                stringResource(R.string.start_scan)
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            if (state.nearbyDevices.isEmpty() && state.isScanning) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.scanning_hint),
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.nearbyDevices.isEmpty() && !state.isScanning) {
                item {
                    Text(
                        stringResource(R.string.no_devices_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.nearbyDevices, key = { it.address }) { device ->
                NearbyDeviceCard(
                    device = device,
                    dateFormat = dateFormat,
                    gattBusy = state.isGattClientBusy,
                    chatBusy = state.showChatScreen,
                    onGattRead = { viewModel.onIntent(LocationShareIntent.ConnectGattRead(device.address)) },
                    onOpenChat = {
                        viewModel.onIntent(
                            LocationShareIntent.OpenChat(device.address, device.name),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    bluetoothEnabled: Boolean,
    locationEnabled: Boolean,
    permissionsGranted: Boolean,
    isAdvertising: Boolean,
    isScanning: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    if (bluetoothEnabled) R.string.bluetooth_on else R.string.bluetooth_off,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    if (locationEnabled) R.string.location_on else R.string.location_off,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    if (permissionsGranted) R.string.permissions_granted else R.string.permissions_missing,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.ble_test_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isAdvertising) {
                Text(stringResource(R.string.advertising_active), color = MaterialTheme.colorScheme.primary)
            }
            if (isScanning) {
                Text(stringResource(R.string.scanning_active), color = MaterialTheme.colorScheme.primary)
            }
            Text(
                stringResource(R.string.ble_modes_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GattServerCard(
    isRunning: Boolean,
    statusMessage: String?,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenChat: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.gatt_example_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.gatt_example_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            ) {
                Icon(Icons.Default.Link, contentDescription = null)
                Text(
                    if (isRunning) {
                        stringResource(R.string.stop_gatt_server)
                    } else {
                        stringResource(R.string.start_gatt_server)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (isRunning && statusMessage != null) {
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            }
            if (isRunning) {
                FilledTonalButton(onClick = onOpenChat, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Chat, contentDescription = null)
                    Text(
                        stringResource(R.string.open_chat_server),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MyLocationCard(
    location: LocationPayload?,
    isLoading: Boolean,
    isAdvertising: Boolean,
    onRefresh: () -> Unit,
    onToggleAdvertise: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.my_location_title), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_location))
                }
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else if (location != null) {
                LocationText(location)
            } else {
                Text(
                    stringResource(R.string.location_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onToggleAdvertise,
                modifier = Modifier.fillMaxWidth(),
                enabled = location != null && !isLoading,
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Text(
                    if (isAdvertising) {
                        stringResource(R.string.stop_advertising)
                    } else {
                        stringResource(R.string.start_advertising)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun NearbyDeviceCard(
    device: NearbyDevice,
    dateFormat: SimpleDateFormat,
    gattBusy: Boolean,
    chatBusy: Boolean,
    onGattRead: () -> Unit,
    onOpenChat: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Text(device.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                stringResource(R.string.device_address, device.address),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.signal_strength, device.rssi), style = MaterialTheme.typography.bodySmall)

            Text(
                stringResource(R.string.broadcast_location_label),
                style = MaterialTheme.typography.labelMedium,
            )
            val broadcastLocation = device.location
            if (broadcastLocation != null) {
                LocationText(broadcastLocation)
            } else {
                Text(
                    stringResource(R.string.no_location_in_ad),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                stringResource(R.string.gatt_location_label),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            val gattLocation = device.gattLocation
            if (gattLocation != null) {
                LocationText(gattLocation)
                Text(
                    stringResource(
                        R.string.location_received_at,
                        dateFormat.format(Date(gattLocation.timestampMillis)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FilledTonalButton(
                    onClick = onGattRead,
                    enabled = !gattBusy && !chatBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Text(
                        stringResource(R.string.read_via_gatt),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Button(
                onClick = onOpenChat,
                enabled = !chatBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Chat, contentDescription = null)
                Text(
                    stringResource(R.string.open_chat_client),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LocationText(location: LocationPayload) {
    Text(
        stringResource(
            R.string.coordinates_format,
            location.latitude,
            location.longitude,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(R.string.accuracy_format, location.accuracyMeters),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
