package com.xylo.watchapp.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import com.xylo.watchapp.data.DurableSyncQueue
import com.xylo.watchapp.data.MeasurementRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xylo.watchapp.crypto.FrameProtector
import com.xylo.watchapp.crypto.EnrollmentCoordinator
import java.util.UUID

sealed interface LinkState { data object Stopped : LinkState; data object Advertising : LinkState; data class Connected(val address: String) : LinkState; data class Fault(val reason: String) : LinkState }

@SuppressLint("MissingPermission")
class WatchGattServer(
    private val context: Context,
    private val queue: DurableSyncQueue,
    private val measurements: MeasurementRepository,
    private val protector: FrameProtector,
    private val enrollment: EnrollmentCoordinator,
    private val router: MessageRouter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BluetoothGattServerCallback() {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var subscribedDevice: BluetoothDevice? = null
    private val _state = MutableStateFlow<LinkState>(LinkState.Stopped)
    val state: StateFlow<LinkState> = _state
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private lateinit var dataCharacteristic: BluetoothGattCharacteristic
    private lateinit var controlCharacteristic: BluetoothGattCharacteristic
    private var negotiatedMtu: Int = 23
    private var fragmentMessageId: Int = 1
    private val incoming = FragmentAssembler()

    fun start(): Result<Unit> = runCatching {
        require(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        server = requireNotNull(manager.openGattServer(context, this)) { "gatt_server_unavailable" }.also { it.addService(createService()) }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(WatchProtocol.SERVICE_UUID)).build()
        manager.adapter.bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
        _state.value = LinkState.Advertising
    }.onFailure { _state.value = LinkState.Fault(it.message ?: "gatt_start_failed") }

    fun stop() {
        runCatching { manager.adapter.bluetoothLeAdvertiser.stopAdvertising(advertiseCallback) }
        server?.close(); server = null; subscribedDevice = null; _state.value = LinkState.Stopped
    }

    private fun createService() = BluetoothGattService(WatchProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        controlCharacteristic = BluetoothGattCharacteristic(WatchProtocol.CONTROL_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE, BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ).apply { addDescriptor(BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)) }
        addCharacteristic(controlCharacteristic)
        addCharacteristic(BluetoothGattCharacteristic(WatchProtocol.MANIFEST_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
        dataCharacteristic = BluetoothGattCharacteristic(WatchProtocol.DATA_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED).apply {
            addDescriptor(BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        addCharacteristic(dataCharacteristic)
        addCharacteristic(BluetoothGattCharacteristic(WatchProtocol.LIVE_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
        addCharacteristic(BluetoothGattCharacteristic(WatchProtocol.STATUS_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) _state.value = LinkState.Connected(device.address)
        else { subscribedDevice = null; _state.value = LinkState.Advertising }
    }
    override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { negotiatedMtu = mtu.coerceIn(23, 517) }

    override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
        if (descriptor.uuid == cccd && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            subscribedDevice = device; server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null); scope.launch { flush() }
        } else server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
    }

    override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
        if (characteristic.uuid == WatchProtocol.CONTROL_UUID && value.size == 151 && value.copyOfRange(0, 5).contentEquals("WAEN1".toByteArray())) {
            val result = runCatching { enrollment.acceptHello(value) }
            server?.sendResponse(device, requestId, if (result.isSuccess) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            result.getOrNull()?.let { controlCharacteristic.value = it.response; server?.notifyCharacteristicChanged(device, controlCharacteristic, true) }
            return
        }
        val accepted = !preparedWrite && offset == 0 && value.size <= negotiatedMtu - 3
        if (accepted && characteristic.uuid in setOf(WatchProtocol.MANIFEST_UUID, WatchProtocol.DATA_UUID, WatchProtocol.CONTROL_UUID)) scope.launch {
            val assembled = if ((value.firstOrNull()?.toInt() ?: 0) and 0xff == 0xa8) value else runCatching { incoming.add(value) }.getOrNull()
            assembled?.let { encoded ->
                runCatching { protector.open(encoded) }.getOrNull()?.let { opened ->
                    if (opened.first.messageType == 1) enrollment.confirmPending()
                    if (opened.first.messageType == 12) runCatching { SyncAckPayloadCbor.measurementId(opened.second) }.getOrNull()?.let { measurements.acknowledge(it) }
                    router.route(opened.first.messageType, opened.second).forEach { sendRouted(it, opened.first.requestId) }
                    flush()
                }
            }
        }
        server?.sendResponse(device, requestId, if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
    }

    private suspend fun sendRouted(message: RoutedMessage, requestId: Long) {
        val device=subscribedDevice?:return; val frame=protector.seal(message.type,requestId,message.payload)?:return
        val id=fragmentMessageId.also{fragmentMessageId=nextFragmentMessageId(it)}
        val packets=if(frame.size<=negotiatedMtu-3) listOf(frame) else FragmentCodec.fragment(id,frame,negotiatedMtu)
        packets.forEach{dataCharacteristic.value=it;server?.notifyCharacteristicChanged(device,dataCharacteristic,false)}
    }

    private suspend fun flush() {
        val device = subscribedDevice ?: return
        queue.claim(10).forEach { item ->
            val bytes = protector.seal(messageType = 20, requestId = 0, payload = item.payloadCbor)
            if (bytes == null) { queue.retry(item, "authentication_required"); return@forEach }
            val id = fragmentMessageId.also { fragmentMessageId = nextFragmentMessageId(it) }
            val packets = if (bytes.size <= negotiatedMtu - 3) listOf(bytes) else FragmentCodec.fragment(id, bytes, negotiatedMtu)
            val sent = bytes.size <= WatchProtocol.MAX_FRAME_BYTES && packets.all { fragment ->
                dataCharacteristic.value = fragment
                server.orFalse { notifyCharacteristicChanged(device, dataCharacteristic, false) }
            }
            if (!sent) queue.retry(item, "notify_failed")
        }
    }

    private inline fun <T> T?.orFalse(block: T.() -> Boolean): Boolean = this?.block() ?: false
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { _state.value = LinkState.Fault("advertise_$errorCode") }
    }
}
