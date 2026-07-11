import Foundation
import Observation

@MainActor struct AppEnvironment {
    let measurements: any MeasurementRepository
    let rawSessions: any RawSessionRepository
    let bluetooth: any BluetoothTransport
    let health: any HealthKitExporter

    static func live() -> AppEnvironment {
        let store = ProtectedStore()
        return AppEnvironment(measurements: store, rawSessions: store, bluetooth: CoreBluetoothTransport(), health: HealthKitService())
    }
}

@MainActor @Observable final class AppModel {
    let environment: AppEnvironment
    var latest: [MeasurementKind: Measurement] = [:]
    var connection: ConnectionState = .offline
    var enrollment: EnrollmentState = .idle
    var watchStatus: WatchStatus = .empty
    var healthStatus: HealthExportStatus = .notRequested
    var rawSessions: [RawSession] = []
    var isLoading = false
    var presentedError: UserFacingError?
    var hasCompletedOnboarding: Bool { didSet { UserDefaults.standard.set(hasCompletedOnboarding, forKey: "onboarding-complete") } }
    var notificationLabEnabled: Bool { didSet { UserDefaults.standard.set(notificationLabEnabled, forKey: "notification-lab") } }

    init(environment: AppEnvironment) {
        self.environment = environment
        hasCompletedOnboarding = UserDefaults.standard.bool(forKey: "onboarding-complete")
        notificationLabEnabled = UserDefaults.standard.bool(forKey: "notification-lab")
        environment.bluetooth.onChange = { [weak self] in self?.readTransportState() }
        environment.bluetooth.onMeasurements = { [weak self] values in guard let self else { return }; try await self.environment.measurements.save(values); await self.refresh(); await self.exportPending() }
        environment.bluetooth.onRawSession = { [weak self] session in guard let self else { return }; try await self.environment.rawSessions.save(session); await self.refresh() }
    }

    func refresh() async {
        isLoading = true; defer { isLoading = false }
        do {
            latest = Dictionary(uniqueKeysWithValues: try await environment.measurements.latest().map { ($0.kind, $0) })
            rawSessions = try await environment.rawSessions.allSessions()
            healthStatus = await environment.health.status
            readTransportState()
        } catch { fail(error) }
    }
    func ingest(_ values: [Measurement]) async {
        do { try await environment.measurements.save(values); await refresh(); await exportPending() } catch { fail(error) }
    }
    func requestHealthAuthorization() async {
        do { try await environment.health.requestAuthorization(for: Set(MeasurementKind.allCases)); healthStatus = await environment.health.status; await exportPending() } catch { fail(error) }
    }
    func exportPending() async {
        do { let pending = try await environment.measurements.pendingHealthExport(limit: 250); let ids = try await environment.health.export(pending); try await environment.measurements.markHealthExported(ids: Array(ids)) } catch { fail(error) }
    }
    func deleteAll() async { do { try await environment.measurements.deleteAll(); await refresh() } catch { fail(error) } }
    func startMeasurement(_ kind: MeasurementKind) { do { try environment.bluetooth.startMeasurement(kind) } catch { fail(error) } }
    func startRaw(_ kind: RawSessionKind) { do { try environment.bluetooth.startRawSession(kind, duration: 30) } catch { fail(error) } }
    func scan() { do { try environment.bluetooth.startScanning() } catch { fail(error) } }
    func confirmEnrollment() { do { try environment.bluetooth.confirmEnrollment(); hasCompletedOnboarding = true } catch { fail(error) } }
    func sync() { do { try environment.bluetooth.synchronize() } catch { fail(error) } }
    func forgetWatch() { do { try environment.bluetooth.forget(); readTransportState() } catch { fail(error) } }
    func fail(_ error: Error) { presentedError = UserFacingError(message: error.localizedDescription) }
    private func readTransportState() { connection = environment.bluetooth.state; enrollment = environment.bluetooth.enrollment; watchStatus = environment.bluetooth.status }
}

struct UserFacingError: Identifiable { let id = UUID(); let message: String }
