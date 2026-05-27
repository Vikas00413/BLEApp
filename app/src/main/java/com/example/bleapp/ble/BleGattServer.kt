package com.example.bleapp.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.example.bleapp.data.ChatMessage
import com.example.bleapp.data.ChatPacket
import com.example.bleapp.data.LocationPayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * GATT server: location read (FE02) + chat write/notify (FE03).
 */
class BleGattServer(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gattServer: BluetoothGattServer? = null
    private var locationCharacteristic: BluetoothGattCharacteristic? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private var leAdvertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var currentLocation: LocationPayload? = null
    private var onChatPacketListener: ((ChatPacket, String) -> Unit)? = null
    private val pendingWriteBuffers = ConcurrentHashMap<String, ByteArray>()
    var onClientConnected: ((BluetoothDevice) -> Unit)? = null
    private val connectedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private val notifyEnabledAddresses = CopyOnWriteArraySet<String>()
    /** Kept when the OS drops [connectedDevices] while the link is still up (common on OnePlus host). */
    private var lastClientAddress: String? = null

    fun getLastClientAddress(): String? = lastClientAddress

    fun start(
        adapter: BluetoothAdapter,
        location: LocationPayload,
        onChatPacket: (ChatPacket, String) -> Unit,
        onStarted: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        stop()
        currentLocation = location
        onChatPacketListener = onChatPacket

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        rememberClient(device)
                        BleMtu.onClientConnected()
                        logGatt("Client ${device.address} CONNECTED (total=${connectedDevices.size})")
                        mainHandler.post { onClientConnected?.invoke(device) }
                    }
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        connectedDevices.remove(device)
                        notifyEnabledAddresses.remove(device.address)
                        if (lastClientAddress == device.address) {
                            lastClientAddress = null
                        }
                        logGatt("Client ${device.address} DISCONNECTED status=$status")
                    }
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (descriptor.uuid != CCCD_UUID) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset,
                            null,
                        )
                    }
                    return
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value,
                    )
                }
                val enableNotify = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                rememberClient(device)
                if (enableNotify) {
                    notifyEnabledAddresses.add(device.address)
                } else {
                    notifyEnabledAddresses.remove(device.address)
                }
                logGatt("CCCD ${device.address} notifications=$enableNotify")
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != BleConstants.LOCATION_CHARACTERISTIC_UUID) return
                val locationBytes = currentLocation?.let { LocationBleCodec.encode(it) }
                if (locationBytes == null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    return
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, locationBytes)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (characteristic.uuid != BleConstants.MESSAGE_CHARACTERISTIC_UUID) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset,
                            null,
                        )
                    }
                    return
                }

                // Samsung/client waits for this response — must reply FIRST (OnePlus server fix).
                if (responseNeeded) {
                    val ok = gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value,
                    )
                    logGatt("Write response to ${device.address} sent=$ok offset=$offset len=${value.size}")
                }

                if (preparedWrite) {
                    accumulatePreparedWrite(device.address, offset, value)
                    return
                }

                val payload = assembleWritePayload(device.address, offset, value) ?: return
                deliverChatPayload(device, characteristic, payload)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onExecuteWrite(
                device: BluetoothDevice,
                requestId: Int,
                execute: Boolean,
            ) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (execute) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
                if (!execute) {
                    pendingWriteBuffers.remove(device.address)
                    return
                }
                val payload = pendingWriteBuffers.remove(device.address) ?: return
                val characteristic = messageCharacteristic ?: return
                deliverChatPayload(device, characteristic, payload)
            }
        }

        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        if (gattServer == null) {
            onFailed("Could not open GATT server. On some phones: disable battery optimization for BleApp.")
            return
        }

        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val locationChar = BluetoothGattCharacteristic(
            BleConstants.LOCATION_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        service.addCharacteristic(locationChar)
        locationCharacteristic = locationChar

        val messageChar = BluetoothGattCharacteristic(
            BleConstants.MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        messageChar.addDescriptor(createCccdDescriptor())
        service.addCharacteristic(messageChar)
        messageCharacteristic = messageChar

        if (gattServer?.addService(service) != true) {
            stop()
            onFailed("Could not add GATT service.")
            return
        }

        updateCharacteristicValue(location)
        startConnectableAdvertising(adapter, onStarted, onFailed)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendChatMessage(message: ChatMessage, onError: (String) -> Unit) {
        sendPacket(BleChatCodec.encodeMessage(message), onError)
    }

    fun sendTyping(isTyping: Boolean, preview: String = "") {
        sendPacket(BleChatCodec.encodeTyping(isTyping, preview)) { }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendRawPacket(bytes: ByteArray, onError: (String) -> Unit) {
        sendPacket(bytes, onError)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendPacket(bytes: ByteArray, onError: (String) -> Unit) {
        val characteristic = messageCharacteristic ?: run {
            onError("Chat not ready.")
            return
        }
        val maxPayload = BleMtu.maxAttPayload()
        if (bytes.size > maxPayload) {
            onError("Packet too large (${bytes.size} > $maxPayload).")
            return
        }
        try {
            characteristic.value = bytes
            val delivered = notifyChatMessage(characteristic)
            if (!delivered) {
                onError("Other phone not reachable. Keep chat open on both devices.")
            }
        } catch (e: IllegalArgumentException) {
            onError(e.message ?: "Packet too large.")
        }
    }

    fun updateLocation(location: LocationPayload) {
        currentLocation = location
        updateCharacteristicValue(location)
    }

    fun stop() {
        leAdvertiser?.let { adv -> advertiseCallback?.let { adv.stopAdvertising(it) } }
        leAdvertiser = null
        advertiseCallback = null
        connectedDevices.clear()
        notifyEnabledAddresses.clear()
        pendingWriteBuffers.clear()
        lastClientAddress = null
        onClientConnected = null
        gattServer?.close()
        gattServer = null
        locationCharacteristic = null
        messageCharacteristic = null
        currentLocation = null
        onChatPacketListener = null
        BleMtu.reset()
        logGatt("GATT server stopped")
    }

    private fun createCccdDescriptor(): BluetoothGattDescriptor = BluetoothGattDescriptor(
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )

    private fun updateCharacteristicValue(location: LocationPayload) {
        locationCharacteristic?.value = LocationBleCodec.encode(location)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyChatMessage(
        characteristic: BluetoothGattCharacteristic,
        excludeAddress: String? = null,
    ): Boolean {
        val server = gattServer ?: return false
        val targets = devicesForNotify(excludeAddress)
        if (targets.isEmpty()) {
            logGattError("No GATT clients to notify — connected=${connectedDevices.size} cccd=${notifyEnabledAddresses.size}")
            return false
        }
        var anyDelivered = false
        targets.forEach { device ->
            var ok = server.notifyCharacteristicChanged(device, characteristic, false)
            if (!ok) {
                logGatt("Notify retry → ${device.address}")
                ok = server.notifyCharacteristicChanged(device, characteristic, false)
            }
            if (ok) anyDelivered = true
            logGatt("Notify → ${device.address} ok=$ok")
        }
        return anyDelivered
    }

    private fun accumulatePreparedWrite(deviceAddress: String, offset: Int, chunk: ByteArray) {
        val merged = mergeWriteChunk(pendingWriteBuffers[deviceAddress], offset, chunk)
        pendingWriteBuffers[deviceAddress] = merged
    }

    /** Reassemble long writes (offset > 0) from the client before decode. */
    private fun assembleWritePayload(deviceAddress: String, offset: Int, chunk: ByteArray): ByteArray? {
        if (offset == 0 && chunk.isNotEmpty() && isCompletePacket(chunk)) {
            pendingWriteBuffers.remove(deviceAddress)
            return chunk
        }
        val merged = mergeWriteChunk(pendingWriteBuffers.remove(deviceAddress), offset, chunk)
        return if (isCompletePacket(merged)) {
            merged
        } else {
            pendingWriteBuffers[deviceAddress] = merged
            null
        }
    }

    private fun mergeWriteChunk(existing: ByteArray?, offset: Int, chunk: ByteArray): ByteArray {
        val needed = offset + chunk.size
        val base = existing ?: ByteArray(0)
        val out = if (base.size >= needed) {
            base.copyOf()
        } else {
            ByteArray(needed).also { System.arraycopy(base, 0, it, 0, base.size) }
        }
        System.arraycopy(chunk, 0, out, offset, chunk.size)
        return out
    }

    private fun isCompletePacket(bytes: ByteArray): Boolean =
        BleChatCodec.decode(bytes) != null || BleImageTransfer.decodeChunk(bytes) != null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun deliverChatPayload(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
    ) {
        val packet = BleChatCodec.decode(payload)
        if (packet == null) {
            logGattError("Undecodable write from ${device.address}, ${payload.size} bytes")
            return
        }
        rememberClient(device)
        logGatt("Chat write from ${device.address}: $packet (${payload.size} bytes)")
        characteristic.value = payload
        notifyChatMessage(characteristic, excludeAddress = device.address)
        mainHandler.post { onChatPacketListener?.invoke(packet, device.address) }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun rememberClient(device: BluetoothDevice) {
        connectedDevices.add(device)
        lastClientAddress = device.address
    }

    /** Prefer live set; fall back to last client / CCCD list when OEM clears [connectedDevices] while still linked. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun devicesForNotify(excludeAddress: String?): List<BluetoothDevice> {
        val adapter = bluetoothManager.adapter ?: return emptyList()
        val live = connectedDevices.filter { excludeAddress == null || it.address != excludeAddress }
        if (live.isNotEmpty()) return live

        val fallbackAddresses = buildList {
            lastClientAddress?.let { add(it) }
            addAll(notifyEnabledAddresses)
        }.distinct().filter { excludeAddress == null || it != excludeAddress }

        return fallbackAddresses.mapNotNull { address ->
            runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        }
    }

    fun hasNotifySubscriber(): Boolean =
        connectedDevices.isNotEmpty() || notifyEnabledAddresses.isNotEmpty() || lastClientAddress != null

    private fun startConnectableAdvertising(
        adapter: BluetoothAdapter,
        onStarted: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onFailed("BLE advertiser not available.")
            return
        }

        val serviceUuid = ParcelUuid(BleConstants.SERVICE_UUID)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .setIncludeDeviceName(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                logGatt("GATT server advertising (connectable)")
                onStarted()
            }

            override fun onStartFailure(errorCode: Int) {
                onFailed(advertiseError(errorCode))
            }
        }

        advertiser.startAdvertising(settings, data, scanResponse, callback)
        leAdvertiser = advertiser
        advertiseCallback = callback
    }

    private fun advertiseError(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "Advertise packet too large. Try again."
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "Stop other BLE apps, then retry."
        else -> "GATT advertise failed (code $code)."
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
