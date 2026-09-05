import UserNotifications

/// Fires a local notification (banner + sound) when a glider newly enters
/// an alerting state. Local notifications only fire while this app process
/// is alive — foreground, or briefly backgrounded — not when the app has
/// been fully terminated; there's no server pushing these.
final class AlertNotifier: NSObject, ObservableObject {
    override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    func requestAuthorizationIfNeeded() {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .notDetermined else { return }
            center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        }
    }

    func notify(gliderName: String, reasons: [GliderAlertReason]) {
        guard let severity = reasons.overallSeverity else { return }

        let content = UNMutableNotificationContent()
        content.title = "高度アラート(\(severity.label))"
        content.body = "\(gliderName): \(reasons.map(\.label).joined(separator: "・"))"
        content.sound = .default

        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// Fired once each time a glider newly enters a competition turnpoint's
    /// (or the management point's) sector, per JSAL's rule 43/管理ポイント
    /// definition — simplified here to "within its 2,000m radius" rather
    /// than the exact 90° wedge, matching how the sector is drawn on the map.
    func notifyTurnpointPassage(gliderName: String, turnpointName: String, altitudeM: Double?) {
        let content = UNMutableNotificationContent()
        content.title = "旋回点通過"
        let altitudeText = altitudeM.map { "\(Int($0))m" } ?? "高度不明"
        content.body = "\(gliderName): \(turnpointName)(\(altitudeText))"
        content.sound = .default

        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

extension AlertNotifier: UNUserNotificationCenterDelegate {
    /// Without this, iOS suppresses banners/sound for notifications fired
    /// while the app is in the foreground — which is exactly when this
    /// app's alerts fire, so it needs to opt in explicitly.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}
