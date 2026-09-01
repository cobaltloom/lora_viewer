import Foundation

/// TrailRouteView's PHP backend is inconsistent about whether numeric fields
/// are emitted as JSON numbers or as strings, so decoding must accept either.
extension KeyedDecodingContainer {
    func decodeLenientDouble(forKey key: Key) throws -> Double? {
        if let d = try? decode(Double.self, forKey: key) { return d }
        if let i = try? decode(Int.self, forKey: key) { return Double(i) }
        if let s = try? decode(String.self, forKey: key) { return Double(s) }
        return nil
    }

    func decodeLenientString(forKey key: Key) throws -> String {
        if let s = try? decode(String.self, forKey: key) { return s }
        if let i = try? decode(Int.self, forKey: key) { return String(i) }
        if let d = try? decode(Double.self, forKey: key) { return String(d) }
        return ""
    }
}
