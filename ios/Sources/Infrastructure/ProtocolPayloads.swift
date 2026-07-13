import Foundation

struct RawManifest: Sendable { let id:UUID; let deviceID:UUID; let kind:RawSessionKind; let startedAt:Date; let endedAt:Date; let sampleRate:Double; let channels:[String]; let chunkCount:Int; let sessionSha256:String }
struct RawChunk: Sendable { let sessionID:UUID; let index:Int; let sha256:String; let data:Data; let uncompressedLength:Int }

enum CBORValue: Equatable, Sendable { case unsigned(UInt64), negative(Int64), bytes(Data), text(String), double(Double), array([CBORValue]), map([UInt64: CBORValue]), bool(Bool), null }

enum PayloadCBOR {
    static func encode(_ value: CBORValue) -> Data {
        switch value {
        case .unsigned(let n): return DeterministicCBOR.uint(n)
        case .negative(let n): return encodeMajor(1, UInt64(-1 - n))
        case .bytes(let d): return encodeMajor(2, UInt64(d.count)) + d
        case .text(let s): return encodeMajor(3, UInt64(s.utf8.count)) + Data(s.utf8)
        case .array(let a): return encodeMajor(4, UInt64(a.count)) + a.reduce(into: Data()) { $0 += encode($1) }
        case .map(let m): return encodeMajor(5, UInt64(m.count)) + m.keys.sorted().reduce(into: Data()) { $0 += DeterministicCBOR.uint($1); $0 += encode(m[$1]!) }
        case .double(let d): var bits = d.bitPattern.bigEndian; return Data([0xfb]) + Data(bytes: &bits, count: 8)
        case .bool(let b): return Data([b ? 0xf5 : 0xf4])
        case .null: return Data([0xf6])
        }
    }
    static func decode(_ data: Data) throws -> CBORValue { var r = Reader(data: data); let value = try r.value(); guard r.index == data.count else { throw AppServiceError.corruptData }; return value }
    private static func encodeMajor(_ major: UInt8, _ n: UInt64) -> Data { let u = DeterministicCBOR.uint(n); var d = u; d[0] |= major << 5; return d }
    private struct Reader {
        let data: Data; var index = 0
        mutating func byte() throws -> UInt8 { guard index < data.count else { throw AppServiceError.corruptData }; defer { index += 1 }; return data[index] }
        mutating func length(_ ai: UInt8) throws -> UInt64 { if ai < 24 { return UInt64(ai) }; let c = ai == 24 ? 1 : ai == 25 ? 2 : ai == 26 ? 4 : ai == 27 ? 8 : 0; guard c > 0 else { throw AppServiceError.corruptData }; var n: UInt64=0; for _ in 0..<c { n=(n<<8)|UInt64(try byte()) }; return n }
        mutating func value() throws -> CBORValue {
            let b = try byte(), major = b >> 5, ai = b & 31
            if b == 0xf4 || b == 0xf5 { return .bool(b == 0xf5) }; if b == 0xf6 { return .null }
            if b == 0xfb { guard index+8 <= data.count else { throw AppServiceError.corruptData }; var n: UInt64=0; for _ in 0..<8 { n=(n<<8)|UInt64(try byte()) }; return .double(Double(bitPattern: n)) }
            let n = try length(ai)
            switch major {
            case 0: return .unsigned(n)
            case 1: return .negative(-1 - Int64(n))
            case 2: guard index+Int(n)<=data.count else { throw AppServiceError.corruptData }; defer { index += Int(n) }; return .bytes(Data(data[index..<index+Int(n)]))
            case 3: guard index+Int(n)<=data.count, let s=String(data:data[index..<index+Int(n)],encoding:.utf8) else { throw AppServiceError.corruptData }; index += Int(n); return .text(s)
            case 4: var a:[CBORValue]=[]; for _ in 0..<n { a.append(try value()) }; return .array(a)
            case 5: var m:[UInt64:CBORValue]=[:]; for _ in 0..<n { guard case .unsigned(let k)=try value(), m[k]==nil else { throw AppServiceError.corruptData }; m[k]=try value() }; return .map(m)
            default: throw AppServiceError.corruptData
            }
        }
    }
}

