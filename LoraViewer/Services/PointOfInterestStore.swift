import Foundation
import FirebaseFirestore

/// Pilot-named ground locations worth remembering on the map — e.g. a spot
/// known to reliably produce thermals ("赤屋根") — synced through a
/// Firestore collection that anyone using the app can read and write, the
/// same way nicknames are (whoever adds one, everyone sees it). A local
/// UserDefaults copy keeps points available instantly on launch and while
/// offline.
final class PointOfInterestStore: ObservableObject {
    @Published private(set) var points: [PointOfInterest] {
        didSet { persist() }
    }

    private let storageKey = "pointsOfInterest"
    private let collectionName = "pointsOfInterest"
    private lazy var db = Firestore.firestore()
    private var listener: ListenerRegistration?

    init() {
        if let data = UserDefaults.standard.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([PointOfInterest].self, from: data) {
            points = decoded
        } else {
            points = []
        }
        listenForRemoteChanges()
    }

    deinit {
        listener?.remove()
    }

    func addPoint(name: String, coordinate: CLLocationCoordinate2D) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let point = PointOfInterest(name: trimmed, coordinate: coordinate)
        points.append(point)
        db.collection(collectionName).document(point.id).setData([
            "name": point.name,
            "latitude": point.latitude,
            "longitude": point.longitude,
            "updatedAt": FieldValue.serverTimestamp(),
        ])
    }

    func deletePoint(_ point: PointOfInterest) {
        points.removeAll { $0.id == point.id }
        db.collection(collectionName).document(point.id).delete()
    }

    /// Keeps every device in near-real-time agreement: when anyone adds or
    /// removes a point, everyone else's map updates automatically without
    /// needing to reopen the app.
    private func listenForRemoteChanges() {
        listener = db.collection(collectionName).addSnapshotListener { [weak self] snapshot, _ in
            guard let self, let snapshot else { return }
            for change in snapshot.documentChanges {
                let id = change.document.documentID
                switch change.type {
                case .added, .modified:
                    let data = change.document.data()
                    guard let name = data["name"] as? String,
                          let latitude = data["latitude"] as? Double,
                          let longitude = data["longitude"] as? Double
                    else { continue }
                    let point = PointOfInterest(id: id, name: name, coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
                    if let index = self.points.firstIndex(where: { $0.id == id }) {
                        self.points[index] = point
                    } else {
                        self.points.append(point)
                    }
                case .removed:
                    self.points.removeAll { $0.id == id }
                }
            }
        }
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(points) else { return }
        UserDefaults.standard.set(data, forKey: storageKey)
    }
}
