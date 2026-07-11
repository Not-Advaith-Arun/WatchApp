import CryptoKit
import Foundation

private struct StoreDocument: Codable {
    var measurements: [Measurement] = []
    var exportedIDs: Set<UUID> = []
    var rawSessions: [RawSession] = []
}

actor ProtectedStore: MeasurementRepository, RawSessionRepository {
    private let fileURL: URL
    private let exportDirectory: URL
    private var document: StoreDocument

    init(baseDirectory: URL? = nil) {
        let root = baseDirectory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        fileURL = root.appending(path: "WatchApp/store.json")
        exportDirectory = root.appending(path: "WatchApp/Exports")
        document = (try? Self.load(from: fileURL)) ?? StoreDocument()
    }

    private static func load(from url: URL) throws -> StoreDocument {
        guard FileManager.default.fileExists(atPath: url.path) else { return StoreDocument() }
        return try JSONDecoder.watchApp.decode(StoreDocument.self, from: Data(contentsOf: url))
    }

    private func persist() throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONEncoder.watchApp.encode(document)
        try data.write(to: fileURL, options: [.atomic, .completeFileProtection])
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: fileURL.path)
    }

    func latest() -> [Measurement] {
        Dictionary(grouping: document.measurements, by: \.kind).compactMap { $0.value.max(by: { $0.observedAt < $1.observedAt }) }
    }

    func history(for kind: MeasurementKind, from: Date, to: Date) -> [Measurement] {
        document.measurements.filter { $0.kind == kind && $0.observedAt >= from && $0.observedAt <= to }.sorted { $0.observedAt < $1.observedAt }
    }

    func save(_ values: [Measurement]) throws {
        var keyed = Dictionary(uniqueKeysWithValues: document.measurements.map { ("\($0.deviceID.uuidString):\($0.id.uuidString)", $0) })
        for value in values {
            let key = "\(value.deviceID.uuidString):\(value.id.uuidString)"
            if let old = keyed[key], old != value { throw AppServiceError.conflictingRecord }
            keyed[key] = value
        }
        document.measurements = keyed.values.sorted { ($0.observedAt, $0.sequence) < ($1.observedAt, $1.sequence) }
        try persist()
    }

    func delete(id: UUID, deviceID: UUID) throws {
        document.measurements.removeAll { $0.id == id && $0.deviceID == deviceID }
        document.exportedIDs.remove(id); try persist()
    }
    func deleteAll() throws { document.measurements = []; document.rawSessions = []; document.exportedIDs = []; try persist() }
    func pendingHealthExport(limit: Int) -> [Measurement] { Array(document.measurements.filter { !document.exportedIDs.contains($0.id) }.prefix(limit)) }
    func markHealthExported(ids: [UUID]) throws { document.exportedIDs.formUnion(ids); try persist() }
    func allSessions() -> [RawSession] { document.rawSessions.sorted { $0.startedAt > $1.startedAt } }
    func session(id: UUID) -> RawSession? { document.rawSessions.first { $0.id == id } }
    func save(_ session: RawSession) throws {
        if let old = document.rawSessions.first(where: { $0.id == session.id }), old != session { throw AppServiceError.conflictingRecord }
        if !document.rawSessions.contains(where: { $0.id == session.id }) { document.rawSessions.append(session); try persist() }
    }
    func delete(id: UUID) throws { document.rawSessions.removeAll { $0.id == id }; try persist() }

    func exportFiles(for id: UUID) throws -> [URL] {
        guard let session = document.rawSessions.first(where: { $0.id == id }) else { return [] }
        try FileManager.default.createDirectory(at: exportDirectory, withIntermediateDirectories: true)
        let json = exportDirectory.appending(path: "\(id.uuidString).json")
        let csv = exportDirectory.appending(path: "\(id.uuidString).csv")
        try JSONEncoder.watchApp.encode(session).write(to: json, options: [.atomic, .completeFileProtection])
        var text = "sample,\(session.channels.joined(separator: ","))\n"
        for (index, row) in session.samples.enumerated() { text += "\(index),\(row.map(String.init).joined(separator: ","))\n" }
        try Data(text.utf8).write(to: csv, options: [.atomic, .completeFileProtection])
        return [json, csv]
    }
}

extension JSONEncoder {
    static var watchApp: JSONEncoder { let value = JSONEncoder(); value.dateEncodingStrategy = .millisecondsSince1970; value.outputFormatting = [.sortedKeys]; return value }
}
extension JSONDecoder {
    static var watchApp: JSONDecoder { let value = JSONDecoder(); value.dateDecodingStrategy = .millisecondsSince1970; return value }
}

actor InMemoryMeasurementRepository: MeasurementRepository {
    private var values: [Measurement]; private var exported: Set<UUID> = []
    init(values: [Measurement] = []) { self.values = values }
    func latest() -> [Measurement] { Dictionary(grouping: values, by: \.kind).compactMap { $0.value.max { $0.observedAt < $1.observedAt } } }
    func history(for kind: MeasurementKind, from: Date, to: Date) -> [Measurement] { values.filter { $0.kind == kind && $0.observedAt >= from && $0.observedAt <= to }.sorted { $0.observedAt < $1.observedAt } }
    func save(_ incoming: [Measurement]) { for item in incoming where !values.contains(where: { $0.id == item.id && $0.deviceID == item.deviceID }) { values.append(item) } }
    func delete(id: UUID, deviceID: UUID) { values.removeAll { $0.id == id && $0.deviceID == deviceID } }
    func deleteAll() { values = []; exported = [] }
    func pendingHealthExport(limit: Int) -> [Measurement] { Array(values.filter { !exported.contains($0.id) }.prefix(limit)) }
    func markHealthExported(ids: [UUID]) { exported.formUnion(ids) }
}
