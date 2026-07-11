import Foundation

enum MeasurementKind: String, Codable, CaseIterable, Identifiable, Sendable {
    case heartRate = "heart_rate"
    case ibi
    case steps
    case distance
    case activeEnergy = "active_energy"
    case workoutSummary = "workout_summary"
    case oxygenSaturation = "oxygen_saturation"
    case skinTemperature = "skin_temperature"
    case bodyComposition = "body_composition"
    case bodyFat = "body_fat"
    case leanBodyMass = "lean_body_mass"
    case hrv // Local derived SDNN; not a BLE v1 metric type.

    var id: String { rawValue }
    var title: String {
        switch self {
        case .heartRate: "Heart Rate"
        case .ibi: "Inter-beat Interval"
        case .steps: "Steps"
        case .distance: "Distance"
        case .activeEnergy: "Active Energy"
        case .workoutSummary: "Workout"
        case .oxygenSaturation: "Blood Oxygen"
        case .skinTemperature: "Skin Temperature"
        case .bodyComposition: "Body Composition"
        case .bodyFat: "Body Fat"
        case .leanBodyMass: "Lean Body Mass"
        case .hrv: "Heart Rate Variability"
        }
    }
    var defaultUnit: String {
        switch self {
        case .heartRate: "{beats}/min"
        case .ibi, .hrv: "ms"
        case .steps: "{count}"
        case .distance: "m"
        case .activeEnergy: "kcal"
        case .workoutSummary, .bodyComposition: "1"
        case .oxygenSaturation, .bodyFat: "%"
        case .skinTemperature: "Cel"
        case .leanBodyMass: "kg"
        }
    }
    var supportsHealthExport: Bool {
        switch self {
        case .heartRate, .steps, .distance, .activeEnergy, .oxygenSaturation,
             .bodyFat, .leanBodyMass, .hrv:
            true
        case .ibi, .workoutSummary, .skinTemperature, .bodyComposition:
            false
        }
    }
}

/// Exact JSON/domain representation of contracts/measurement.schema.json. Feature UI consumes
/// `Measurement` scalar projections; compound records remain intact here during transport/storage.
struct DurableMeasurementRecord: Codable, Sendable {
    let id: UUID
    let schemaVersion: Int
    let deviceId: String
    let sequence: String
    let metricType: String
    let observedAt: Date
    let timezoneOffsetMinutes: Int
    let durationMillis: Int?
    let value: Double?
    let components: [String: Double]?
    let unit: String
    let source: String
    let quality: MeasurementQuality
    let statusFlags: [String]
    let sessionId: UUID?
    let createdAt: Date

    func validate() throws {
        let wireKinds = Set(["heart_rate", "ibi", "steps", "distance", "active_energy", "workout_summary", "oxygen_saturation", "skin_temperature", "body_composition"])
        let units = Set(["{beats}/min", "ms", "{count}", "m", "kcal", "%", "Cel", "kg", "1"])
        guard schemaVersion == 1, wireKinds.contains(metricType), units.contains(unit), (-840...840).contains(timezoneOffsetMinutes),
              (value == nil) != (components == nil), UInt64(sequence) != nil else { throw AppServiceError.corruptData }
    }
}

enum MeasurementQuality: String, Codable, Sendable { case good, uncertain, poor, unknown }

struct Measurement: Identifiable, Codable, Hashable, Sendable {
    let id: UUID
    let deviceID: UUID
    let sequence: UInt64
    let kind: MeasurementKind
    let value: Double
    let unit: String
    let observedAt: Date
    let source: String
    let quality: MeasurementQuality
    let statusFlags: [String]

    init(id: UUID = UUID(), deviceID: UUID = .zero, sequence: UInt64 = 0,
         kind: MeasurementKind, value: Double, unit: String? = nil,
         observedAt: Date, source: String = "wear_health_services", quality: MeasurementQuality = .good,
         statusFlags: [String] = []) {
        self.id = id; self.deviceID = deviceID; self.sequence = sequence; self.kind = kind
        self.value = value; self.unit = unit ?? kind.defaultUnit; self.observedAt = observedAt
        self.source = source; self.quality = quality; self.statusFlags = statusFlags
    }
}

extension UUID { static let zero = UUID(uuidString: "00000000-0000-0000-0000-000000000000")! }

enum CapabilityState: String, Codable, Sendable {
    case available, permissionBlocked = "permission_blocked", approvalBlocked = "approval_blocked"
    case temporarilyUnavailable = "temporarily_unavailable", unsupported
}

struct WatchCapability: Identifiable, Codable, Hashable, Sendable {
    var id: MeasurementKind { kind }
    let kind: MeasurementKind
    let state: CapabilityState
    let canMeasureOnDemand: Bool
}

struct WatchStatus: Codable, Equatable, Sendable {
    var batteryPercent: Int?
    var pendingMeasurements: Int
    var pendingRawSessions: Int
    var lastSyncAt: Date?
    var capabilities: [WatchCapability]
    static let empty = WatchStatus(batteryPercent: nil, pendingMeasurements: 0, pendingRawSessions: 0, lastSyncAt: nil, capabilities: [])
}

enum RawSessionKind: String, Codable, CaseIterable, Sendable { case ecg, ppg, accelerometer }

struct RawSession: Identifiable, Codable, Hashable, Sendable {
    let id: UUID
    let deviceID: UUID
    let kind: RawSessionKind
    let startedAt: Date
    let endedAt: Date
    let sampleRateHz: Double
    let channels: [String]
    let samples: [[Double]]
    let sha256: String
}

struct TrendSummary: Sendable {
    let samples: [Measurement]
    var minimum: Double? { samples.map(\.value).min() }
    var maximum: Double? { samples.map(\.value).max() }
    var average: Double? { samples.isEmpty ? nil : samples.map(\.value).reduce(0, +) / Double(samples.count) }
}
