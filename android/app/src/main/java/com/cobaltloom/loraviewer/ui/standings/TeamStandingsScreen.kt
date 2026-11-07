package com.cobaltloom.loraviewer.ui.standings

import com.cobaltloom.loraviewer.ui.common.AdBanner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.CompetitionScoring
import com.cobaltloom.loraviewer.data.alert.CompetitionTaskCourseData
import com.cobaltloom.loraviewer.data.alert.TeamRanking
import com.cobaltloom.loraviewer.data.settings.DataSource
import com.cobaltloom.loraviewer.ui.common.ToBeat
import com.cobaltloom.loraviewer.ui.common.ToBeatTag
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Provisional team standings over a competition set up in the app (shared through the track server):
 * every glider seen on the current source is a team, scored each competition day from the laps the
 * track server timed (so days when no app was open count too) - corrected where someone entered it -
 * plus today's, and for each team the lap time that would take it to P1, or one place up, today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamStandingsScreen(viewModel: GliderTrackerViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    // Fetched on opening and every 30 s while open: the server re-times today's laps every minute.
    LaunchedEffect(uiState.dataSource) {
        while (true) {
            viewModel.refreshCompetition()
            delay(30_000)
        }
    }
    val competition = uiState.competition
    val results = uiState.competitionResults
    val today = LocalDate.now()
    // Today counts only during the competition.
    val includeToday = competition?.dates(today)?.lastOrNull() == today
    val handicap: (String) -> Double = { uiState.competitionHandicap(it) }
    val standings = remember(competition, results, uiState.handicaps) {
        competition?.let { CompetitionScoring.standingsBeforeToday(it, today, results, handicap) }
    }
    val todayResults = results[today.toString()]
    val todayCourse = competition?.let { CompetitionScoring.dayCourse(it, today, todayResults) } ?: uiState.selectedCourse?.name
    val courseIsSet = competition?.days?.get(today.toString())?.course != null
    val flights = remember(todayResults, todayCourse, uiState.handicaps, competition) { CompetitionScoring.flights(todayResults, todayCourse, handicap) }
    val distanceKm = CompetitionTaskCourseData.courses.firstOrNull { it.name == todayCourse }?.distanceKm
    val rows = remember(standings, flights, includeToday) {
        standings?.let { TeamRanking.rows(it, flights, includeToday) }.orEmpty()
    }
    // Each team's lap time to P1 and to one place up.
    val targets = remember(rows, distanceKm, uiState.handicaps) {
        if (standings == null || distanceKm == null || !includeToday) {
            emptyMap()
        } else {
            rows.associate { row ->
                row.name to if (row.rank == 1) {
                    emptyList()
                } else {
                    listOfNotNull(1, (row.rank - 1).takeIf { it > 1 }).map { target ->
                        target to TeamRanking.lapSecondsToReach(standings, flights, row.name, row.name, handicap(row.name), distanceKm, target)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("団体順位") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                },
                actions = { TextButton(onClick = onEdit) { Text("設定") } },
            )
        },
        bottomBar = { AdBanner() },
    ) { padding ->
        if (competition == null || competition.startDate == null || standings == null) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    uiState.standingsError ?: "競技会がまだ設定されていません。右上の「設定」から、大会期間を設定してください。期間中に飛んだ機体が自動でチームとして並びます。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(competition.name.ifBlank { "競技会" }, style = MaterialTheme.typography.titleMedium)
                    val days = competition.dates(today)
                    Text(
                        "${competition.startDate}〜${competition.endDate.orEmpty()}" +
                            (if (includeToday) "(第${days.size}日・本日の暫定を含む)" else "(期間外: 本日分は含みません)"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (competition.finalizedAt != null) {
                        Text(
                            "最終日の3日後に、全日の得点を自動で確定しました。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (includeToday) {
                        Text(
                            "本日のコース: " + (todayCourse ?: "まだ周回がありません") + if (courseIsSet || todayCourse == null) "" else "(周回の多さから自動判定)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "期間中にまだ周回・旋回点の通過がありません。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(rows, key = { it.name }) { row ->
                StandingsRow(row.copy(name = uiState.standingsName(row.name)), includeToday, targets[row.name].orEmpty())
                HorizontalDivider()
            }
            item(key = "note") {
                Text(
                    "期間中に${if (uiState.dataSource == DataSource.OGN) "FLARM" else "LoRa"}で飛んだ機体が自動でチームとして並びます。各日の得点は、トラックサーバーが計時した周回の参考得点のうち機体ごとの上位3本(全国選出点は最高点)で、端末を閉じていた日も含みます。ペナルティなどは「設定」で修正できます。公式成績とは異なることがあります。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun StandingsRow(row: TeamRanking.Row, includeToday: Boolean, targets: List<Pair<Int, Double?>>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${row.rank}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (includeToday) "前日まで ${row.official.toInt()} + 本日 ${row.today.toInt()}(${row.flightsToday}本)" else "累計",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "全国選出点 ${row.nationalTotal.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${row.total.toInt()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        if (targets.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 36.dp, top = 4.dp),
            ) {
                ToBeatTag("TARGET")
                targets.forEach { (rank, seconds) ->
                    val label = if (rank == 1) "P1" else "${rank}位"
                    val value = when {
                        seconds == null -> "届かない"
                        seconds.isInfinite() -> "周回で到達"
                        else -> ToBeat.clock(seconds)
                    }
                    Text(
                        "$label $value",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (seconds == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
