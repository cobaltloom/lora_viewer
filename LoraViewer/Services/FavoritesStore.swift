import Foundation

/// IDs of gliders the pilot has marked as favorites, persisted locally on
/// the device (keyed by tracker id so it survives the server reassigning
/// board position numbers). LoRa (IMEI) and OGN (FLARM etc.) ids differ for
/// the same glider, so each source keeps its own list; `favoriteIMEIs` is
/// the list for the source currently in use.
final class FavoritesStore: ObservableObject {
    @Published private(set) var favoriteIMEIs: Set<String> {
        didSet { persist() }
    }

    /// Set by the map view whenever the effective data source changes.
    var dataSource: DataSource = .lora {
        didSet {
            guard dataSource != oldValue else { return }
            favoriteIMEIs = Self.load(for: dataSource)
        }
    }

    init() {
        favoriteIMEIs = Self.load(for: .lora)
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

    /// The LoRa key predates OGN support and is kept as-is so existing favorites survive.
    private static func storageKey(for source: DataSource) -> String {
        switch source {
        case .lora: return "favoriteGliderIMEIs"
        case .ogn: return "favoriteOGNGliders"
        }
    }

    private static func load(for source: DataSource) -> Set<String> {
        Set(UserDefaults.standard.array(forKey: storageKey(for: source)) as? [String] ?? [])
    }

    private func persist() {
        UserDefaults.standard.set(Array(favoriteIMEIs), forKey: Self.storageKey(for: dataSource))
    }
}
