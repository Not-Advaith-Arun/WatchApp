import SwiftUI

struct TodayView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        ScrollView { LazyVStack(spacing: 12) {
            HStack { Circle().fill(model.connection == .live ? OLED.activity : OLED.tertiary).frame(width: 8); Text(model.connection.label); Spacer(); if model.isLoading { ProgressView() } }
                .font(.caption.weight(.medium)).foregroundStyle(OLED.secondary).padding(12).background(OLED.raised, in: .capsule)
            metric(.heartRate, hero: true)
            ForEach([MeasurementKind.steps, .oxygenSaturation, .skinTemperature, .hrv], id: \.self) { metric($0, hero: false) }
            NavigationLink { MeasureView() } label: { Label("Take a measurement", systemImage: "waveform.path.ecg").frame(maxWidth: .infinity).padding() }.buttonStyle(.borderedProminent).tint(OLED.primary).foregroundStyle(.black)
        }.padding(16) }.refreshable { await model.refresh() }.navigationTitle("Today").modifier(ScreenBackground())
    }
    @ViewBuilder private func metric(_ kind: MeasurementKind, hero: Bool) -> some View {
        if let value = model.latest[kind] {
            NavigationLink { MetricDetailView(kind: kind) } label: { MetricCard(value: value, hero: hero) }.buttonStyle(.plain)
        } else { EmptyMetricCard(kind: kind, reason: model.connection == .offline ? "Watch offline" : "Not measured") }
    }
}

struct MetricCard: View {
    let value: Measurement; let hero: Bool
    var body: some View { OLEDCard {
        Text(value.kind.title).font(.subheadline).foregroundStyle(OLED.secondary)
        HStack(alignment: .firstTextBaseline, spacing: 6) { Text(value.value.formatted(.number.precision(.fractionLength(value.kind == .steps || value.kind == .heartRate ? 0 : 1)))).font(.system(size: hero ? 56 : 38, weight: .medium, design: .rounded)).monospacedDigit(); Text(value.kind.displayUnit).font(.caption.bold()).foregroundStyle(OLED.secondary) }.foregroundStyle(value.kind.tint)
        Text("\(value.observedAt.formatted(.relative(presentation: .named))) · \(value.source)").font(.caption).foregroundStyle(OLED.tertiary)
    }.accessibilityElement(children: .ignore).accessibilityLabel("\(value.kind.title), \(value.value.formatted()), \(value.kind.displayUnit), observed \(value.observedAt.formatted(.relative(presentation: .named))), source \(value.source), quality \(value.quality.rawValue)") }
}

extension MeasurementKind { var displayUnit: String { switch self { case .heartRate: "BPM"; case .ibi, .hrv: "ms"; case .steps: "steps"; case .distance: "m"; case .activeEnergy: "kcal"; case .workoutSummary, .bodyComposition: ""; case .oxygenSaturation, .bodyFat: "%"; case .skinTemperature: "°C"; case .leanBodyMass: "kg" } } }
extension ConnectionState { var label: String { switch self { case .needsAttention: "Needs attention"; default: rawValue.capitalized } } }
