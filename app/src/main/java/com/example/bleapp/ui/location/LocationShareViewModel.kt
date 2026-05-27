package com.example.bleapp.ui.location

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bleapp.ble.BleChatCodec
import com.example.bleapp.ble.BleChatGattClient
import com.example.bleapp.ble.BleGattClient
import com.example.bleapp.ble.BleGattServer
import com.example.bleapp.ble.BleImageCompressor
import com.example.bleapp.ble.BleImageTransfer
import com.example.bleapp.ble.BleLocationAdvertiser
import com.example.bleapp.ble.BleLocationScanner
import com.example.bleapp.ble.BleLog
import com.example.bleapp.data.normalizeBleAddress
import com.example.bleapp.data.ChatContentType
import com.example.bleapp.data.ChatMessage
import com.example.bleapp.data.ChatPacket
import com.example.bleapp.data.ChatRepository
import com.example.bleapp.data.NearbyDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.example.bleapp.location.DeviceLocationProvider
import com.example.bleapp.location.LocationSettings
import com.example.bleapp.permissions.BlePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LocationShareViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val locationProvider = DeviceLocationProvider(application)
    private val advertiser = BleLocationAdvertiser()
    private val gattServer = BleGattServer(application)
    private val gattClient = BleGattClient(application)
    private val chatClient = BleChatGattClient(application)
    private val chatRepository = ChatRepository(application)
    private val scanner = BleLocationScanner(
        onDeviceFound = ::onNearbyDeviceFound,
        onScanError = ::onBleScanError,
    )

    private val bluetoothAdapter: BluetoothAdapter? =
        (application.getSystemService(BluetoothManager::class.java))?.adapter

    private var typingIdleJob: Job? = null
    private var lastTypingPayload: ByteArray? = null
    private var typingDebounceJob: Job? = null
    private var chatObserveJob: Job? = null
    private var imageSendJob: Job? = null
    private var lastScanUiUpdateMs = 0L

    private val _state = MutableStateFlow(LocationShareState())
    val state: StateFlow<LocationShareState> = _state.asStateFlow()

    private val _effects = Channel<LocationShareEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onIntent(intent: LocationShareIntent) {
        when (intent) {
            LocationShareIntent.OnScreenStarted -> onScreenStarted()
            is LocationShareIntent.PermissionsResult -> onPermissionsResult(intent.granted)
            LocationShareIntent.RefreshMyLocation -> refreshMyLocation()
            LocationShareIntent.ToggleAdvertising -> toggleAdvertising()
            LocationShareIntent.ToggleScanning -> toggleScanning()
            LocationShareIntent.ToggleGattServer -> toggleGattServer()
            is LocationShareIntent.ConnectGattRead -> connectGattRead(intent.deviceAddress)
            is LocationShareIntent.OpenChat -> openChat(intent.deviceAddress, intent.deviceName)
            LocationShareIntent.OpenChatAsServer -> openChatAsServer()
            LocationShareIntent.CloseChat -> closeChat()
            is LocationShareIntent.ChatInputChanged -> onChatInputChanged(intent.text)
            is LocationShareIntent.SendChatMessage -> sendChatMessage(intent.text)
            LocationShareIntent.ShareMyLocation -> shareMyLocation()
            is LocationShareIntent.ShareLink -> shareLink(intent.url)
            is LocationShareIntent.SharePhoto -> sharePhoto(intent.uriString)
            is LocationShareIntent.ShareVideo -> shareVideo(intent.uriString)
            LocationShareIntent.DismissError -> dismissError()
            is LocationShareIntent.BluetoothStateChanged -> updateBluetoothState(intent.enabled)
        }
    }

    private fun refreshSystemStatus() {
        _state.update {
            it.copy(
                permissionsGranted = BlePermissions.hasAll(app),
                bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
                locationEnabled = LocationSettings.isEnabled(app),
            )
        }
    }

    private fun onScreenStarted() {
        refreshSystemStatus()
        if (!_state.value.permissionsGranted) {
            viewModelScope.launch {
                _effects.send(
                    LocationShareEffect.RequestPermissions(BlePermissions.requiredPermissions()),
                )
            }
        } else {
            refreshMyLocation()
        }
    }

    private fun onPermissionsResult(granted: Boolean) {
        refreshSystemStatus()
        _state.update { it.copy(permissionsGranted = granted) }
        if (granted) {
            refreshMyLocation()
        } else {
            showError("Bluetooth and location permissions are required.")
        }
    }

    private fun refreshMyLocation() {
        if (!_state.value.permissionsGranted) return
        viewModelScope.launch {
            _state.update { it.copy(isLocationLoading = true, errorMessage = null) }
            val location = locationProvider.getCurrentLocation()
            _state.update {
                it.copy(
                    myLocation = location,
                    isLocationLoading = false,
                    errorMessage = if (location == null) {
                        "Could not get location. Enable GPS and try again."
                    } else {
                        null
                    },
                )
            }
            location?.let { loc ->
                if (_state.value.isGattServerRunning) {
                    gattServer.updateLocation(loc)
                }
            }
        }
    }

    private fun toggleAdvertising() {
        refreshSystemStatus()
        val current = _state.value
        if (!ensureBleReady(current)) return
        if (current.isGattServerRunning) {
            showError("Stop GATT server first.")
            return
        }
        if (current.isScanning) {
            showError("Stop scanning on this phone first.")
            return
        }

        if (current.isAdvertising) {
            advertiser.stop()
            _state.update { it.copy(isAdvertising = false) }
            return
        }

        val location = current.myLocation ?: run {
            showError("Refresh your location before advertising.")
            return
        }

        advertiser.start(
            adapter = bluetoothAdapter!!,
            payload = location,
            onStarted = { _state.update { it.copy(isAdvertising = true, errorMessage = null) } },
            onFailed = ::showError,
        )
    }

    private fun toggleGattServer() {
        refreshSystemStatus()
        val current = _state.value
        if (!ensureBleReady(current)) return
        if (current.isAdvertising) {
            showError("Stop broadcast advertise first.")
            return
        }
        if (current.isScanning) {
            showError("Stop scanning on this phone.")
            return
        }

        if (current.isGattServerRunning) {
            closeChat()
            gattServer.stop()
            _state.update {
                it.copy(isGattServerRunning = false, gattStatusMessage = null)
            }
            return
        }

        val location = current.myLocation ?: run {
            showError("Refresh your location before starting GATT server.")
            return
        }

        gattServer.onClientConnected = { device -> onGattClientJoinedChat(device.address) }

        gattServer.start(
            adapter = bluetoothAdapter!!,
            location = location,
            onChatPacket = { packet, sender -> handleIncomingChatPacket(packet, sender) },
            onStarted = {
                _state.update {
                    it.copy(
                        isGattServerRunning = true,
                        gattStatusMessage = "GATT server + chat ready",
                        errorMessage = null,
                    )
                }
            },
            onFailed = ::showError,
        )
    }

    private fun openChatAsServer() {
        if (!_state.value.isGattServerRunning) {
            showError("Start GATT server first (OnePlus / host phone).")
            return
        }
        val existingClient = gattServer.getLastClientAddress()?.normalizeBleAddress()
        _state.update {
            it.copy(
                showChatScreen = true,
                chatPeerAddress = existingClient,
                chatPeerName = if (existingClient != null) "Connected device" else "Chat host (this phone)",
                isChatConnected = existingClient != null,
                chatStatus = if (existingClient != null) {
                    "Connected — you can type now"
                } else {
                    "Waiting for other phone to connect…"
                },
                isLocalTyping = false,
                isPeerTyping = false,
            )
        }
        pauseBackgroundBleForChat()
        if (existingClient != null) {
            startObservingChat(existingClient, "Connected device")
        } else {
            chatObserveJob?.cancel()
        }
    }

    private fun onGattClientJoinedChat(clientAddress: String) {
        if (!_state.value.showChatScreen || !_state.value.isGattServerRunning) return
        val address = clientAddress.normalizeBleAddress()
        startObservingChat(address, _state.value.chatPeerName ?: "Client")
        _state.update {
            it.copy(
                chatPeerAddress = address,
                chatStatus = "Connected — you can type now",
                isChatConnected = true,
            )
        }
    }

    private fun openChat(deviceAddress: String, deviceName: String) {
        val normalizedAddress = deviceAddress.normalizeBleAddress()
        refreshSystemStatus()
        val current = _state.value
        if (!ensureBleReady(current)) return
        if (current.isGattServerRunning || current.isAdvertising) {
            showError("Stop GATT server on this phone. Use Scan + Chat on client phone.")
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(normalizedAddress) ?: run {
            showError("Invalid device.")
            return
        }

        scanner.stop()
        _state.update {
            it.copy(
                isScanning = false,
                showChatScreen = true,
                chatPeerAddress = normalizedAddress,
                chatPeerName = deviceName,
                isChatConnected = false,
                chatStatus = "Connecting…",
                isLocalTyping = false,
                isPeerTyping = false,
            )
        }
        pauseBackgroundBleForChat()
        startObservingChat(normalizedAddress, deviceName)

        chatClient.connect(
            device = device,
            onStatus = { status ->
                _state.update { it.copy(chatStatus = status) }
            },
            onChatReady = {
                _state.update {
                    it.copy(
                        isChatConnected = true,
                        chatStatus = "Connected — you can type now",
                    )
                }
            },
            onLinkState = { connected ->
                _state.update {
                    it.copy(
                        isChatConnected = connected,
                        chatStatus = if (connected) {
                            "Connected — you can type now"
                        } else {
                            "Reconnecting…"
                        },
                        isPeerTyping = if (connected) it.isPeerTyping else false,
                    )
                }
            },
            onPacket = { packet, sender -> handleIncomingChatPacket(packet, sender) },
            onError = { message ->
                showError(message)
            },
        )
    }

    private fun closeChat() {
        typingIdleJob?.cancel()
        typingDebounceJob?.cancel()
        imageSendJob?.cancel()
        chatObserveJob?.cancel()
        chatObserveJob = null
        lastTypingPayload = null
        localTypingActive = false
        sendTypingToPeer(isTyping = false)
        chatClient.disconnect()
        gattServer.onClientConnected = null
        _state.update {
            it.copy(
                showChatScreen = false,
                chatPeerAddress = null,
                chatPeerName = null,
                isChatConnected = false,
                chatStatus = null,
                isLocalTyping = false,
                isPeerTyping = false,
            )
        }
    }

    private fun pauseBackgroundBleForChat() {
        if (_state.value.isScanning) {
            scanner.stop()
            _state.update { it.copy(isScanning = false) }
        }
    }

    private fun onChatInputChanged(text: String) {
        typingIdleJob?.cancel()
        typingDebounceJob?.cancel()
        if (!_state.value.isChatConnected && !_state.value.isGattServerRunning) return
        if (text.isEmpty()) {
            if (localTypingActive) {
                localTypingActive = false
                sendTypingToPeer(isTyping = false)
            }
            return
        }
        typingDebounceJob = viewModelScope.launch {
            delay(TYPING_SEND_DEBOUNCE_MS)
            if (!localTypingActive) {
                localTypingActive = true
                sendTypingToPeer(isTyping = true)
            }
        }
        typingIdleJob = viewModelScope.launch {
            delay(TYPING_IDLE_MS)
            localTypingActive = false
            sendTypingToPeer(isTyping = false)
        }
    }

    private var localTypingActive = false

    private fun sendChatMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        typingIdleJob?.cancel()
        typingDebounceJob?.cancel()
        localTypingActive = false
        sendTypingToPeer(isTyping = false)

        val message = ChatMessage(text = trimmed, contentType = ChatContentType.TEXT, isMine = true)
        sendChatContent(message)
        _state.update { it.copy(isPeerTyping = false) }
    }

    private fun shareMyLocation() {
        val location = _state.value.myLocation ?: run {
            showError("Refresh your location first.")
            return
        }
        val label = "📍 ${location.latitude.formatCoord()}, ${location.longitude.formatCoord()}"
        val message = ChatMessage(
            text = label,
            contentType = ChatContentType.LOCATION,
            latitude = location.latitude,
            longitude = location.longitude,
            isMine = true,
        )
        sendChatContent(message)
    }

    private fun shareLink(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val message = ChatMessage(
            text = trimmed,
            contentType = ChatContentType.LINK,
            linkUrl = trimmed,
            isMine = true,
        )
        sendChatContent(message)
    }

    private fun sharePhoto(uriString: String) {
        val peerAddress = resolveChatPeerAddress()
        if (peerAddress == null) {
            showError("Wait for the other phone to connect.")
            return
        }
        if (!_state.value.isGattServerRunning && !chatClient.isConnected()) {
            showError("Chat not connected yet. Wait for “Connected — you can type now”.")
            return
        }

        val message = ChatMessage(
            text = "Photo",
            contentType = ChatContentType.PHOTO,
            mediaUri = uriString,
            isMine = true,
        )
        appendMessageToState(message)
        viewModelScope.launch { persistMessage(message, peerAddress) }

        imageSendJob?.cancel()
        imageSendJob = viewModelScope.launch {
            _state.update { it.copy(chatStatus = "Sending photo over Bluetooth…") }
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val jpeg = BleImageCompressor.compress(app, Uri.parse(uriString))
                    val transferId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
                    transferId to BleImageTransfer.encodeChunks(jpeg, transferId)
                }
            }
            prepared.onFailure {
                showError(it.message ?: "Could not read photo")
                restoreConnectedChatStatus()
                return@launch
            }
            val chunks = prepared.getOrThrow().second
            try {
                for (chunk in chunks) {
                    if (!sendBlePacketAndWait(chunk)) {
                        showError("Photo send failed. Keep both chat screens open.")
                        return@launch
                    }
                    delay(IMAGE_CHUNK_DELAY_MS)
                }
            } finally {
                restoreConnectedChatStatus()
            }
        }
    }

    private fun shareVideo(uriString: String) {
        val message = ChatMessage(
            text = "Video",
            contentType = ChatContentType.VIDEO,
            mediaUri = uriString,
            isMine = true,
        )
        sendChatContent(message)
    }

    private fun sendChatContent(message: ChatMessage) {
        typingIdleJob?.cancel()
        typingDebounceJob?.cancel()
        localTypingActive = false
        sendTypingToPeer(isTyping = false)

        val peerAddress = resolveChatPeerAddress()
        if (peerAddress == null) {
            showError("Wait for the other phone to connect.")
            return
        }

        // Show on sender screen immediately (Room Flow may not be collecting yet on host).
        appendMessageToState(message)

        if (_state.value.isGattServerRunning) {
            gattServer.sendChatMessage(message, ::showError)
        } else {
            if (!chatClient.isConnected()) {
                showError("Chat not connected yet. Wait for “Connected — you can type now”.")
                return
            }
            chatClient.sendMessage(message, ::showError)
        }

        viewModelScope.launch {
            persistMessage(message, peerAddress)
        }
    }

    private fun appendMessageToState(message: ChatMessage) {
        _state.update { state ->
            state.copy(
                chatMessages = state.chatMessages + message,
                isPeerTyping = false,
            )
        }
    }

    private fun handleIncomingChatPacket(packet: ChatPacket, senderAddress: String) {
        ensureChatPeerFromSender(senderAddress)
        if (!_state.value.isChatConnected && chatClient.isConnected()) {
            _state.update {
                it.copy(isChatConnected = true, chatStatus = "Connected — you can type now")
            }
        }
        when (packet) {
            is ChatPacket.Content -> {
                val incoming = ChatMessage(
                    text = packet.text,
                    contentType = packet.contentType,
                    linkUrl = packet.linkUrl,
                    latitude = packet.latitude,
                    longitude = packet.longitude,
                    isMine = false,
                )
                if (isDuplicateIncoming(incoming)) return
                val peerAddress = resolveChatPeerAddress() ?: return
                appendMessageToState(incoming)
                viewModelScope.launch {
                    persistMessage(incoming, peerAddress)
                }
                _state.update { it.copy(isPeerTyping = false) }
            }
            is ChatPacket.Typing -> {
                if (_state.value.isPeerTyping != packet.isTyping) {
                    _state.update {
                        it.copy(isPeerTyping = packet.isTyping, peerTypingPreview = "")
                    }
                }
            }
            is ChatPacket.ImageChunk -> {
                BleImageTransfer.pruneStaleAssemblies()
                BleImageTransfer.onChunk(app, packet) { localUri ->
                    val incoming = ChatMessage(
                        text = "Photo",
                        contentType = ChatContentType.PHOTO,
                        mediaUri = localUri,
                        isMine = false,
                    )
                    if (isDuplicateIncoming(incoming)) return@onChunk
                    val peerAddress = resolveChatPeerAddress() ?: return@onChunk
                    appendMessageToState(incoming)
                    viewModelScope.launch { persistMessage(incoming, peerAddress) }
                    _state.update { it.copy(isPeerTyping = false) }
                }
            }
        }
    }

    private suspend fun sendBlePacketAndWait(bytes: ByteArray): Boolean =
        suspendCancellableCoroutine { cont ->
            if (_state.value.isGattServerRunning) {
                var failed = false
                gattServer.sendRawPacket(bytes) {
                    failed = true
                    if (cont.isActive) cont.resume(false)
                }
                if (!failed && cont.isActive) cont.resume(true)
            } else {
                chatClient.sendRawPacket(
                    bytes = bytes,
                    onError = { if (cont.isActive) cont.resume(false) },
                    onSuccess = { if (cont.isActive) cont.resume(true) },
                )
            }
        }

    private fun restoreConnectedChatStatus() {
        _state.update { state ->
            if (state.isChatConnected) {
                state.copy(chatStatus = "Connected — you can type now")
            } else {
                state
            }
        }
    }

    private fun startObservingChat(peerAddress: String, peerName: String) {
        chatObserveJob?.cancel()
        chatObserveJob = viewModelScope.launch {
            chatRepository.observeMessages(peerAddress)
                .distinctUntilChanged()
                .collect { fromDb ->
                    _state.update { state ->
                        val merged = mergeWithPendingLocal(state.chatMessages, fromDb)
                        if (merged == state.chatMessages) state
                        else state.copy(chatMessages = merged)
                    }
                }
        }
    }

    /** Keep optimistic "my" messages if DB Flow emits before insert finishes. */
    private fun mergeWithPendingLocal(
        current: List<ChatMessage>,
        fromDb: List<ChatMessage>,
    ): List<ChatMessage> {
        if (fromDb.size >= current.size) return fromDb
        val dbKeys = fromDb.map { "${it.timestampMillis}_${it.text}_${it.isMine}" }.toSet()
        val pending = current.filter { msg ->
            msg.isMine && "${msg.timestampMillis}_${msg.text}_${msg.isMine}" !in dbKeys
        }
        return (fromDb + pending).sortedBy { it.timestampMillis }
    }

    private suspend fun persistMessage(message: ChatMessage, peerAddress: String) {
        val peerName = _state.value.chatPeerName ?: "Peer"
        chatRepository.saveMessage(message, peerAddress, peerName)
    }

    private fun ensureChatPeerFromSender(senderAddress: String) {
        if (senderAddress.isBlank()) return
        val normalized = senderAddress.normalizeBleAddress()
        val state = _state.value
        if (!_state.value.isGattServerRunning || !state.showChatScreen) return
        if (state.chatPeerAddress?.normalizeBleAddress() == normalized) return
        startObservingChat(normalized, state.chatPeerName?.takeIf { it != "Chat host (this phone)" } ?: "Client")
        _state.update {
            it.copy(
                chatPeerAddress = normalized,
                isChatConnected = true,
                chatStatus = "Connected — you can type now",
            )
        }
    }

    private fun resolveChatPeerAddress(): String? {
        val fromState = _state.value.chatPeerAddress
        if (fromState != null) return fromState.normalizeBleAddress()
        val fromServer = gattServer.getLastClientAddress()?.normalizeBleAddress()
        if (fromServer != null && _state.value.isGattServerRunning) {
            return fromServer
        }
        return null
    }

    private fun isDuplicateIncoming(message: ChatMessage): Boolean {
        val last = _state.value.chatMessages.lastOrNull() ?: return false
        if (last.isMine) return false
        if (last.contentType != message.contentType) return false
        if (last.text != message.text) return false
        if (last.linkUrl != message.linkUrl) return false
        if (last.latitude != message.latitude || last.longitude != message.longitude) return false
        if (last.mediaUri != message.mediaUri) return false
        return System.currentTimeMillis() - last.timestampMillis < DUPLICATE_MESSAGE_MS
    }

    private fun Double.formatCoord(): String = String.format("%.5f", this)

    private fun sendTypingToPeer(isTyping: Boolean) {
        if (!chatClient.isConnected() && !_state.value.isChatConnected) return
        val payload = BleChatCodec.encodeTyping(isTyping, preview = "")
        if (lastTypingPayload?.contentEquals(payload) == true) return
        lastTypingPayload = payload

        if (_state.value.isGattServerRunning && _state.value.chatPeerAddress == null) {
            gattServer.sendTyping(isTyping, preview = "")
        } else if (chatClient.isConnected()) {
            chatClient.sendTyping(isTyping, preview = "")
        }
    }

    companion object {
        private const val TYPING_IDLE_MS = 1_500L
        private const val TYPING_SEND_DEBOUNCE_MS = 450L
        private const val DUPLICATE_MESSAGE_MS = 2_000L
        private const val IMAGE_CHUNK_DELAY_MS = 30L
        private const val SCAN_UI_THROTTLE_MS = 500L
    }

    private fun connectGattRead(deviceAddress: String) {
        refreshSystemStatus()
        val current = _state.value
        if (!ensureBleReady(current)) return
        if (current.isGattServerRunning || current.isAdvertising) {
            showError("Stop sharing on this phone before GATT read.")
            return
        }
        if (current.isGattClientBusy) return

        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress) ?: run {
            showError("Invalid device address.")
            return
        }

        scanner.stop()
        _state.update {
            it.copy(isScanning = false, isGattClientBusy = true, gattStatusMessage = "Reading location…")
        }

        gattClient.connectAndRead(
            device = device,
            onStatus = { status -> _state.update { it.copy(gattStatusMessage = status) } },
            onLocation = { location ->
                _state.update { state ->
                    val updatedDevices = state.nearbyDevices.map { d ->
                        if (d.address == deviceAddress) d.copy(gattLocation = location) else d
                    }
                    state.copy(
                        nearbyDevices = updatedDevices,
                        isGattClientBusy = false,
                        gattStatusMessage = "Location read complete",
                    )
                }
            },
            onError = { message ->
                _state.update { it.copy(isGattClientBusy = false, gattStatusMessage = null) }
                showError(message)
            },
        )
    }

    private fun toggleScanning() {
        refreshSystemStatus()
        val current = _state.value
        if (!ensureBleReady(current)) return
        if (current.isAdvertising || current.isGattServerRunning) {
            showError("Stop GATT server on this phone. Other phone scans.")
            return
        }
        if (current.isGattClientBusy || current.showChatScreen) {
            showError("Close chat or wait for GATT read to finish.")
            return
        }

        if (current.isScanning) {
            scanner.stop()
            _state.update { it.copy(isScanning = false) }
            return
        }

        val leScanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            showError("BLE scanner not available.")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, nearbyDevices = emptyList(), errorMessage = null) }
            withContext(Dispatchers.Main) { scanner.start(leScanner) }
        }
    }

    private fun ensureBleReady(state: LocationShareState): Boolean {
        if (!state.permissionsGranted) {
            requestPermissions()
            return false
        }
        if (!state.locationEnabled) {
            showError("Turn ON Location/GPS.")
            return false
        }
        if (bluetoothAdapter?.isEnabled != true) {
            showError("Turn on Bluetooth.")
            return false
        }
        return true
    }

    private fun onNearbyDeviceFound(device: NearbyDevice) {
        val now = System.currentTimeMillis()
        if (now - lastScanUiUpdateMs < SCAN_UI_THROTTLE_MS) return
        lastScanUiUpdateMs = now

        _state.update { state ->
            val previous = state.nearbyDevices.find { it.address == device.address }
            val merged = device.copy(gattLocation = previous?.gattLocation)
            if (previous != null &&
                previous.rssi == merged.rssi &&
                previous.name == merged.name &&
                previous.location == merged.location
            ) {
                return@update state
            }
            val updated = state.nearbyDevices
                .filterNot { it.address == device.address }
                .plus(merged)
                .sortedByDescending { it.rssi }
            state.copy(nearbyDevices = updated)
        }
    }

    private fun onBleScanError(message: String) {
        scanner.stop()
        _state.update { it.copy(isScanning = false) }
        showError(message)
    }

    private fun updateBluetoothState(enabled: Boolean) {
        _state.update { it.copy(bluetoothEnabled = enabled) }
        if (!enabled) stopAllBle()
    }

    private fun stopAllBle() {
        closeChat()
        advertiser.stop()
        gattServer.stop()
        gattClient.disconnect()
        scanner.stop()
        _state.update {
            it.copy(
                isAdvertising = false,
                isScanning = false,
                isGattServerRunning = false,
                isGattClientBusy = false,
                gattStatusMessage = null,
            )
        }
    }

    private fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun showError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }

    private fun requestPermissions() {
        viewModelScope.launch {
            _effects.send(
                LocationShareEffect.RequestPermissions(BlePermissions.requiredPermissions()),
            )
        }
    }

    override fun onCleared() {
        stopAllBle()
        super.onCleared()
    }
}
