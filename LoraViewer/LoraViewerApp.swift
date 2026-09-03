import SwiftUI
import FirebaseCore

@main
struct LoraViewerApp: App {
    @StateObject private var settings = APISettings()
    @StateObject private var nicknameStore = NicknameStore()
    @StateObject private var favoritesStore = FavoritesStore()
    @StateObject private var alertSettings = AlertSettings()
    @StateObject private var competitionGuideline = CompetitionAltitudeGuideline()
    @StateObject private var upperAltitudeGuideline = UpperAltitudeGuideline()

    init() {
        FirebaseApp.configure()
    }

    var body: some Scene {
        WindowGroup {
            CurrentMapView(settings: settings)
                .environmentObject(settings)
                .environmentObject(nicknameStore)
                .environmentObject(favoritesStore)
                .environmentObject(alertSettings)
                .environmentObject(competitionGuideline)
                .environmentObject(upperAltitudeGuideline)
        }
    }
}
