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

    func notify(gliderName: String, reasons: [String]) {
        let content = UNMutableNotificationContent()
        content.title = "高度不足の可能性"
        content.body = "\(gliderName): \(reasons.joined(separator: "・"))"
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
