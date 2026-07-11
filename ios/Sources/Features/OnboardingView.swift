import SwiftUI

struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @State private var page = 0
    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Spacer()
            Image(systemName: page == 2 ? "applewatch.radiowaves.left.and.right" : "waveform.path.ecg").font(.system(size: 50)).foregroundStyle(page == 2 ? OLED.activity : OLED.heart).accessibilityHidden(true)
            Text(["Your watch data. Beautifully private.", "Know the boundaries", "Pair WatchApp"][page]).font(.largeTitle.bold())
            Text(copy).font(.title3).foregroundStyle(OLED.secondary)
            if case .code(let code) = model.enrollment {
                Text(code).font(.system(size: 48, weight: .medium, design: .rounded)).monospacedDigit().accessibilityLabel("Verification code \(code)")
                Text("Confirm this code matches the one on your watch.").foregroundStyle(OLED.secondary)
            }
            Spacer()
            Button(action: advance) { Text(buttonTitle).frame(maxWidth: .infinity).padding(.vertical, 8) }.buttonStyle(.borderedProminent).tint(OLED.primary).foregroundStyle(.black).controlSize(.large)
            if page == 2, case .scanning = model.enrollment { ProgressView("Looking for WatchApp…").frame(maxWidth: .infinity) }
        }.padding(24).background(OLED.canvas.ignoresSafeArea())
    }
    private var copy: String {
        switch page {
        case 0: "See supported Galaxy Watch measurements, trends, and recordings on your iPhone. Data stays local unless you choose Apple Health."
        case 1: "WatchApp does not replace Galaxy Wearable, import Samsung Health history, or provide medical advice. Your Watch6 must already be set up and run the WatchApp companion."
        default: "Bluetooth is requested only when you scan. Verify the six-digit code on both devices before trusting the connection."
        }
    }
    private var buttonTitle: String { if page < 2 { return "Continue" }; if case .code = model.enrollment { return "Codes Match" }; return "Pair Watch" }
    private func advance() { if page < 2 { page += 1 } else if case .code = model.enrollment { model.confirmEnrollment() } else { model.scan() } }
}
