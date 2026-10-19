import SwiftUI
import FirebaseCore

@main
struct LoraViewerApp: App {
    @StateObject private var settings = APISettings()
    @StateObject private var nicknameStore = NicknameStore()
    @StateObject private var pointOfInterestStore = PointOfInterestStore()
    @StateObject private var favoritesStore = FavoritesStore()
    @StateObject private var alertSettings = AlertSettings()
    @StateObject private var competitionGuideline = CompetitionAltitudeGuideline()
    @StateObject private var upperAltitudeGuideline = UpperAltitudeGuideline()
    @StateObject private var proximityAlertSettings = ProximityAlertSettings()
    @StateObject private var subscriptionManager = SubscriptionManager()
    @StateObject private var adManager = AdManager()
    @StateObject private var handicapStore = GliderHandicapStore()

    init() {
        FirebaseApp.configure()
    }

    var body: some Scene {
        WindowGroup {
            CurrentMapView(settings: settings)
                .environmentObject(settings)
                .environmentObject(nicknameStore)
                .environmentObject(pointOfInterestStore)
                .environmentObject(favoritesStore)
                .environmentObject(alertSettings)
                .environmentObject(competitionGuideline)
                .environmentObject(upperAltitudeGuideline)
                .environmentObject(proximityAlertSettings)
                .environmentObject(subscriptionManager)
                .environmentObject(handicapStore)
                .environmentObject(adManager)
                // The tracking prompt only shows once the app is active.
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
                    adManager.start()
                }
        }
    }
}
