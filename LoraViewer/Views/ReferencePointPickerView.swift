import SwiftUI
import MapKit

/// A standalone map for picking a coordinate by panning until a fixed
/// center pin sits where you want it, rather than typing latitude/longitude
/// by hand.
struct ReferencePointPickerView: View {
    @Binding var latitude: Double
    @Binding var longitude: Double

    @Environment(\.dismiss) private var dismiss
    @State private var cameraPosition: MapCameraPosition
    @State private var visibleRegion: MKCoordinateRegion?

    init(latitude: Binding<Double>, longitude: Binding<Double>, initialCoordinate: CLLocationCoordinate2D?) {
        _latitude = latitude
        _longitude = longitude

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
                    .foregroundStyle(.red)
                    .shadow(radius: 2)
                    .offset(y: -18) // the pin's tip, not its center, marks the point
                    .allowsHitTesting(false)
            }
            .navigationTitle("基準地点を選択")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                Text("地図を動かして、中央のピンの位置を基準地点に合わせてください")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    .background(.thinMaterial)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("キャンセル") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("この地点に設定") {
                        if let center = visibleRegion?.center {
                            latitude = center.latitude
                            longitude = center.longitude
                        }
                        dismiss()
                    }
                    .disabled(visibleRegion == nil)
                }
            }
        }
    }
}
