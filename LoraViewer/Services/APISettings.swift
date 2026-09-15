import Foundation
import Combine
import FirebaseFirestore

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
    @Published var refreshIntervalSeconds: Double {
        didSet { UserDefaults.standard.set(refreshIntervalSeconds, forKey: Keys.refreshInterval) }
    }

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
        static let baseURLCustomized = "apiBaseURLCustomized"
    }

    init() {
        let defaults = UserDefaults.standard
        let storedBaseURL = defaults.string(forKey: Keys.baseURL)
        baseURLString = storedBaseURL ?? Self.defaultBaseURLString
        secretKey = defaults.string(forKey: Keys.secretKey) ?? ""
        let storedInterval = defaults.double(forKey: Keys.refreshInterval)
        refreshIntervalSeconds = storedInterval > 0 ? storedInterval : 10
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
                guard let url = snapshot?.data()?["baseUrl"] as? String, !url.isEmpty else { return }
                guard !self.isBaseURLCustomized, url != self.baseURLString else { return }
                self.isApplyingProgrammaticUpdate = true
                self.baseURLString = url
                self.isApplyingProgrammaticUpdate = false
            }
    }
}
