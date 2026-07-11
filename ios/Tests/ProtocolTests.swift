import CryptoKit
import XCTest
@testable import WatchApp

final class ProtocolTests: XCTestCase {
    func testTransportFacingSecureFrameRoundTrip() throws {
        let key = SymmetricKey(data: Data(repeating: 4, count: 32))
        let keys = ChannelKeys(phoneToWatch: key, watchToPhone: key, rekeySecret: key)
        let outgoing = SecureCBORChannel(keys: keys)
        let incoming = SecureCBORChannel(keys: keys)
        let bytes = try outgoing.seal(type: UInt64(ProtocolMessage.MessageType.command.rawValue), requestID: 42, payload: Data([1, 2]))
        let frame = try incoming.open(bytes)
        XCTAssertEqual(frame.type, UInt64(ProtocolMessage.MessageType.command.rawValue))
        XCTAssertEqual(frame.requestID, 42)
        XCTAssertEqual(frame.payload, Data([1, 2]))
    }

    func testDurableContractRecordValidatesCanonicalUnit() throws {
        let value = DurableMeasurementRecord(id: UUID(), schemaVersion: 1, deviceId: "wa_0123456789012345678901", sequence: "1", metricType: "heart_rate", observedAt: .now, timezoneOffsetMinutes: 330, durationMillis: nil, value: 70, components: nil, unit: "{beats}/min", source: "wear_health_services", quality: .good, statusFlags: [], sessionId: nil, createdAt: .now)
        XCTAssertNoThrow(try value.validate())
    }

    func testMeasurementPayloadRoundTripIsCBOR() throws {
        let expected = Measurement(deviceID: UUID(), sequence: 9, kind: .heartRate, value: 71, observedAt: Date(timeIntervalSince1970: 10), statusFlags: ["off_body"])
        let bytes = ProtocolPayloadCodec.measurement(expected)
        XCTAssertNotEqual(bytes.first, UInt8(ascii: "{"))
        XCTAssertEqual(try ProtocolPayloadCodec.decodeMeasurement(bytes), expected)
    }

    func testCommandMapIsDeterministic() throws {
        let a = ProtocolPayloadCodec.command("start_measurement", arguments: [1:.unsigned(30),0:.text("heart_rate")])
        let b = ProtocolPayloadCodec.command("start_measurement", arguments: [0:.text("heart_rate"),1:.unsigned(30)])
        XCTAssertEqual(a, b)
    }

    func testStatusPayloadDecode() throws {
        let bytes = PayloadCBOR.encode(.map([0:.unsigned(82),1:.unsigned(4),2:.unsigned(1),3:.unsigned(1_000)]))
        let status = try ProtocolPayloadCodec.decodeStatus(bytes)
        XCTAssertEqual(status.batteryPercent, 82); XCTAssertEqual(status.pendingMeasurements, 4)
    }

    func testEnrollmentWireSizesAreFrozen() {
        XCTAssertEqual(Data("WAEN1".utf8).count + 65 + 65 + 16, 151)
        XCTAssertEqual(Data("WAER1".utf8).count + 65 + 65 + 16, 151)
    }

    @MainActor func testSharedRawDeflateFixtureInflatesWithBound() throws {
        let compressed = try XCTUnwrap(Data(base64Encoded:"O3vmjO3ZMz52s2bOtDt75owdAwOD/ayZkvYA"))
        let bytes = try XCTUnwrap(CoreBluetoothTransport.inflateRaw(compressed, expected:24))
        XCTAssertEqual(bytes.count,24)
        XCTAssertEqual(SHA256.hash(data:bytes).map{String(format:"%02x",$0)}.joined(),"778d038eac6f35c7551d9874b51b48ff6fddb3119992529a1fdcbb634d6fb96e")
        XCTAssertNil(CoreBluetoothTransport.inflateRaw(compressed, expected:23))
        XCTAssertNil(CoreBluetoothTransport.inflateRaw(compressed, expected:8*1024*1024+1))
    }
}
