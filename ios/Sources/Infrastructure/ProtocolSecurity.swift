import CryptoKit
import Foundation

struct ChannelKeys: Sendable {
    let phoneToWatch: SymmetricKey
    let watchToPhone: SymmetricKey
    let rekeySecret: SymmetricKey
}

enum ProtocolSecurity {
    static func deriveKeys(localEphemeral: P256.KeyAgreement.PrivateKey,
                           remoteEphemeral: P256.KeyAgreement.PublicKey,
                           watchNonce: Data, phoneNonce: Data,
                           orderedIdentityKeys: Data) throws -> ChannelKeys {
        let shared = try localEphemeral.sharedSecretFromKeyAgreement(with: remoteEphemeral)
        let salt = SHA256.hash(data: watchNonce + phoneNonce)
        let info = Data("WatchApp enrollment v1".utf8) + orderedIdentityKeys
        let material = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(salt), sharedInfo: info, outputByteCount: 96)
        let bytes = material.withUnsafeBytes { Data($0) }
        return ChannelKeys(phoneToWatch: SymmetricKey(data: bytes[0..<32]), watchToPhone: SymmetricKey(data: bytes[32..<64]), rekeySecret: SymmetricKey(data: bytes[64..<96]))
    }

    static func verificationCode(rekeySecret: SymmetricKey, transcriptHash: Data) -> String {
        let digest = HMAC<SHA256>.authenticationCode(for: Data("SAS".utf8) + transcriptHash, using: rekeySecret)
        let number = digest.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } % 1_000_000
        return String(format: "%06u", number)
    }

    static func nonce(epoch: UInt32, counter: UInt64) throws -> AES.GCM.Nonce {
        var data = Data(); data.appendUInt32(epoch); data.appendUInt64(counter)
        return try AES.GCM.Nonce(data: data)
    }
}

struct SecureEnvelope: Sendable {
    let major: UInt64, minor: UInt64, type: UInt64, requestID: UInt64, epoch: UInt32, counter: UInt64
    let ciphertext: Data, tag: Data
}

final class SecureCBORChannel: @unchecked Sendable {
    private let sendKey: SymmetricKey, receiveKey: SymmetricKey
    private let lock = NSLock()
    private var sendCounter: UInt64
    private var receiveHighWater: UInt64
    private(set) var epoch: UInt32

    init(keys: ChannelKeys, phoneRole: Bool = true, epoch: UInt32 = 1, sendCounter: UInt64 = 0, receiveHighWater: UInt64 = 0) {
        sendKey = phoneRole ? keys.phoneToWatch : keys.watchToPhone
        receiveKey = phoneRole ? keys.watchToPhone : keys.phoneToWatch
        self.epoch = epoch; self.sendCounter = sendCounter; self.receiveHighWater = receiveHighWater
    }

    func seal(type: UInt64, requestID: UInt64, payload: Data, minor: UInt64 = 0) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        guard sendCounter < UInt64.max else { throw AppServiceError.unavailable("Secure channel requires re-keying.") }
        sendCounter += 1
        let aad = DeterministicCBOR.header(major: 1, minor: minor, type: type, requestID: requestID, epoch: UInt64(epoch), counter: sendCounter)
        let sealed = try AES.GCM.seal(payload, using: sendKey, nonce: ProtocolSecurity.nonce(epoch: epoch, counter: sendCounter), authenticating: aad)
        return DeterministicCBOR.envelope(header: aad, ciphertext: sealed.ciphertext, tag: sealed.tag)
    }

    func open(_ bytes: Data) throws -> (type: UInt64, requestID: UInt64, payload: Data) {
        lock.lock(); defer { lock.unlock() }
        let envelope = try DeterministicCBOR.decodeEnvelope(bytes)
        guard envelope.major == 1, envelope.epoch == epoch, envelope.counter > receiveHighWater else { throw AppServiceError.unavailable("Secure message was incompatible or replayed.") }
        let aad = DeterministicCBOR.header(major: envelope.major, minor: envelope.minor, type: envelope.type, requestID: envelope.requestID, epoch: UInt64(envelope.epoch), counter: envelope.counter)
        let box = try AES.GCM.SealedBox(nonce: ProtocolSecurity.nonce(epoch: epoch, counter: envelope.counter), ciphertext: envelope.ciphertext, tag: envelope.tag)
        let payload = try AES.GCM.open(box, using: receiveKey, authenticating: aad)
        receiveHighWater = envelope.counter
        return (envelope.type, envelope.requestID, payload)
    }
}

