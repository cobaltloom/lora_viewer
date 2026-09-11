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
/// ring (3/5/7/9km from 妻沼滑空場中心点), plus a few extra locally-known
/// visual landmarks added on top of that official set (their distanceKm
/// is the real measured distance, not a labeled ring). All coordinates
/// are surveyed locations, not estimates.
enum DistanceReferencePointData {
    static let points: [DistanceReferencePoint] = [
        // 3km
        DistanceReferencePoint(name: "①青屋根(味の素)", distanceKm: 3, coordinate: CLLocationCoordinate2D(latitude: 36.236159, longitude: 139.405079)),
        DistanceReferencePoint(name: "②サントリー工場手前", distanceKm: 3, coordinate: CLLocationCoordinate2D(latitude: 36.227707, longitude: 139.449893)),
        DistanceReferencePoint(name: "③第2滑空場南端対岸赤・黄色の水門塔", distanceKm: 3, coordinate: CLLocationCoordinate2D(latitude: 36.218429, longitude: 139.421856)),
        DistanceReferencePoint(name: "④メタルワン建材", distanceKm: 3, coordinate: CLLocationCoordinate2D(latitude: 36.211974, longitude: 139.451984)),
        // Not one of JSAL's original 19 - a locally-known visual landmark added on top of them.
        DistanceReferencePoint(name: "赤屋根", distanceKm: 4.1, coordinate: CLLocationCoordinate2D(latitude: 36.230694, longitude: 139.418281)),
        // 5km
        DistanceReferencePoint(name: "⑤刀水橋南詰", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.239919, longitude: 139.378726)),
        DistanceReferencePoint(name: "⑥いずみ総合公園野球場", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.245464, longitude: 139.394687)),
        DistanceReferencePoint(name: "⑦御正作公園", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.251963, longitude: 139.422989)),
        DistanceReferencePoint(name: "⑧城之内公園", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.260831, longitude: 139.412929)),
        DistanceReferencePoint(name: "⑨パナソニック中央交差点", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.252342, longitude: 139.400896)),
        DistanceReferencePoint(name: "⑩田の字(鞍掛第一工業団地)中心", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.224179, longitude: 139.469551)),
        DistanceReferencePoint(name: "⑪ジョイフル本田", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.205613, longitude: 139.472661)),
        DistanceReferencePoint(name: "⑫千代田町立東小", distanceKm: 5, coordinate: CLLocationCoordinate2D(latitude: 36.199496, longitude: 139.46518)),
        // 7km
        DistanceReferencePoint(name: "⑬石田川分流点の橋", distanceKm: 7, coordinate: CLLocationCoordinate2D(latitude: 36.255355, longitude: 139.355098)),
        DistanceReferencePoint(name: "⑭スバル大泉工場", distanceKm: 7, coordinate: CLLocationCoordinate2D(latitude: 36.271549, longitude: 139.409691)),
        DistanceReferencePoint(name: "⑮近藤沼", distanceKm: 7, coordinate: CLLocationCoordinate2D(latitude: 36.226088, longitude: 139.50233)),
        // 9km
        DistanceReferencePoint(name: "⑯高山(西部工業団地)手前", distanceKm: 9, coordinate: CLLocationCoordinate2D(latitude: 36.273072, longitude: 139.338928)),
        DistanceReferencePoint(name: "⑰スバル矢島工場", distanceKm: 9, coordinate: CLLocationCoordinate2D(latitude: 36.276825, longitude: 139.371439)),
        DistanceReferencePoint(name: "⑱多々良沼", distanceKm: 9, coordinate: CLLocationCoordinate2D(latitude: 36.260236, longitude: 139.497473)),
        DistanceReferencePoint(name: "⑲昭和橋", distanceKm: 9, coordinate: CLLocationCoordinate2D(latitude: 36.192931, longitude: 139.511203)),
    ]
}
