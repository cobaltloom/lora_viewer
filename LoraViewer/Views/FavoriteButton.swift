import SwiftUI

/// A tappable star that toggles whether a glider is marked as a favorite.
/// Favoriting is a subscriber-only feature — without one, tapping shows
/// the paywall instead of toggling.
struct FavoriteButton: View {
    let imei: String

    @EnvironmentObject private var favoritesStore: FavoritesStore
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @State private var showPaywall = false

    var body: some View {
        Button {
            if subscriptionManager.isSubscribed {
                favoritesStore.toggle(imei)
            } else {
                showPaywall = true
            }
        } label: {
            Image(systemName: favoritesStore.isFavorite(imei) ? "star.fill" : "star")
                .foregroundStyle(favoritesStore.isFavorite(imei) ? .yellow : .secondary)
        }
        .buttonStyle(.plain)
        .sheet(isPresented: $showPaywall) {
            PaywallView()
        }
    }
}
