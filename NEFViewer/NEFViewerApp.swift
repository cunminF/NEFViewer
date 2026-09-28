import SwiftUI
import SwiftData

@main
struct NEFViewerApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .windowToolbarStyle(.unified)
        .modelContainer(for: [Project.self, Photo.self])

        Settings {
            SettingsView()
        }
    }
}
