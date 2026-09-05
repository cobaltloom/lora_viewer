package com.cobaltloom.loraviewer.ui.common

import androidx.compose.ui.graphics.Color

private val gliderColors = listOf(
    Color(0xFF00BCD4), Color(0xFFE91E63), Color(0xFF4CAF50), Color(0xFFFF9800),
    Color(0xFF2196F3), Color(0xFF9C27B0), Color(0xFFF44336), Color(0xFFFFEB3B),
)

/** A consistent-per-glider color (by IMEI) so multiple trails/tracks can be told apart. */
fun colorForGlider(imei: String): Color = gliderColors[Math.floorMod(imei.hashCode(), gliderColors.size)]
