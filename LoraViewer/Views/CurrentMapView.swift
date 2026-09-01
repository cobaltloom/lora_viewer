import SwiftUI
import MapKit

struct CurrentMapView: View {
    @EnvironmentObject var settings: APISettings
    @EnvironmentObject private var favoritesStore: FavoritesStore
    @StateObject private var viewModel: GliderTrackerViewModel
    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var selectedGlider: GliderPosition?
    @State private var showSettings = false
    @State private var didCenterInitially = false
    @State private var showFavoritesOnly = false

    init(settings: APISettings) {
        _viewModel = StateObject(wrappedValue: GliderTrackerViewModel(settings: settings))
    }

    private var displayedPositions: [GliderPosition] {
        guard showFavoritesOnly else { return viewModel.positions }
        let favorites = viewModel.positions.filter { favoritesStore.isFavorite($0.imei) }
        // Don't leave the map blank if the last favorite just got un-favorited.
        return favorites.isEmpty ? viewModel.positions : favorites
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                Map(position: $cameraPosition) {
                    ForEach(displayedPositions) { glider in
                        Annotation(viewModel.nameFor(index: glider.index), coordinate: glider.coordinate) {
                            GliderMarkerView(
                                glider: glider,
                                isSelected: selectedGlider?.id == glider.id,
                                isFavorite: favoritesStore.isFavorite(glider.imei)
                            )
                                .onTapGesture {
                                    withAnimation { selectedGlider = glider }
                                }
                        }
                    }
                }
                .mapControls {
                    MapCompass()
                    MapScaleView()
                }

                if let selectedGlider {
                    GliderDetailCard(glider: selectedGlider, baseName: viewModel.nameFor(index: selectedGlider.index))
                        .padding()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .onTapGesture {
                            withAnimation { self.selectedGlider = nil }
                        }
                }
            }
            .navigationTitle(viewModel.config?.settings.siteTitle ?? "グライダー位置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    NavigationLink {
                        GliderListView(viewModel: viewModel)
                    } label: {
                        Image(systemName: "list.bullet")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        toggleFavoritesOnly()
                    } label: {
                        Image(systemName: showFavoritesOnly ? "star.circle.fill" : "star.circle")
                    }
                    .disabled(!viewModel.positions.contains { favoritesStore.isFavorite($0.imei) })
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    if viewModel.isLoading {
                        ProgressView()
                    }
                    if let lastUpdated = viewModel.lastUpdated {
                        Text("更新: \(lastUpdated, style: .time)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    NavigationLink {
                        TrackHistoryView(settings: settings)
                    } label: {
                        Label("行動軌跡", systemImage: "clock.arrow.circlepath")
                    }
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
                    .environmentObject(settings)
            }
            .alert("エラー", isPresented: Binding(
                get: { viewModel.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil } }
            )) {
                Button("OK") { viewModel.errorMessage = nil }
            } message: {
                Text(viewModel.errorMessage ?? "")
            }
            .task {
                viewModel.startPolling()
            }
            .onDisappear {
                viewModel.stopPolling()
            }
            .onChange(of: viewModel.positions) { _, newPositions in
                centerMapIfNeeded(on: newPositions)
            }
        }
    }

    private func centerMapIfNeeded(on positions: [GliderPosition]) {
        guard !didCenterInitially, !positions.isEmpty else { return }
        didCenterInitially = true
        let coordinates = positions.map(\.coordinate)
        cameraPosition = .region(MKCoordinateRegion(coordinates: coordinates))
    }

    private func toggleFavoritesOnly() {
        withAnimation {
            showFavoritesOnly.toggle()
        }

        guard showFavoritesOnly else { return }

        if let selectedGlider, !favoritesStore.isFavorite(selectedGlider.imei) {
            self.selectedGlider = nil
        }

        let favoritePositions = viewModel.positions.filter { favoritesStore.isFavorite($0.imei) }
        guard !favoritePositions.isEmpty else { return }
        withAnimation {
            cameraPosition = .region(MKCoordinateRegion(coordinates: favoritePositions.map(\.coordinate)))
        }
    }
}
