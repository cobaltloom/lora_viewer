import Foundation
import Combine

/// User-configurable connection settings, persisted to UserDefaults.
///
/// The site's `mapapi.php` expects a `key` query parameter that is baked
/// into the page's own JavaScript per-account; since this app talks to the
/// API directly it must be supplied by the user (found via Safari's Web
/// Inspector Network tab while viewing the site) rather than hardcoded.
final class APISettings: ObservableObject {
    @Published var baseURLString: String {
        didSet { UserDefaults.standard.set(baseURLString, forKey: Keys.baseURL) }
    }
    @Published var secretKey: String {
        didSet { UserDefaults.standard.set(secretKey, forKey: Keys.secretKey) }
    }
    @Published var refreshIntervalSeconds: Double {
        didSet { UserDefaults.standard.set(refreshIntervalSeconds, forKey: Keys.refreshInterval) }
    }

    private enum Keys {
        static let baseURL = "apiBaseURL"
        static let secretKey = "apiSecretKey"
        static let refreshInterval = "refreshIntervalSeconds"
    }

    init() {
        let defaults = UserDefaults.standard
        baseURLString = defaults.string(forKey: Keys.baseURL)
            ?? "https://www.trailrouteview.com/user/jsal/gmap/"
        secretKey = defaults.string(forKey: Keys.secretKey) ?? ""
        let storedInterval = defaults.double(forKey: Keys.refreshInterval)
        refreshIntervalSeconds = storedInterval > 0 ? storedInterval : 10
    }

    var baseURL: URL? { URL(string: baseURLString) }
}
