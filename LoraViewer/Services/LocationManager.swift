import CoreLocation

/// Thin wrapper whose only job is to trigger the system location-permission
/// prompt. Once authorized, MapKit's own `UserAnnotation()` and
/// `MapUserLocationButton()` handle showing and centering on the user's
/// location without this class doing anything further.
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
