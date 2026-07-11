package com.xylo.watchapp.crypto

import org.junit.Assert.*
import org.junit.Test

class EnrollmentInteropTest {
    @Test fun x963PointIsExactlyCryptoKitCompatibleShapeAndRoundTrips() {
        val pair = ChannelCrypto.generateEphemeral(); val encoded = ChannelCrypto.encodeX963(pair.public)
        assertEquals(65, encoded.size); assertEquals(4, encoded[0].toInt()); assertArrayEquals(encoded, ChannelCrypto.encodeX963(ChannelCrypto.decodeX963(encoded)))
        assertArrayEquals(ChannelCrypto.agree(pair.private, ChannelCrypto.decodeX963(encoded)), ChannelCrypto.agree(pair.private, pair.public))
    }
    @Test fun exactEnrollmentHelloLayoutIs151Bytes() {
        val identity = ChannelCrypto.encodeX963(ChannelCrypto.generateEphemeral().public); val ephemeral = ChannelCrypto.encodeX963(ChannelCrypto.generateEphemeral().public)
        val hello = "WAEN1".toByteArray() + identity + ephemeral + ByteArray(16)
        assertEquals(151, hello.size); assertArrayEquals(identity, hello.copyOfRange(5, 70)); assertArrayEquals(ephemeral, hello.copyOfRange(70, 135))
    }
}
