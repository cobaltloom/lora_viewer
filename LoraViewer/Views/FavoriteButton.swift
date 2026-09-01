import SwiftUI

/// A tappable star that toggles whether a glider is marked as a favorite.
struct FavoriteButton: View {
    let imei: String

    @EnvironmentObject private var favoritesStore: FavoritesStore

    var body: some View {
        Button {
            favoritesStore.toggle(imei)
        } label: {
            Image(systemName: favoritesStore.isFavorite(imei) ? "star.fill" : "star")
                .foregroundStyle(favoritesStore.isFavorite(imei) ? .yellow : .secondary)
        }
        .buttonStyle(.plain)
    }
}
