package com.cobaltloom.loraviewer.data.alert

/**
 * One of JSAL's published ground landmarks (図3, Ver.2026-01-26) used to visually judge distance
 * from 妻沼滑空場中心点 for the A/B区域 altitude guideline.
 */
data class DistanceReferencePoint(val name: String, val distanceKm: Double, val coordinate: Coordinate)

/**
 * JSAL's 19 named distance-judging landmarks, grouped by their labeled ring (3/5/7/9km from
 * 妻沼滑空場中心点), plus a few extra locally-known visual landmarks added on top of that official
 * set (their distanceKm is the real measured distance, not a labeled ring). All coordinates are
 * surveyed locations, not estimates.
 */
object DistanceReferencePointData {
    val points: List<DistanceReferencePoint> = listOf(
        // 3km
        DistanceReferencePoint("①青屋根(味の素)", 3.0, Coordinate(36.236159, 139.405079)),
        DistanceReferencePoint("②サントリー工場手前", 3.0, Coordinate(36.227707, 139.449893)),
        DistanceReferencePoint("③第2滑空場南端対岸赤・黄色の水門塔", 3.0, Coordinate(36.199346, 139.445622)),
        DistanceReferencePoint("④メタルワン建材", 3.0, Coordinate(36.211974, 139.451984)),
        // Not one of JSAL's original 19 - a locally-known visual landmark added on top of them.
        DistanceReferencePoint("赤屋根", 4.1, Coordinate(36.230694, 139.418281)),
        // 5km
        DistanceReferencePoint("⑤刀水橋南詰", 5.0, Coordinate(36.239919, 139.378726)),
        DistanceReferencePoint("⑥いずみ総合公園野球場", 5.0, Coordinate(36.245464, 139.394687)),
        DistanceReferencePoint("⑦御正作公園", 5.0, Coordinate(36.251963, 139.422989)),
        DistanceReferencePoint("⑧城之内公園", 5.0, Coordinate(36.260831, 139.412929)),
        DistanceReferencePoint("⑨パナソニック中央交差点", 5.0, Coordinate(36.252342, 139.400896)),
        DistanceReferencePoint("⑩田の字(鞍掛第一工業団地)中心", 5.0, Coordinate(36.224179, 139.469551)),
        DistanceReferencePoint("⑪ジョイフル本田", 5.0, Coordinate(36.205613, 139.472661)),
        DistanceReferencePoint("⑫千代田町立東小", 5.0, Coordinate(36.199496, 139.46518)),
        // 7km
        DistanceReferencePoint("⑬石田川分流点の橋", 7.0, Coordinate(36.255355, 139.355098)),
        DistanceReferencePoint("⑭スバル大泉工場", 7.0, Coordinate(36.271549, 139.409691)),
        DistanceReferencePoint("⑮近藤沼", 7.0, Coordinate(36.226088, 139.50233)),
        // 9km
        DistanceReferencePoint("⑯高山(西部工業団地)手前", 9.0, Coordinate(36.273072, 139.338928)),
        DistanceReferencePoint("⑰スバル矢島工場", 9.0, Coordinate(36.276825, 139.371439)),
        DistanceReferencePoint("⑱多々良沼", 9.0, Coordinate(36.260236, 139.497473)),
        DistanceReferencePoint("⑲昭和橋", 9.0, Coordinate(36.192931, 139.511203)),
    )
}