enum ProtocolPayloadCodec {
    // Measurement keys: 0 id, 1 deviceId, 2 sequence, 3 metric, 4 value, 5 unit,
    // 6 observed epoch-ms, 7 source, 8 quality, 9 status flags.
    static func measurement(_ item: Measurement) -> Data {
        PayloadCBOR.encode(.map([0:.bytes(uuidData(item.id)),1:.bytes(uuidData(item.deviceID)),2:.unsigned(item.sequence),3:.text(item.kind.rawValue),4:.double(item.value),5:.text(item.unit),6:.unsigned(UInt64(item.observedAt.timeIntervalSince1970*1000)),7:.text(item.source),8:.text(item.quality.rawValue),9:.array(item.statusFlags.map(CBORValue.text))]))
    }
    static func decodeMeasurement(_ data: Data) throws -> Measurement { try decodeMeasurementValue(PayloadCBOR.decode(data)) }
    static func decodeMeasurementBatch(_ data: Data) throws -> [Measurement] { guard case .array(let values)=try PayloadCBOR.decode(data) else { throw AppServiceError.corruptData }; return try values.map(decodeMeasurementValue) }
    private static func decodeMeasurementValue(_ value: CBORValue) throws -> Measurement {
        guard case .map(let m)=value, case .bytes(let id)=m[0], case .bytes(let device)=m[1], case .unsigned(let seq)=m[2], case .text(let metric)=m[3], case .double(let number)=m[4], case .text(let unit)=m[5], case .unsigned(let ms)=m[6], case .text(let source)=m[7], case .text(let q)=m[8], case .array(let flags)=m[9], id.count==16, device.count==16, let kind=MeasurementKind(rawValue:metric), let quality=MeasurementQuality(rawValue:q) else { throw AppServiceError.corruptData }
        return Measurement(id: uuid(id), deviceID: uuid(device), sequence: seq, kind: kind, value: number, unit: unit, observedAt: Date(timeIntervalSince1970:Double(ms)/1000), source: source, quality: quality, statusFlags: flags.compactMap { if case .text(let s)=$0{return s};return nil })
    }
    static func command(_ name: String, arguments: [UInt64:CBORValue] = [:]) -> Data { PayloadCBOR.encode(.map([0:.text(name),1:.map(arguments)])) }
    static func syncRequest(after sequence: UInt64) -> Data { PayloadCBOR.encode(.map([0:.unsigned(sequence)])) }
    static func syncAck(ids: [UUID], contiguous: UInt64) -> Data { PayloadCBOR.encode(.map([0:.array(ids.map { .bytes(uuidData($0)) }),1:.unsigned(contiguous)])) }
    static func decodeStatus(_ data: Data) throws -> WatchStatus {
        guard case .map(let m)=try PayloadCBOR.decode(data), case .unsigned(let pending)=m[1], case .unsigned(let raw)=m[2] else { throw AppServiceError.corruptData }
        let battery: Int? = { if case .unsigned(let n)=m[0] { return Int(n) }; return nil }()
        let last: Date? = { if case .unsigned(let n)=m[3] { return Date(timeIntervalSince1970:Double(n)/1000) }; return nil }()
        return WatchStatus(batteryPercent:battery,pendingMeasurements:Int(pending),pendingRawSessions:Int(raw),lastSyncAt:last,capabilities:[])
    }
    static func decodeRawManifest(_ data:Data)throws->RawManifest { guard case .map(let m)=try PayloadCBOR.decode(data),case .text("raw_manifest")=m[0],case .bytes(let id)=m[1],case .bytes(let device)=m[2],case .text(let k)=m[3],case .unsigned(let start)=m[4],case .unsigned(let end)=m[5],case .double(let rate)=m[6],case .array(let channelValues)=m[7],case .unsigned(let count)=m[8],case .text(let digest)=m[9],let kind=RawSessionKind(rawValue:k) else{throw AppServiceError.corruptData}; return RawManifest(id:uuid(id),deviceID:uuid(device),kind:kind,startedAt:Date(timeIntervalSince1970:Double(start)/1000),endedAt:Date(timeIntervalSince1970:Double(end)/1000),sampleRate:rate,channels:channelValues.compactMap{if case .text(let s)=$0{return s};return nil},chunkCount:Int(count),sessionSha256:digest) }
    static func decodeRawChunk(_ data:Data)throws->RawChunk { guard case .map(let m)=try PayloadCBOR.decode(data),case .text("raw_chunk")=m[0],case .bytes(let id)=m[1],case .unsigned(let index)=m[2],case .text(let digest)=m[3],case .bytes(let bytes)=m[4],case .unsigned(let length)=m[5],length <= 8*1024*1024 else{throw AppServiceError.corruptData}; return RawChunk(sessionID:uuid(id),index:Int(index),sha256:digest,data:bytes,uncompressedLength:Int(length)) }
    static func rawMissingRequest(id:UUID,indexes:[Int])->Data { PayloadCBOR.encode(.map([0:.text("raw_missing"),1:.bytes(uuidData(id)),2:.array(indexes.map{.unsigned(UInt64($0))})])) }
    static func rawAck(id:UUID,digest:String)->Data { PayloadCBOR.encode(.map([0:.text("raw_ack"),1:.bytes(uuidData(id)),2:.text(digest)])) }
    static func decodeRawSession(_ data:Data,manifest:RawManifest)throws->RawSession { guard case .array(let rows)=try PayloadCBOR.decode(data) else{throw AppServiceError.corruptData}; let samples=try rows.map{ row -> [Double] in guard case .array(let values)=row else{throw AppServiceError.corruptData}; return try values.map{if case .double(let d)=$0{return d};throw AppServiceError.corruptData} }; return RawSession(id:manifest.id,deviceID:manifest.deviceID,kind:manifest.kind,startedAt:manifest.startedAt,endedAt:manifest.endedAt,sampleRateHz:manifest.sampleRate,channels:manifest.channels,samples:samples,sha256:manifest.sessionSha256) }
    private static func uuidData(_ id: UUID) -> Data { var value=id.uuid; return withUnsafeBytes(of:&value){Data($0)} }
    private static func uuid(_ data: Data) -> UUID { let b=[UInt8](data); return UUID(uuid:(b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7],b[8],b[9],b[10],b[11],b[12],b[13],b[14],b[15])) }
}
