import Foundation
import CoreLocation

enum PositionSource: String {
    case gps = "1"
    case cell = "2"
    case unknown = "0"

    var label: String {
        switch self {
        case .gps: return "GPS"
        case .cell: return "セル"
        case .unknown: return "不明"
        }
    }
}

/// One glider's current position, as returned by `mapapi.php`.
struct GliderPosition: Identifiable, Decodable {
    let imei: String
    let index: String
    let lat: Double
    let lon: Double
    let alt: Double?
    let source: PositionSource
    let isDisconnected: Bool
    let positionDateTimeUTC: Date?

    var id: String { imei }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    enum CodingKeys: String, CodingKey {
        case imei, index, lat, lon, alt, source
        case dcFlag = "dc_flag"
        case positionDatetime = "position_datetime"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        imei = try c.decodeLenientString(forKey: .imei)
        index = try c.decodeLenientString(forKey: .index)
        lat = try c.decodeLenientDouble(forKey: .lat) ?? 0
        lon = try c.decodeLenientDouble(forKey: .lon) ?? 0
        alt = try c.decodeLenientDouble(forKey: .alt)
        let sourceRaw = try c.decodeLenientString(forKey: .source)
        source = PositionSource(rawValue: sourceRaw) ?? .unknown
        let dcFlagRaw = try c.decodeLenientString(forKey: .dcFlag)
        isDisconnected = (dcFlagRaw == "1")
        let dtStr = try? c.decode(String.self, forKey: .positionDatetime)
        positionDateTimeUTC = dtStr.flatMap { TrailRouteDateFormatter.utc.date(from: $0) }
    }
}

extension GliderPosition: Equatable {
    static func == (lhs: GliderPosition, rhs: GliderPosition) -> Bool {
        lhs.imei == rhs.imei &&
        lhs.lat == rhs.lat &&
        lhs.lon == rhs.lon &&
        lhs.alt == rhs.alt &&
        lhs.positionDateTimeUTC == rhs.positionDateTimeUTC
    }
}

struct CurrentPositionsResponse: Decodable {
    let result: String
    let message: String?
    let positions: [GliderPosition]
}
