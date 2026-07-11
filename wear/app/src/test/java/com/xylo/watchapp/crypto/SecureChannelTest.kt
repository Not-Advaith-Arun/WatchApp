package com.xylo.watchapp.crypto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class SecureChannelTest {
    @Test fun ecdhHkdfMatchesBothPeersAndSasIsSixDigits() {
        val watch = ChannelCrypto.generateEphemeral(); val phone = ChannelCrypto.generateEphemeral()
        val a = ChannelCrypto.agree(watch.private, phone.public); val b = ChannelCrypto.agree(phone.private, watch.public)
        assertArrayEquals(a, b)
        val keysA = ChannelCrypto.derive(a, ByteArray(16) { 1 }, ByteArray(16) { 2 }, watch.public.encoded + phone.public.encoded)
        val keysB = ChannelCrypto.derive(b, ByteArray(16) { 1 }, ByteArray(16) { 2 }, watch.public.encoded + phone.public.encoded)
        assertArrayEquals(keysA.watchToPhone, keysB.watchToPhone)
        assertTrue(ChannelCrypto.sas(keysA.rekeySecret, MessageDigest.getInstance("SHA-256").digest("t".toByteArray())).matches(Regex("[0-9]{6}")))
    }

    @Test fun encryptedFrameRoundTripsAndReplayFails() = runTest {
        val keys = SessionKeys(ByteArray(32) { 3 }, ByteArray(32) { 4 }, ByteArray(32))
        val sender = SecureChannel(keys.watchToPhone, keys.phoneToWatch, MemoryCounters())
        val receiver = SecureChannel(keys.phoneToWatch, keys.watchToPhone, MemoryCounters())
        val encoded = sender.seal(20, 7, "secret".toByteArray())
        val opened = receiver.open(encoded)
        assertEquals(20, opened.first.messageType); assertEquals("secret", opened.second.toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { receiver.open(encoded) } }
    }

    @Test fun deterministicCborUsesCanonicalIntegerKeyOrder() {
        val header = DeterministicCbor.header(1, 0, 20, 0, 1, 1)
        assertArrayEquals(byteArrayOf(0xa6.toByte(), 0, 1, 1, 0, 2, 20, 3, 0, 4, 1, 5, 1), header)
    }

    @Test fun tamperedCiphertextFailsAuthentication() = runTest {
        val sender = SecureChannel(ByteArray(32) { 7 }, ByteArray(32) { 8 }, MemoryCounters())
        val receiver = SecureChannel(ByteArray(32) { 8 }, ByteArray(32) { 7 }, MemoryCounters())
        val encoded = sender.seal(20, 0, byteArrayOf(1, 2, 3)); encoded[encoded.lastIndex - 17] = (encoded[encoded.lastIndex - 17].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { receiver.open(encoded) } }
    }

    private class MemoryCounters : CounterStore {
        var out = 0L; var inbound = 0L
        override suspend fun nextOutbound(epoch: Long) = ++out
        override suspend fun acceptInbound(epoch: Long, counter: Long) = if (counter > inbound) { inbound = counter; true } else false
    }
}
