package com.xylo.watchapp.ble

import org.junit.Assert.*
import org.junit.Test

class AncsParserTest {
    @Test fun parsesLifecycleAndOnlyFlaggedActions() {
        val client = ExperimentalAncsClient(true); client.authorized()
        val request = client.onNotificationSource(byteArrayOf(0, 8, 6, 1, 0x78, 0x56, 0x34, 0x12))!!
        assertEquals(0, request[0].toInt()); val notice = client.inbox.snapshot().single(); assertTrue(notice.positiveAction); assertFalse(notice.negativeAction)
        assertThrows(IllegalArgumentException::class.java) { client.action(notice, false) }
        client.onNotificationSource(byteArrayOf(2, 0, 6, 1, 0x78, 0x56, 0x34, 0x12)); assertTrue(client.inbox.snapshot().isEmpty())
    }
    @Test fun rejectsOversizedAttributes() { assertThrows(IllegalArgumentException::class.java) { AncsParser.attributes(ByteArray(AncsParser.MAX_ATTRIBUTE_BYTES + 1)) } }
}
