import SwiftUI

@main
struct LoraViewerApp: App {
    @StateObject private var settings = APISettings()

    var body: some Scene {
        WindowGroup {
            CurrentMapView(settings: settings)
                .environmentObject(settings)
        }
    }
}
