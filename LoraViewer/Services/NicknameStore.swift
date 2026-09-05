import Foundation
import FirebaseFirestore

/// Whether this device's nicknames follow the shared list, or are kept
/// purely local. `.manual` is an escape hatch: since the shared list has no
/// authentication (anyone with the app can write to it), a device can drop
/// out of sync to protect its own names from being overwritten by mischief
/// elsewhere, without affecting what other devices see.
enum NicknameSyncMode: String, Codable {
    case synced
    case manual
}

/// User-assigned nicknames for gliders, keyed by IMEI. The site itself only
/// labels members "1.", "2." etc., so this lets pilots attach names they
/// actually recognize — and, by default, shares them: nicknames are synced
/// through a Firestore collection that anyone using the app can read and
/// write, the same way the club's paper/whiteboard roster works (whoever
/// fills it in, everyone sees it). A local UserDefaults copy keeps names
/// available instantly on launch and while offline.
final class NicknameStore: ObservableObject {
    @Published private var nicknames: [String: String] {
        didSet { persist() }
    }
    @Published var syncMode: NicknameSyncMode {
        didSet {
            UserDefaults.standard.set(syncMode.rawValue, forKey: syncModeKey)
            applySyncMode()
        }
    }

    private let storageKey = "gliderNicknames"
    private let syncModeKey = "gliderNicknameSyncMode"
    private let collectionName = "nicknames"
    private let scheduleDocPath = ("meta", "nicknameClearSchedule")
    private lazy var db = Firestore.firestore()
    private var listener: ListenerRegistration?

    init() {
        if let data = UserDefaults.standard.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            nicknames = decoded
        } else {
            nicknames = [:]
        }
        let storedMode = UserDefaults.standard.string(forKey: syncModeKey)
        syncMode = NicknameSyncMode(rawValue: storedMode ?? "") ?? .synced
        applySyncMode()
    }

    deinit {
        listener?.remove()
    }

    func nickname(forIMEI imei: String) -> String? {
        nicknames[imei]
    }

    /// The server's base name plus a user-assigned nickname, if one is set
    /// (e.g. "7. 学連21") — the canonical way to show a glider's name
    /// anywhere a nickname should be reflected.
    func displayName(baseName: String, imei: String) -> String {
        guard let nickname = nickname(forIMEI: imei) else { return baseName }
        return "\(baseName) \(nickname)"
    }

    func setNickname(_ name: String, forIMEI imei: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            nicknames.removeValue(forKey: imei)
            if syncMode == .synced {
                db.collection(collectionName).document(imei).delete()
            }
        } else {
            nicknames[imei] = trimmed
            if syncMode == .synced {
                db.collection(collectionName).document(imei).setData([
                    "nickname": trimmed,
                    "updatedAt": FieldValue.serverTimestamp(),
                ])
            }
        }
    }

    /// Starts or stops following the shared list to match `syncMode`.
    /// Switching to `.manual` just stops listening/writing — it leaves
    /// whatever names this device already had. Switching back to `.synced`
    /// re-attaches the listener, which then overwrites local names with
    /// the shared list's current values.
    private func applySyncMode() {
        switch syncMode {
        case .synced:
            listenForRemoteChanges()
            checkAndPerformScheduledClearIfNeeded()
        case .manual:
            listener?.remove()
            listener = nil
        }
    }

    /// The shared list has no server to run a timer on its own, so instead
    /// every synced device checks — on launch, and whenever it (re)joins
    /// sync — whether the most recent weekly boundary has already passed
    /// without being cleared, and if so clears it itself. This keeps the
    /// reset entirely within Firestore's free tier (no scheduled Cloud
    /// Function), at the cost of not firing at the exact minute: it takes
    /// effect the next time anyone opens the app after Wed 23:00 JST,
    /// which for an app people open before/during flying is normally soon
    /// after. Any client can safely perform this — if two both do, the
    /// second is a harmless no-op over an already-empty collection.
    private func checkAndPerformScheduledClearIfNeeded() {
        let boundary = Self.mostRecentClearBoundary()
        let scheduleRef = db.collection(scheduleDocPath.0).document(scheduleDocPath.1)
        scheduleRef.getDocument { [weak self] snapshot, _ in
            guard let self else { return }
            let lastCleared = (snapshot?.data()?["lastClearedAt"] as? Timestamp)?.dateValue()
            guard lastCleared == nil || lastCleared! < boundary else { return }
            self.performScheduledClear(boundary: boundary, scheduleRef: scheduleRef)
        }
    }

    private func performScheduledClear(boundary: Date, scheduleRef: DocumentReference) {
        db.collection(collectionName).getDocuments { [weak self] snapshot, _ in
            guard let self, let snapshot else { return }
            let batch = self.db.batch()
            for document in snapshot.documents {
                batch.deleteDocument(document.reference)
            }
            batch.setData(["lastClearedAt": Timestamp(date: boundary)], forDocument: scheduleRef)
            batch.commit()
        }
    }

    /// The most recent Wednesday 23:00 JST that has already passed (or now,
    /// if it's exactly that moment) — the shared list resets weekly at this
    /// time so old names don't linger indefinitely; anyone still using a
    /// name just re-enters it and it's back until the following week.
    private static func mostRecentClearBoundary(from now: Date = Date()) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        let weekday = calendar.component(.weekday, from: now) // 1 = Sunday ... 4 = Wednesday
        var daysSinceWednesday = weekday - 4
        if daysSinceWednesday < 0 { daysSinceWednesday += 7 }
        let candidateDay = calendar.date(byAdding: .day, value: -daysSinceWednesday, to: now)!
        var candidate = calendar.date(bySettingHour: 23, minute: 0, second: 0, of: candidateDay)!
        if candidate > now {
            candidate = calendar.date(byAdding: .day, value: -7, to: candidate)!
        }
        return candidate
    }

    /// Keeps every synced device in near-real-time agreement: when anyone
    /// using the app renames a glider (by hand or via the whiteboard scan),
    /// everyone else's list updates automatically without needing to
    /// reopen the app.
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
