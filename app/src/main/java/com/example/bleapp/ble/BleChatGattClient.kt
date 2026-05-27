package com.example.bleapp.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.example.bleapp.data.ChatMessage
import com.example.bleapp.data.ChatPacket
import java.util.UUID

/**
 * Stays connected and sends/receives UTF-8 text on characteristic FE03.
 */
class BleChatGattClient(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private var onPacketListener: ((ChatPacket, String) -> Unit)? = null
    private var onChatReadyListener: (() -> Unit)? = null
    private var onLinkStateListener: ((Boolean) -> Unit)? = null
    private var pendingOnStatus: ((String) -> Unit)? = null
    private var pendingOnError: ((String) -> Unit)? = null
    private var chatReadySignalled = false
    private var readyFallbackRunnable: Runnable? = null
    private var discoverFallbackRunnable: Runnable? = null

    private var pendingWriteError: ((String) -> Unit)? = null
    private var pendingWriteSuccess: (() -> Unit)? = null
    private var writeTimeoutRunnable: Runnable? = null
    private val writeQueue = ArrayDeque<QueuedWrite>()

    private data class QueuedWrite(
        val bytes: ByteArray,
        val onError: (String) -> Unit,
        val onSuccess: () -> Unit,
    )
    private var lastIncomingBytes: ByteArray? = null
    private var lastIncomingTimeMs: Long = 0L
    private var remoteDevice: BluetoothDevice? = null
    private var autoReconnectEnabled = false

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(
        device: BluetoothDevice,
        onStatus: (String) -> Unit,
        onChatReady: () -> Unit,
        onLinkState: (Boolean) -> Unit,
        onPacket: (ChatPacket, String) -> Unit,
        onError: (String) -> Unit,
    ) {
        disconnect()
        remoteDevice = device
        autoReconnectEnabled = true
        onPacketListener = onPacket
        onChatReadyListener = onChatReady
        onLinkStateListener = onLinkState
        pendingOnStatus = onStatus
        pendingOnError = onError
        chatReadySignalled = false
        onStatus("Connecting…")
        logGatt("Chat connect → ${device.address}")

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    signalStatus("Connected — setting up…")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    scheduleDiscoverServicesFallback(gatt)
                    if (!gatt.requestMtu(BleMtu.REQUEST_MTU)) {
                        logGatt("MTU request failed — discovering services")
                        gatt.discoverServices()
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    cancelReadyFallback()
                    messageCharacteristic = null
                    chatReadySignalled = false
                    clearWriteState()
                    signalLinkUp(false)
                    if (autoReconnectEnabled && remoteDevice != null) {
                        logGatt("Chat disconnected (status=$status) — reconnecting…")
                        signalStatus("Reconnecting…")
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            logGatt("MTU=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BleMtu.updateNegotiated(mtu)
            }
            gatt.discoverServices()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != BleConstants.MESSAGE_CHARACTERISTIC_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logGattError("Write failed status=$status")
                failPendingWrite("Message not delivered (error $status). Try again.")
                writeQueue.clear()
            } else {
                logGatt("Write to server OK")
                completePendingWrite()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            cancelDiscoverFallback()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                signalError("Service discovery failed ($status)")
                return
            }
            val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                ?.getCharacteristic(BleConstants.MESSAGE_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                signalError("Chat not found. Is GATT server running on the other phone?")
                return
            }
            messageCharacteristic = characteristic
            // Enable keyboard immediately; CCCD may finish slightly later (needed to receive from host).
            signalChatReady()
            enableNotifications(gatt, characteristic)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            cancelReadyFallback()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logGatt("CCCD write OK — chat ready")
                signalChatReady()
            } else {
                logGattError("CCCD write failed ($status) — enabling chat anyway")
                signalChatReady()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleIncoming(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleIncoming(characteristic, value)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessage(message: ChatMessage, onError: (String) -> Unit) =
        enqueueWrite(BleChatCodec.encodeMessage(message), onError)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendRawPacket(
        bytes: ByteArray,
        onError: (String) -> Unit,
        onSuccess: () -> Unit = {},
    ) = enqueueWrite(bytes, onError, onSuccess)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendTyping(isTyping: Boolean, preview: String = "") {
        enqueueWrite(BleChatCodec.encodeTyping(isTyping, preview), onError = {})
    }

    fun disconnect() {
        autoReconnectEnabled = false
        cancelReadyFallback()
        cancelDiscoverFallback()
        gatt?.close()
        gatt = null
        messageCharacteristic = null
        remoteDevice = null
        onPacketListener = null
        onChatReadyListener = null
        onLinkStateListener = null
        pendingOnStatus = null
        pendingOnError = null
        chatReadySignalled = false
        clearWriteState()
        BleMtu.reset()
        lastIncomingBytes = null
        lastIncomingTimeMs = 0L
    }

    fun isConnected(): Boolean = gatt != null && messageCharacteristic != null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            logGatt("No CCCD — chat ready after service discovery")
            signalChatReady()
            return
        }
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            status == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }
        logGatt("writeDescriptor (API ${Build.VERSION.SDK_INT}) queued=$queued")
        scheduleReadyFallback()
        if (!queued) {
            // Samsung / some OEMs: writeDescriptor returns false but still works async, or write-only OK
            mainHandler.postDelayed({ signalChatReady() }, 400)
        }
    }

    private fun scheduleReadyFallback() {
        cancelReadyFallback()
        readyFallbackRunnable = Runnable {
            logGatt("Chat ready fallback timeout")
            signalChatReady()
        }
        mainHandler.postDelayed(readyFallbackRunnable!!, READY_FALLBACK_MS)
    }

    private fun cancelReadyFallback() {
        readyFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        readyFallbackRunnable = null
    }

    private fun scheduleDiscoverServicesFallback(gatt: BluetoothGatt) {
        cancelDiscoverFallback()
        discoverFallbackRunnable = Runnable {
            if (messageCharacteristic == null) {
                logGatt("Discover services fallback (MTU slow?)")
                gatt.discoverServices()
            }
        }
        mainHandler.postDelayed(discoverFallbackRunnable!!, DISCOVER_FALLBACK_MS)
    }

    private fun cancelDiscoverFallback() {
        discoverFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        discoverFallbackRunnable = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun scheduleReconnect() {
        val device = remoteDevice ?: return
        mainHandler.postDelayed({
            if (!autoReconnectEnabled) return@postDelayed
            logGatt("Reconnect GATT → ${device.address}")
            gatt?.close()
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun signalChatReady() {
        if (chatReadySignalled) return
        chatReadySignalled = true
        cancelReadyFallback()
        signalStatus("Connected — you can type now")
        signalLinkUp(true)
        mainHandler.post { onChatReadyListener?.invoke() }
    }

    private fun signalLinkUp(up: Boolean) {
        mainHandler.post { onLinkStateListener?.invoke(up) }
    }

    private fun clearWriteState() {
        pendingWriteError = null
        pendingWriteSuccess = null
        writeQueue.clear()
        cancelWriteTimeout()
    }

    private fun signalStatus(message: String) {
        mainHandler.post { pendingOnStatus?.invoke(message) }
    }

    private fun signalError(message: String) {
        mainHandler.post { pendingOnError?.invoke(message) }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enqueueWrite(
        bytes: ByteArray,
        onError: (String) -> Unit,
        onSuccess: () -> Unit = {},
    ) {
        writeQueue.add(QueuedWrite(bytes, onError, onSuccess))
        drainWriteQueue()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun drainWriteQueue() {
        if (pendingWriteError != null || pendingWriteSuccess != null) return
        val next = writeQueue.removeFirstOrNull() ?: return
        writePacket(next.bytes, next.onError, next.onSuccess)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writePacket(
        bytes: ByteArray,
        onError: (String) -> Unit,
        onSuccess: () -> Unit,
    ) {
        val characteristic = messageCharacteristic
        val connection = gatt
        if (characteristic == null || connection == null) {
            onError("Not connected to chat.")
            return
        }
        val maxPayload = BleMtu.maxAttPayload()
        if (bytes.size > maxPayload) {
            onError("Packet too large (${bytes.size} > $maxPayload). Reconnect chat.")
            return
        }
        mainHandler.post {
            try {
                cancelWriteTimeout()
                pendingWriteError = onError
                pendingWriteSuccess = onSuccess

                // WRITE_WITH_RESPONSE so the GATT host (OnePlus) reliably gets onCharacteristicWriteRequest.
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = bytes
                var queued = connection.writeCharacteristic(characteristic)
                if (!queued) {
                    logGatt("WRITE_TYPE_DEFAULT failed — trying WRITE_NO_RESPONSE")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    characteristic.value = bytes
                    queued = connection.writeCharacteristic(characteristic)
                    if (!queued) {
                        failPendingWrite("Could not send. Keep chat open on both phones.")
                        return@post
                    }
                    mainHandler.postDelayed({ completePendingWrite() }, WRITE_PACE_MS)
                    return@post
                }
                scheduleWriteTimeout()
            } catch (e: IllegalArgumentException) {
                failPendingWrite(e.message ?: "Message too long.")
            }
        }
    }

    private fun completePendingWrite() {
        cancelWriteTimeout()
        val onSuccess = pendingWriteSuccess
        pendingWriteSuccess = null
        pendingWriteError = null
        onSuccess?.invoke()
        drainWriteQueue()
    }

    private fun failPendingWrite(message: String) {
        cancelWriteTimeout()
        val onError = pendingWriteError
        pendingWriteError = null
        pendingWriteSuccess = null
        onError?.invoke(message)
        drainWriteQueue()
    }

    private fun scheduleWriteTimeout() {
        cancelWriteTimeout()
        writeTimeoutRunnable = Runnable {
            logGatt("Write ack timeout — continuing queue (Samsung workaround)")
            completePendingWrite()
        }
        mainHandler.postDelayed(writeTimeoutRunnable!!, WRITE_ACK_TIMEOUT_MS)
    }

    private fun cancelWriteTimeout() {
        writeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        writeTimeoutRunnable = null
    }

    private fun handleIncoming(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (characteristic.uuid != BleConstants.MESSAGE_CHARACTERISTIC_UUID) return
        if (isDuplicateNotification(value)) {
            logGatt("Ignoring duplicate notification")
            return
        }
        signalChatReady()
        val packet = BleChatCodec.decode(value) ?: return
        logGatt("Chat packet in (${value.size} bytes): $packet")
        val sender = remoteDevice?.address.orEmpty()
        mainHandler.post { onPacketListener?.invoke(packet, sender) }
    }

    private fun isDuplicateNotification(value: ByteArray): Boolean {
        val last = lastIncomingBytes ?: return false
        val elapsed = System.currentTimeMillis() - lastIncomingTimeMs
        val duplicate = elapsed < DUPLICATE_NOTIFY_MS && last.contentEquals(value)
        if (!duplicate) {
            lastIncomingBytes = value.copyOf()
            lastIncomingTimeMs = System.currentTimeMillis()
        }
        return duplicate
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val READY_FALLBACK_MS = 2_500L
        private const val DISCOVER_FALLBACK_MS = 1_200L
        private const val DUPLICATE_NOTIFY_MS = 400L
        private const val RECONNECT_DELAY_MS = 800L
        private const val WRITE_ACK_TIMEOUT_MS = 2_000L
        private const val WRITE_PACE_MS = 12L
    }
}
