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

    /** F1-style delta to the target: "Δ −11:02" with time in hand, "Δ +0:45" once over. */
    val delta: String get() = if (remainingSeconds > 0) "Δ −${clock(remainingSeconds)}" else "Δ +${clock(-remainingSeconds)}"

    companion object {
        /** "21:00", as on motorsport timing screens. */
        fun clock(seconds: Double): String {
            val total = maxOf(0, seconds.toInt())
            return "%d:%02d".format(total / 60, total % 60)
        }
    }
}

val ToBeatAccent = Color(0xFFC6FF00)

/** F1 timing's "purple": on pace for the fastest of all. */
private val TimingPurple = Color(0xFFB052F2)

/** F1 timing's yellow. */
private val TimingYellow = Color(0xFFFFD600)
private val ToBeatPanel = Brush.linearGradient(listOf(Color(0xFF0A1F3B), Color(0xFF12355C)))
private val BigTime = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

/** A timing-screen tag ("TIME TO BEAT", "TARGET"), as in motorsport broadcasts. */
@Composable
fun ToBeatTag(text: String = "TIME TO BEAT") {
    Text(
        text,
        color = Color.Black,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.5.sp,
        modifier = Modifier.background(ToBeatAccent, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The detail card's target strip, F1 style: the lap time that would take P1, the delta to it, and a
 * bar of the lap so far - purple while on pace for P1, yellow in the last minute, red once over. */
@Composable
fun ToBeatBadge(toBeat: ToBeat, modifier: Modifier = Modifier) {
    val infinite = toBeat.targetSeconds.isInfinite()
    val barColor = when {
        toBeat.remainingSeconds <= 0 -> Color.Red
        toBeat.remainingSeconds < 60 -> TimingYellow
        else -> TimingPurple
    }
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ToBeatPanel).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToBeatTag("TARGET")
            if (infinite) {
                Text("周回でP1", color = Color.White, style = MaterialTheme.typography.titleMedium)
            } else {
                Text(ToBeat.clock(toBeat.targetSeconds), color = ToBeatAccent, style = BigTime)
                Spacer(Modifier.weight(1f))
                Text(
                    toBeat.delta,
                    color = if (barColor == TimingPurple) Color.White else barColor,
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

/** The history's TIME TO BEAT panel, styled after F1 timing graphics: today's P1, then the lap time
 * each handicap class must beat to take P1 (see [LapScore.lapSecondsToBeat]). */
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
                Text("P1  $leaderName", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
                Text(if (row.seconds.isInfinite()) "周回でP1" else ToBeat.clock(row.seconds), color = ToBeatAccent, style = BigTime)
            }
        }
        Text(
            "この時間より速く周回するとP1(1位)。ゴール330m以上で通過した場合",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
