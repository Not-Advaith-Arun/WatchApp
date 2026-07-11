package com.xylo.watchapp.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.xylo.watchapp.data.ChannelStateEntity
import com.xylo.watchapp.data.WatchDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SessionKeys(val phoneToWatch: ByteArray, val watchToPhone: ByteArray, val rekeySecret: ByteArray)
data class SecureFrame(val major: Int, val minor: Int, val messageType: Int, val requestId: Long, val keyEpoch: Long, val counter: Long, val ciphertext: ByteArray, val tag: ByteArray)

interface CounterStore {
    suspend fun nextOutbound(epoch: Long): Long
    suspend fun acceptInbound(epoch: Long, counter: Long): Boolean
}

class RoomCounterStore(private val dao: WatchDao) : CounterStore {
    private val mutex = Mutex()
    override suspend fun nextOutbound(epoch: Long): Long = mutex.withLock {
        val state = dao.channelState(epoch) ?: ChannelStateEntity(epoch, 0, 0)
        val next = Math.addExact(state.outboundCounter, 1)
        dao.saveChannelState(state.copy(outboundCounter = next)); next
    }
    override suspend fun acceptInbound(epoch: Long, counter: Long): Boolean = mutex.withLock {
        val state = dao.channelState(epoch) ?: ChannelStateEntity(epoch, 0, 0)
        if (counter <= state.inboundHighWater) false else { dao.saveChannelState(state.copy(inboundHighWater = counter)); true }
    }
}

interface FrameProtector { suspend fun seal(messageType: Int, requestId: Long, payload: ByteArray): ByteArray?; suspend fun open(frame: ByteArray): Pair<SecureFrame, ByteArray>? }
class SessionFrameProtector : FrameProtector {
    @Volatile var channel: SecureChannel? = null
    @Volatile private var pending: SecureChannel? = null
    fun stage(value: SecureChannel) { pending = value }
    fun promote() { channel = requireNotNull(pending); pending = null }
    fun clearPending() { pending = null }
    override suspend fun seal(messageType: Int, requestId: Long, payload: ByteArray): ByteArray? = channel?.seal(messageType, requestId, payload)
    override suspend fun open(frame: ByteArray): Pair<SecureFrame, ByteArray>? = (channel ?: pending)?.open(frame)
}

object ChannelCrypto {
    fun generateEphemeral(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun decodeP256Public(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    fun encodeX963(publicKey: PublicKey): ByteArray {
        val ec = publicKey as ECPublicKey
        fun coordinate(value: java.math.BigInteger) = value.toByteArray().let { raw -> ByteArray(32).also { out -> raw.copyOfRange(maxOf(0, raw.size - 32), raw.size).copyInto(out, maxOf(0, 32 - raw.size)) } }
        return byteArrayOf(4) + coordinate(ec.w.affineX) + coordinate(ec.w.affineY)
    }
    fun decodeX963(encoded: ByteArray): PublicKey {
        require(encoded.size == 65 && encoded[0] == 4.toByte()) { "invalid_p256_point" }
        val parameters = (generateEphemeral().public as ECPublicKey).params
        val point = ECPoint(java.math.BigInteger(1, encoded.copyOfRange(1, 33)), java.math.BigInteger(1, encoded.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
    }
    fun agree(privateKey: PrivateKey, peer: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(peer, true); generateSecret() }
    fun derive(shared: ByteArray, watchNonce: ByteArray, phoneNonce: ByteArray, orderedIdentityKeys: ByteArray): SessionKeys {
        require(watchNonce.size == 16 && phoneNonce.size == 16)
        val salt = MessageDigest.getInstance("SHA-256").digest(watchNonce + phoneNonce)
        val bytes = hkdf(shared, salt, "WatchApp enrollment v1".toByteArray() + orderedIdentityKeys, 96)
        return SessionKeys(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64), bytes.copyOfRange(64, 96))
    }
    fun sas(rekeySecret: ByteArray, transcriptHash: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(rekeySecret, "HmacSHA256")) }.doFinal("SAS".toByteArray() + transcriptHash)
        val number = ByteBuffer.wrap(mac, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        return (number % 1_000_000).toString().padStart(6, '0')
    }
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)
        val output = ByteArrayOutputStream(); var previous = ByteArray(0); var index = 1
        while (output.size() < length) {
            previous = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }.doFinal(previous + info + index.toByte())
            output.write(previous); index++
        }
        return output.toByteArray().copyOf(length)
    }
}

