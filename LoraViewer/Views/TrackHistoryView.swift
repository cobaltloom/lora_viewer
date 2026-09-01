import SwiftUI
import MapKit

struct TrackHistoryView: View {
    let settings: APISettings

    @State private var startDate = Calendar.current.startOfDay(for: Date())
    @State private var endDate = Date()
    @State private var trackData: [String: TrackLogDevice] = [:]
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var cameraPosition: MapCameraPosition = .automatic

    private let api: TrailRouteAPI

    init(settings: APISettings) {
        self.settings = settings
        self.api = TrailRouteAPI(settings: settings)
    }

    var body: some View {
        VStack(spacing: 0) {
            Form {
                DatePicker("開始", selection: $startDate)
                DatePicker("終了", selection: $endDate)
                Button {
                    Task { await search() }
                } label: {
                    if isLoading {
                        ProgressView()
                    } else {
                        Text("検索する")
                    }
                }
                .disabled(isLoading)
            }
            .frame(height: 220)

            Map(position: $cameraPosition) {
                ForEach(Array(trackData.keys), id: \.self) { imei in
                    let points = trackData[imei]?.positionLog ?? []
                    MapPolyline(coordinates: points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) })
                        .stroke(colorFor(imei: imei), lineWidth: 3)
                    ForEach(points) { point in
                        Annotation("", coordinate: CLLocationCoordinate2D(latitude: point.lat, longitude: point.lon)) {
                            Circle()
                                .fill(colorFor(imei: imei))
                                .frame(width: 6, height: 6)
                        }
                    }
                }
            }
        }
        .navigationTitle("行動軌跡")
        .alert("エラー", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func search() async {
        isLoading = true
        defer { isLoading = false }
        do {
            trackData = try await api.fetchTrackLog(start: startDate, end: endDate)
            let allCoordinates = trackData.values.flatMap { device in
                device.positionLog.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
            }
            if !allCoordinates.isEmpty {
                cameraPosition = .region(MKCoordinateRegion(coordinates: allCoordinates))
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func colorFor(imei: String) -> Color {
        let palette: [Color] = [.cyan, .pink, .green, .orange, .blue, .purple, .red, .yellow]
        let index = abs(imei.hashValue) % palette.count
        return palette[index]
    }
}
