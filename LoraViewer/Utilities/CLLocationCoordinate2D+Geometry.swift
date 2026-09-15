import CoreLocation

extension CLLocationCoordinate2D {
    /// Initial great-circle bearing (degrees, 0 = north, clockwise) from
    /// this coordinate toward `other`.
    func bearingDegrees(to other: CLLocationCoordinate2D) -> Double {
        let lat1 = latitude * .pi / 180
        let lat2 = other.latitude * .pi / 180
        let deltaLon = (other.longitude - longitude) * .pi / 180
        let y = sin(deltaLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        let bearingRad = atan2(y, x)
        return (bearingRad * 180 / .pi).truncatingRemainder(dividingBy: 360)
    }

    /// The point `distanceMeters` away from this coordinate, along the
    /// great circle in the direction `bearingDegrees` (0 = north, clockwise).
    func destination(distanceMeters: Double, bearingDegrees: Double) -> CLLocationCoordinate2D {
        let earthRadiusM = 6_371_000.0
        let bearingRad = bearingDegrees * .pi / 180
        let lat1 = latitude * .pi / 180
        let lon1 = longitude * .pi / 180
        let angularDistance = distanceMeters / earthRadiusM

        let lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearingRad))
        let lon2 = lon1 + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        return CLLocationCoordinate2D(latitude: lat2 * 180 / .pi, longitude: lon2 * 180 / .pi)
    }

    /// A point on the circle of `radiusMeters` around this coordinate,
    /// on the side facing `target`. Used to keep a label attached to a map
    /// circle on-screen: passing the current visible region's center as
    /// `target` slides the label around the circle as the map is panned or
    /// zoomed, instead of pinning it to one fixed compass point that can
    /// scroll out of view.
    func pointOnCircle(radiusMeters: Double, towards target: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        destination(distanceMeters: radiusMeters, bearingDegrees: bearingDegrees(to: target))
    }
}
