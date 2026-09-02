import SwiftUI
import MapKit

struct CurrentMapView: View {
    @EnvironmentObject var settings: APISettings
    @EnvironmentObject private var favoritesStore: FavoritesStore
    @EnvironmentObject private var alertSettings: AlertSettings
    @EnvironmentObject private var competitionGuideline: CompetitionAltitudeGuideline
    @EnvironmentObject private var upperAltitudeGuideline: UpperAltitudeGuideline
    @StateObject private var viewModel: GliderTrackerViewModel
    @StateObject private var locationManager = LocationManager()
    @StateObject private var alertNotifier = AlertNotifier()
    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var selectedGlider: GliderPosition?
    @State private var showSettings = false
    @State private var didCenterInitially = false
    @State private var showFavoritesOnly = false
    @State private var previouslyAlertingIMEIs: Set<String> = []

    init(settings: APISettings) {
        _viewModel = StateObject(wrappedValue: GliderTrackerViewModel(settings: settings))
    }

    private var displayedPositions: [GliderPosition] {
        guard showFavoritesOnly else { return viewModel.positions }
        let favorites = viewModel.positions.filter { favoritesStore.isFavorite($0.imei) }
        // Don't leave the map blank if the last favorite just got un-favorited.
        return favorites.isEmpty ? viewModel.positions : favorites
    }

    /// The safety-altitude reference point used when no custom one is set:
    /// the airfield coordinate from JSAL's own guideline document. The
    /// site's own "settings.lat/lon" turned out unreliable for this — it's
    /// sometimes 0 (Tokyo Station in MapKit's fallback region) and
    /// sometimes some other value far from the actual field, so circles
    /// drawn around it could end up off-screen. This coordinate is the one
    /// value known to actually be the airfield.
    private var defaultReferenceCoordinate: CLLocationCoordinate2D {
        CompetitionAltitudeGuideline.referenceCoordinate
    }

    private var alertReferenceCoordinate: CLLocationCoordinate2D? {
        alertSettings.referenceCoordinate(default: defaultReferenceCoordinate)
    }

    private func alertReasons(for glider: GliderPosition) -> [GliderAlertReason] {
        var reasons: [GliderAlertReason] = []
        if let severity = alertSettings.alertSeverity(for: glider, defaultReference: defaultReferenceCoordinate) {
            reasons.append(GliderAlertReason(label: "カスタム設定", severity: severity))
        }
        if competitionGuideline.isBelowGuideline(glider, minimumFlyingAltitudeM: alertSettings.minimumFlyingAltitudeM) {
            reasons.append(GliderAlertReason(label: "競技会ガイドライン", severity: .warning))
        }
        if upperAltitudeGuideline.exceedsCeiling(glider), let zone = upperAltitudeGuideline.applicableZone(for: glider) {
            reasons.append(GliderAlertReason(label: "\(zone.name)上限\(Int(zone.ceilingM))m超過", severity: .warning))
        }
        return reasons
    }

    private var alertingGliders: [GliderPosition] {
        displayedPositions.filter { !alertReasons(for: $0).isEmpty }
    }

