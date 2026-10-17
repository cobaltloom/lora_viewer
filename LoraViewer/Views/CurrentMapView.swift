import SwiftUI
import MapKit
import CoreLocation

struct CurrentMapView: View {
    @EnvironmentObject var settings: APISettings
    @EnvironmentObject private var nicknameStore: NicknameStore
    @EnvironmentObject private var pointOfInterestStore: PointOfInterestStore
    @EnvironmentObject private var favoritesStore: FavoritesStore
    @EnvironmentObject private var alertSettings: AlertSettings
    @EnvironmentObject private var competitionGuideline: CompetitionAltitudeGuideline
    @EnvironmentObject private var upperAltitudeGuideline: UpperAltitudeGuideline
    @EnvironmentObject private var proximityAlertSettings: ProximityAlertSettings
    @EnvironmentObject private var subscriptionManager: SubscriptionManager
    @StateObject private var viewModel: GliderTrackerViewModel
    @StateObject private var locationManager = LocationManager()
    @StateObject private var alertNotifier = AlertNotifier()
    @StateObject private var turnpointPassageLog = TurnpointPassageLog()
    @AppStorage("showGliderTrails") private var showGliderTrails = true
    @AppStorage("showDistanceReferencePoints") private var showDistanceReferencePoints = false
    @AppStorage("showKK43Area") private var showKK43Area = false
    @AppStorage("mapStyleIsSatellite") private var mapStyleIsSatellite = false
    @State private var showAddPointOfInterest = false
    @State private var pointOfInterestPendingDeletion: PointOfInterest?
    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var selectedGlider: GliderPosition?
    /// Each timed glider's progress around the selected task course (see `updateLapStatuses`).
    @State private var lapStatuses: [String: TaskLapStatus] = [:]
    /// "imei|time" of starts/finishes already logged (finishes also notified), so each is reported once.
    @State private var reportedLapEvents: Set<String> = []
    @State private var lapCache = TaskLapCache()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    /// Launch-time LoRa/OGN chooser, so people notice OGN is available at all. Shown once per launch
    /// until "次回から表示しない" is checked.
    @AppStorage(DataSourcePromptView.suppressedKey) private var dataSourcePromptSuppressed = false
    @State private var showDataSourcePrompt = false
    @State private var hasOfferedDataSourcePrompt = false
    @State private var showPaywall = false
    @State private var showTurnpointHistory = false
    @State private var didCenterInitially = false
    @State private var showFavoritesOnly = false
    /// The visible map region's center, kept up to date so distance labels
    /// on the alert circles can be placed on whichever side of the circle
    /// faces the visible area, instead of a fixed compass point that can
    /// pan or zoom out of view.
    @State private var visibleRegionCenter: CLLocationCoordinate2D = CompetitionAltitudeGuideline.referenceCoordinate
    @State private var previouslyAlertingIMEIs: Set<String> = []
    /// "imei|turnpoint name" keys for gliders currently inside a
    /// turnpoint's sector, so passage notifications fire once on entry
    /// rather than repeatedly while a glider lingers inside.
    @State private var glidersInsideTurnpoints: Set<String> = []
    /// Proximity reasons (see `ProximityAlertSettings`) for the most recent
    /// position update, keyed by glider imei, merged into `alertReasons(for:)`.
    @State private var proximityReasonsByIMEI: [String: [GliderAlertReason]] = [:]
    /// Each glider pair's horizontal distance (meters) as of the previous
    /// position update, keyed by a sorted "imei1|imei2", so a new update can
    /// tell whether a pair is closing rather than just currently near.
    @State private var previousProximityDistancesM: [String: Double] = [:]
    /// Pair keys currently past the proximity warning threshold and
    /// closing, so the push notification fires once per approach rather
    /// than every update while the pair remains close.
    @State private var proximityWarningPairs: Set<String> = []

    init(settings: APISettings) {
        _viewModel = StateObject(wrappedValue: GliderTrackerViewModel(settings: settings))
    }

    /// The tapped glider as of the latest poll — `selectedGlider` is a
    /// snapshot from tap time, so reading it directly would leave the detail
    /// card showing a stale altitude/time while the marker keeps updating.
    private var liveSelectedGlider: GliderPosition? {
        guard let selectedGlider else { return nil }
        return viewModel.positions.first { $0.imei == selectedGlider.imei } ?? selectedGlider
    }

    private var displayedPositions: [GliderPosition] {
        guard showFavoritesOnly else { return viewModel.positions }
        let favorites = viewModel.positions.filter { favoritesStore.isFavorite($0.imei) }
        // Don't leave the map blank if the last favorite just got un-favorited.
        return favorites.isEmpty ? viewModel.positions : favorites
    }