enum DeterministicCBOR {
    static func uint(_ value: UInt64) -> Data {
        if value < 24 { return Data([UInt8(value)]) }
        if value <= UInt64(UInt8.max) { return Data([0x18, UInt8(value)]) }
        if value <= UInt64(UInt16.max) { return Data([0x19, UInt8(value >> 8), UInt8(value)]) }
        if value <= UInt64(UInt32.max) { var d = Data([0x1a]); d.appendUInt32(UInt32(value)); return d }
        var d = Data([0x1b]); d.appendUInt64(value); return d
    }
    static func bytes(_ value: Data) -> Data { majorLength(2, value.count) + value }
    static func header(major: UInt64, minor: UInt64, type: UInt64, requestID: UInt64, epoch: UInt64, counter: UInt64) -> Data {
        var d = Data([0xa6]); for (key, value) in [(0,major),(1,minor),(2,type),(3,requestID),(4,epoch),(5,counter)] { d += uint(UInt64(key)); d += uint(value) }; return d
    }
    static func envelope(header: Data, ciphertext: Data, tag: Data) -> Data {
        var d = Data([0xa8]); d += header.dropFirst(); d += uint(6); d += bytes(ciphertext); d += uint(7); d += bytes(tag); return d
    }
    static func decodeEnvelope(_ data: Data) throws -> SecureEnvelope {
        var r = Reader(data); guard try r.byte() == 0xa8 else { throw AppServiceError.corruptData }
        var nums: [UInt64: UInt64] = [:]; var blobs: [UInt64: Data] = [:]
        for _ in 0..<8 { let key = try r.uint(); if key < 6 { nums[key] = try r.uint() } else { blobs[key] = try r.bytes() } }
        guard r.atEnd, nums[0] != nil, let epoch = nums[4].flatMap(UInt32.init(exactly:)), let cipher = blobs[6], let tag = blobs[7], tag.count == 16 else { throw AppServiceError.corruptData }
        guard let major = nums[0], let minor = nums[1], let type = nums[2], let request = nums[3], let counter = nums[5] else { throw AppServiceError.corruptData }
        return SecureEnvelope(major: major, minor: minor, type: type, requestID: request, epoch: epoch, counter: counter, ciphertext: cipher, tag: tag)
    }
    private static func majorLength(_ major: UInt8, _ count: Int) -> Data { let base = major << 5; let n = UInt64(count); if n < 24 { return Data([base | UInt8(n)]) }; if n <= 255 { return Data([base | 24, UInt8(n)]) }; return Data([base | 25, UInt8(n >> 8), UInt8(n)]) }
    private struct Reader {
        let data: Data; var index = 0; var atEnd: Bool { index == data.count }
        mutating func byte() throws -> UInt8 { guard index < data.count else { throw AppServiceError.corruptData }; defer { index += 1 }; return data[index] }
        mutating func uint() throws -> UInt64 { let b = try byte(); guard b >> 5 == 0 else { throw AppServiceError.corruptData }; return try length(b & 31) }
        mutating func bytes() throws -> Data { let b = try byte(); guard b >> 5 == 2 else { throw AppServiceError.corruptData }; let n = Int(try length(b & 31)); guard index+n <= data.count else { throw AppServiceError.corruptData }; defer { index += n }; return data[index..<index+n] }
        mutating func length(_ ai: UInt8) throws -> UInt64 { if ai < 24 { return UInt64(ai) }; let count = ai == 24 ? 1 : ai == 25 ? 2 : ai == 26 ? 4 : ai == 27 ? 8 : 0; guard count > 0 else { throw AppServiceError.corruptData }; var v: UInt64=0; for _ in 0..<count { v=(v<<8)|UInt64(try byte()) }; return v }
    }
}

private extension Data {
    mutating func appendUInt32(_ value: UInt32) { append(contentsOf: [UInt8(value >> 24), UInt8(value >> 16), UInt8(value >> 8), UInt8(value)]) }
    mutating func appendUInt64(_ value: UInt64) { appendUInt32(UInt32(value >> 32)); appendUInt32(UInt32(value)) }
}
