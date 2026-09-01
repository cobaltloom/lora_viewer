import SwiftUI

@main
struct LoraViewerApp: App {
    @StateObject private var settings = APISettings()
    @StateObject private var nicknameStore = NicknameStore()
    @StateObject private var favoritesStore = FavoritesStore()
    @StateObject private var alertSettings = AlertSettings()

    var body: some Scene {
        WindowGroup {
            CurrentMapView(settings: settings)
                .environmentObject(settings)
                .environmentObject(nicknameStore)
                .environmentObject(favoritesStore)
                .environmentObject(alertSettings)
        }
    }
}
