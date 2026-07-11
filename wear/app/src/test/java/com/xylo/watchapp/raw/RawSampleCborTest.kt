package com.xylo.watchapp.raw

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class RawSampleCborTest {
    @Test fun encodesDefiniteArrayOfBinary64Rows() {
        val encoded = RawSampleCbor.encode(shortArrayOf(1, -2))
        assertEquals(0x82, encoded[0].toInt() and 255); assertEquals(0x81, encoded[1].toInt() and 255); assertEquals(0xfb, encoded[2].toInt() and 255)
        assertEquals(1.0, ByteBuffer.wrap(encoded, 3, 8).order(ByteOrder.BIG_ENDIAN).double, 0.0)
        assertEquals(-2.0, ByteBuffer.wrap(encoded, 13, 8).order(ByteOrder.BIG_ENDIAN).double, 0.0)
    }
    @Test fun multiChunkInflationReconstructsExactCompleteDocumentAndDigest() {
        val complete=RawSampleCbor.encode(ShortArray(2000){it.toShort()});val rebuilt=ByteArrayOutputStream()
        RawSampleCbor.partitions(complete,512).forEach{(_,compressed)->val i=Inflater(true).apply{setInput(compressed)};val b=ByteArray(1024);while(!i.finished()){val n=i.inflate(b);rebuilt.write(b,0,n)};i.end()}
        assertArrayEquals(complete,rebuilt.toByteArray());assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(complete),MessageDigest.getInstance("SHA-256").digest(rebuilt.toByteArray()))
    }
}
