import SwiftUI
import MapKit

struct CurrentMapView: View {
    @EnvironmentObject var settings: APISettings
    @EnvironmentObject private var favoritesStore: FavoritesStore
    @EnvironmentObject private var alertSettings: AlertSettings
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

    /// The site's own configured map center, normally the airfield itself —
    /// used as the safety-altitude reference point unless a custom one is set.
    private var defaultReferenceCoordinate: CLLocationCoordinate2D? {
        guard let siteSettings = viewModel.config?.settings, siteSettings.lat != 0, siteSettings.lon != 0 else {
            return nil
        }
        return CLLocationCoordinate2D(latitude: siteSettings.lat, longitude: siteSettings.lon)
    }

    private var alertReferenceCoordinate: CLLocationCoordinate2D? {
        alertSettings.referenceCoordinate(default: defaultReferenceCoordinate)
    }

    private var alertingGliders: [GliderPosition] {
        displayedPositions.filter { alertSettings.isBelowSafeAltitude($0, defaultReference: defaultReferenceCoordinate) }
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                Map(position: $cameraPosition) {
                    if alertSettings.isEnabled, let alertReferenceCoordinate {
                        MapCircle(center: alertReferenceCoordinate, radius: alertSettings.distanceThresholdKm * 1000)
                            .foregroundStyle(.red.opacity(0.08))
                            .stroke(.red.opacity(0.4), lineWidth: 1)
                        Annotation("基準地点", coordinate: alertReferenceCoordinate) {
                            Image(systemName: "flag.circle.fill")
                                .font(.title2)
                                .foregroundStyle(.red)
                                .background(Circle().fill(.white))
                        }
                    }
                    ForEach(displayedPositions) { glider in
                        Annotation(viewModel.nameFor(index: glider.index), coordinate: glider.coordinate) {
                            GliderMarkerView(
                                glider: glider,
                                isSelected: selectedGlider?.id == glider.id,
                                isFavorite: favoritesStore.isFavorite(glider.imei),
                                isAlerting: alertSettings.isBelowSafeAltitude(glider, defaultReference: defaultReferenceCoordinate)
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
                .safeAreaInset(edge: .top) {
                    if !alertingGliders.isEmpty {
                        alertBanner
                    }
                }

                if let selectedGlider {
                    GliderDetailCard(
                        glider: selectedGlider,
                        baseName: viewModel.nameFor(index: selectedGlider.index),
                        isAlerting: alertSettings.isBelowSafeAltitude(selectedGlider, defaultReference: defaultReferenceCoordinate)
                    )
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
                SettingsView(defaultReferenceCoordinate: defaultReferenceCoordinate)
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

    private var alertBanner: some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text("高度不足の可能性: " + alertingGliders.map { viewModel.nameFor(index: $0.index) }.joined(separator: "、"))
                .font(.caption)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(.white)
        .background(.red, in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
        .padding(.top, 4)
    }
}
