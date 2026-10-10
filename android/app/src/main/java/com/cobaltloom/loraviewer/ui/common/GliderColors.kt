package com.cobaltloom.loraviewer.ui.common

import androidx.compose.ui.graphics.Color

private val gliderColors = listOf(
    Color(0xFF00BCD4), Color(0xFFE91E63), Color(0xFF4CAF50), Color(0xFFFF9800),
    Color(0xFF2196F3), Color(0xFF9C27B0), Color(0xFFF44336), Color(0xFFFFEB3B),
)

/** A consistent-per-glider color (by IMEI) so multiple trails/tracks can be told apart. */
fun colorForGlider(imei: String): Color = gliderColors[Math.floorMod(imei.hashCode(), gliderColors.size)]

/**
 * Gives each imei in [imeis] its own palette slot, keeping the slots already in [previous] so a
 * trail's color never changes mid-flight. New gliders take the lowest free slot, so colors only
 * repeat once more gliders are flying than the palette has colors.
 */
fun assignGliderColorSlots(previous: Map<String, Int>, imeis: Collection<String>): Map<String, Int> {
    val slots = previous.filterKeys { it in imeis }.toMutableMap()
    for (imei in imeis.sorted()) {
        if (imei in slots) continue
        val used = slots.values.toSet()
        slots[imei] = gliderColors.indices.firstOrNull { it !in used } ?: Math.floorMod(imei.hashCode(), gliderColors.size)
    }
    return slots
}

/** The color for a slot from [assignGliderColorSlots], or [colorForGlider] if it has none. */
fun gliderColor(slot: Int?, imei: String): Color = slot?.let { gliderColors[it] } ?: colorForGlider(imei)