    private var alertReferenceCoordinate: CLLocationCoordinate2D {
        alertSettings.referenceCoordinate
    }

    /// The three altitude-based alert reasons only (not proximity) — kept
    /// separate so `notifyNewAlerts` can debounce and word its notification
    /// around altitude specifically, independent of the proximity feature's
    /// own dedicated notification/debounce.
    private func altitudeAlertReasons(for glider: GliderPosition) -> [GliderAlertReason] {
        // All three altitude-alert features are subscriber-only, regardless
        // of whether their individual settings are switched on — otherwise
        // someone could enable them during a trial and keep the alerts
        // after lapsing.
        guard subscriptionManager.hasFullAccess else { return [] }
        var reasons: [GliderAlertReason] = []
        // When one or more gliders are favorited, only they are checked against the custom
        // altitude alert and competition guideline — otherwise, e.g. a glider flying from a
        // different field than the one these are configured for triggers noise notifications
        // for gliders the person tracking them doesn't actually care about. With no favorites
        // set, every glider is checked, same as before this distinction existed.
        // Both are Menuma-specific, so only for gliders within 15 km of the field center.
        if favoritesStore.favoriteIMEIs.isEmpty || favoritesStore.isFavorite(glider.imei),
           CompetitionAltitudeGuideline.isWithinAlertRadius(glider) {
            if let severity = alertSettings.alertSeverity(for: glider) {
                reasons.append(GliderAlertReason(label: "カスタム設定", severity: severity))
            }
            if competitionGuideline.isBelowGuideline(glider, minimumFlyingAltitudeM: alertSettings.minimumFlyingAltitudeM) {
                reasons.append(GliderAlertReason(label: "競技会ガイドライン", severity: .warning))
            }
        }
        if upperAltitudeGuideline.exceedsCeiling(glider), let zone = upperAltitudeGuideline.applicableZone(for: glider) {
            reasons.append(GliderAlertReason(label: "\(zone.name)上限超過", severity: .warning))
        }
        return reasons
    }

    /// All alert reasons — altitude-based plus proximity — used for the
    /// map's ring coloring, the active-alert banner, and the "safe" ring
    /// state. Push notifications are handled separately per reason type
    /// (`notifyNewAlerts`, `updateProximityAlerts`) so each keeps its own
    /// wording and debounce.
    private func alertReasons(for glider: GliderPosition) -> [GliderAlertReason] {
        altitudeAlertReasons(for: glider) + (proximityReasonsByIMEI[glider.imei] ?? [])
    }

    private var alertingGliders: [GliderPosition] {
        displayedPositions.filter { !alertReasons(for: $0).isEmpty }
    }

    /// Whether to show the marker's "safe margin" ring: the custom
    /// low-altitude rule is on, this glider is currently flying, and it
    /// isn't triggering that rule (or any other alert) right now.
    private func isReturnGlideSafe(_ glider: GliderPosition) -> Bool {
        guard subscriptionManager.hasFullAccess, alertSettings.isEnabled else { return false }
        guard let alt = glider.alt, alt > alertSettings.minimumFlyingAltitudeM else { return false }
        return alertReasons(for: glider).isEmpty
    }

