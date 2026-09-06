import Foundation
import CoreLocation

/// One of JSAL's published ground landmarks (図3, Ver.2026-01-26) used to
/// visually judge distance from 妻沼滑空場中心点 for the A/B区域 altitude
/// guideline.
struct DistanceReferencePoint: Identifiable {
    let name: String
    let distanceKm: Double
    let coordinate: CLLocationCoordinate2D
    var id: String { name }
}

/// JSAL's 19 named distance-judging landmarks, grouped by their labeled
/// ring (3/5/7/9km from 妻沼滑空場中心点). The source document gives each
/// point's name and ring distance, but not a surveyed coordinate — these
/// coordinates were estimated by reading each point's approximate bearing
/// off the published diagram and combining it with its exact labeled
/// distance, matching the source's own "距離の主な目安目標" framing. Treat
/// as approximate, not surveyed.
enum DistanceReferencePointData {
    static let points: [DistanceReferencePoint] = [
        // 3km
        DistanceReferencePoint(name: "①青屋根(味の素)", distanceKm: 3, coordinate: CLLocationCoordinate2D(latitude: 36.19998, longitude: 139.38859)),
        DistanceReferencePoint(name: "②サントリー工場手前", distanceKm: 3, coordinate: CLLocationCoordinate2D(latitude: 36.21374, longitude: 139.45220)),
        DistanceReferencePoint(name: "③第2滑空場南端対岸赤・黄色の水門塔", distanceKm: 3, coordinate: CLLocationCoordinate2D(latitude: 36.18604, longitude: 139.40746)),
        DistanceReferencePoint(name: "④メタルワン建材", distanceKm: 3, coordinate: CLLocationCoordinate2D(latitude: 36.22061, longitude: 139.45031)),
        // 5km
        DistanceReferencePoint(name: "⑤刀水橋南詰", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.21530, longitude: 139.36337)),
        DistanceReferencePoint(name: "⑥いずみ総合公園野球場", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.19974, longitude: 139.36506)),
        DistanceReferencePoint(name: "⑦御正作公園", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.25364, longitude: 139.39982)),
        DistanceReferencePoint(name: "⑧城之内公園", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.25618, longitude: 139.41403)),
        DistanceReferencePoint(name: "⑨パナソニック中央交差点", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.22676, longitude: 139.36651)),
        DistanceReferencePoint(name: "⑩田の字(鞍掛第一工業団地)中心", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.20746, longitude: 139.47440)),
        DistanceReferencePoint(name: "⑪ジョイフル本田", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.19237, longitude: 139.46939)),
        DistanceReferencePoint(name: "⑫千代田町立東小", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.20746, longitude: 139.47440)),
        // 7km
        DistanceReferencePoint(name: "⑬石田川分流点の橋", distanceKm: 7, coordinate: CLLocationCoordinate2D(latitude: 36.25589, longitude: 139.36369)),
        DistanceReferencePoint(name: "⑭スバル大泉工場", distanceKm: 7, coordinate: CLLocationCoordinate2D(latitude: 36.24748, longitude: 139.48283)),
        DistanceReferencePoint(name: "⑮近藤沼", distanceKm: 7, coordinate: CLLocationCoordinate2D(latitude: 36.20043, longitude: 139.49572)),
        // 9km
        DistanceReferencePoint(name: "⑯高山(西部工業団地)手前", distanceKm: 9, coordinate: CLLocationCoordinate2D(latitude: 36.27768, longitude: 139.36130)),
        DistanceReferencePoint(name: "⑰スバル矢島工場", distanceKm: 9, coordinate: CLLocationCoordinate2D(latitude: 36.29110, longitude: 139.43633)),
        DistanceReferencePoint(name: "⑱多々良沼", distanceKm: 9, coordinate: CLLocationCoordinate2D(latitude: 36.26860, longitude: 139.48987)),
        DistanceReferencePoint(name: "⑲昭和橋", distanceKm: 9, coordinate: CLLocationCoordinate2D(latitude: 36.18367, longitude: 139.51312)),
    ]
}
