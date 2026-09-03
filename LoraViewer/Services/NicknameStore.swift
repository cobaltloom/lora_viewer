import Foundation
import FirebaseFirestore

/// User-assigned nicknames for gliders, keyed by IMEI. The site itself only
/// labels members "1.", "2." etc., so this lets pilots attach names they
/// actually recognize — and shares them: nicknames are synced through a
/// Firestore collection that anyone using the app can read and write, the
/// same way the club's paper/whiteboard roster works (whoever fills it in,
/// everyone sees it). A local UserDefaults copy keeps names available
/// instantly on launch and while offline.
final class NicknameStore: ObservableObject {
    @Published private var nicknames: [String: String] {
        didSet { persist() }
    }

    private let storageKey = "gliderNicknames"
    private let collectionName = "nicknames"
    private lazy var db = Firestore.firestore()
    private var listener: ListenerRegistration?

    init() {
        if let data = UserDefaults.standard.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            nicknames = decoded
        } else {
            nicknames = [:]
        }
        listenForRemoteChanges()
    }

    deinit {
        listener?.remove()
    }

    func nickname(forIMEI imei: String) -> String? {
        nicknames[imei]
    }

    func setNickname(_ name: String, forIMEI imei: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            nicknames.removeValue(forKey: imei)
            db.collection(collectionName).document(imei).delete()
        } else {
            nicknames[imei] = trimmed
            db.collection(collectionName).document(imei).setData([
                "nickname": trimmed,
                "updatedAt": FieldValue.serverTimestamp(),
            ])
        }
    }

    /// Keeps every device in sync in near-real-time: when anyone using the
    /// app renames a glider (by hand or via the whiteboard scan), everyone
    /// else's list updates automatically without needing to reopen the app.
    private func listenForRemoteChanges() {
        listener = db.collection(collectionName).addSnapshotListener { [weak self] snapshot, _ in
            guard let self, let snapshot else { return }
            for change in snapshot.documentChanges {
                let imei = change.document.documentID
                switch change.type {
                case .added, .modified:
                    if let name = change.document.data()["nickname"] as? String {
                        self.nicknames[imei] = name
                    }
                case .removed:
                    self.nicknames.removeValue(forKey: imei)
                }
            }
        }
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(nicknames) else { return }
        UserDefaults.standard.set(data, forKey: storageKey)
    }
}
