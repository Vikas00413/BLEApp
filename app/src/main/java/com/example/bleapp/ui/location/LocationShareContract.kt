package com.example.bleapp.ui.location

import com.example.bleapp.data.ChatMessage
import com.example.bleapp.data.LocationPayload
import com.example.bleapp.data.NearbyDevice

data class LocationShareState(
    val myLocation: LocationPayload? = null,
    val isLocationLoading: Boolean = false,
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val isGattServerRunning: Boolean = false,
    val isGattClientBusy: Boolean = false,
    val gattStatusMessage: String? = null,
    val nearbyDevices: List<NearbyDevice> = emptyList(),
    val bluetoothEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val errorMessage: String? = null,
    val showChatScreen: Boolean = false,
    val chatPeerAddress: String? = null,
    val chatPeerName: String? = null,
    val isChatConnected: Boolean = false,
    val chatStatus: String? = null,
    val chatMessages: List<ChatMessage> = emptyList(),
    val isLocalTyping: Boolean = false,
    val isPeerTyping: Boolean = false,
    val peerTypingPreview: String = "",
)

sealed interface LocationShareIntent {
    data object OnScreenStarted : LocationShareIntent
    data class PermissionsResult(val granted: Boolean) : LocationShareIntent
    data object RefreshMyLocation : LocationShareIntent
    data object ToggleAdvertising : LocationShareIntent
    data object ToggleScanning : LocationShareIntent
    data object ToggleGattServer : LocationShareIntent
    data class ConnectGattRead(val deviceAddress: String) : LocationShareIntent
    data class OpenChat(val deviceAddress: String, val deviceName: String) : LocationShareIntent
    data object OpenChatAsServer : LocationShareIntent
    data object CloseChat : LocationShareIntent
    /** Debounced — used only for BLE typing signal, not stored in UI state. */
    data class ChatInputChanged(val text: String) : LocationShareIntent
    data class SendChatMessage(val text: String) : LocationShareIntent
    data object ShareMyLocation : LocationShareIntent
    data class ShareLink(val url: String) : LocationShareIntent
    data class SharePhoto(val uriString: String) : LocationShareIntent
    data class ShareVideo(val uriString: String) : LocationShareIntent
    data object DismissError : LocationShareIntent
    data class BluetoothStateChanged(val enabled: Boolean) : LocationShareIntent
}

sealed interface LocationShareEffect {
    data class RequestPermissions(val permissions: Array<String>) : LocationShareEffect
}
