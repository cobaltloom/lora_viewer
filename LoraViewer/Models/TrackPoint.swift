import Foundation

/// One point of a glider's flight history, as returned by `query_position_log.php`.
struct TrackPoint: Identifiable, Decodable {
    let lat: Double
    let lon: Double
    let alt: Double?
    let voltage: Double?
    let rssi: Double?
    let source: PositionSource
    let createDateTimeUTC: Date?

    var id: String { "\(lat)-\(lon)-\(createDateTimeUTC?.timeIntervalSince1970 ?? 0)" }

    enum CodingKeys: String, CodingKey {
        case lat, lon, alt, voltage, rssi, source
        case createDatetime = "create_datetime"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        lat = try c.decodeLenientDouble(forKey: .lat) ?? 0
        lon = try c.decodeLenientDouble(forKey: .lon) ?? 0
        alt = try c.decodeLenientDouble(forKey: .alt)
        voltage = try c.decodeLenientDouble(forKey: .voltage)
        rssi = try c.decodeLenientDouble(forKey: .rssi)
        let sourceRaw = try c.decodeLenientString(forKey: .source)
        source = PositionSource(rawValue: sourceRaw) ?? .unknown
        let dtStr = try? c.decode(String.self, forKey: .createDatetime)
        createDateTimeUTC = dtStr.flatMap { TrailRouteDateFormatter.utc.date(from: $0) }
    }
}

struct TrackLogDevice: Decodable {
    let positionCount: Int
    let positionLog: [TrackPoint]

    enum CodingKeys: String, CodingKey {
        case positionCount = "position_count"
        case positionLog = "position_log"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        positionCount = Int(try c.decodeLenientDouble(forKey: .positionCount) ?? 0)
        positionLog = (try? c.decode([TrackPoint].self, forKey: .positionLog)) ?? []
    }
}

struct TrackLogResponse: Decodable {
    let result: String
    let message: String?
    let positionData: [String: TrackLogDevice]

    enum CodingKeys: String, CodingKey {
        case result, message
        case positionData = "position_data"
    }
}
