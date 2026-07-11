package com.xylo.watchapp.ble

import com.xylo.watchapp.data.MeasurementEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.ceil
import java.io.ByteArrayOutputStream
import org.json.JSONArray

object WatchProtocol {
    const val VERSION = 1
    val SERVICE_UUID: UUID = UUID.fromString("8f6d0000-7a5b-4c31-9e21-6d4f57415443")
    val CONTROL_UUID: UUID = UUID.fromString("8f6d0001-7a5b-4c31-9e21-6d4f57415443")
    val MANIFEST_UUID: UUID = UUID.fromString("8f6d0002-7a5b-4c31-9e21-6d4f57415443")
    val DATA_UUID: UUID = UUID.fromString("8f6d0003-7a5b-4c31-9e21-6d4f57415443")
    val LIVE_UUID: UUID = UUID.fromString("8f6d0004-7a5b-4c31-9e21-6d4f57415443")
    val STATUS_UUID: UUID = UUID.fromString("8f6d0005-7a5b-4c31-9e21-6d4f57415443")
    const val MAX_FRAME_BYTES = 65_536
    const val MAX_RAW_FRAME_BYTES = 8 * 1024 * 1024
    const val FRAGMENT_HEADER_BYTES = 6
}
object DeviceIdentity { val uuidBytes: ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest("WatchApp.device.v1".toByteArray()).copyOf(16).also { it[6]=((it[6].toInt()and 15)or 0x50).toByte();it[8]=((it[8].toInt()and 63)or 0x80).toByte() } }
fun nextFragmentMessageId(current: Int): Int { var next = if (current >= 65_535) 1 else current + 1; if (next in 0xA800..0xA8FF) next = 0xA900; return next }

/** Deterministic CBOR measurement payload map. Integer keys are documented in wear/README.md. */
object MeasurementPayloadCbor {
    fun encode(v: MeasurementEntity): ByteArray = ByteArrayOutputStream().apply { write(0x81); writeMap(v) }.toByteArray()
    fun encodeLive(v: MeasurementEntity): ByteArray = ByteArrayOutputStream().apply { writeMap(v) }.toByteArray()
    private fun ByteArrayOutputStream.writeMap(v: MeasurementEntity) {
        write(0xaa)
        uint(0); bytes(uuidBytes(v.id)); uint(1); bytes(DeviceIdentity.uuidBytes); uint(2); uint(v.sequence); uint(3); text(v.type.name.lowercase())
        uint(4); write(0xfb); write(ByteBuffer.allocate(8).putDouble(v.value).array()); uint(5); text(v.unit)
        uint(6); uint(v.capturedAtEpochMs); uint(7); text(v.source); uint(8); text(v.quality.name.lowercase()); uint(9); strings(statusFlags(v.statusFlags))
    }
    private fun uuidBytes(value: String): ByteArray = ByteBuffer.allocate(16).putLong(UUID.fromString(value).mostSignificantBits).putLong(UUID.fromString(value).leastSignificantBits).array()
    private fun ByteArrayOutputStream.uint(value: Long) = major(0, value)
    private fun ByteArrayOutputStream.signed(value: Long) { if (value >= 0) uint(value) else major(1, -1 - value) }
    private fun ByteArrayOutputStream.text(value: String) { val bytes = value.toByteArray(); major(3, bytes.size.toLong()); write(bytes) }
    private fun ByteArrayOutputStream.bytes(value: ByteArray) { major(2, value.size.toLong()); write(value) }
    private fun ByteArrayOutputStream.strings(values: List<String>) { major(4, values.size.toLong()); values.forEach { text(it) } }
    private fun statusFlags(json: String): List<String> = runCatching {
        val values = JSONArray(json)
        List(values.length()) { values.getString(it) }.distinct().sorted()
    }.getOrDefault(emptyList())
    private fun ByteArrayOutputStream.major(type: Int, value: Long) { require(value >= 0); when { value < 24 -> write((type shl 5) or value.toInt()); value <= 255 -> { write((type shl 5) or 24); write(value.toInt()) }; value <= 65535 -> { write((type shl 5) or 25); write(byteArrayOf((value shr 8).toByte(), value.toByte())) }; value <= 0xffffffffL -> { write((type shl 5) or 26); write(ByteBuffer.allocate(4).putInt(value.toInt()).array()) }; else -> { write((type shl 5) or 27); write(ByteBuffer.allocate(8).putLong(value).array()) } } }
}

object SyncAckPayloadCbor {
    /** ACK map key 0 is the accepted measurement UUID text. */
    fun measurementId(bytes: ByteArray): String {
        var at = 0
        fun length(expected: Int): Int { val b = bytes[at++].toInt() and 255; require(b shr 5 == expected); return when (val n = b and 31) { in 0..23 -> n; 24 -> bytes[at++].toInt() and 255; 25 -> ((bytes[at++].toInt() and 255) shl 8) or (bytes[at++].toInt() and 255); else -> error("unsupported_ack_length") } }
        require(length(5) == 2 && length(0) == 0 && length(4) >= 1); val size = length(2); require(size == 16 && at + size <= bytes.size)
        val value = ByteBuffer.wrap(bytes, at, 16); return UUID(value.long, value.long).toString()
    }
}

object FragmentCodec {
    fun fragment(messageId: Int, frame: ByteArray, mtu: Int): List<ByteArray> {
        val payloadSize = mtu - 3 - WatchProtocol.FRAGMENT_HEADER_BYTES
        require(payloadSize > 0 && frame.size <= WatchProtocol.MAX_RAW_FRAME_BYTES)
        val count = maxOf(1, ceil(frame.size.toDouble() / payloadSize).toInt())
        require(count <= 4096)
        return (0 until count).map { index ->
            val start = index * payloadSize
            val end = minOf(frame.size, start + payloadSize)
            ByteBuffer.allocate(WatchProtocol.FRAGMENT_HEADER_BYTES + end - start).order(ByteOrder.BIG_ENDIAN)
                .putShort(messageId.toShort()).putShort(index.toShort()).putShort(count.toShort()).put(frame, start, end - start).array()
        }
    }
}

class FragmentAssembler(private val maxBytes: Int = WatchProtocol.MAX_FRAME_BYTES) {
    private var id = -1; private var expected = 0; private var next = 0
    private val output = ByteArrayOutputStream()
    fun add(fragment: ByteArray): ByteArray? {
        require(fragment.size >= WatchProtocol.FRAGMENT_HEADER_BYTES)
        val header = ByteBuffer.wrap(fragment).order(ByteOrder.BIG_ENDIAN)
        val incomingId = header.short.toInt() and 0xffff
        val index = header.short.toInt() and 0xffff
        val count = header.short.toInt() and 0xffff
        require(count in 1..4096)
        if (index == 0) { id = incomingId; expected = count; next = 0; output.reset() }
        require(incomingId == id && count == expected && index == next) { "malformed_fragment" }
        output.write(fragment, 6, fragment.size - 6); require(output.size() <= maxBytes); next++
        return if (next == expected) output.toByteArray().also { id = -1; expected = 0; next = 0; output.reset() } else null
    }
}
