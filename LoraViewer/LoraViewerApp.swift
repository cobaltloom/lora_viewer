import SwiftUI

@main
struct LoraViewerApp: App {
    @StateObject private var settings = APISettings()
    @StateObject private var nicknameStore = NicknameStore()

    var body: some Scene {
        WindowGroup {
            CurrentMapView(settings: settings)
                .environmentObject(settings)
                .environmentObject(nicknameStore)
        }
    }
}
