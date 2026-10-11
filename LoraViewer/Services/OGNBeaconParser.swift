import Foundation

/// One aircraft position beacon from the OGN APRS stream.
struct OGNBeacon {
    /// The APRS source callsign, e.g. "FLRDDE626" - used as the glider's id.
    let callsign: String
    /// The 6-hex-digit device address, as keyed in the OGN device database.
    let address: String
    let lat: Double
    let lon: Double
    let altitudeM: Double
    let timestamp: Date
    let aircraftType: Int
    let addressType: Int
    let isStealth: Bool
    let isNoTracking: Bool
    /// The APRS destination ("OGFLR", "OGNTRK", "OGADSB", ...), i.e. which radio system relayed it.
    let destination: String
}

/// Parses OGN-flavoured APRS aircraft beacons
/// (http://wiki.glidernet.org/wiki:ogn-flavoured-aprs), e.g.
/// `FLRDDE626>APRS,qAS,EGHL:/074548h5111.32N/00102.04W'086/007/A=000607 !W52! id0ADDE626 -019fpm ...`
/// Kept in step with the Android app's OgnBeaconParser.kt, which has the unit tests.
enum OGNBeaconParser {
    private static let beaconPattern = try! NSRegularExpression(pattern:
        #"^([A-Za-z0-9]{3,9})>([A-Z0-9]+)[^:]*:[/@](\d{2})(\d{2})(\d{2})h"# +
        #"(\d{2})(\d{2}\.\d{2})([NS]).(\d{3})(\d{2}\.\d{2})([EW])."# +
        #"(?:\d{3}/\d{3})?/A=(-?\d{5,6})(.*)$"#)
    private static let precisionPattern = try! NSRegularExpression(pattern: #"!W(\d)(\d)!"#)
    private static let idPattern = try! NSRegularExpression(pattern: #"\bid([0-9A-Fa-f]{2})([0-9A-Fa-f]{6})\b"#)

    static let addressTypeRandom = 0
    /// Aircraft types we never show: 9 = jet/airliner, 15 = static object.
    private static let excludedAircraftTypes: Set<Int> = [9, 15]
    private static let feetToM = 0.3048

    /// Returns nil for anything that isn't an aircraft position beacon (receiver beacons, comments).
    static func parse(_ line: String, now: Date) -> OGNBeacon? {
        if line.hasPrefix("#") { return nil }
        guard let g = groups(of: beaconPattern, in: line), g.count == 14 else { return nil }
        let rest = g[13]
        guard let id = groups(of: idPattern, in: rest), let flags = Int(id[1], radix: 16) else {
            return nil // receiver beacons have no id field
        }

        let precision = groups(of: precisionPattern, in: rest)
        let latExtra = precision.flatMap { Double($0[1]) }.map { $0 / 1000 } ?? 0
        let lonExtra = precision.flatMap { Double($0[2]) }.map { $0 / 1000 } ?? 0
        guard let latDeg = Double(g[6]), let latMin = Double(g[7]),
              let lonDeg = Double(g[9]), let lonMin = Double(g[10]),
              let altitudeFt = Double(g[12]),
              let h = Int(g[3]), let m = Int(g[4]), let s = Int(g[5]) else { return nil }
        var lat = latDeg + (latMin + latExtra) / 60
        if g[8] == "S" { lat = -lat }
        var lon = lonDeg + (lonMin + lonExtra) / 60
        if g[11] == "W" { lon = -lon }

        return OGNBeacon(
            callsign: g[1],
            address: id[2].uppercased(),
            lat: lat,
            lon: lon,
            altitudeM: altitudeFt * feetToM,
            timestamp: timestamp(hour: h, minute: m, second: s, now: now),
            aircraftType: (flags >> 2) & 0x0F,
            addressType: flags & 0x03,
            isStealth: flags & 0x80 != 0,
            isNoTracking: flags & 0x40 != 0,
            destination: g[2]
        )
    }

    /// Whether a beacon may be shown at all, before consulting the device database.
    static func isDisplayable(_ beacon: OGNBeacon) -> Bool {
        !beacon.isStealth &&
            !beacon.isNoTracking &&
            beacon.addressType != addressTypeRandom &&
            !excludedAircraftTypes.contains(beacon.aircraftType) &&
            // ADS-B relayed by OGN receivers is airliner traffic, not what this app is for.
            !beacon.destination.hasPrefix("OGADSB")
    }

    /// Beacons carry only hh:mm:ss UTC; pick the date that puts it nearest to `now`.
    private static func timestamp(hour: Int, minute: Int, second: Int, now: Date) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        var components = calendar.dateComponents([.year, .month, .day], from: now)
        components.hour = hour % 24
        components.minute = minute % 60
        components.second = second % 60
        var t = calendar.date(from: components) ?? now
        if t.timeIntervalSince(now) > 12 * 3600 { t.addTimeInterval(-86400) }
        else if now.timeIntervalSince(t) > 12 * 3600 { t.addTimeInterval(86400) }
        return t
    }

    /// The whole match followed by each capture group (empty string for a group that didn't take part).
    private static func groups(of regex: NSRegularExpression, in string: String) -> [String]? {
        let range = NSRange(string.startIndex..., in: string)
        guard let match = regex.firstMatch(in: string, range: range) else { return nil }
        return (0..<match.numberOfRanges).map { i in
            Range(match.range(at: i), in: string).map { String(string[$0]) } ?? ""
        }
    }
}
