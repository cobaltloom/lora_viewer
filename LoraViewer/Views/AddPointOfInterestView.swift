import SwiftUI
import MapKit

/// A standalone map for adding a named point of interest (e.g. a reliable
/// thermal spot) by panning until a fixed center pin sits where you want
/// it, then naming it.
struct AddPointOfInterestView: View {
    @EnvironmentObject private var pointOfInterestStore: PointOfInterestStore
    @Environment(\.dismiss) private var dismiss
    @State private var cameraPosition: MapCameraPosition
    @State private var visibleRegion: MKCoordinateRegion?
    @State private var name = ""

    init(initialCoordinate: CLLocationCoordinate2D?) {
        if let initialCoordinate {
            let region = MKCoordinateRegion(
                center: initialCoordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
            )
            _cameraPosition = State(initialValue: .region(region))
            _visibleRegion = State(initialValue: region)
        } else {
            _cameraPosition = State(initialValue: .automatic)
            _visibleRegion = State(initialValue: nil)
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Map(position: $cameraPosition)
                    .onMapCameraChange { context in
                        visibleRegion = context.region
                    }

                Image(systemName: "mappin")
                    .font(.system(size: 36))
                    .foregroundStyle(.brown)
                    .shadow(radius: 2)
                    .offset(y: -18)
                    .allowsHitTesting(false)
            }
            .navigationTitle("地点を追加")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 10) {
                    Text("地図を動かして、中央のピンの位置を合わせてください")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    TextField("名前(例: 赤屋根)", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .padding(.horizontal)
                }
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity)
                .background(.thinMaterial)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("キャンセル") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        if let center = visibleRegion?.center {
                            pointOfInterestStore.addPoint(name: name, coordinate: center)
                        }
                        dismiss()
                    }
                    .disabled(visibleRegion == nil || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
