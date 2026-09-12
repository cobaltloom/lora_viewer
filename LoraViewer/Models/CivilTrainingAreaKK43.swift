import Foundation
import CoreLocation

/// 民間訓練試験空域 KK4-3(AIP Japan ENR 5.3-15)。地表(SFC)〜2,000ft MSL。
/// 妻沼滑空場の南側にあり、B区域の一部(特に第二滑空場の土手側場周)と重なる。
///
/// AIP上の定義は緯度経度の頂点ではなく、次の4本の線で囲まれた区域として記載されている:
/// 「N36°09'24" E139°20'13" と N36°13'33" E139°34'34" を結ぶ直線(熊谷北部・館林IC)」
/// 「上越・北陸新幹線の中心線」「埼玉県道38号(加須鴻巣線)の中心線」「東北自動車道の中心線」。
/// 新幹線・東北自動車道の実際の経路はOpenStreetMapのデータからトレースしたが、
/// 加須市周辺の県道38号は並行する区間のデータが曖昧だったため、新幹線との交点から
/// 加須ICまでを直線で近似している。公式チャートそのものではなく、あくまで目安であり、
/// 実際の判断の根拠にはしないこと。
enum CivilTrainingAreaKK43 {
    static let boundary: [CLLocationCoordinate2D] = [
        CLLocationCoordinate2D(latitude: 36.156667, longitude: 139.336944),
        CLLocationCoordinate2D(latitude: 36.224223, longitude: 139.570545),
        CLLocationCoordinate2D(latitude: 36.221381, longitude: 139.569543),
        CLLocationCoordinate2D(latitude: 36.218201, longitude: 139.569108),
        CLLocationCoordinate2D(latitude: 36.212820, longitude: 139.569949),
        CLLocationCoordinate2D(latitude: 36.208827, longitude: 139.572047),
        CLLocationCoordinate2D(latitude: 36.198213, longitude: 139.579825),
        CLLocationCoordinate2D(latitude: 36.190976, longitude: 139.583688),
        CLLocationCoordinate2D(latitude: 36.183399, longitude: 139.586164),
        CLLocationCoordinate2D(latitude: 36.169053, longitude: 139.588835),
        CLLocationCoordinate2D(latitude: 36.164262, longitude: 139.590445),
        CLLocationCoordinate2D(latitude: 36.158179, longitude: 139.593393),
        CLLocationCoordinate2D(latitude: 36.151588, longitude: 139.597909),
        CLLocationCoordinate2D(latitude: 36.144611, longitude: 139.604516),
        CLLocationCoordinate2D(latitude: 36.140504, longitude: 139.609639),
        CLLocationCoordinate2D(latitude: 36.133649, longitude: 139.619984),
        CLLocationCoordinate2D(latitude: 36.129348, longitude: 139.625153),
        CLLocationCoordinate2D(latitude: 36.062742, longitude: 139.537916),
        CLLocationCoordinate2D(latitude: 36.075059, longitude: 139.522098),
        CLLocationCoordinate2D(latitude: 36.078771, longitude: 139.516620),
        CLLocationCoordinate2D(latitude: 36.116915, longitude: 139.450170),
        CLLocationCoordinate2D(latitude: 36.120272, longitude: 139.442234),
        CLLocationCoordinate2D(latitude: 36.149748, longitude: 139.361806),
        CLLocationCoordinate2D(latitude: 36.151719, longitude: 139.352364),
        CLLocationCoordinate2D(latitude: 36.152947, longitude: 139.336666),
    ]
}
