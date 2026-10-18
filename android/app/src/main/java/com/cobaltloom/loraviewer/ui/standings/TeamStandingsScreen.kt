package com.cobaltloom.loraviewer.ui.standings

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
import com.cobaltloom.loraviewer.data.alert.TeamRanking
import com.cobaltloom.loraviewer.ui.common.ToBeat
import com.cobaltloom.loraviewer.ui.common.ToBeatTag
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Provisional team standings over a competition a representative set up (shared through the track
 * server): each earlier competition day's team scores - corrected where someone entered them, else
 * worked out from this device's lap records - plus today's reference scores, and for each team the lap
 * time that would take it to P1, or one place up, today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamStandingsScreen(viewModel: GliderTrackerViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    // Fetched on opening and every minute while open: the representative may update it any time.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshCompetition()
            delay(60_000)
        }
    }
    val competition = uiState.competition
    val course = uiState.selectedCourse
    val today = LocalDate.now()
    val standings = remember(competition, uiState.turnpointPassageRecords, uiState.handicaps) { uiState.standingsBeforeToday() }
    // Today counts only during the competition.
    val includeToday = competition?.dates(today)?.lastOrNull() == today
    val flights = remember(uiState.turnpointPassageRecords, uiState.handicaps, course) { uiState.todaysTeamFlights() }
    val rows = remember(standings, flights, includeToday) {
        standings?.let { TeamRanking.rows(it, flights, includeToday) }.orEmpty()
    }
    // Each team's lap time to P1 and to one place up, in its first glider.
    val targets = remember(rows, course, uiState.handicaps) {
        if (standings == null || course == null || !includeToday) {
            emptyMap()
        } else {
            rows.associate { row ->
                val glider = standings.teams.firstOrNull { it.name == row.name }?.gliderIds?.firstOrNull()
                row.name to if (glider == null || row.rank == 1) {
                    emptyList()
                } else {
                    listOfNotNull(1, (row.rank - 1).takeIf { it > 1 }).map { target ->
                        target to TeamRanking.lapSecondsToReach(
                            standings, flights, row.name, glider, uiState.handicapFor(glider), course.distanceKm, target,
                        )
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
    ) { padding ->
        if (competition == null || standings == null || competition.teams.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    uiState.standingsError ?: "競技会がまだ設定されていません。右上の「設定」から、大会期間とチームを設定してください。",
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
                        "${competition.startDate.orEmpty()}〜${competition.endDate.orEmpty()}" +
                            (if (includeToday) "(第${days.size}日・本日の暫定を含む)" else "(期間外: 本日分は含みません)"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (includeToday && course == null) {
                        Text(
                            "タスクコースを選ぶと、順位を上げるのに必要な周回時間を表示します。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
            items(rows, key = { it.name }) { row ->
                StandingsRow(row, includeToday, targets[row.name].orEmpty())
                HorizontalDivider()
            }
            item(key = "note") {
                Text(
                    "各日の得点は、その日の参考得点のチーム上位3本(全国選出点は最高点)を、選手が別々として合計した目安です。過去の日は、この端末の記録から計算するか、「設定」で修正・確定された値を使います。公式成績とは異なることがあります。",
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
