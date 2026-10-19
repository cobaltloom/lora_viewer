import CoreLocation

/// Thin wrapper whose only job is to trigger the system location-permission
/// prompt, the first time the user asks to see their location. Once
/// authorized, MapKit's own `UserAnnotation()` (shown only while the map's
/// location button is on) handles showing it without this class doing
/// anything further.
final class LocationManager: NSObject, ObservableObject {
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
    }

    func requestAuthorizationIfNeeded() {
        guard manager.authorizationStatus == .notDetermined else { return }
        manager.requestWhenInUseAuthorization()
    }
}

extension LocationManager: CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {}
}
