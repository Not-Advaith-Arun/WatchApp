package com.xylo.watchapp.ble

import com.xylo.watchapp.data.MeasurementEntity
import com.xylo.watchapp.data.MetricType
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolCodecTest {
    @Test fun measurementPayloadIsDeterministicCborMap() {
        val source = MeasurementEntity(id = "00000000-0000-4000-8000-000000000001", type = MetricType.HEART_RATE, value = 72.0, unit = "{beats}/min", capturedAtEpochMs = 123, source = "wear_health_services")
        val a = MeasurementPayloadCbor.encode(source); val b = MeasurementPayloadCbor.encode(source)
        assertEquals(0x81, a[0].toInt() and 255); assertEquals(0xaa, a[1].toInt() and 255)
        assertArrayEquals(a, b)
    }
    @Test fun fragmentAllocatorSkipsDirectEnvelopePrefixRange() { assertEquals(0xA900, nextFragmentMessageId(0xA7FF)); assertEquals(1, nextFragmentMessageId(65_535)) }

    @Test fun fragmentsRespectMinimumMtuAndHeader() {
        val parts = FragmentCodec.fragment(9, ByteArray(40) { it.toByte() }, 23)
        assertEquals(3, parts.size)
        parts.forEachIndexed { index, bytes ->
            assertTrue(bytes.size <= 20)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            assertEquals(9, header.short.toInt()); assertEquals(index, header.short.toInt()); assertEquals(3, header.short.toInt())
        }
    }
}
