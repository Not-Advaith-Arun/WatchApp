import Foundation
import HealthKit

actor HealthKitService: HealthKitExporter {
    private let store: HKHealthStore
    private var requested = false
    init(store: HKHealthStore = HKHealthStore()) { self.store = store }

    var status: HealthExportStatus {
        guard HKHealthStore.isHealthDataAvailable() else { return .notSupported }
        return requested ? .enabled : .notRequested
    }

    func requestAuthorization(for kinds: Set<MeasurementKind>) async throws {
        guard HKHealthStore.isHealthDataAvailable() else { throw AppServiceError.unsupported }
        let types = Set(kinds.compactMap(Self.quantityType))
        try await store.requestAuthorization(toShare: types, read: [])
        requested = true
    }

    func export(_ measurements: [Measurement]) async throws -> Set<UUID> {
        var result = Set<UUID>()
        for measurement in measurements where measurement.quality != .poor {
            guard let type = Self.quantityType(measurement.kind), let unit = Self.unit(measurement.kind) else { continue }
            let metadata: [String: Any] = [HKMetadataKeySyncIdentifier: "\(measurement.deviceID.uuidString):\(measurement.id.uuidString)", HKMetadataKeySyncVersion: 1, HKMetadataKeyWasUserEntered: false]
            let sample = HKQuantitySample(type: type, quantity: HKQuantity(unit: unit, doubleValue: Self.healthValue(measurement)), start: measurement.observedAt, end: measurement.observedAt, metadata: metadata)
            try await store.save(sample); result.insert(measurement.id)
        }
        return result
    }

    private static func quantityType(_ kind: MeasurementKind) -> HKQuantityType? {
        let identifier: HKQuantityTypeIdentifier = switch kind {
        case .heartRate: .heartRate
        // IBI is not equivalent to the SDNN statistic HealthKit expects.
        case .ibi: return nil
        case .steps: .stepCount
        case .distance: .distanceWalkingRunning
        case .activeEnergy: .activeEnergyBurned
        case .oxygenSaturation: .oxygenSaturation
        // Galaxy skin temperature is not automatically equivalent to Apple's
        // sleeping wrist-temperature semantic.
        case .skinTemperature: return nil
        case .bodyFat: .bodyFatPercentage
        case .leanBodyMass: .leanBodyMass
        case .hrv: .heartRateVariabilitySDNN
        case .workoutSummary, .bodyComposition: return nil
        }
        return HKObjectType.quantityType(forIdentifier: identifier)
    }
    private static func unit(_ kind: MeasurementKind) -> HKUnit? {
        switch kind {
        case .heartRate: HKUnit.count().unitDivided(by: .minute())
        case .ibi: return nil
        case .steps: .count()
        case .distance: .meter()
        case .activeEnergy: .kilocalorie()
        case .oxygenSaturation, .bodyFat: .percent()
        case .skinTemperature: return nil
        case .leanBodyMass: .gramUnit(with: .kilo)
        case .hrv: .secondUnit(with: .milli)
        case .workoutSummary, .bodyComposition: return nil
        }
    }
    private static func healthValue(_ item: Measurement) -> Double {
        switch item.kind { case .oxygenSaturation, .bodyFat: item.value / 100; default: item.value }
    }
}

actor DisabledHealthKitExporter: HealthKitExporter {
    var status: HealthExportStatus { .notSupported }
    func requestAuthorization(for kinds: Set<MeasurementKind>) throws { throw AppServiceError.unsupported }
    func export(_ measurements: [Measurement]) throws -> Set<UUID> { throw AppServiceError.unsupported }
}
