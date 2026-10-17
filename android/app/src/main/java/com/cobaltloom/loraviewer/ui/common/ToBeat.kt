package com.cobaltloom.loraviewer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cobaltloom.loraviewer.data.alert.LapScore

/**
 * A lap's "to beat" target - the lap time that would top today's best reference score (see
 * [LapScore.lapSecondsToBeat]) - and how far into the lap the glider is.
 */
data class ToBeat(
    /** [Double.POSITIVE_INFINITY] when any completed lap would top the day. */
    val targetSeconds: Double,
    val elapsedSeconds: Double,
) {
    val remainingSeconds: Double get() = targetSeconds - elapsedSeconds

    companion object {
        /** "21:00", like a ski jump broadcast's to-beat line. */
        fun clock(seconds: Double): String {
            val total = maxOf(0, seconds.toInt())
            return "%d:%02d".format(total / 60, total % 60)
        }
    }
}

val ToBeatAccent = Color(0xFFC6FF00)
private val ToBeatPanel = Brush.linearGradient(listOf(Color(0xFF0A1F3B), Color(0xFF12355C)))
private val BigTime = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

/** The "TO BEAT" tag, as on a ski jump broadcast. */
@Composable
fun ToBeatTag() {
    Text(
        "TO BEAT",
        color = Color.Black,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.5.sp,
        modifier = Modifier.background(ToBeatAccent, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The detail card's to-beat strip: the target lap time, the time left, and a bar of the lap so far
 * that turns orange in the last minute and red once the target has passed. */
@Composable
fun ToBeatBadge(toBeat: ToBeat, modifier: Modifier = Modifier) {
    val infinite = toBeat.targetSeconds.isInfinite()
    val barColor = when {
        toBeat.remainingSeconds <= 0 -> Color.Red
        toBeat.remainingSeconds < 60 -> Color(0xFFFF9800)
        else -> ToBeatAccent
    }
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ToBeatPanel).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToBeatTag()
            if (infinite) {
                Text("周回で1位", color = Color.White, style = MaterialTheme.typography.titleMedium)
            } else {
                Text(ToBeat.clock(toBeat.targetSeconds), color = ToBeatAccent, style = BigTime)
                Spacer(Modifier.weight(1f))
                Text(
                    if (toBeat.remainingSeconds > 0) "あと ${ToBeat.clock(toBeat.remainingSeconds)}" else "超過",
                    color = if (toBeat.remainingSeconds > 0) Color.White else Color.Red,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (!infinite) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) {
                val fraction = (toBeat.elapsedSeconds / toBeat.targetSeconds).coerceIn(0.0, 1.0).toFloat()
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(barColor))
            }
        }
    }
}

/** One handicap class's to-beat time in [ToBeatCard]. */
data class ToBeatRow(val handicap: Double, val seconds: Double)

/** The history's to-beat panel, styled after a ski jump broadcast: today's leader, then the lap time
 * each handicap class must beat to take the lead (see [LapScore.lapSecondsToBeat]). */
@Composable
fun ToBeatCard(leaderName: String, leaderScore: Double, leaderLapSeconds: Double?, rows: List<ToBeatRow>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ToBeatPanel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToBeatTag()
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("1位  $leaderName", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(
                    listOfNotNull(leaderLapSeconds?.let { ToBeat.clock(it) }, "%.1f点".format(leaderScore)).joinToString("  "),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        rows.forEach { row ->
            // Tapping a row shows its glider types in full instead of one truncated line.
            var expanded by remember(row.handicap) { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                Text(
                    "%.2f".format(row.handicap),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Text(
                    LapScore.handicapOptions.firstOrNull { it.value == row.handicap }?.types.orEmpty(),
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(if (row.seconds.isInfinite()) "周回で1位" else ToBeat.clock(row.seconds), color = ToBeatAccent, style = BigTime)
            }
        }
        Text(
            "この時間より速く周回すると1位(ゴール330m以上で通過した場合)",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
