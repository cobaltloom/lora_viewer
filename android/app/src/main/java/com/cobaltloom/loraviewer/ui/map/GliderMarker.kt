package com.cobaltloom.loraviewer.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.model.PositionSource
import kotlin.math.roundToInt

/**
 * A glider's marker content on the map: a colored circle carrying its board
 * index number, plus an altitude badge underneath. Color encodes the
 * position source (GPS/cell/disconnected); mirrors the iOS app's
 * GliderMarkerView, minus favorite/alert styling (not implemented yet).
 */
@Composable
fun GliderMarkerContent(glider: GliderPosition) {
    val circleColor = when {
        glider.isDisconnected -> Color.Gray
        glider.source == PositionSource.GPS -> Color(0xFF2E7DFF)
        glider.source == PositionSource.CELL -> Color(0xFFFF9500)
        else -> Color.Gray
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (glider.isDisconnected) 0.4f else 1f),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .background(circleColor, CircleShape)
                .border(2.dp, Color.White, CircleShape),
        ) {
            Text(
                text = glider.index,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        glider.alt?.let { alt ->
            Text(
                text = "${alt.roundToInt()}m",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(50))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
