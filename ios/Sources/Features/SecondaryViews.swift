import Charts
import SwiftUI

struct TrendsView: View {
    @State private var kind: MeasurementKind = .heartRate
    var body: some View { List {
        Picker("Metric", selection: $kind) { ForEach(MeasurementKind.allCases) { Text($0.title).tag($0) } }
        NavigationLink { MetricDetailView(kind: kind) } label: { Label("Open \(kind.title)", systemImage: "chart.line.uptrend.xyaxis").foregroundStyle(kind.tint) }
        Section("Recordings") { NavigationLink("Raw sessions") { RawSessionsView() } }
    }.scrollContentBackground(.hidden).background(OLED.canvas).navigationTitle("Trends") }
}

struct MetricDetailView: View {
    @Environment(AppModel.self) private var model
    let kind: MeasurementKind
    @State private var days = 1
    @State private var samples: [Measurement] = []
    var body: some View { ScrollView { VStack(spacing: 16) {
        Picker("Range", selection: $days) { Text("1D").tag(1); Text("7D").tag(7); Text("30D").tag(30) }.pickerStyle(.segmented)
        OLEDCard {
            if samples.isEmpty { ContentUnavailableView("No measurements", systemImage: "chart.xyaxis.line", description: Text("Sync your watch or take a measurement.")) }
            else { Chart(samples) { item in LineMark(x: .value("Time", item.observedAt), y: .value(kind.title, item.value)).foregroundStyle(kind.tint); PointMark(x: .value("Time", item.observedAt), y: .value(kind.title, item.value)).foregroundStyle(kind.tint) }.frame(height: 220).chartYScale(domain: .automatic(includesZero: false)) }
        }
        let trend = TrendSummary(samples: samples)
        HStack { summary("Min", trend.minimum); summary("Average", trend.average); summary("Max", trend.maximum) }
        ForEach(samples.reversed()) { item in MetricCard(value: item, hero: false) }
    }.padding(16) }.navigationTitle(kind.title).modifier(ScreenBackground()).task(id: days) { await load() } }
    private func summary(_ label: String, _ value: Double?) -> some View { OLEDCard { Text(label).font(.caption).foregroundStyle(OLED.secondary); Text(value?.formatted(.number.precision(.fractionLength(1))) ?? "—").font(.title3).monospacedDigit() } }
    private func load() async { do { samples = try await model.environment.measurements.history(for: kind, from: Calendar.current.date(byAdding: .day, value: -days, to: .now)!, to: .now) } catch { model.fail(error) } }
}

struct WatchView: View {
    @Environment(AppModel.self) private var model
    var body: some View { List {
        Section("Connection") {
            LabeledContent("Status", value: model.connection.label)
            if let battery = model.watchStatus.batteryPercent { LabeledContent("Battery", value: "\(battery)%") }
            if let sync = model.watchStatus.lastSyncAt { LabeledContent("Last sync", value: sync.formatted(.relative(presentation: .named))) }
            Button("Sync now") { model.sync() }.disabled(model.connection == .offline)
            Button("Find watch") { model.scan() }
        }
        Section("Capabilities") {
            if model.watchStatus.capabilities.isEmpty { Text("Capabilities appear after enrollment.").foregroundStyle(OLED.secondary) }
            ForEach(model.watchStatus.capabilities) { item in LabeledContent(item.kind.title, value: item.state.label) }
        }
        Section("Measurements") { NavigationLink("Take a measurement") { MeasureView() }; NavigationLink("Raw recordings") { RawSessionsView() } }
        Section { Text("WatchApp does not replace Galaxy Wearable pairing, firmware, calls, or backups.").foregroundStyle(OLED.secondary) }
    }.scrollContentBackground(.hidden).background(OLED.canvas).navigationTitle("Watch") }
}

struct MeasureView: View {
    @Environment(AppModel.self) private var model
    var body: some View { List {
        Section { Text("The watch opens a foreground guide for sensors requiring contact or positioning.").foregroundStyle(OLED.secondary) }
        Section("Spot measurement") { ForEach(MeasurementKind.allCases) { kind in Button { model.startMeasurement(kind) } label: { Label(kind.title, systemImage: "waveform.path.ecg").foregroundStyle(kind.tint) } } }
        Section("30-second recording") { ForEach(RawSessionKind.allCases, id: \.self) { kind in Button(kind.title) { model.startRaw(kind) } } }
    }.scrollContentBackground(.hidden).background(OLED.canvas).navigationTitle("Measure") }
}

