import SwiftUI
import GoogleMobileAds

/// An adaptive banner (full width, height chosen by the SDK for that width) along the bottom of a
/// screen, unless ads are off (subscribed, or a rewarded ad watched in the last 24 h).
struct AdBanner: View {
    @EnvironmentObject private var adManager: AdManager
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    /// Extra bar-coloured space under the ad, keeping it off buttons just below it.
    var bottomSpacing: CGFloat = 0
    /// The width available to the banner; 0 until measured, and no ad is requested before that.
    @State private var width: CGFloat = 0

    var body: some View {
        if adManager.isStarted, !subscriptionManager.isSubscribed, !adManager.isRewardActive {
            let adSize = largeAnchoredAdaptiveBanner(width: width)
            ZStack {
                if width > 0 {
                    BannerAdView(adSize: adSize)
                        .frame(width: adSize.size.width, height: adSize.size.height)
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

    func makeUIView(context: Context) -> BannerView {
        let banner = BannerView(adSize: adSize)
        banner.adUnitID = AdManager.bannerUnitID
        banner.rootViewController = AdManager.topViewController()
        banner.load(Request())
        return banner
    }

    /// On rotation the width changes, so the banner takes the new size and loads an ad for it.
    func updateUIView(_ banner: BannerView, context: Context) {
        guard !CGSizeEqualToSize(banner.adSize.size, adSize.size) else { return }
        banner.adSize = adSize
        banner.load(Request())
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
