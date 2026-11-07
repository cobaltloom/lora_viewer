import Foundation
import UIKit
import AppTrackingTransparency
import GoogleMobileAds

/// Banner ads on the screens other than the map, and a rewarded ad that turns them off for
/// `rewardDuration`. The subscription (`SubscriptionManager`) turns them off for good. Every
/// feature is free either way.
@MainActor
final class AdManager: ObservableObject {
    // Debug builds use Google's test ad units, so development never serves (or clicks) real ads.
    #if DEBUG
    static let bannerUnitID = "ca-app-pub-3940256099942544/2435281174"
    static let rewardedUnitID = "ca-app-pub-3940256099942544/1712485313"
    #else
    static let bannerUnitID = "ca-app-pub-6754188955033183/9583570803"
    static let rewardedUnitID = "ca-app-pub-6754188955033183/4079962683"
    #endif
    static let rewardDuration: TimeInterval = 24 * 3600

    /// Ads are off until then, after watching a rewarded ad.
    @Published private(set) var rewardedAdFreeUntil: Date? {
        didSet { UserDefaults.standard.set(rewardedAdFreeUntil, forKey: Self.adFreeUntilKey) }
    }
    @Published private(set) var isStarted = false
    @Published private(set) var rewardedAd: RewardedAd?
    @Published private(set) var isLoadingRewardedAd = false

    private static let adFreeUntilKey = "rewardedAdFreeUntil"
    private var startTask: Task<Void, Never>?

    init() {
        rewardedAdFreeUntil = UserDefaults.standard.object(forKey: Self.adFreeUntilKey) as? Date
    }

    var isRewardActive: Bool {
        guard let rewardedAdFreeUntil else { return false }
        return Date() < rewardedAdFreeUntil
    }

    /// Asks for tracking permission (personalized ads; ads are shown either way), then starts the
    /// SDK. Call once the app is active: the permission prompt doesn't show otherwise.
    func start() {
        guard startTask == nil else { return }
        startTask = Task {
            if ATTrackingManager.trackingAuthorizationStatus == .notDetermined {
                _ = await ATTrackingManager.requestTrackingAuthorization()
            }
            _ = await MobileAds.shared.start()
            isStarted = true
            await loadRewardedAd()
        }
    }

    func loadRewardedAd() async {
        guard rewardedAd == nil, !isLoadingRewardedAd else { return }
        isLoadingRewardedAd = true
        defer { isLoadingRewardedAd = false }
        rewardedAd = try? await RewardedAd.load(with: Self.rewardedUnitID, request: Request())
    }

    /// Shows the loaded rewarded ad; watching it to the end turns ads off for `rewardDuration`.
    func showRewardedAd() {
        guard let ad = rewardedAd, let root = Self.topViewController() else { return }
        rewardedAd = nil
        ad.present(from: root) {
            Task { @MainActor [weak self] in
                self?.grantReward()
            }
        }
        Task { await loadRewardedAd() }
    }

    private func grantReward() {
        rewardedAdFreeUntil = Date().addingTimeInterval(Self.rewardDuration)
        // Re-evaluate isRewardActive when the reward runs out.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.rewardDuration + 1))
            self?.objectWillChange.send()
        }
    }

    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.keyWindow?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