    /// Short labels for whichever altitude alerts are currently turned on,
    /// so it's visible at a glance without opening Settings. Empty when
    /// neither alert is enabled.
    private var activeAlertLabels: [String] {
        guard subscriptionManager.hasFullAccess else { return [] }
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
                    if subscriptionManager.hasFullAccess, alertSettings.isEnabled {
                        if alertSettings.mode == .steps {
                            ForEach(alertSettings.steps) { step in
                                MapCircle(center: alertReferenceCoordinate, radius: step.distanceKm * 1000)
                                    .foregroundStyle(.red.opacity(0.04))
                                    .stroke(.red.opacity(0.4), lineWidth: 1)
                                Annotation("", coordinate: alertReferenceCoordinate.pointOnCircle(radiusMeters: step.distanceKm * 1000, towards: visibleRegionCenter)) {
                                    distanceLabel(step.distanceKm)
                                }
                            }
                        }
                        Annotation("基準地点", coordinate: alertReferenceCoordinate) {
                            Image(systemName: "flag.circle.fill")
                                .font(.title2)
                                .foregroundStyle(.red)
                                .background(Circle().fill(.white))
                        }
                    }
                    if subscriptionManager.hasFullAccess, competitionGuideline.isEnabled {
                        MapCircle(
                            center: CompetitionAltitudeGuideline.referenceCoordinate,
                            radius: CompetitionAltitudeGuideline.innerRadiusKm * 1000
                        )
                            .foregroundStyle(.purple.opacity(0.06))
                            .stroke(.purple.opacity(0.5), lineWidth: 1)
                        Annotation("", coordinate: CompetitionAltitudeGuideline.referenceCoordinate.pointOnCircle(radiusMeters: CompetitionAltitudeGuideline.innerRadiusKm * 1000, towards: visibleRegionCenter)) {
                            distanceLabel(CompetitionAltitudeGuideline.innerRadiusKm)
                        }
                        ForEach(CompetitionAltitudeGuideline.boundaryDistancesKm.filter { $0 > CompetitionAltitudeGuideline.innerRadiusKm }, id: \.self) { km in
                            MapCircle(center: CompetitionAltitudeGuideline.referenceCoordinate, radius: km * 1000)
                                .foregroundStyle(.clear)
                                .stroke(.purple.opacity(0.35), lineWidth: 1)
                            Annotation("", coordinate: CompetitionAltitudeGuideline.referenceCoordinate.pointOnCircle(radiusMeters: km * 1000, towards: visibleRegionCenter)) {
                                distanceLabel(km)
                            }
                        }
                        Annotation("競技会基準地点", coordinate: CompetitionAltitudeGuideline.referenceCoordinate) {
                            Image(systemName: "flag.checkered")
                                .font(.subheadline.bold())
                                .foregroundStyle(.purple)
                                .padding(6)
                                .background(Circle().fill(.white))
                        }
                    }
                    if subscriptionManager.hasFullAccess, competitionGuideline.showTaskCourse {
                        ForEach(CompetitionTaskCourseData.turnpointDisplayOrder, id: \.self) { name in
                            if let coordinate = CompetitionTaskCourseData.turnpoints[name] {
                                Annotation(name, coordinate: coordinate) {
                                    Image(systemName: "arrowshape.turn.up.right.circle.fill")
                                        .font(.subheadline.bold())
                                        .foregroundStyle(.orange)
                                        .padding(4)
                                        .background(Circle().fill(.white))
                                }
                            }
                        }
                        if let selectedCourseIndex = competitionGuideline.selectedCourseIndex,
                           CompetitionTaskCourseData.courses.indices.contains(selectedCourseIndex) {
                            MapPolyline(coordinates: CompetitionTaskCourseData.coordinates(for: CompetitionTaskCourseData.courses[selectedCourseIndex]))
                                .stroke(.orange, lineWidth: 2)
                        }
                    }
                    if subscriptionManager.hasFullAccess, upperAltitudeGuideline.isEnabled {
                        MapPolygon(coordinates: UpperAltitudeGuideline.zoneA.boundary)
                            .foregroundStyle(.blue.opacity(0.03))
                            .stroke(.blue.opacity(0.5), lineWidth: 1)
                        MapPolygon(coordinates: UpperAltitudeGuideline.zoneB.boundary)
                            .foregroundStyle(.cyan.opacity(0.06))
                            .stroke(.cyan.opacity(0.6), lineWidth: 1.5)
                    }
                    if subscriptionManager.hasFullAccess, showKK43Area {
                        MapPolygon(coordinates: CivilTrainingAreaKK43.boundary)
                            .foregroundStyle(.orange.opacity(0.05))
                            .stroke(.orange.opacity(0.6), lineWidth: 1.5)
                    }
                    if showDistanceReferencePoints {
                        ForEach(DistanceReferencePointData.points) { point in
                            Annotation(point.name, coordinate: point.coordinate) {
                                Text(String(point.name.first ?? "?"))
                                    .font(.system(size: 9, weight: .bold))
                                    .foregroundStyle(.white)
                                    .padding(5)
                                    .background(Circle().fill(.indigo))
                            }
                        }
                    }
                    ForEach(pointOfInterestStore.points) { point in
                        Annotation(point.name, coordinate: point.coordinate) {
                            Image(systemName: "mappin.circle.fill")
                                .font(.title3)
                                .foregroundStyle(.brown)
                                .background(Circle().fill(.white))
                                .onTapGesture {
                                    pointOfInterestPendingDeletion = point
                                }
                        }
                    }
                    if showGliderTrails {
                        ForEach(displayedPositions) { glider in
                            if let trail = viewModel.trails[glider.imei], trail.count > 1 {
                                MapPolyline(coordinates: trail)
                                    .stroke(colorFor(imei: glider.imei), lineWidth: 2)
                                Annotation("", coordinate: trail[trail.count / 2]) {
                                    gliderNameLabel(for: glider)
                                }
                            }
                        }
                    }
                    ForEach(displayedPositions) { glider in
                        Annotation(displayName(for: glider), coordinate: glider.coordinate) {
                            GliderMarkerView(
                                glider: glider,
                                isSelected: selectedGlider?.id == glider.id,
                                isFavorite: favoritesStore.isFavorite(glider.imei),
                                alertSeverity: alertReasons(for: glider).overallSeverity,
                                isReturnGlideSafe: isReturnGlideSafe(glider),
                                lapBadge: selectedTaskCourse == nil ? nil : lapStatuses[glider.imei]?.markerBadge
                            )
                                .onTapGesture {
                                    withAnimation { selectedGlider = glider }
                                }
                        }
                    }
                    UserAnnotation()
                }
                // Flat, not 3D terrain: the Menuma area gains nothing visible from it, and rendering terrain
                // kept the GPU busy enough to make phones run hot.
                .mapStyle(mapStyleIsSatellite ? .hybrid(elevation: .flat) : .standard(elevation: .flat))
                .mapControls {
                    MapCompass()
                    MapScaleView()
                    MapUserLocationButton()
                }
                // Only once a pan/zoom ends: updating on every frame rebuilt the whole map content each frame.
                .onMapCameraChange(frequency: .onEnd) { context in
                    visibleRegionCenter = context.region.center
                }
                .overlay(alignment: .bottomLeading) {
                    if viewModel.dataSource == .ogn {
                        Text(OGNAPI.attribution)
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(.thinMaterial, in: Capsule())
                            .padding(.leading, 8)
                            .padding(.bottom, 28)
                    }
                }
                .safeAreaInset(edge: .top) {
                    VStack(spacing: 6) {
                        if !subscriptionManager.isSubscribed, subscriptionManager.isFreePeriodActive,
                           let freeAccessUntil = subscriptionManager.freeAccessUntil {
                            freePeriodIndicator(until: freeAccessUntil)
                        }
                        if !activeAlertLabels.isEmpty {
                            activeAlertsIndicator
                        }
                        if !alertingGliders.isEmpty {
                            alertBanner
                        }
                    }
                }

