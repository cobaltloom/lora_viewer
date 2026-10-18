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
 * Provisional team standings: the official totals a representative entered (shared through the track
 * server) plus today's reference scores of each team's gliders, and for each team the lap time that
 * would take it to P1, or one place up, today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamStandingsScreen(viewModel: GliderTrackerViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    // Fetched on opening and every minute while open: the representative may update it any time.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshStandings()
            delay(60_000)
        }
    }
    val standings = uiState.standings
    val course = uiState.selectedCourse
    // Once the official totals include today, today's flights mustn't be added again.
    val includeToday = standings?.throughDate != LocalDate.now().toString()
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
                actions = { TextButton(onClick = onEdit) { Text("入力") } },
            )
        },
    ) { padding ->
        if (standings == null) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    uiState.standingsError ?: "まだ団体成績が入力されていません。右上の「入力」から、代表者が入力できます。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(standings.competition.ifBlank { "競技会" }, style = MaterialTheme.typography.titleMedium)
                    val through = listOfNotNull(standings.throughDay?.let { "第${it}日" }, standings.throughDate).joinToString(" ")
                    Text(
                        "公式: ${through.ifBlank { "-" }}終了時点" +
                            if (includeToday) " + 本日の暫定(参考得点の上位3本)" else "(本日分を含む)",
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
                    "本日の暫定は、この端末が計時した飛行の参考得点(各機体の上位3本)を、選手が別々として合計した目安です。全国選出点は各チームの本日最高得点を加えています。公式成績とは異なることがあります。",
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
                    if (includeToday) "公式 ${row.official.toInt()} + 本日 ${row.today.toInt()}(${row.flightsToday}本)" else "公式",
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
