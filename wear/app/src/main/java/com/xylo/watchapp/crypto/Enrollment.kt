package com.xylo.watchapp.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.xylo.watchapp.data.EnrollmentEntity
import com.xylo.watchapp.data.WatchDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class HandshakeResult(val response: ByteArray, val sas: String)

data class EnrollmentOffer(val watchNonce: ByteArray, val ephemeralPublicKey: ByteArray, val expiresAtEpochMs: Long)
data class PendingEnrollment(val phoneId: String, val phoneName: String, val keys: SessionKeys, val sas: String)

class EnrollmentCoordinator(
    private val store: EnrollmentStore,
    private val counters: CounterStore,
    private val protector: SessionFrameProtector,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _sas = MutableStateFlow<String?>(null)
    val sas: StateFlow<String?> = _sas
    private var ephemeral: KeyPair? = null
    private var nonce: ByteArray? = null
    private var expiresAt = 0L
    private var pending: PendingEnrollment? = null
    private val identity by lazy { WatchIdentityStore().getOrCreate() }
    fun begin(): EnrollmentOffer {
        ephemeral = ChannelCrypto.generateEphemeral(); nonce = ByteArray(16).also(SecureRandom()::nextBytes); expiresAt = clock() + 300_000
        return EnrollmentOffer(nonce!!.copyOf(), ChannelCrypto.encodeX963(ephemeral!!.public), expiresAt)
    }
    fun acceptHello(hello: ByteArray): HandshakeResult {
        require(hello.size == 151 && hello.copyOfRange(0, 5).contentEquals("WAEN1".toByteArray())) { "invalid_enrollment_hello" }
        if (ephemeral == null || clock() > expiresAt) begin()
        val phoneIdentityBytes = hello.copyOfRange(5, 70)
        val phoneEphemeralBytes = hello.copyOfRange(70, 135)
        val phoneNonce = hello.copyOfRange(135, 151)
        ChannelCrypto.decodeX963(phoneIdentityBytes)
        val local = requireNotNull(ephemeral); val watchNonce = requireNotNull(nonce)
        val watchIdentityBytes = ChannelCrypto.encodeX963(identity.public)
        val ordered = if (compareUnsigned(phoneIdentityBytes, watchIdentityBytes) < 0) phoneIdentityBytes + watchIdentityBytes else watchIdentityBytes + phoneIdentityBytes
        val keys = ChannelCrypto.derive(ChannelCrypto.agree(local.private, ChannelCrypto.decodeX963(phoneEphemeralBytes)), watchNonce, phoneNonce, ordered)
        val response = "WAER1".toByteArray() + watchIdentityBytes + ChannelCrypto.encodeX963(local.public) + watchNonce
        val phoneId = "wa_" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(phoneIdentityBytes)).take(43)
        val value = PendingEnrollment(phoneId, "iPhone", keys, ChannelCrypto.sas(keys.rekeySecret, MessageDigest.getInstance("SHA-256").digest(hello + response)))
        pending = value
        _sas.value = value.sas
        protector.stage(SecureChannel(keys.watchToPhone, keys.phoneToWatch, counters))
        return HandshakeResult(response, value.sas)
    }
    suspend fun confirmPending() {
        val value = requireNotNull(pending)
        store.enroll(value.phoneId, value.phoneName, 1)
        protector.promote()
        pending = null
        _sas.value = null
        ephemeral = null; nonce = null; expiresAt = 0
    }
    fun currentSas(): String? = pending?.sas
    fun cancel() { ephemeral = null; nonce = null; pending = null; _sas.value = null; protector.clearPending(); expiresAt = 0 }
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int { for (i in a.indices) { val d = (a[i].toInt() and 255) - (b[i].toInt() and 255); if (d != 0) return d }; return 0 }
}

class WatchIdentityStore(private val alias: String = "watchapp.watch.identity") {
    fun getOrCreate(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return KeyPair(store.getCertificate(alias).publicKey, store.getKey(alias, null) as java.security.PrivateKey)
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY).setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1")).build())
        }.generateKeyPair()
    }
}

interface EnrollmentStore {
    val enrollment: Flow<EnrollmentEntity?>
    suspend fun enroll(phoneId: String, phoneName: String, protocolVersion: Int): EnrollmentEntity
    suspend fun revoke()
}

class AndroidEnrollmentStore(private val dao: WatchDao) : EnrollmentStore {
    override val enrollment = dao.observeEnrollment()
    override suspend fun enroll(phoneId: String, phoneName: String, protocolVersion: Int): EnrollmentEntity {
        val alias = "watchapp.phone.$phoneId"
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY or KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        generator.generateKeyPair()
        return EnrollmentEntity(phoneId = phoneId, phoneName = phoneName.take(64), keyAlias = alias, enrolledAtEpochMs = System.currentTimeMillis(), protocolVersion = protocolVersion).also { dao.saveEnrollment(it) }
    }
    override suspend fun revoke() {
        val current = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        current.aliases().toList().filter { it.startsWith("watchapp.phone.") }.forEach(current::deleteEntry)
        dao.clearEnrollment()
    }
}
