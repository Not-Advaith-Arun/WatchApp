package com.xylo.watchapp.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object ExperimentalFeatures { const val ANCS_ENABLED_BY_DEFAULT = false }
object AncsContract {
    val SERVICE: UUID = UUID.fromString("7905f431-b5ce-4e99-a40f-4b1e122d00d0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9fbf120d-6301-42d9-8c58-25e699a21dbd")
    val CONTROL_POINT: UUID = UUID.fromString("69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9")
    val DATA_SOURCE: UUID = UUID.fromString("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb")
}
enum class AncsState { DISABLED, DISCOVERING, AUTHORIZED, UNAVAILABLE, FAILED }
enum class AncsEvent { ADDED, MODIFIED, REMOVED }
data class AncsNotice(val uid: Long, val category: Int, val title: String = "", val message: String = "", val positiveAction: Boolean, val negativeAction: Boolean)
data class AncsSourceEvent(val event: AncsEvent, val flags: Int, val category: Int, val uid: Long)

object AncsParser {
    const val MAX_ATTRIBUTE_BYTES = 4096
    fun notificationSource(bytes: ByteArray): AncsSourceEvent {
        require(bytes.size == 8)
        val event = AncsEvent.entries.getOrNull(bytes[0].toInt()) ?: error("unknown_event")
        val uid = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        return AncsSourceEvent(event, bytes[1].toInt() and 255, bytes[2].toInt() and 255, uid)
    }
    fun attributes(bytes: ByteArray): Pair<Long, Map<Int, String>> {
        require(bytes.size in 5..MAX_ATTRIBUTE_BYTES && bytes[0] == 0.toByte())
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); buffer.get(); val uid = buffer.int.toLong() and 0xffffffffL
        val result = linkedMapOf<Int, String>()
        while (buffer.hasRemaining()) { require(buffer.remaining() >= 3); val id = buffer.get().toInt() and 255; val length = buffer.short.toInt() and 0xffff; require(length <= 1024 && length <= buffer.remaining()); val value = ByteArray(length); buffer.get(value); result[id] = value.toString(Charsets.UTF_8) }
        return uid to result
    }
    fun attributeRequest(uid: Long): ByteArray = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).put(0).putInt(uid.toInt()).put(1).putShort(128.toShort()).put(3).putShort(512.toShort()).put(6).put(7).array()
    fun action(uid: Long, positive: Boolean): ByteArray = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put(2).putInt(uid.toInt()).put(if (positive) 0 else 1).array()
}

class AncsSessionInbox(private val limit: Int = 50) {
    private val values = LinkedHashMap<Long, AncsNotice>()
    fun apply(event: AncsSourceEvent) { if (event.event == AncsEvent.REMOVED) values.remove(event.uid) else values[event.uid] = AncsNotice(event.uid, event.category, positiveAction = event.flags and 8 != 0, negativeAction = event.flags and 16 != 0).also { while (values.size >= limit) values.remove(values.keys.first()) } }
    fun applyAttributes(uid: Long, attributes: Map<Int, String>) { values[uid]?.let { values[uid] = it.copy(title = attributes[1].orEmpty(), message = attributes[3].orEmpty()) } }
    fun snapshot(): List<AncsNotice> = values.values.toList()
    fun clear() = values.clear()
}
class AncsDataReassembler {
    private var buffer = ByteArray(0)
    fun append(fragment: ByteArray): Pair<Long, Map<Int, String>>? {
        require(buffer.size + fragment.size <= AncsParser.MAX_ATTRIBUTE_BYTES); buffer += fragment
        return runCatching { AncsParser.attributes(buffer) }.getOrNull()?.also { buffer = ByteArray(0) }
    }
    fun reset() { buffer = ByteArray(0) }
}

/** Platform GATT central implementation owns discovery/subscription; this policy core stays disabled unless explicitly opted in. */
class ExperimentalAncsClient(private val enabled: Boolean = ExperimentalFeatures.ANCS_ENABLED_BY_DEFAULT) {
    var state = if (enabled) AncsState.DISCOVERING else AncsState.DISABLED; private set
    val inbox = AncsSessionInbox()
    private val reassembler = AncsDataReassembler()
    fun authorized() { check(enabled); state = AncsState.AUTHORIZED }
    fun unavailable() { state = AncsState.UNAVAILABLE; inbox.clear(); reassembler.reset() }
    fun onNotificationSource(bytes: ByteArray): ByteArray? { check(state == AncsState.AUTHORIZED); val event = AncsParser.notificationSource(bytes); inbox.apply(event); return if (event.event == AncsEvent.REMOVED) null else AncsParser.attributeRequest(event.uid) }
    fun onDataSource(bytes: ByteArray) { reassembler.append(bytes)?.let { (uid, attrs) -> inbox.applyAttributes(uid, attrs) } }
    fun action(notice: AncsNotice, positive: Boolean): ByteArray { require(if (positive) notice.positiveAction else notice.negativeAction); return AncsParser.action(notice.uid, positive) }
}

/** Optional Android GATT-central transport. It is never started unless the lab flag is explicitly enabled. */
@SuppressLint("MissingPermission")
class AndroidAncsGattClient(private val context: Context, private val policy: ExperimentalAncsClient) : BluetoothGattCallback() {
    private var gatt: BluetoothGatt? = null; private var control: BluetoothGattCharacteristic? = null
    private var pendingDataDescriptor: BluetoothGattDescriptor? = null
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    fun connect(phone: BluetoothDevice) { check(policy.state != AncsState.DISABLED); gatt = phone.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) }
    fun close() { gatt?.close(); gatt = null; policy.unavailable() }
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices() else close() }
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        val service = gatt.getService(AncsContract.SERVICE) ?: return close()
        control = service.getCharacteristic(AncsContract.CONTROL_POINT)
        val notification = service.getCharacteristic(AncsContract.NOTIFICATION_SOURCE) ?: return close()
        val data = service.getCharacteristic(AncsContract.DATA_SOURCE) ?: return close()
        if (control == null) return close()
        gatt.setCharacteristicNotification(notification, true); gatt.setCharacteristicNotification(data, true)
        val first = notification.getDescriptor(cccd) ?: return close(); pendingDataDescriptor = data.getDescriptor(cccd) ?: return close()
        first.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(first)
    }
    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { if (status != BluetoothGatt.GATT_SUCCESS) return close(); pendingDataDescriptor?.let { pendingDataDescriptor = null; it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(it) } ?: policy.authorized() }
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        when (characteristic.uuid) { AncsContract.NOTIFICATION_SOURCE -> policy.onNotificationSource(characteristic.value)?.let(::writeControl); AncsContract.DATA_SOURCE -> policy.onDataSource(characteristic.value) }
    }
    fun perform(notice: AncsNotice, positive: Boolean) = writeControl(policy.action(notice, positive))
    private fun writeControl(value: ByteArray) { control?.let { it.value = value; gatt?.writeCharacteristic(it) } }
}
