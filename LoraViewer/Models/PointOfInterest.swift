import Foundation
import CoreLocation

/// A pilot-named ground location worth remembering — e.g. a spot known to
/// reliably produce thermals ("赤屋根") — shared across everyone using the
/// app, the same way nicknames are.
struct PointOfInterest: Identifiable, Codable, Equatable {
    let id: String
    var name: String
    var latitude: Double
    var longitude: Double

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    init(id: String = UUID().uuidString, name: String, coordinate: CLLocationCoordinate2D) {
        self.id = id
        self.name = name
        self.latitude = coordinate.latitude
        self.longitude = coordinate.longitude
    }
}
