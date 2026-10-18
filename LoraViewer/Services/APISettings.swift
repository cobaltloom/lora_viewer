import Foundation
import Combine
import FirebaseFirestore

/// Where glider positions come from.
enum DataSource: String, CaseIterable {
    /// The club's LoRa trackers, via trailrouteview.com.
    case lora
    /// The Open Glider Network - FLARM/OGN-tracker equipped aircraft, received live from OGN.
    /// Shown to people as "FLARM", the name they know; OGN is credited in the attribution.
    case ogn

    var displayName: String {
        switch self {
        case .lora: return "LoRa"
        case .ogn: return "FLARM"
        }
    }
}

/// User-configurable connection settings, persisted to UserDefaults.
///
/// The site's `mapapi.php` expects a `key` query parameter that is baked
/// into the page's own JavaScript per-account; since this app talks to the
/// API directly it must be supplied by the user (found via Safari's Web
/// Inspector Network tab while viewing the site) rather than hardcoded.
final class APISettings: ObservableObject {
    static let defaultBaseURLString = "https://www.trailrouteview.com/user/jsal/gmap/"

    @Published var baseURLString: String {
        didSet {
            UserDefaults.standard.set(baseURLString, forKey: Keys.baseURL)
            if !isApplyingProgrammaticUpdate {
                isBaseURLCustomized = true
            }
        }
    }
    @Published var secretKey: String {
        didSet { UserDefaults.standard.set(secretKey, forKey: Keys.secretKey) }
    }
    @Published var dataSource: DataSource {
        didSet { UserDefaults.standard.set(dataSource.rawValue, forKey: Keys.dataSource) }
    }
    /// Whether OGN may be offered at all, from `meta/serverConfig.ognEnabled`. Off until the
    /// feature has been checked against live traffic, and doubles as a remote kill switch - so
    /// it's controlled from Firestore rather than baked into a release.
    @Published private(set) var isOGNAvailable: Bool {
        // Cached so launch doesn't briefly poll LoRa before the Firestore snapshot arrives.
        didSet { UserDefaults.standard.set(isOGNAvailable, forKey: Keys.ognEnabledCached) }
    }
    /// The club's track server, from `meta/serverConfig` (`trackServerUrl` / `trackServerKey`).
    /// nil until both are set, which keeps trail backfill off.
    @Published private(set) var trackServer: TrackServerConfig?
    @Published var refreshIntervalSeconds: Double {
        didSet { UserDefaults.standard.set(refreshIntervalSeconds, forKey: Keys.refreshInterval) }
    }
    /// The refresh interval while OGN is selected, separate from LoRa's. A refresh only reads beacons
    /// already streamed in over the open connection - it asks no server anything - so it can be much
    /// shorter; trails get a point per refresh, so they cover a shorter span than with LoRa.
    @Published var ognRefreshIntervalSeconds: Double {
        didSet { UserDefaults.standard.set(ognRefreshIntervalSeconds, forKey: Keys.ognRefreshInterval) }
    }
    static let defaultOGNRefreshIntervalSeconds: Double = 1

    /// Whether this device has typed its own base URL into Settings, as opposed to following the
    /// server-provided default from `listenForRemoteBaseURL`. `resetBaseURLToServerDefault` is
    /// the escape hatch back to auto-following.
    @Published private(set) var isBaseURLCustomized: Bool {
        didSet { UserDefaults.standard.set(isBaseURLCustomized, forKey: Keys.baseURLCustomized) }
    }

    /// Suppresses marking the URL as user-customized while `init` restores the persisted value
    /// and while `listenForRemoteBaseURL` applies a server-provided one.
    private var isApplyingProgrammaticUpdate = true
    private lazy var db = Firestore.firestore()
    private var remoteConfigListener: ListenerRegistration?

