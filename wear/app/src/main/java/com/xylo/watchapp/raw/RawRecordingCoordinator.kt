package com.xylo.watchapp.raw

import com.xylo.watchapp.data.*
import com.xylo.watchapp.health.RawSensorProvider
import java.util.UUID
import java.util.zip.Deflater
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class RawRecordingCoordinator(private val dao: WatchDao, private val provider: RawSensorProvider) {
    companion object { const val MAX_DURATION_MS = 300_000L; const val MAX_SAMPLES = 300_000; const val CHUNK_BYTES = 8 * 1024 }
    suspend fun record(type: RawMetricType, durationMs: Long, sampleRateHz: Int): Result<String> {
        require(type in provider.supported) { "Sensor unavailable" }
        require(durationMs in 1_000..MAX_DURATION_MS) { "Recording exceeds bounded duration" }
        require(sampleRateHz in 1..1_000)
        val id = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        dao.upsertSession(RawSessionEntity(id, type, startedAt, sampleRateHz = sampleRateHz, state = "RECORDING"))
        var sampleCount = 0
        val sessionRows = ByteArrayOutputStream()
        val result = provider.samples(type, durationMs) { batch ->
            require(sampleCount + batch.size <= MAX_SAMPLES) { "Raw recording exceeded memory/storage cap" }
            batch.forEach { sessionRows.write(RawSampleCbor.row(it)) }; sampleCount += batch.size
        }
        val complete = RawSampleCbor.arrayHeader(sampleCount) + sessionRows.toByteArray()
        var sequence = 0
        if (result.isSuccess) RawSampleCbor.partitions(complete,CHUNK_BYTES).forEach { (raw,output) ->
            dao.insertChunk(RawChunkEntity(id,sequence++,System.currentTimeMillis(),0,0,output,raw.size,MessageDigest.getInstance("SHA-256").digest(raw)))
        }
        dao.upsertSession(RawSessionEntity(id, type, startedAt, System.currentTimeMillis(), sampleRateHz, sequence, sampleCount, MessageDigest.getInstance("SHA-256").digest(complete), if (result.isSuccess) "COMPLETE" else "FAILED"))
        return result.map { id }
    }
}

object RawSampleCbor {
    fun encode(samples: ShortArray): ByteArray = arrayHeader(samples.size) + ByteArrayOutputStream().apply { samples.forEach { write(row(it)) } }.toByteArray()
    fun row(value: Short): ByteArray = byteArrayOf(0x81.toByte(), 0xfb.toByte()) + ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value.toDouble()).array()
    fun arrayHeader(count: Int): ByteArray = when { count < 24 -> byteArrayOf((0x80 or count).toByte()); count <= 255 -> byteArrayOf(0x98.toByte(), count.toByte()); count <= 65535 -> byteArrayOf(0x99.toByte(), (count shr 8).toByte(), count.toByte()); else -> byteArrayOf(0x9a.toByte()) + ByteBuffer.allocate(4).putInt(count).array() }
    fun partitions(complete:ByteArray,size:Int)=complete.asIterable().chunked(size).map{part->val raw=part.toByteArray();val d=Deflater(Deflater.DEFAULT_COMPRESSION,true).apply{setInput(raw);finish()};val out=ByteArrayOutputStream();val b=ByteArray(1024);while(!d.finished()){val n=d.deflate(b);out.write(b,0,n)};d.end();raw to out.toByteArray()}
}
