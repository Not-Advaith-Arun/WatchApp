@preconcurrency import CoreBluetooth
import CryptoKit
import Foundation
import Security
import zlib

enum BLEContract {
    static let service = CBUUID(string: "8f6d0000-7a5b-4c31-9e21-6d4f57415443")
    static let control = CBUUID(string: "8f6d0001-7a5b-4c31-9e21-6d4f57415443")
    static let manifest = CBUUID(string: "8f6d0002-7a5b-4c31-9e21-6d4f57415443")
    static let data = CBUUID(string: "8f6d0003-7a5b-4c31-9e21-6d4f57415443")
    static let live = CBUUID(string: "8f6d0004-7a5b-4c31-9e21-6d4f57415443")
    static let status = CBUUID(string: "8f6d0005-7a5b-4c31-9e21-6d4f57415443")
}

struct ProtocolMessage: Sendable {
    enum MessageType: UInt8, Sendable {
        case hello = 1, helloResult = 2, command = 3, commandResult = 4
        case syncOffer = 10, syncRequest = 11, syncAck = 12, dataFragment = 20
        case liveValue = 21, status = 30, rekeyRequest = 40, rekeyResult = 41, error = 255
    }
    let protocolMajor: UInt8
    let protocolMinor: UInt8
    let type: MessageType
    let requestID: UInt64
    let payload: Data
}

@MainActor final class CoreBluetoothTransport: NSObject, BluetoothTransport {
    private lazy var central = CBCentralManager(delegate: self, queue: nil, options: [CBCentralManagerOptionRestoreIdentifierKey: "com.xylo.WatchApp.central"])
    private var peripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private var subscriptionGate = SubscriptionGate()
    private var requestID: UInt64 = 0
    private var enrollmentCode: String?
    private var enrollmentHello: Data?
    private var pendingIdentity: P256.KeyAgreement.PrivateKey?
    private var pendingEphemeral: P256.KeyAgreement.PrivateKey?
    private var pendingChannel: SecureCBORChannel?
    private var secureChannel: SecureCBORChannel?
    private var rawTransfers: [UUID: RawTransfer] = [:]
    private var fragmentAssembler = FragmentAssembler()
    private(set) var state: ConnectionState = .offline { didSet { onChange?() } }
    private(set) var enrollment: EnrollmentState = .idle { didSet { onChange?() } }
    private(set) var status: WatchStatus = .empty { didSet { onChange?() } }
    var onChange: (@MainActor @Sendable () -> Void)?
    var onMeasurements: (@MainActor @Sendable ([Measurement]) async throws -> Void)?
    var onRawSession: (@MainActor @Sendable (RawSession) async throws -> Void)?

    override init() { super.init(); _ = central }

