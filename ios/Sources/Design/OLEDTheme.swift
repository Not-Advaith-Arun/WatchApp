import SwiftUI

enum OLED {
    static let canvas = Color.black
    static let surface = Color(red: 10/255, green: 10/255, blue: 10/255)
    static let raised = Color(red: 17/255, green: 17/255, blue: 19/255)
    static let separator = Color(red: 36/255, green: 36/255, blue: 40/255)
    static let primary = Color(red: 245/255, green: 245/255, blue: 247/255)
    static let secondary = Color(red: 161/255, green: 161/255, blue: 170/255)
    static let tertiary = Color(red: 107/255, green: 107/255, blue: 115/255)
    static let heart = Color(red: 1, green: 69/255, blue: 58/255)
    static let oxygen = Color(red: 100/255, green: 210/255, blue: 1)
    static let temperature = Color(red: 1, green: 159/255, blue: 10/255)
    static let activity = Color(red: 48/255, green: 209/255, blue: 88/255)
    static let warning = Color(red: 1, green: 214/255, blue: 10/255)
}

extension MeasurementKind {
    var tint: Color {
        switch self { case .heartRate, .ibi, .hrv: OLED.heart; case .oxygenSaturation: OLED.oxygen
        case .skinTemperature: OLED.temperature; case .steps, .distance, .activeEnergy, .workoutSummary: OLED.activity
        case .bodyComposition, .bodyFat, .leanBodyMass: OLED.secondary }
    }
}

struct OLEDCard<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View { content.frame(maxWidth: .infinity, alignment: .leading).padding(16).background(OLED.surface, in: .rect(cornerRadius: 20)) }
}

struct EmptyMetricCard: View {
    let kind: MeasurementKind
    let reason: String
    var body: some View {
        OLEDCard {
            Text(kind.title).font(.subheadline).foregroundStyle(OLED.secondary)
            Text("—").font(.system(size: 48, weight: .medium, design: .rounded)).foregroundStyle(OLED.primary)
            Text(reason).font(.caption).foregroundStyle(OLED.tertiary)
        }.accessibilityElement(children: .combine)
    }
}
