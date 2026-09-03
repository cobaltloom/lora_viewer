package com.cobaltloom.loraviewer.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cobaltloom.loraviewer.data.alert.AlertSeverity
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.model.PositionSource
import kotlin.math.roundToInt

/**
 * A glider's marker content on the map: a colored circle carrying its board index number, plus an
 * altitude badge underneath, a favorite star, and an alert triangle when applicable. Color encodes
 * the position source (GPS/cell/disconnected); mirrors the iOS app's GliderMarkerView.
 */
@Composable
fun GliderMarkerContent(
    glider: GliderPosition,
    isSelected: Boolean = false,
    isFavorite: Boolean = false,
    alertSeverity: AlertSeverity? = null,
) {
    val circleColor = when {
        glider.isDisconnected -> Color.Gray
        glider.source == PositionSource.GPS -> Color(0xFF2E7DFF)
        glider.source == PositionSource.CELL -> Color(0xFFFF9500)
        else -> Color.Gray
    }
    val ringColor = when (alertSeverity) {
        AlertSeverity.WARNING -> Color(0xFFD32F2F)
        AlertSeverity.CAUTION -> Color(0xFFF57C00)
        null -> if (isFavorite) Color(0xFFFFC107) else Color.White
    }
    val badgeColor = when (alertSeverity) {
        AlertSeverity.WARNING -> Color(0xFFD32F2F)
        AlertSeverity.CAUTION -> Color(0xFFF57C00)
        null -> Color.Black
    }
    val ringWidth = if (alertSeverity != null || isFavorite) 3.dp else 2.dp
    val circleSize = if (isSelected) 34.dp else 28.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (glider.isDisconnected) 0.4f else 1f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(circleSize)
                    .background(circleColor, CircleShape)
                    .border(ringWidth, ringColor, CircleShape),
            ) {
                Text(glider.index, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                        .padding(2.dp),
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(10.dp))
                }
            }
            if (alertSeverity != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .background(Color.White, CircleShape)
                        .padding(2.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = ringColor, modifier = Modifier.size(10.dp))
                }
            }
        }
        glider.alt?.let { alt ->
            Text(
                text = "${alt.roundToInt()}m",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(badgeColor.copy(alpha = 0.75f), RoundedCornerShape(50))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
