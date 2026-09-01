import MapKit

extension MKCoordinateRegion {
    /// A region that fits all given coordinates, with generous padding.
    init(coordinates: [CLLocationCoordinate2D], paddingFactor: Double = 1.4) {
        guard !coordinates.isEmpty else {
            self = MKCoordinateRegion(
                center: CLLocationCoordinate2D(latitude: 0, longitude: 0),
                span: MKCoordinateSpan(latitudeDelta: 1, longitudeDelta: 1)
            )
            return
        }

        var minLat = coordinates[0].latitude
        var maxLat = coordinates[0].latitude
        var minLon = coordinates[0].longitude
        var maxLon = coordinates[0].longitude
        for c in coordinates {
            minLat = min(minLat, c.latitude)
            maxLat = max(maxLat, c.latitude)
            minLon = min(minLon, c.longitude)
            maxLon = max(maxLon, c.longitude)
        }

        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLon + maxLon) / 2)
        let span = MKCoordinateSpan(
            latitudeDelta: max((maxLat - minLat) * paddingFactor, 0.01),
            longitudeDelta: max((maxLon - minLon) * paddingFactor, 0.01)
        )
        self.init(center: center, span: span)
    }
}
