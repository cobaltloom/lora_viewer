import Foundation

/// User-assigned nicknames for gliders, keyed by IMEI and persisted locally
/// on the device. The site itself only labels members "1.", "2." etc., so
/// this lets the pilot attach a name they actually recognize.
final class NicknameStore: ObservableObject {
    @Published private var nicknames: [String: String] {
        didSet { persist() }
    }

    private let storageKey = "gliderNicknames"

    init() {
        if let data = UserDefaults.standard.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            nicknames = decoded
        } else {
            nicknames = [:]
        }
    }

    func nickname(forIMEI imei: String) -> String? {
        nicknames[imei]
    }

    func setNickname(_ name: String, forIMEI imei: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            nicknames.removeValue(forKey: imei)
        } else {
            nicknames[imei] = trimmed
        }
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(nicknames) else { return }
        UserDefaults.standard.set(data, forKey: storageKey)
    }
}