    private enum Keys {
        static let baseURL = "apiBaseURL"
        static let secretKey = "apiSecretKey"
        static let refreshInterval = "refreshIntervalSeconds"
        static let ognRefreshInterval = "ognRefreshIntervalSeconds"
        static let baseURLCustomized = "apiBaseURLCustomized"
        static let dataSource = "dataSource"
        static let ognEnabledCached = "ognEnabledCached"
    }

    init() {
        let defaults = UserDefaults.standard
        let storedBaseURL = defaults.string(forKey: Keys.baseURL)
        baseURLString = storedBaseURL ?? Self.defaultBaseURLString
        secretKey = defaults.string(forKey: Keys.secretKey) ?? ""
        dataSource = defaults.string(forKey: Keys.dataSource).flatMap(DataSource.init(rawValue:)) ?? .lora
        isOGNAvailable = defaults.bool(forKey: Keys.ognEnabledCached)
        let storedInterval = defaults.double(forKey: Keys.refreshInterval)
        refreshIntervalSeconds = storedInterval > 0 ? storedInterval : 10
        let storedOGNInterval = defaults.double(forKey: Keys.ognRefreshInterval)
        ognRefreshIntervalSeconds = storedOGNInterval > 0 ? storedOGNInterval : Self.defaultOGNRefreshIntervalSeconds
        if defaults.object(forKey: Keys.baseURLCustomized) != nil {
            isBaseURLCustomized = defaults.bool(forKey: Keys.baseURLCustomized)
        } else {
            // Migrating from before this flag existed: a saved URL that still matches the app's
            // own built-in default is just what everyone always had, not a real customization.
            isBaseURLCustomized = storedBaseURL != nil && storedBaseURL != Self.defaultBaseURLString
        }
        isApplyingProgrammaticUpdate = false
        listenForRemoteBaseURL()
    }

    deinit {
        remoteConfigListener?.remove()
    }

    var baseURL: URL? { URL(string: baseURLString) }

    /// The source actually in use: a saved OGN choice falls back to LoRa while OGN is switched off.
    var effectiveDataSource: DataSource {
        dataSource == .ogn && !isOGNAvailable ? .lora : dataSource
    }

    /// The polling interval for the source actually in use.
    var currentRefreshIntervalSeconds: Double {
        switch effectiveDataSource {
        case .lora: return max(refreshIntervalSeconds, 3)
        case .ogn: return max(ognRefreshIntervalSeconds, 1)
        }
    }

    /// Discards a manually-entered URL and goes back to following the server-provided default.
    func resetBaseURLToServerDefault() {
        isBaseURLCustomized = false
    }

    /// Every install listens here for the club's current base URL, the same way the nickname
    /// list listens for shared nicknames - so a site URL change (rare, but has happened) reaches
    /// everyone without waiting for an App Store release. A device that has typed its own URL
    /// into Settings (`isBaseURLCustomized`) is left alone.
    private func listenForRemoteBaseURL() {
        remoteConfigListener = db.collection("meta").document("serverConfig")
            .addSnapshotListener { [weak self] snapshot, _ in
                guard let self else { return }
                if let snapshot {
                    let data = snapshot.data()
                    let trackServer = (data?["trackServerUrl"] as? String).flatMap(URL.init(string:)).flatMap { url in
                        (data?["trackServerKey"] as? String).map { TrackServerConfig(url: url, key: $0) }
                    }
                    if trackServer != self.trackServer {
                        self.trackServer = trackServer
                    }
                    let ognEnabled = snapshot.data()?["ognEnabled"] as? Bool ?? false
                    if ognEnabled != self.isOGNAvailable {
                        self.isOGNAvailable = ognEnabled
                    }
                }
                guard let url = snapshot?.data()?["baseUrl"] as? String, !url.isEmpty else { return }
                guard !self.isBaseURLCustomized, url != self.baseURLString else { return }
                self.isApplyingProgrammaticUpdate = true
                self.baseURLString = url
                self.isApplyingProgrammaticUpdate = false
            }
    }
}