struct RawSessionsView: View {
    @Environment(AppModel.self) private var model
    var body: some View { List {
        if model.rawSessions.isEmpty { ContentUnavailableView("No recordings", systemImage: "waveform", description: Text("Start a 30-second recording from Measure.")) }
        ForEach(model.rawSessions) { session in NavigationLink { RawSessionDetailView(session: session) } label: { VStack(alignment: .leading) { Text(session.kind.title); Text(session.startedAt.formatted()).font(.caption).foregroundStyle(OLED.secondary) } } }
    }.scrollContentBackground(.hidden).background(OLED.canvas).navigationTitle("Recordings") }
}

struct RawSessionDetailView: View {
    @Environment(AppModel.self) private var model
    let session: RawSession
    @State private var files: [URL] = []
    var body: some View { List {
        LabeledContent("Started", value: session.startedAt.formatted())
        LabeledContent("Duration", value: session.endedAt.timeIntervalSince(session.startedAt).formatted(.number.precision(.fractionLength(1))) + " s")
        LabeledContent("Sample rate", value: "\(session.sampleRateHz.formatted()) Hz")
        LabeledContent("Samples", value: "\(session.samples.count)")
        Button("Prepare JSON and CSV export") { Task { do { files = try await model.environment.rawSessions.exportFiles(for: session.id) } catch { model.fail(error) } } }
        if !files.isEmpty { ShareLink(items: files) { Label("Share export", systemImage: "square.and.arrow.up") } }
    }.scrollContentBackground(.hidden).background(OLED.canvas).navigationTitle(session.kind.title) }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmDelete = false
    var body: some View { @Bindable var model = model; List {
        Section("Apple Health") { LabeledContent("Export", value: model.healthStatus.label); Button("Authorize and export") { Task { await model.requestHealthAuthorization() } }; Button("Retry pending exports") { Task { await model.exportPending() } } }
        Section("Privacy") { LabeledContent("Cloud account", value: "None"); LabeledContent("Analytics", value: "Off"); Button("Delete all local health data", role: .destructive) { confirmDelete = true }; Button("Forget watch", role: .destructive) { model.forgetWatch() } }
        Section("Labs") { Toggle("Notification Lab", isOn: $model.notificationLabEnabled); if model.notificationLabEnabled { NavigationLink("Open Notification Lab") { NotificationLabView() } } }
        Section { LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—") }
    }.scrollContentBackground(.hidden).background(OLED.canvas).navigationTitle("Settings").confirmationDialog("Delete all measurements and recordings from this iPhone?", isPresented: $confirmDelete, titleVisibility: .visible) { Button("Delete All", role: .destructive) { Task { await model.deleteAll() } } } }
}

struct NotificationLabView: View {
    var body: some View { List {
        Section { Label("Experimental", systemImage: "flask").foregroundStyle(OLED.warning); Text("Notification delivery depends on ANCS, Bluetooth bonding, and real-hardware validation. Health sync never depends on this lab.") }
        Section("Available behavior") { Label("View notifications when iOS exposes them", systemImage: "bell"); Label("Use only actions supplied by iOS", systemImage: "hand.tap"); Label("No universal typed replies", systemImage: "xmark.bubble") }
        Section { Text("This build contains the product gate and disclosure UI. ANCS accessory behavior is implemented on the Wear OS companion and must remain disabled until hardware conformance passes.").foregroundStyle(OLED.secondary) }
    }.scrollContentBackground(.hidden).background(OLED.canvas).navigationTitle("Notification Lab") }
}

extension CapabilityState { var label: String { rawValue.replacingOccurrences(of: "_", with: " ").capitalized } }
extension RawSessionKind { var title: String { switch self { case .ecg: "ECG"; case .ppg: "PPG"; case .accelerometer: "Accelerometer" } } }
extension HealthExportStatus { var label: String { rawValue.replacingOccurrences(of: "([a-z])([A-Z])", with: "$1 $2", options: .regularExpression).capitalized } }
