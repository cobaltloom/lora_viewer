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

/// Each glider's competition handicap (競技規則53), set per glider in the member list and used for
/// the reference scores of completed laps. Kept on this device, keyed by tracker id (LoRa IMEI or
/// OGN callsign - they never collide, so one table serves both sources). Unset gliders use
/// `builtInHandicaps`, else 1.00.
final class GliderHandicapStore: ObservableObject {
    struct Option: Identifiable {
        let value: Double
        let types: String
        var id: Double { value }
    }

    /// The rule's handicap table, with the glider types each value covers.
    static let options: [Option] = [
        Option(value: 0.90, types: "Discus, LS4, LS8, ASW24, ASW28"),
        Option(value: 0.95, types: "G102(引込脚), シーラス, ASW19, DG-101G, クラブリベレ"),
        Option(value: 1.00, types: "ASK23, SZD51, ASK21, G102(固定脚)"),
        Option(value: 1.05, types: "Ka6, SZD50, PW-5"),
        Option(value: 1.10, types: "K-8, ASK13"),
    ]
    static let defaultHandicap = 1.0

    /// Known club gliders that aren't 1.00, so laps score right even for gliders that have landed
    /// and left the member list (as of 第42回関東学生グライダー競技会, 2026-10): LoRa tracker ids from
    /// the site's board, and the FLARM each glider was seen carrying. Spare LoRa trackers (000D-000F)
    /// move between gliders, so they're left out.
    static let builtInHandicaps: [String: Double] = [
        "0006": 0.90, "ICA84C247": 0.90, // 早稲田 LS4
        "0007": 0.95, "FLRDB0774": 0.95, // 明治 DG-100G
        "0008": 0.90, "FLRDB0775": 0.90, // 慶應 Discus
        "000B": 0.90, "ICA841083": 0.90, // 法政 Discus
    ]
    private static let storageKey = "gliderHandicaps"

    @Published private(set) var handicaps: [String: Double] {
        didSet { UserDefaults.standard.set(handicaps, forKey: Self.storageKey) }
    }

    init() {
        handicaps = UserDefaults.standard.dictionary(forKey: Self.storageKey) as? [String: Double] ?? [:]
    }

    func handicap(for id: String) -> Double {
        handicaps[id] ?? Self.builtInDefault(for: id)
    }

    func setHandicap(_ value: Double, for id: String) {
        handicaps[id] = value == Self.builtInDefault(for: id) ? nil : value
    }

    static func builtInDefault(for id: String) -> Double {
        builtInHandicaps[id] ?? defaultHandicap
    }
}
