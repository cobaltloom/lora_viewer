import SwiftUI
import GoogleMobileAds

/// A full-width banner along the bottom of a screen, unless ads are off (subscribed, or a rewarded
/// ad watched in the last 24 h). The SDK picks the ad's height, capped at `maxHeight` (lower in
/// landscape, where the map is short); the slot shrinks to the ad it actually gets.
struct AdBanner: View {
    @EnvironmentObject private var adManager: AdManager
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    /// Extra bar-coloured space under the ad, keeping it off buttons just below it.
    var bottomSpacing: CGFloat = 0
    /// The width available to the banner; 0 until measured, and no ad is requested before that.
    @State private var width: CGFloat = 0
    /// The height of the ad that loaded; nil until one has.
    @State private var adHeight: CGFloat?

    private var maxHeight: CGFloat { verticalSizeClass == .compact ? 50 : 60 }

    var body: some View {
        if adManager.isStarted, !subscriptionManager.isSubscribed, !adManager.isRewardActive {
            let adSize = inlineAdaptiveBanner(width: width, maxHeight: maxHeight)
            ZStack {
                if width > 0 {
                    BannerAdView(adSize: adSize) { adHeight = $0 }
                        .frame(width: adSize.size.width, height: min(adHeight ?? maxHeight, maxHeight))
                }
            }
            .frame(maxWidth: .infinity)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .padding(.bottom, bottomSpacing)
            .background(.bar)
        }
    }
}

private struct BannerAdView: UIViewRepresentable {
    let adSize: AdSize
    /// Called with the loaded ad's height (at most the requested max height).
    let onLoad: (CGFloat) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> BannerView {
        let banner = BannerView(adSize: adSize)
        banner.adUnitID = AdManager.bannerUnitID
        banner.rootViewController = AdManager.topViewController()
        banner.delegate = context.coordinator
        context.coordinator.onLoad = onLoad
        context.coordinator.requestedSize = adSize.size
        banner.load(Request())
        return banner
    }

    /// On rotation the width (and max height) changes, so the banner takes the new size and loads an
    /// ad for it.
    func updateUIView(_ banner: BannerView, context: Context) {
        context.coordinator.onLoad = onLoad
        guard !CGSizeEqualToSize(context.coordinator.requestedSize, adSize.size) else { return }
        context.coordinator.requestedSize = adSize.size
        banner.adSize = adSize
        banner.load(Request())
    }

    final class Coordinator: NSObject, BannerViewDelegate {
        var onLoad: (CGFloat) -> Void = { _ in }
        /// The size last asked for; the banner's own adSize becomes the loaded ad's after a load.
        var requestedSize: CGSize = .zero

        func bannerViewDidReceiveAd(_ bannerView: BannerView) {
            onLoad(bannerView.adSize.size.height)
        }
    }
}

/// The settings rows for turning ads off: the subscription, or a rewarded ad for 24 h.
struct AdFreeRows: View {
    @EnvironmentObject private var adManager: AdManager
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @State private var showPurchase = false

    var body: some View {
        if subscriptionManager.isSubscribed {
            Label("広告なしを購入済みです。ありがとうございます!", systemImage: "checkmark.seal.fill")
        } else {
            Button {
                showPurchase = true
            } label: {
                Label("広告なしにする(購入)", systemImage: "nosign")
            }
            .sheet(isPresented: $showPurchase) {
                PaywallView()
            }
            if adManager.isRewardActive, let until = adManager.rewardedAdFreeUntil {
                Label("\(until.formatted(.dateTime.month().day().hour().minute())) まで広告なし", systemImage: "gift.fill")
            } else {
                Button {
                    if adManager.rewardedAd == nil {
                        Task { await adManager.loadRewardedAd() }
                    } else {
                        adManager.showRewardedAd()
                    }
                } label: {
                    if adManager.rewardedAd != nil {
                        Label("動画広告を見て24時間広告なし", systemImage: "play.rectangle.fill")
                    } else if adManager.isLoadingRewardedAd || !adManager.isStarted {
                        Label("動画広告を読み込み中…", systemImage: "play.rectangle")
                    } else {
                        Label("動画広告を読み込めませんでした(タップで再読み込み)", systemImage: "arrow.clockwise")
                    }
                }
                .disabled(adManager.isLoadingRewardedAd || !adManager.isStarted)
            }
        }
    }
}
