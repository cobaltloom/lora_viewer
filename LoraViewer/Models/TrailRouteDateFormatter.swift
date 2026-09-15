import Foundation

/// TrailRouteView sends/receives all timestamps as "yyyy-MM-dd HH:mm:ss" in UTC.
enum TrailRouteDateFormatter {
    static let utc: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.timeZone = TimeZone(identifier: "UTC")
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
}
