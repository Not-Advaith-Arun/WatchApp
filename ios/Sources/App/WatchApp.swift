import SwiftUI

@main struct WatchApp: App {
    @State private var model = AppModel(environment: .live())
    var body: some Scene {
        WindowGroup { RootView().environment(model).preferredColorScheme(.dark) }
    }
}
