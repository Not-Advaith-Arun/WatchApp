import CryptoKit
import XCTest
@testable import WatchApp

final class ProtocolSecurityTests: XCTestCase {
    func testDeterministicCBORIntegerBoundaries() {
        XCTAssertEqual(DeterministicCBOR.uint(0), Data([0x00]))
        XCTAssertEqual(DeterministicCBOR.uint(23), Data([0x17]))
        XCTAssertEqual(DeterministicCBOR.uint(24), Data([0x18, 0x18]))
        XCTAssertEqual(DeterministicCBOR.uint(256), Data([0x19, 0x01, 0x00]))
    }

    func testKeyAgreementIsSymmetricAndSASIsSixDigits() throws {
        let phone = P256.KeyAgreement.PrivateKey(), watch = P256.KeyAgreement.PrivateKey()
        let identity = Data("ordered-identities".utf8), wn = Data(repeating: 1, count: 16), pn = Data(repeating: 2, count: 16)
        let a = try ProtocolSecurity.deriveKeys(localEphemeral: phone, remoteEphemeral: watch.publicKey, watchNonce: wn, phoneNonce: pn, orderedIdentityKeys: identity)
        let b = try ProtocolSecurity.deriveKeys(localEphemeral: watch, remoteEphemeral: phone.publicKey, watchNonce: wn, phoneNonce: pn, orderedIdentityKeys: identity)
        XCTAssertEqual(a.phoneToWatch.withUnsafeBytes { Data($0) }, b.phoneToWatch.withUnsafeBytes { Data($0) })
        XCTAssertEqual(ProtocolSecurity.verificationCode(rekeySecret: a.rekeySecret, transcriptHash: Data(SHA256.hash(data: identity))).count, 6)
    }

    func testAuthenticatedEnvelopeAndReplayRejection() throws {
        let key = SymmetricKey(data: Data(repeating: 7, count: 32))
        let keys = ChannelKeys(phoneToWatch: key, watchToPhone: key, rekeySecret: key)
        let sender = SecureCBORChannel(keys: keys), receiver = SecureCBORChannel(keys: keys)
        let frame = try sender.seal(type: 3, requestID: 9, payload: Data("hello".utf8))
        let opened = try receiver.open(frame)
        XCTAssertEqual(opened.payload, Data("hello".utf8)); XCTAssertEqual(opened.requestID, 9)
        XCTAssertThrowsError(try receiver.open(frame))
        var tampered = frame; tampered[tampered.index(before: tampered.endIndex)] ^= 1
        XCTAssertThrowsError(try SecureCBORChannel(keys: keys).open(tampered))
    }
}