class SecureChannel(private val outboundKey: ByteArray, private val inboundKey: ByteArray, private val counters: CounterStore, private val epoch: Long = 1, private val minor: Int = 0) {
    suspend fun seal(messageType: Int, requestId: Long, payload: ByteArray): ByteArray {
        val counter = counters.nextOutbound(epoch)
        val aad = DeterministicCbor.header(1, minor, messageType, requestId, epoch, counter)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, outboundKey, epoch, counter, aad, payload)
        return DeterministicCbor.frame(1, minor, messageType, requestId, epoch, counter, encrypted.copyOfRange(0, encrypted.size - 16), encrypted.copyOfRange(encrypted.size - 16, encrypted.size))
    }
    suspend fun open(encoded: ByteArray): Pair<SecureFrame, ByteArray> {
        val frame = DeterministicCbor.parseFrame(encoded)
        require(frame.major == 1 && frame.keyEpoch == epoch && frame.tag.size == 16) { "incompatible_or_malformed" }
        val aad = DeterministicCbor.header(frame.major, frame.minor, frame.messageType, frame.requestId, frame.keyEpoch, frame.counter)
        val plaintext = crypt(Cipher.DECRYPT_MODE, inboundKey, frame.keyEpoch, frame.counter, aad, frame.ciphertext + frame.tag)
        require(counters.acceptInbound(frame.keyEpoch, frame.counter)) { "replay_rejected" }
        return frame to plaintext
    }
    private fun crypt(mode: Int, key: ByteArray, epoch: Long, counter: Long, aad: ByteArray, input: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(epoch.toInt()).putLong(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(input) }
    }
}

object DeterministicCbor {
    fun header(major: Int, minor: Int, type: Int, request: Long, epoch: Long, counter: Long): ByteArray = map(listOf(0L to major.toLong(), 1L to minor.toLong(), 2L to type.toLong(), 3L to request, 4L to epoch, 5L to counter))
    fun frame(major: Int, minor: Int, type: Int, request: Long, epoch: Long, counter: Long, ciphertext: ByteArray, tag: ByteArray): ByteArray = map(listOf(0L to major.toLong(), 1L to minor.toLong(), 2L to type.toLong(), 3L to request, 4L to epoch, 5L to counter, 6L to ciphertext, 7L to tag))
    private fun map(values: List<Pair<Long, Any>>): ByteArray = ByteArrayOutputStream().apply {
        writeType(this, 5, values.size.toLong()); values.forEach { (key, value) -> writeType(this, 0, key); when (value) { is Long -> writeType(this, 0, value); is ByteArray -> { writeType(this, 2, value.size.toLong()); write(value) } } }
    }.toByteArray()
    fun parseFrame(bytes: ByteArray): SecureFrame {
        val reader = Reader(bytes); require(reader.length(5) == 8L)
        val values = HashMap<Long, Any>(); repeat(8) { val key = reader.length(0); values[key] = if (key < 6) reader.length(0) else reader.bytes() }
        require(reader.done())
        return SecureFrame((values[0] as Long).toInt(), (values[1] as Long).toInt(), (values[2] as Long).toInt(), values[3] as Long, values[4] as Long, values[5] as Long, values[6] as ByteArray, values[7] as ByteArray)
    }
    private fun writeType(out: ByteArrayOutputStream, major: Int, value: Long) { require(value >= 0); when { value < 24 -> out.write((major shl 5) or value.toInt()); value <= 0xff -> { out.write((major shl 5) or 24); out.write(value.toInt()) }; value <= 0xffff -> { out.write((major shl 5) or 25); out.write(byteArrayOf((value shr 8).toByte(), value.toByte())) }; value <= 0xffffffffL -> { out.write((major shl 5) or 26); out.write(ByteBuffer.allocate(4).putInt(value.toInt()).array()) }; else -> { out.write((major shl 5) or 27); out.write(ByteBuffer.allocate(8).putLong(value).array()) } } }
    private class Reader(private val data: ByteArray) { var at = 0; fun length(expectedMajor: Int): Long { val b = data[at++].toInt() and 255; require(b shr 5 == expectedMajor); return when (val info = b and 31) { in 0..23 -> info.toLong(); 24 -> (data[at++].toInt() and 255).toLong(); 25 -> ByteBuffer.wrap(data, at, 2).run { at += 2; short.toLong() and 0xffff }; 26 -> ByteBuffer.wrap(data, at, 4).run { at += 4; int.toLong() and 0xffffffffL }; 27 -> ByteBuffer.wrap(data, at, 8).run { at += 8; long }; else -> error("indefinite_or_invalid") } }; fun bytes(): ByteArray { val size = length(2).toInt(); require(size >= 0 && at + size <= data.size); return data.copyOfRange(at, at + size).also { at += size } }; fun done() = at == data.size }
}
