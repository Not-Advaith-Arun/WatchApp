package com.xylo.watchapp.ble

import com.xylo.watchapp.data.*
import com.xylo.watchapp.health.ActiveMeasureProvider
import com.xylo.watchapp.health.WorkoutProvider
import com.xylo.watchapp.raw.RawRecordingCoordinator
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

data class RoutedMessage(val type: Int, val payload: ByteArray)
class MessageRouter(private val dao: WatchDao, private val repository: MeasurementRepository, private val active: ActiveMeasureProvider, private val raw: RawRecordingCoordinator, private val workout: WorkoutProvider) {
    suspend fun route(type: Int, payload: ByteArray): List<RoutedMessage> = when (type) {
        1 -> listOf(status())
        3 -> command(Cbor.readCommand(payload))
        11 -> syncRequest(payload)
        12 -> { Cbor.rawAck(payload)?.let { dao.markSessionSynced(it) }; emptyList() }
        else -> emptyList()
    }
    private suspend fun command(value: Pair<String, Map<Int, Any>>): List<RoutedMessage> { val (name,args)=value; when(name) {
        "start_measurement" -> active.measure(MetricType.valueOf((args[0] as String).uppercase()), 10_000).getOrThrow().forEach { repository.record(it) }
        "start_raw" -> raw.record(if ((args[0] as String)=="ppg") RawMetricType.PPG_GREEN else RawMetricType.valueOf((args[0] as String).uppercase()), (args[1] as Long)*1_000, 100)
        "start_workout" -> workout.start((args[0] as Long).toInt())
        "cancel_session" -> Unit
        "request_status" -> return listOf(status())
        else -> error("unsupported_message") }; return listOf(RoutedMessage(4, Cbor.result("ok"))) }
    private suspend fun syncRequest(payload: ByteArray): List<RoutedMessage> {
        Cbor.rawMissing(payload)?.let { (id,indexes) -> val chunks=dao.chunks(id).associateBy { it.sequence }; return indexes.mapNotNull { chunks[it] }.map { RoutedMessage(20,Cbor.rawChunk(it)) } }
        val high=Cbor.sequenceRequest(payload); val out=mutableListOf<RoutedMessage>()
        dao.measurementsAfter(high).chunked(256).forEach { batch -> out += RoutedMessage(20,Cbor.measurementBatch(batch)) }
        dao.completedSessions().forEach { out += RoutedMessage(10,Cbor.rawManifest(it)) }; return out
    }
    private suspend fun status()=RoutedMessage(30,Cbor.status(dao.pending(System.currentTimeMillis(),256).size,dao.completedSessions().size))
}

object Cbor {
    fun measurementBatch(v:List<MeasurementEntity>)=array(v.map { MeasurementPayloadCbor.encodeLive(it) })
    fun status(p:Int,r:Int)=map(listOf(1 to u(p.toLong()),2 to u(r.toLong())))
    fun result(s:String)=map(listOf(0 to text(s)))
    fun rawManifest(s:RawSessionEntity)=map(listOf(0 to text("raw_manifest"),1 to uuid(s.id),2 to bytes(DeviceIdentity.uuidBytes),3 to text(if(s.type==RawMetricType.PPG_GREEN)"ppg" else s.type.name.lowercase()),4 to u(s.startedAtEpochMs),5 to u(s.endedAtEpochMs?:s.startedAtEpochMs),6 to dbl(s.sampleRateHz.toDouble()),7 to array(listOf(text("value"))),8 to u(s.chunkCount.toLong()),9 to text(s.sessionSha256?.toHex().orEmpty())))
    fun rawChunk(c:RawChunkEntity)=map(listOf(0 to text("raw_chunk"),1 to uuid(c.sessionId),2 to u(c.sequence.toLong()),3 to text(c.uncompressedSha256.toHex()),4 to bytes(c.encodedSamples),5 to u(c.uncompressedLength.toLong())))
    fun readCommand(b:ByteArray):Pair<String,Map<Int,Any>> { val r=R(b); require(r.len(5)==2L&&r.len(0)==0L); val name=r.text(); require(r.len(0)==1L); val n=r.len(5).toInt(); val a=mutableMapOf<Int,Any>(); repeat(n){val k=r.len(0).toInt(); a[k]=if(r.peekMajor()==3)r.text() else r.len(0)}; return name to a }
    fun sequenceRequest(b:ByteArray):Long { val r=R(b); require(r.len(5)==1L&&r.len(0)==0L); return r.len(0) }
    fun rawMissing(b:ByteArray):Pair<String,List<Int>>?=runCatching { val r=R(b); require(r.len(5)==3L&&r.len(0)==0L&&r.text()=="raw_missing"&&r.len(0)==1L); val id=r.uuid(); require(r.len(0)==2L); val n=r.len(4).toInt(); id to List(n){r.len(0).toInt()} }.getOrNull()
    fun rawAck(b:ByteArray):String?=runCatching { val r=R(b); require(r.len(5)==3L&&r.len(0)==0L&&r.text()=="raw_ack"&&r.len(0)==1L); r.uuid() }.getOrNull()
    private fun map(v:List<Pair<Int,ByteArray>>)=byteArrayOf((0xa0 or v.size).toByte())+v.flatMap{(k,x)->(u(k.toLong())+x).toList()}.toByteArray(); private fun array(v:List<ByteArray>)=header(4,v.size)+v.flatMap{it.toList()}.toByteArray()
    private fun u(v:Long)=when{v<24->byteArrayOf(v.toByte());v<=255->byteArrayOf(0x18,v.toByte());v<=65535->byteArrayOf(0x19,(v shr 8).toByte(),v.toByte());v<=0xffffffffL->byteArrayOf(0x1a)+ByteBuffer.allocate(4).putInt(v.toInt()).array();else->byteArrayOf(0x1b)+ByteBuffer.allocate(8).putLong(v).array()}; private fun text(s:String)=header(3,s.toByteArray().size)+s.toByteArray(); private fun bytes(b:ByteArray)=header(2,b.size)+b; private fun dbl(v:Double)=byteArrayOf(0xfb.toByte())+ByteBuffer.allocate(8).putDouble(v).array(); private fun uuid(s:String)=bytes(ByteBuffer.allocate(16).putLong(UUID.fromString(s).mostSignificantBits).putLong(UUID.fromString(s).leastSignificantBits).array()); private fun uuidFromText(s:String)=bytes(java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).copyOf(16)); private fun header(m:Int,n:Int)=when{n<24->byteArrayOf(((m shl 5)or n).toByte());n<=255->byteArrayOf(((m shl 5)or 24).toByte(),n.toByte());else->byteArrayOf(((m shl 5)or 25).toByte(),(n shr 8).toByte(),n.toByte())}; private fun ByteArray.toHex()=joinToString(""){"%02x".format(it)}
    private class R(val b:ByteArray){var i=0;fun peekMajor()=(b[i].toInt()and 255)shr 5;fun len(m:Int):Long{val x=b[i++].toInt()and 255;require(x shr 5==m);return when(val a=x and 31){in 0..23->a.toLong();24->(b[i++].toInt()and 255).toLong();25->(((b[i++].toInt()and 255)shl 8)or(b[i++].toInt()and 255)).toLong();27->ByteBuffer.wrap(b,i,8).long.also{i+=8};else->error("bad")}};fun text():String{val n=len(3).toInt();return b.copyOfRange(i,i+n).toString(Charsets.UTF_8).also{i+=n}};fun uuid():String{require(len(2)==16L);val x=ByteBuffer.wrap(b,i,16);i+=16;return UUID(x.long,x.long).toString()}}
}