    func startScanning() throws {
        guard CBCentralManager.authorization == .allowedAlways else { throw AppServiceError.unavailable("Allow Bluetooth access in Settings to find your watch.") }
        guard central.state == .poweredOn else { throw AppServiceError.unavailable("Turn on Bluetooth to find your watch.") }
        enrollment = .scanning
        central.scanForPeripherals(withServices: [BLEContract.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }
    func confirmEnrollment() throws {
        guard case .code = enrollment, let pendingChannel else { throw AppServiceError.unavailable("No verified secure enrollment is waiting for confirmation.") }
        try Keychain.save(Data((peripheral?.identifier.uuidString ?? "").utf8), account: "enrolled-watch")
        if let identity = pendingIdentity { try Keychain.save(identity.rawRepresentation, account: "phone-identity-private") }
        secureChannel = pendingChannel
        self.pendingChannel = nil; pendingIdentity = nil; pendingEphemeral = nil; enrollmentHello = nil
        enrollment = .enrolled(peripheral?.name ?? "Galaxy Watch")
        try send(type: .hello, payload: DeterministicCBOR.uint(1), characteristic: BLEContract.control)
    }
    func cancelEnrollment() { enrollment = .idle; enrollmentCode = nil; pendingChannel = nil; pendingIdentity = nil; pendingEphemeral = nil; enrollmentHello = nil; disconnect() }
    func synchronize() throws { state = .syncing; try send(type: .syncRequest, payload: ProtocolPayloadCodec.syncRequest(after: 0), characteristic: BLEContract.manifest) }
    func startMeasurement(_ kind: MeasurementKind) throws { try send(type: .command, payload: ProtocolPayloadCodec.command("start_measurement", arguments: [0:.text(kind.rawValue)]), characteristic: BLEContract.control) }
    func startRawSession(_ kind: RawSessionKind, duration: TimeInterval) throws { try send(type: .command, payload: ProtocolPayloadCodec.command("start_raw", arguments: [0:.text(kind.rawValue),1:.unsigned(UInt64(duration))]), characteristic: BLEContract.control) }
    func disconnect() { if let peripheral { central.cancelPeripheralConnection(peripheral) }; secureChannel = nil; fragmentAssembler.reset(); subscriptionGate.reset(); state = .offline }
    func forget() throws { disconnect(); try Keychain.delete(account: "enrolled-watch"); try? Keychain.delete(account: "phone-identity-private"); peripheral = nil; enrollment = .idle }
    private func send(type: ProtocolMessage.MessageType, payload: Data, characteristic id: CBUUID) throws {
        guard let peripheral, let characteristic = characteristics[id] else { throw AppServiceError.unavailable("Connect to the watch before using this action.") }
        requestID &+= 1
        guard let secureChannel else { throw AppServiceError.unavailable("Complete secure enrollment before sending watch commands.") }
        let value = try secureChannel.seal(type: UInt64(type.rawValue), requestID: requestID, payload: payload)
        let writeType: CBCharacteristicWriteType = id == BLEContract.data && characteristic.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        peripheral.writeValue(value, for: characteristic, type: writeType)
    }
    private func accept(_ data: Data) {
        if secureChannel == nil, case .scanning = enrollment { acceptEnrollmentResult(data); return }
        // Deterministic secure envelopes begin with an eight-entry CBOR map (0xA8).
        // Wear fragments only frames exceeding ATT payload capacity and prefixes those with 6 bytes.
        if data.first != 0xa8 {
            do { guard let frame = try fragmentAssembler.add(data) else { return }; accept(frame) }
            catch { state = .needsAttention; fragmentAssembler.reset() }
            return
        }
        guard let channel = secureChannel, let opened = try? channel.open(data),
              let rawType = UInt8(exactly: opened.type), let type = ProtocolMessage.MessageType(rawValue: rawType) else { state = .needsAttention; disconnect(); return }
        switch type {
        case .liveValue:
            if let value = try? ProtocolPayloadCodec.decodeMeasurement(opened.payload) { Task { try? await onMeasurements?([value]) } }
        case .status:
            if let value = try? ProtocolPayloadCodec.decodeStatus(opened.payload) { status = value; state = .live }
        case .dataFragment:
            if let values = try? ProtocolPayloadCodec.decodeMeasurementBatch(opened.payload) { Task { do { try await onMeasurements?(values); let high = values.map(\.sequence).max() ?? 0; try send(type: .syncAck, payload: ProtocolPayloadCodec.syncAck(ids: values.map(\.id), contiguous: high), characteristic: BLEContract.manifest); state = .live } catch { state = .needsAttention } } }
            else if let chunk = try? ProtocolPayloadCodec.decodeRawChunk(opened.payload) { acceptRawChunk(chunk) }
            else { state = .needsAttention }
        case .syncOffer:
            if let manifest = try? ProtocolPayloadCodec.decodeRawManifest(opened.payload) { rawTransfers[manifest.id] = RawTransfer(manifest:manifest,chunks:[:]); try? send(type:.syncRequest,payload:ProtocolPayloadCodec.rawMissingRequest(id:manifest.id,indexes:Array(0..<manifest.chunkCount)),characteristic:BLEContract.manifest) }
        default: break
        }
    }

    private func beginEnrollment() throws {
        guard let peripheral, let control = characteristics[BLEContract.control] else { throw AppServiceError.unavailable("Watch control service is unavailable.") }
        let identity = (try? Keychain.read(account: "phone-identity-private")).flatMap { try? P256.KeyAgreement.PrivateKey(rawRepresentation: $0) } ?? P256.KeyAgreement.PrivateKey()
        let ephemeral = P256.KeyAgreement.PrivateKey()
        var nonce = Data(count: 16); let result = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        guard result == errSecSuccess else { throw AppServiceError.unavailable("Secure random generation failed.") }
        var hello = Data("WAEN1".utf8); hello += identity.publicKey.x963Representation; hello += ephemeral.publicKey.x963Representation; hello += nonce
        pendingIdentity = identity; pendingEphemeral = ephemeral; enrollmentHello = hello; enrollment = .scanning
        peripheral.writeValue(hello, for: control, type: .withResponse)
    }

    private func acceptEnrollmentResult(_ result: Data) {
        let expected = 5 + 65 + 65 + 16
        guard result.count == expected, result.prefix(5) == Data("WAER1".utf8), let identity = pendingIdentity, let ephemeral = pendingEphemeral, let hello = enrollmentHello else { enrollment = .failed("The watch returned an invalid enrollment handshake."); disconnect(); return }
        do {
            let watchIdentityData = Data(result[5..<70]), watchEphemeralData = Data(result[70..<135]), watchNonce = Data(result[135..<151])
            let watchEphemeral = try P256.KeyAgreement.PublicKey(x963Representation: watchEphemeralData)
            let phoneNonce = Data(hello.suffix(16))
            let phoneIdentityData = identity.publicKey.x963Representation
            let ordered = phoneIdentityData.lexicographicallyPrecedes(watchIdentityData) ? phoneIdentityData + watchIdentityData : watchIdentityData + phoneIdentityData
            let keys = try ProtocolSecurity.deriveKeys(localEphemeral: ephemeral, remoteEphemeral: watchEphemeral, watchNonce: watchNonce, phoneNonce: phoneNonce, orderedIdentityKeys: ordered)
            let transcript = Data(SHA256.hash(data: hello + result))
            pendingChannel = SecureCBORChannel(keys: keys)
            let code = ProtocolSecurity.verificationCode(rekeySecret: keys.rekeySecret, transcriptHash: transcript)
            enrollmentCode = code; enrollment = .code(code)
        } catch { enrollment = .failed("Secure enrollment validation failed."); disconnect() }
    }
    private func acceptRawChunk(_ chunk: RawChunk) {
        guard var transfer=rawTransfers[chunk.sessionID], chunk.index < transfer.manifest.chunkCount else { state = .needsAttention; return }
        guard let uncompressed = Self.inflateRaw(chunk.data, expected: chunk.uncompressedLength), SHA256.hash(data:uncompressed).compactMap({String(format:"%02x",$0)}).joined()==chunk.sha256 else { try? send(type:.syncRequest,payload:ProtocolPayloadCodec.rawMissingRequest(id:chunk.sessionID,indexes:[chunk.index]),characteristic:BLEContract.manifest); return }
        transfer.chunks[chunk.index]=uncompressed; rawTransfers[chunk.sessionID]=transfer
        guard transfer.chunks.count==transfer.manifest.chunkCount else { return }
        let combined=(0..<transfer.manifest.chunkCount).reduce(into:Data()){$0 += transfer.chunks[$1]!}
        guard SHA256.hash(data:combined).compactMap({String(format:"%02x",$0)}).joined()==transfer.manifest.sessionSha256, let session=try? ProtocolPayloadCodec.decodeRawSession(combined,manifest:transfer.manifest) else { state = .needsAttention; return }
        Task { do { try await onRawSession?(session); try send(type:.syncAck,payload:ProtocolPayloadCodec.rawAck(id:session.id,digest:transfer.manifest.sessionSha256),characteristic:BLEContract.manifest); rawTransfers.removeValue(forKey:session.id); state = .live } catch { state = .needsAttention } }
    }
    static func inflateRaw(_ compressed:Data,expected:Int)->Data? {
        guard expected > 0 && expected <= 8*1024*1024 && !compressed.isEmpty else{return nil}
        var output=Data(count:expected), stream=z_stream()
        let status:Int32 = output.withUnsafeMutableBytes { dst in compressed.withUnsafeBytes { src in
            stream.next_in = UnsafeMutablePointer(mutating: src.bindMemory(to:Bytef.self).baseAddress!); stream.avail_in = uInt(compressed.count)
            stream.next_out = dst.bindMemory(to:Bytef.self).baseAddress!; stream.avail_out = uInt(expected)
            guard inflateInit2_(&stream,-MAX_WBITS,ZLIB_VERSION,Int32(MemoryLayout<z_stream>.size)) == Z_OK else{return Z_VERSION_ERROR}
            defer{inflateEnd(&stream)}
            return zlib.inflate(&stream,Z_FINISH)
        }}
        guard status==Z_STREAM_END && stream.total_out==expected && stream.avail_in==0 else{return nil}
        return output
    }
}

private struct RawTransfer { let manifest: RawManifest; var chunks:[Int:Data] }

extension CoreBluetoothTransport: @preconcurrency CBCentralManagerDelegate, @preconcurrency CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) { if central.state != .poweredOn { state = .offline } }
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first { peripheral = restored; restored.delegate = self; central.connect(restored) }
    }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        central.stopScan(); self.peripheral = peripheral; peripheral.delegate = self; state = .nearby; central.connect(peripheral)
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) { subscriptionGate.reset(); state = .nearby; peripheral.discoverServices([BLEContract.service]) }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) { state = .needsAttention; enrollment = .failed(error?.localizedDescription ?? "Connection failed") }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) { characteristics = [:]; secureChannel=nil; pendingChannel=nil; fragmentAssembler.reset(); subscriptionGate.reset(); state = error == nil ? .offline : .needsAttention }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) { if let error { enrollment = .failed("Service discovery failed: \(error.localizedDescription)"); state = .needsAttention; disconnect(); return }; peripheral.services?.forEach { peripheral.discoverCharacteristics(nil, for: $0) } }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { enrollment = .failed("Characteristic discovery failed: \(error.localizedDescription)"); state = .needsAttention; disconnect(); return }
        for item in service.characteristics ?? [] { characteristics[item.uuid] = item; if item.properties.contains(.notify) || item.properties.contains(.indicate) { peripheral.setNotifyValue(true, for: item) } }
        if service.uuid == BLEContract.service && !SubscriptionGate.required.allSatisfy({ characteristics.keys.map(\.uuidString).contains($0) }) { enrollment = .failed("The watch is missing required protocol characteristics."); state = .needsAttention; disconnect() }
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error { enrollment = .failed("Bluetooth subscription failed: \(error.localizedDescription)"); state = .needsAttention; disconnect(); return }
        guard characteristic.isNotifying else { enrollment = .failed("The watch declined a required Bluetooth subscription."); state = .needsAttention; disconnect(); return }
        subscriptionGate.confirm(characteristic.uuid.uuidString)
        if subscriptionGate.consumeReady() {
            do { try beginEnrollment() }
            catch { enrollment = .failed(error.localizedDescription); state = .needsAttention; disconnect() }
        }
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) { if let data = characteristic.value { accept(data) } }
}

struct SubscriptionGate: Sendable {
    static let required = Set([BLEContract.control.uuidString, BLEContract.data.uuidString, BLEContract.live.uuidString])
    private(set) var confirmed = Set<String>()
    private(set) var started = false
    mutating func confirm(_ id:String) { if Self.required.contains(id) { confirmed.insert(id) } }
    mutating func consumeReady() -> Bool { guard !started, confirmed.isSuperset(of:Self.required) else{return false}; started=true; return true }
    mutating func reset() { confirmed=[]; started=false }
}

private enum Keychain {
    static func exists(account: String) -> Bool { (try? read(account: account)) != nil }
    static func save(_ data: Data, account: String) throws {
        try? delete(account: account)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.xylo.WatchApp", kSecAttrAccount as String: account, kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else { throw AppServiceError.unavailable("Secure enrollment could not be saved.") }
    }
    static func read(account: String) throws -> Data {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.xylo.WatchApp", kSecAttrAccount as String: account, kSecReturnData as String: true]
        var result: CFTypeRef?; guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { throw AppServiceError.unavailable("No enrolled watch was found.") }; return data
    }
    static func delete(account: String) throws { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.xylo.WatchApp", kSecAttrAccount as String: account] as CFDictionary) }
}
