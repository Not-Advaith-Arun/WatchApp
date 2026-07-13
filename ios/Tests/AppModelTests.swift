import XCTest
@testable import WatchApp

@MainActor final class AppModelTests: XCTestCase {
    func testRefreshLoadsLatestAndHonestStates() async {
        let repository = InMemoryMeasurementRepository(values: [Measurement(kind: .heartRate, value: 68, observedAt: .now)])
        let raw = InMemoryRawSessionRepository()
        let transport = TestTransport()
        let model = AppModel(environment: AppEnvironment(measurements: repository, rawSessions: raw, bluetooth: transport, health: DisabledHealthKitExporter()))
        await model.refresh()
        XCTAssertEqual(model.latest[.heartRate]?.value, 68)
        XCTAssertEqual(model.connection, .offline)
        XCTAssertEqual(model.healthStatus, .notSupported)
    }

    func testIncomingMeasurementIsPersisted() async {
        let repository = InMemoryMeasurementRepository()
        let transport = TestTransport()
        let model = AppModel(environment: AppEnvironment(measurements: repository, rawSessions: InMemoryRawSessionRepository(), bluetooth: transport, health: DisabledHealthKitExporter()))
        try? await transport.onMeasurements?([Measurement(kind: .steps, value: 12, observedAt: .now)])
        try? await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(model.latest[.steps]?.value, 12)
    }
}

@MainActor final class TestTransport: BluetoothTransport {
    var state: ConnectionState = .offline; var enrollment: EnrollmentState = .idle; var status = WatchStatus.empty
    var onChange: (@MainActor @Sendable () -> Void)?; var onMeasurements: (@MainActor @Sendable ([WatchApp.Measurement]) async throws -> Void)?
    var onRawSession: (@MainActor @Sendable (RawSession) async throws -> Void)?
    func startScanning() throws { enrollment = .scanning; onChange?() }
    func confirmEnrollment() throws {}; func cancelEnrollment() {}; func synchronize() throws {}
    func startMeasurement(_ kind: MeasurementKind) throws {}; func startRawSession(_ kind: RawSessionKind, duration: TimeInterval) throws {}
    func disconnect() {}; func forget() throws {}
}

actor InMemoryRawSessionRepository: RawSessionRepository {
    var values: [RawSession] = []
    func allSessions() -> [RawSession] { values }; func session(id: UUID) -> RawSession? { values.first { $0.id == id } }
    func save(_ session: RawSession) { values.append(session) }; func delete(id: UUID) { values.removeAll { $0.id == id } }
    func exportFiles(for id: UUID) -> [URL] { [] }
}