                if let selectedGlider = liveSelectedGlider {
                    GliderDetailCard(
                        glider: selectedGlider,
                        baseName: viewModel.nameFor(selectedGlider),
                        alertReasons: alertReasons(for: selectedGlider),
                        lapSummary: selectedTaskCourse.flatMap { course in
                            lapStatuses[selectedGlider.imei]?.summary(courseDistanceKm: course.distanceKm, now: Date())
                        }
                    )
                        .padding()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .onTapGesture {
                            withAnimation { self.selectedGlider = nil }
                        }
                }
            }
            .navigationTitle(viewModel.dataSource == .ogn ? DataSource.ogn.displayName : (viewModel.config?.settings.siteTitle ?? "LoRa妻沼"))
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
                        if subscriptionManager.hasFullAccess {
                            toggleFavoritesOnly()
                        } else {
                            showPaywall = true
                        }
                    } label: {
                        Image(systemName: showFavoritesOnly ? "star.circle.fill" : "star.circle")
                    }
                    .disabled(subscriptionManager.hasFullAccess && !viewModel.positions.contains { favoritesStore.isFavorite($0.imei) })
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        mapStyleIsSatellite.toggle()
                    } label: {
                        Image(systemName: mapStyleIsSatellite ? "map.fill" : "globe.americas.fill")
                    }
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
                    Button {
                        showAddPointOfInterest = true
                    } label: {
                        Label("地点を追加", systemImage: "mappin.and.ellipse")
                    }
                    Button {
                        if subscriptionManager.hasFullAccess {
                            showTurnpointHistory = true
                        } else {
                            showPaywall = true
                        }
                    } label: {
                        Label("通過履歴", systemImage: "list.bullet.clipboard")
                    }
                }
            }
            .sheet(isPresented: $showDataSourcePrompt) {
                DataSourcePromptView()
                    .environmentObject(settings)
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
                    .environmentObject(settings)
            }
            .sheet(isPresented: $showPaywall) {
                PaywallView()
            }
            .sheet(isPresented: $showAddPointOfInterest) {
                AddPointOfInterestView(initialCoordinate: visibleRegionCenter)
            }
            .confirmationDialog(
                "「\(pointOfInterestPendingDeletion?.name ?? "")」を削除しますか?",
                isPresented: Binding(
                    get: { pointOfInterestPendingDeletion != nil },
                    set: { if !$0 { pointOfInterestPendingDeletion = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("削除", role: .destructive) {
                    if let point = pointOfInterestPendingDeletion {
                        pointOfInterestStore.deletePoint(point)
                    }
                    pointOfInterestPendingDeletion = nil
                }
                Button("キャンセル", role: .cancel) {
                    pointOfInterestPendingDeletion = nil
                }
            }
            .sheet(isPresented: $showTurnpointHistory) {
                NavigationStack {
                    TurnpointPassageHistoryView(log: turnpointPassageLog)
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                Button("閉じる") { showTurnpointHistory = false }
                            }
                        }
                }
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
                viewModel.groundAltitudeThresholdM = alertSettings.minimumFlyingAltitudeM
                favoritesStore.dataSource = settings.effectiveDataSource
                turnpointPassageLog.dataSource = settings.effectiveDataSource
                viewModel.startPolling()
                offerDataSourcePromptIfNeeded()
                locationManager.requestAuthorizationIfNeeded()
                alertNotifier.requestAuthorizationIfNeeded()
            }
            .onDisappear {
                viewModel.stopPolling()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { viewModel.saveTrailsNow() }
            }
            .onChange(of: alertSettings.minimumFlyingAltitudeM) { _, newValue in
                viewModel.groundAltitudeThresholdM = newValue
            }
            .onChange(of: settings.isOGNAvailable) {
                // OGN availability comes from Firestore and can arrive after launch.
                offerDataSourcePromptIfNeeded()
            }
            .onChange(of: settings.effectiveDataSource) {
                favoritesStore.dataSource = settings.effectiveDataSource
                turnpointPassageLog.dataSource = settings.effectiveDataSource
                // Fetch from the new source right away instead of waiting out the current interval.
                selectedGlider = nil
                viewModel.startPolling()
            }
            .onChange(of: viewModel.backfilledFlights?.id) {
                if let backfilled = viewModel.backfilledFlights {
                    recordPastTurnpointPassages(backfilled)
                }
                updateLapStatuses()
            }
            .onChange(of: viewModel.positions) { _, newPositions in
                centerMapIfNeeded(on: newPositions)
                updateProximityAlerts(in: newPositions)
                notifyNewAlerts(in: newPositions)
                notifyTurnpointPassages(in: newPositions)
                updateLapStatuses()
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
            let reasons = altitudeAlertReasons(for: glider)
            guard !reasons.isEmpty else { continue }
            currentlyAlertingIMEIs.insert(glider.imei)
            if !previouslyAlertingIMEIs.contains(glider.imei) {
                alertNotifier.notify(gliderName: displayName(for: glider), reasons: reasons)
            }
        }
        previouslyAlertingIMEIs = currentlyAlertingIMEIs
    }

    /// Notifies once per glider each time it newly enters a turnpoint's
    /// sector (excluding 管理ポイント — see
    /// `CompetitionTaskCourseData.notifiableTurnpointNames`), and lets it
    /// notify again on a later lap once it leaves and re-enters. Also
    /// records the event to `turnpointPassageLog` so it can be reviewed
    /// in-app if the push notification is missed. The sector is the true
    /// 90° wedge from JSAL rule 43 (bisecting the selected task course's
    /// incoming and outgoing legs at that turnpoint — see
    /// `CompetitionTaskCourseData.sectorBearing`), so this requires a task
    /// course to be selected: with no course selected ("旋回点のみ") there's
    /// no leg geometry to derive a sector from, and nothing is
    /// notified/recorded. A subscriber-only feature.
    private func notifyTurnpointPassages(in positions: [GliderPosition]) {
        guard subscriptionManager.hasFullAccess, competitionGuideline.showTaskCourse,
              let selectedCourseIndex = competitionGuideline.selectedCourseIndex,
              CompetitionTaskCourseData.courses.indices.contains(selectedCourseIndex)
        else { return }
        let selectedCourse = CompetitionTaskCourseData.courses[selectedCourseIndex]
        var currentlyInside: Set<String> = []
        for glider in positions {
            let gliderCoordinate = CLLocationCoordinate2D(latitude: glider.lat, longitude: glider.lon)
            for name in Self.turnpointSectors(containing: gliderCoordinate, in: selectedCourse) {
                let key = "\(glider.imei)|\(name)"
                currentlyInside.insert(key)
                if !glidersInsideTurnpoints.contains(key) {
                    let gliderName = displayName(for: glider)
                    alertNotifier.notifyTurnpointPassage(gliderName: gliderName, turnpointName: name, altitudeM: glider.alt)
                    turnpointPassageLog.record(gliderName: gliderName, turnpointName: name, altitudeM: glider.alt)
                }
            }
        }
        glidersInsideTurnpoints = currentlyInside
    }

    private func offerDataSourcePromptIfNeeded() {
        guard settings.isOGNAvailable, !dataSourcePromptSuppressed, !hasOfferedDataSourcePrompt else { return }
        hasOfferedDataSourcePrompt = true
        showDataSourcePrompt = true
    }

    /// The task course chosen in the competition guideline settings, if the course is shown.
    private var selectedTaskCourse: CompetitionTaskCourse? {
        guard subscriptionManager.hasFullAccess, competitionGuideline.showTaskCourse,
              let index = competitionGuideline.selectedCourseIndex,
              CompetitionTaskCourseData.courses.indices.contains(index) else { return nil }
        return CompetitionTaskCourseData.courses[index]
    }

    /// Re-times every flying glider around the selected course and logs/announces new starts and
    /// finishes. Reference only: official timing uses each glider's 1-second IGC logger.
    private func updateLapStatuses() {
        guard let course = selectedTaskCourse else {
            lapStatuses = [:]
            return
        }
        let now = Date()
        let statuses = lapCache.statuses(for: viewModel.timedTracks, course: course)
        for (id, status) in statuses {
            guard let glider = viewModel.positions.first(where: { $0.imei == id }) else { continue }
            let gliderName = displayName(for: glider)
            // Logged only once the first turnpoint is reached, so a flight that lands without
            // rounding any turnpoint leaves no stray "スタート" in the history.
            if let start = status.start, !status.passedTurnpoints.isEmpty, reportedLapEvents.insert("\(id)|start|\(start.timeIntervalSince1970)").inserted {
                turnpointPassageLog.recordPastPassage(gliderName: gliderName, turnpointName: "スタート", altitudeM: status.startAltitudeM, timestamp: start)
            }
            guard let finish = status.finish,
                  reportedLapEvents.insert("\(id)|finish|\(finish.timeIntervalSince1970)").inserted else { continue }
            let summary = String(status.summary(courseDistanceKm: course.distanceKm, now: now).dropFirst("周回: ".count))
            turnpointPassageLog.recordPastPassage(gliderName: gliderName, turnpointName: TurnpointPassageRecord.finishPrefix + summary, altitudeM: status.finishAltitudeM, timestamp: finish)
            // Only announce a finish that just happened - not one found in history on reopening.
            if now.timeIntervalSince(finish) < 5 * 60 {
                alertNotifier.notifyTaskFinish(gliderName: gliderName, summary: summary)
            }
        }
        lapStatuses = statuses
    }

    /// Names of the selected course's turnpoints whose 90° sector (within the turnpoint radius)
    /// contains `coordinate`.
    private static func turnpointSectors(containing coordinate: CLLocationCoordinate2D, in course: CompetitionTaskCourse) -> [String] {
        let location = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        return CompetitionTaskCourseData.notifiableTurnpointNames.filter { name in
            guard let turnpoint = CompetitionTaskCourseData.turnpoints[name],
                  let bisector = CompetitionTaskCourseData.sectorBearing(in: course, turnpointName: name) else { return false }
            let distanceKm = CLLocation(latitude: turnpoint.latitude, longitude: turnpoint.longitude).distance(from: location) / 1000.0
            guard distanceKm <= CompetitionTaskCourseData.turnpointRadiusKm else { return false }
            return CompetitionTaskCourseData.isBearing(turnpoint.bearingDegrees(to: coordinate), withinSectorCenteredOn: bisector)
        }
    }

    /// Adds turnpoint passages flown before the app was opened, found by walking each flying
    /// glider's track from the track server. Logged at the time they happened; no notification,
    /// since a ping about a passage from long ago would only confuse.
    private func recordPastTurnpointPassages(_ backfilled: BackfilledFlights) {
        guard subscriptionManager.hasFullAccess, competitionGuideline.showTaskCourse,
              let selectedCourseIndex = competitionGuideline.selectedCourseIndex,
              CompetitionTaskCourseData.courses.indices.contains(selectedCourseIndex)
        else { return }
        let course = CompetitionTaskCourseData.courses[selectedCourseIndex]
        for (id, points) in backfilled.flights {
            guard let glider = viewModel.positions.first(where: { $0.imei == id }) else { continue }
            let gliderName = displayName(for: glider)
            var inside: Set<String> = []
            for point in points {
                let now = Set(Self.turnpointSectors(containing: point.coordinate, in: course))
                for name in now.subtracting(inside) {
                    turnpointPassageLog.recordPastPassage(gliderName: gliderName, turnpointName: name, altitudeM: point.altitudeM, timestamp: point.time)
                }
                inside = now
            }
        }
    }

    /// Detects gliders getting close to each other (see
    /// `ProximityAlertSettings`) and updates `proximityReasonsByIMEI` so the
    /// map's ring coloring picks it up like any other alert reason. A pair
    /// within the warning distance fires a push notification too, but only
    /// once per continuous approach, and only while actually closing —
    /// gliders sharing a thermal are commonly close together without being
    /// on a collision course, so "just nearby" alone only shows quietly on
    /// the map (`.caution`), never as a notification. A subscriber-only
    /// feature.
    private func updateProximityAlerts(in positions: [GliderPosition]) {
        guard subscriptionManager.hasFullAccess, proximityAlertSettings.isEnabled else {
            proximityReasonsByIMEI = [:]
            previousProximityDistancesM = [:]
            proximityWarningPairs = []
            return
        }
        let flying = positions.filter { ($0.alt ?? 0) > alertSettings.minimumFlyingAltitudeM }
        let referenceLocation = CLLocation(latitude: alertSettings.referenceCoordinate.latitude, longitude: alertSettings.referenceCoordinate.longitude)
        let patternRadiusM = proximityAlertSettings.patternExclusionRadiusKm * 1000
        let patternCeilingM = proximityAlertSettings.patternExclusionCeilingM
        var reasonsByIMEI: [String: [GliderAlertReason]] = [:]
        var currentDistancesM: [String: Double] = [:]
        var currentWarningPairs: Set<String> = []

        for i in flying.indices {
            for j in flying.indices where j > i {
                let gliderA = flying[i]
                let gliderB = flying[j]
                guard let altitudeA = gliderA.alt, let altitudeB = gliderB.alt else { continue }
                let altitudeDifferenceM = abs(altitudeA - altitudeB)
                guard altitudeDifferenceM <= proximityAlertSettings.maxAltitudeDifferenceM else { continue }

                let locationA = CLLocation(latitude: gliderA.lat, longitude: gliderA.lon)
                let locationB = CLLocation(latitude: gliderB.lat, longitude: gliderB.lon)
                let distanceM = locationA.distance(from: locationB)
                guard distanceM <= proximityAlertSettings.cautionDistanceM else { continue }

                // Near the field and low, gliders are routinely close and
                // converging by design (following each other around the
                // landing pattern) — cap at .caution there so a notification
                // doesn't fire on essentially every landing.
                let isInPattern: Bool = {
                    guard altitudeA <= patternCeilingM, altitudeB <= patternCeilingM else { return false }
                    return referenceLocation.distance(from: locationA) <= patternRadiusM
                        && referenceLocation.distance(from: locationB) <= patternRadiusM
                }()

                let pairKey = [gliderA.imei, gliderB.imei].sorted().joined(separator: "|")
                currentDistancesM[pairKey] = distanceM

                var severity: AlertSeverity = .caution
                if !isInPattern,
                   distanceM <= proximityAlertSettings.warningDistanceM,
                   let previousDistanceM = previousProximityDistancesM[pairKey],
                   distanceM < previousDistanceM {
                    severity = .warning
                    currentWarningPairs.insert(pairKey)
                    if !proximityWarningPairs.contains(pairKey) {
                        alertNotifier.notifyProximity(
                            gliderName: displayName(for: gliderA),
                            otherGliderName: displayName(for: gliderB),
                            distanceM: distanceM,
                            altitudeDifferenceM: altitudeDifferenceM
                        )
                    }
                }

                let distanceText = "\(Int(distanceM))m"
                reasonsByIMEI[gliderA.imei, default: []].append(GliderAlertReason(label: "\(displayName(for: gliderB))と接近(\(distanceText))", severity: severity, pairKey: pairKey))
                reasonsByIMEI[gliderB.imei, default: []].append(GliderAlertReason(label: "\(displayName(for: gliderA))と接近(\(distanceText))", severity: severity, pairKey: pairKey))
            }
        }

        proximityReasonsByIMEI = reasonsByIMEI
        previousProximityDistancesM = currentDistancesM
        proximityWarningPairs = currentWarningPairs
    }

    /// Small distance-in-km label used on the alert circles, e.g. "3.0km".
    private func distanceLabel(_ km: Double) -> some View {
        Text("\(km, specifier: "%.1f")km")
            .font(.system(size: 9, weight: .semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .background(.white.opacity(0.85), in: Capsule())
    }

    /// A per-glider trail color, distinct among gliders currently flying
    /// (see `GliderTrackerViewModel.trailColorSlots`) so trails can be told apart.
    private func colorFor(imei: String) -> Color {
        let palette: [Color] = [.cyan, .pink, .green, .orange, .blue, .purple, .red, .yellow]
        let index = viewModel.trailColorSlots[imei] ?? abs(imei.hashValue) % palette.count
        return palette[index % palette.count]
    }

    /// The pilot's nickname, if any, otherwise the server's base name — used
    /// anywhere a glider's name is shown or spoken (map labels, alerts,
    /// notifications), kept compact rather than combining both like the
    /// glider list does. Nicknames are a subscriber-only perk to view (not
    /// just to set), so this falls back to the base name without one.
    private func displayName(for glider: GliderPosition) -> String {
        let baseName = viewModel.nameFor(glider)
        guard subscriptionManager.hasFullAccess, !glider.isAnonymous else { return baseName }
        return nicknameStore.compactDisplayName(baseName: baseName, imei: glider.imei)
    }

    /// A small name tag placed at the midpoint of a glider's trail, colored
    /// to match it, so multiple simultaneous flights can be told apart at a
    /// glance instead of only by memorizing trail colors. Placed on the
    /// trail rather than next to the marker so it doesn't crowd the
    /// marker's own index number/altitude badge. Shown for every trail: the
    /// nickname if set, otherwise the server's base name.
    private func gliderNameLabel(for glider: GliderPosition) -> some View {
        Text(displayName(for: glider))
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(colorFor(imei: glider.imei))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(.white.opacity(0.9), in: Capsule())
            .overlay(Capsule().stroke(colorFor(imei: glider.imei), lineWidth: 1))
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

    private func freePeriodIndicator(until freeAccessUntil: Date) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "gift.fill")
            Text("全機能を無料開放中(\(freeAccessUntil.formatted(.dateTime.month().day().hour().minute())) まで)")
        }
        .font(.caption2)
        .foregroundStyle(.blue)
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(.thinMaterial, in: Capsule())
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
        .padding(.top, 4)
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
        // A proximity reason is recorded on both gliders in the pair (each
        // needs its own copy for the map ring/badge), so here we only
        // surface it once — on whichever glider is listed first — instead
        // of reporting the same pair from both directions.
        var shownProximityPairKeys: Set<String> = []
        let lines = alertingGliders.compactMap { glider -> String? in
            let reasons = alertReasons(for: glider).filter { reason in
                guard let pairKey = reason.pairKey else { return true }
                guard !shownProximityPairKeys.contains(pairKey) else { return false }
                shownProximityPairKeys.insert(pairKey)
                return true
            }
            guard !reasons.isEmpty else { return nil }
            return "\(displayName(for: glider)): " + reasons.map(\.label).joined(separator: "・")
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

/// Asks which position source to use, shown at launch while OGN is available (see
/// `CurrentMapView.offerDataSourcePromptIfNeeded`). Closing it without choosing keeps the current one.
struct DataSourcePromptView: View {
    static let suppressedKey = "dataSourcePromptSuppressed"

    @EnvironmentObject private var settings: APISettings
    @AppStorage(suppressedKey) private var suppressed = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("位置情報のデータ元を選んでください")
                .font(.headline)
            choice(.lora, description: "クラブのLoRa発信機を積んだ機体を表示します。")
            choice(.ogn, description: "FLARMやOGNトラッカーを積み、OGN(Open Glider Network)受信機の範囲内(妻沼から約150km)にいる機体を表示します。")
            Button {
                suppressed.toggle()
            } label: {
                Label("次回から表示しない", systemImage: suppressed ? "checkmark.square.fill" : "square")
            }
            .buttonStyle(.plain)
            Text("設定画面の「位置情報のデータ元」からいつでも変更できます。")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(24)
        .presentationDetents([.medium, .large])
    }

    private func choice(_ source: DataSource, description: String) -> some View {
        Button {
            settings.dataSource = source
            dismiss()
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(source.displayName).font(.title3.bold())
                    Spacer()
                    if settings.effectiveDataSource == source {
                        Text("選択中").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Text(description)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).stroke(settings.effectiveDataSource == source ? Color.accentColor : Color.secondary.opacity(0.4), lineWidth: settings.effectiveDataSource == source ? 2 : 1))
        }
        .buttonStyle(.plain)
    }
}
