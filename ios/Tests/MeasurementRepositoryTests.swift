import XCTest
@testable import WatchApp

final class MeasurementRepositoryTests: XCTestCase {
    func testLatestReturnsNewestValuePerKind() async throws {
        let old = Measurement(kind: .heartRate, value: 60, observedAt: Date(timeIntervalSince1970: 1))
        let new = Measurement(kind: .heartRate, value: 72, observedAt: Date(timeIntervalSince1970: 2))
        let steps = Measurement(kind: .steps, value: 100, observedAt: Date(timeIntervalSince1970: 1))
        let repository = InMemoryMeasurementRepository(values: [old, new, steps])
        let latest = try await repository.latest()
        XCTAssertEqual(latest.first(where: { $0.kind == .heartRate })?.value, 72)
        XCTAssertEqual(latest.count, 2)
    }

    func testHistoryFiltersKindAndInclusiveRange() async throws {
        let repository = InMemoryMeasurementRepository(values: [
            Measurement(kind: .heartRate, value: 60, observedAt: Date(timeIntervalSince1970: 1)),
            Measurement(kind: .heartRate, value: 70, observedAt: Date(timeIntervalSince1970: 3)),
            Measurement(kind: .steps, value: 5, observedAt: Date(timeIntervalSince1970: 2))
        ])
        let history = try await repository.history(for: .heartRate, from: Date(timeIntervalSince1970: 1), to: Date(timeIntervalSince1970: 2))
        XCTAssertEqual(history.map(\.value), [60])
    }

    func testSaveIsIdempotentByDeviceAndID() async throws {
        let id = UUID(); let device = UUID()
        let value = Measurement(id: id, deviceID: device, kind: .steps, value: 50, observedAt: .now)
        let repository = InMemoryMeasurementRepository()
        try await repository.save([value, value])
        let latest = try await repository.latest()
        XCTAssertEqual(latest.count, 1)
    }
}
