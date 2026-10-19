import SwiftUI
import GoogleMobileAds

/// A standard 320×50 banner along the bottom of a screen, unless ads are off (subscribed, or a rewarded
/// ad watched in the last 24 h). Not used on the map, which stays clear and cool.
struct AdBanner: View {
    @EnvironmentObject private var adManager: AdManager
    @EnvironmentObject private var subscriptionManager: SubscriptionManager

    var body: some View {
        if adManager.isStarted, !subscriptionManager.isSubscribed, !adManager.isRewardActive {
            BannerAdView()
                .frame(width: AdSizeBanner.size.width, height: AdSizeBanner.size.height)
                .frame(maxWidth: .infinity)
                .background(.bar)
        }
    }
}

private struct BannerAdView: UIViewRepresentable {
    func makeUIView(context: Context) -> BannerView {
        let banner = BannerView(adSize: AdSizeBanner)
        banner.adUnitID = AdManager.bannerUnitID
        banner.rootViewController = AdManager.topViewController()
        banner.load(Request())
        return banner
    }

    func updateUIView(_ banner: BannerView, context: Context) {}
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
