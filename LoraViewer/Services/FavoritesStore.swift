import Foundation

/// IMEIs of gliders the pilot has marked as favorites, persisted locally on
/// the device (keyed by IMEI so it survives the server reassigning board
/// position numbers).
final class FavoritesStore: ObservableObject {
    @Published private(set) var favoriteIMEIs: Set<String> {
        didSet { persist() }
    }

    private let storageKey = "favoriteGliderIMEIs"

    init() {
        if let saved = UserDefaults.standard.array(forKey: storageKey) as? [String] {
            favoriteIMEIs = Set(saved)
        } else {
            favoriteIMEIs = []
        }
    }

    func isFavorite(_ imei: String) -> Bool {
        favoriteIMEIs.contains(imei)
    }

    func toggle(_ imei: String) {
        if favoriteIMEIs.contains(imei) {
            favoriteIMEIs.remove(imei)
        } else {
            favoriteIMEIs.insert(imei)
        }
    }

    private func persist() {
        UserDefaults.standard.set(Array(favoriteIMEIs), forKey: storageKey)
    }
}
