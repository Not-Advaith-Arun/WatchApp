import Foundation

protocol MeasurementRepository: Sendable {
    func latest() async throws -> [Measurement]
    func history(for kind: MeasurementKind, from: Date, to: Date) async throws -> [Measurement]
    func save(_ measurements: [Measurement]) async throws
    func delete(id: UUID, deviceID: UUID) async throws
    func deleteAll() async throws
    func pendingHealthExport(limit: Int) async throws -> [Measurement]
    func markHealthExported(ids: [UUID]) async throws
}

protocol RawSessionRepository: Sendable {
    func allSessions() async throws -> [RawSession]
    func session(id: UUID) async throws -> RawSession?
    func save(_ session: RawSession) async throws
    func delete(id: UUID) async throws
    func exportFiles(for id: UUID) async throws -> [URL]
}

enum ConnectionState: String, Codable, Sendable { case live, nearby, syncing, offline, needsAttention }
enum EnrollmentState: Equatable, Sendable { case idle, scanning, code(String), enrolled(String), failed(String) }

@MainActor protocol BluetoothTransport: AnyObject {
    var state: ConnectionState { get }
    var enrollment: EnrollmentState { get }
    var status: WatchStatus { get }
    var onChange: (@MainActor @Sendable () -> Void)? { get set }
    var onMeasurements: (@MainActor @Sendable ([Measurement]) async throws -> Void)? { get set }
    var onRawSession: (@MainActor @Sendable (RawSession) async throws -> Void)? { get set }
    func startScanning() throws
    func confirmEnrollment() throws
    func cancelEnrollment()
    func synchronize() throws
    func startMeasurement(_ kind: MeasurementKind) throws
    func startRawSession(_ kind: RawSessionKind, duration: TimeInterval) throws
    func disconnect()
    func forget() throws
}

enum HealthExportStatus: String, Sendable { case enabled, notRequested, needsSettings, notSupported }

protocol HealthKitExporter: Sendable {
    var status: HealthExportStatus { get async }
    func requestAuthorization(for kinds: Set<MeasurementKind>) async throws
    func export(_ measurements: [Measurement]) async throws -> Set<UUID>
}

enum AppServiceError: LocalizedError, Sendable {
    case unavailable(String), corruptData, conflictingRecord, unsupported
    var errorDescription: String? {
        switch self {
        case .unavailable(let reason): reason
        case .corruptData: "Stored data could not be read safely."
        case .conflictingRecord: "The watch sent a conflicting copy of an existing record."
        case .unsupported: "This feature is not supported on this device."
        }
    }
}
