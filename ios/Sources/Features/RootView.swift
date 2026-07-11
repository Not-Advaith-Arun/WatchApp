import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        @Bindable var model = model
        Group {
            if !model.hasCompletedOnboarding { OnboardingView() }
            else {
                TabView {
                    Tab("Today", systemImage: "heart.text.square") { NavigationStack { TodayView() } }
                    Tab("Trends", systemImage: "chart.xyaxis.line") { NavigationStack { TrendsView() } }
                    Tab("Watch", systemImage: "applewatch") { NavigationStack { WatchView() } }
                    Tab("Settings", systemImage: "gearshape") { NavigationStack { SettingsView() } }
                }.tint(OLED.primary)
            }
        }
        .task { await model.refresh() }
        .alert(item: $model.presentedError) { error in Alert(title: Text("WatchApp"), message: Text(error.message), dismissButton: .default(Text("OK"))) }
    }
}

struct ScreenBackground: ViewModifier {
    func body(content: Content) -> some View { content.background(OLED.canvas.ignoresSafeArea()).toolbarBackground(OLED.canvas, for: .navigationBar) }
}
