package com.cobaltloom.loraviewer.ui.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * A text label on the map, for [visibleLabelIds]. [centerOffsetYDp] is how far below its
 * coordinate the label's center is drawn.
 */
data class MapLabel(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val text: String,
    val centerOffsetYDp: Float,
)

/**
 * Which labels to show at [zoom] so none overlap - the Android counterpart of MapKit hiding
 * colliding Annotation titles on iOS. [labels] are in priority order: each one is shown only if
 * its approximate on-screen box doesn't overlap a higher-priority label that's already shown.
 * Only zoom matters (not panning), since overlap depends on the labels' relative screen positions.
 */
fun visibleLabelIds(labels: List<MapLabel>, zoom: Float): Set<String> {
    // Google Maps' world is 256dp wide at zoom 0, doubling with each zoom level.
    val worldSizeDp = 256.0 * 2.0.pow(zoom.toDouble())
    val shown = mutableListOf<LabelBox>()
    val ids = mutableSetOf<String>()
    for (label in labels) {
        val x = (label.longitude + 180.0) / 360.0 * worldSizeDp
        val latRad = label.latitude * PI / 180.0
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * worldSizeDp
        val box = LabelBox(x, y + label.centerOffsetYDp, estimatedWidthDp(label.text) / 2.0, LABEL_HEIGHT_DP / 2.0)
        if (shown.none { it.overlaps(box) }) {
            shown += box
            ids += label.id
        }
    }
    return ids
}

private const val LABEL_HEIGHT_DP = 16.0

/** Roughly the rendered width of 10-11sp bold text: full-width (Japanese) glyphs are about twice as wide as ASCII. */
private fun estimatedWidthDp(text: String): Double = text.sumOf { if (it.code < 0x80) 7.0 else 12.0 } + 8.0

private data class LabelBox(val centerX: Double, val centerY: Double, val halfWidth: Double, val halfHeight: Double) {
    fun overlaps(other: LabelBox): Boolean =
        abs(centerX - other.centerX) < halfWidth + other.halfWidth &&
            abs(centerY - other.centerY) < halfHeight + other.halfHeight
}
