package com.xylo.watchapp.ble

import com.xylo.watchapp.data.*
import org.junit.Assert.*
import org.junit.Test

class MessageRouterCodecTest {
    @Test fun decodesFrozenCommandAndSequenceRequest() {
        val command = byteArrayOf(0xa2.toByte(),0,0x6e)+"request_status".toByteArray()+byteArrayOf(1,0xa0.toByte())
        assertEquals("request_status", Cbor.readCommand(command).first)
        assertEquals(42, Cbor.sequenceRequest(byteArrayOf(0xa1.toByte(),0,0x18,42)))
    }
    @Test fun encodesRawManifestAndChunkDiscriminators() {
        val session=RawSessionEntity("00000000-0000-4000-8000-000000000001",RawMetricType.ECG,1,2,100,1,1,ByteArray(32),"COMPLETE")
        assertTrue(Cbor.rawManifest(session).toString(Charsets.ISO_8859_1).contains("raw_manifest"))
        val chunk=RawChunkEntity(session.id,0,1,0,1,byteArrayOf(1),2,ByteArray(32))
        assertTrue(Cbor.rawChunk(chunk).toString(Charsets.ISO_8859_1).contains("raw_chunk"))
    }
}