    /// Short labels for whichever altitude alerts are currently turned on,
    /// so it's visible at a glance without opening Settings. Empty when
    /// neither alert is enabled.
    private var activeAlertLabels: [String] {
        var labels: [String] = []
        if alertSettings.isEnabled {
            switch alertSettings.mode {
            case .steps: labels.append("カスタム:距離段階")
            case .glideRatio: labels.append("カスタム:L/D")
            }
        }
        if competitionGuideline.isEnabled {
            labels.append("競技会ガイドライン")
        }
        if upperAltitudeGuideline.isEnabled {
            labels.append("上限高度")
        }
        return labels
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                Map(position: $cameraPosition) {
                    if alertSettings.isEnabled, let alertReferenceCoordinate {
                        if alertSettings.mode == .steps {
                            ForEach(alertSettings.steps) { step in
                                MapCircle(center: alertReferenceCoordinate, radius: step.distanceKm * 1000)
                                    .foregroundStyle(.red.opacity(0.04))
                                    .stroke(.red.opacity(0.4), lineWidth: 1)
                            }
                        }
                        Annotation("基準地点", coordinate: alertReferenceCoordinate) {
                            Image(systemName: "flag.circle.fill")
                                .font(.title2)
                                .foregroundStyle(.red)
                                .background(Circle().fill(.white))
                        }
                    }
                    if competitionGuideline.isEnabled {
                        MapCircle(
                            center: CompetitionAltitudeGuideline.referenceCoordinate,
                            radius: CompetitionAltitudeGuideline.innerRadiusKm * 1000
                        )
                            .foregroundStyle(.purple.opacity(0.06))
                            .stroke(.purple.opacity(0.5), lineWidth: 1)
                        ForEach(CompetitionAltitudeGuideline.boundaryDistancesKm.filter { $0 > CompetitionAltitudeGuideline.innerRadiusKm }, id: \.self) { km in
                            MapCircle(center: CompetitionAltitudeGuideline.referenceCoordinate, radius: km * 1000)
                                .foregroundStyle(.clear)
                                .stroke(.purple.opacity(0.35), lineWidth: 1)
                        }
                        Annotation("競技会基準地点", coordinate: CompetitionAltitudeGuideline.referenceCoordinate) {
                            Image(systemName: "flag.checkered")
                                .font(.subheadline.bold())
                                .foregroundStyle(.purple)
                                .padding(6)
                                .background(Circle().fill(.white))
                        }
                    }
                    if upperAltitudeGuideline.isEnabled {
                        MapPolygon(coordinates: UpperAltitudeGuideline.zoneA.boundary)
                            .foregroundStyle(.blue.opacity(0.03))
                            .stroke(.blue.opacity(0.5), lineWidth: 1)
                        MapPolygon(coordinates: UpperAltitudeGuideline.zoneB.boundary)
                            .foregroundStyle(.cyan.opacity(0.06))
                            .stroke(.cyan.opacity(0.6), lineWidth: 1.5)
                    }
                    ForEach(displayedPositions) { glider in
                        Annotation(viewModel.nameFor(index: glider.index), coordinate: glider.coordinate) {
                            GliderMarkerView(
                                glider: glider,
                                isSelected: selectedGlider?.id == glider.id,
                                isFavorite: favoritesStore.isFavorite(glider.imei),
                                alertSeverity: alertReasons(for: glider).overallSeverity
                            )
                                .onTapGesture {
                                    withAnimation { selectedGlider = glider }
                                }
                        }
                    }
                    UserAnnotation()
                }
                .mapControls {
                    MapCompass()
                    MapScaleView()
                    MapUserLocationButton()
                }
                .safeAreaInset(edge: .top) {
                    VStack(spacing: 6) {
                        if !activeAlertLabels.isEmpty {
                            activeAlertsIndicator
                        }
                        if !alertingGliders.isEmpty {
                            alertBanner
                        }
                    }
                }

                if let selectedGlider {
                    GliderDetailCard(
                        glider: selectedGlider,
                        baseName: viewModel.nameFor(index: selectedGlider.index),
                        alertReasons: alertReasons(for: selectedGlider)
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
                locationManager.requestAuthorizationIfNeeded()
                alertNotifier.requestAuthorizationIfNeeded()
            }
            .onDisappear {
                viewModel.stopPolling()
            }
            .onChange(of: viewModel.positions) { _, newPositions in
                centerMapIfNeeded(on: newPositions)
                notifyNewAlerts(in: newPositions)
            }
        }
    }

    private func centerMapIfNeeded(on positions: [GliderPosition]) {
        guard !didCenterInitially, !positions.isEmpty else { return }
        didCenterInitially = true
        let coordinates = positions.map(\.coordinate)
        cameraPosition = .region(MKCoordinateRegion(coordinates: coordinates))
    }

    /// Notifies once per glider each time it newly enters an alerting state
    /// (not on every poll while it stays alerting), and lets it notify again
    /// if it later clears and re-triggers. Checked against all known
    /// positions, not just the ones currently shown by the favorites filter.
    private func notifyNewAlerts(in positions: [GliderPosition]) {
        var currentlyAlertingIMEIs: Set<String> = []
        for glider in positions {
            let reasons = alertReasons(for: glider)
            guard !reasons.isEmpty else { continue }
            currentlyAlertingIMEIs.insert(glider.imei)
            if !previouslyAlertingIMEIs.contains(glider.imei) {
                alertNotifier.notify(gliderName: viewModel.nameFor(index: glider.index), reasons: reasons)
            }
        }
        previouslyAlertingIMEIs = currentlyAlertingIMEIs
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

    private var activeAlertsIndicator: some View {
        HStack(spacing: 6) {
            Image(systemName: "bell.fill")
            Text(activeAlertLabels.joined(separator: "・"))
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(.thinMaterial, in: Capsule())
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
        .padding(.top, 4)
    }

    private var alertBanner: some View {
        let worstSeverity = alertingGliders.compactMap { alertReasons(for: $0).overallSeverity }.max() ?? .caution
        // Reasons can now mean "too low" or "too high" depending on which
        // rule fired, so each glider lists its own reason labels rather
        // than sharing one blanket "high altitude" or "low altitude" title.
        let lines = alertingGliders.map { glider in
            "\(viewModel.nameFor(index: glider.index)): " + alertReasons(for: glider).map(\.label).joined(separator: "・")
        }
        return HStack(alignment: .top, spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text("高度アラート(\(worstSeverity.label)) " + lines.joined(separator: "、"))
                .font(.caption)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(.white)
        .background(worstSeverity == .warning ? .red : .orange, in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
        .padding(.top, 4)
    }
}
