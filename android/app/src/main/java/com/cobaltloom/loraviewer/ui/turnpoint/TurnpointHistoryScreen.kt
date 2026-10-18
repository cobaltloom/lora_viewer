package com.cobaltloom.loraviewer.ui.turnpoint

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.LapScore
import com.cobaltloom.loraviewer.data.alert.TurnpointPassageRecord
import com.cobaltloom.loraviewer.ui.common.ToBeatCard
import com.cobaltloom.loraviewer.ui.common.ToBeatRow
import com.cobaltloom.loraviewer.ui.map.FinishSortOrder
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** Shows today's turnpoint-passage events as a backup to the push notification, in case it was missed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnpointHistoryScreen(viewModel: GliderTrackerViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    // Today by default, or an earlier day's laps and scores (kept for 30 days).
    val today = LocalDate.now()
    var selectedDay by remember { mutableStateOf(today) }
    val isToday = selectedDay == today
    val days = remember(uiState.turnpointPassageRecords) {
        (uiState.turnpointPassageRecords.map { it.localDate } + today).distinct().sortedDescending()
    }
    // Today: the selected task course's rows (all rows when none is selected). An earlier day: the
    // course picked for it, else the selected course if that day has its rows, else the course with
    // most rows that day.
    var pastCourseName by remember(selectedDay) { mutableStateOf<String?>(null) }
    val pastCourseNames = remember(uiState.turnpointPassageRecords, selectedDay) {
        uiState.turnpointPassageRecords.filter { it.localDate == selectedDay && it.isScoredFlight }
            .mapNotNull { it.courseName }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
    }
    val selectedCourseName = uiState.selectedCourse?.name
    val courseName = when {
        isToday -> selectedCourseName
        pastCourseName != null && pastCourseName in pastCourseNames -> pastCourseName
        selectedCourseName != null && selectedCourseName in pastCourseNames -> selectedCourseName
        else -> pastCourseNames.firstOrNull()
    }
    val todaysRecords = remember(uiState.turnpointPassageRecords, courseName, selectedDay) {
        uiState.turnpointPassageRecords.filter { it.localDate == selectedDay && it.belongsToCourse(courseName) }
            .sortedByDescending { it.timestampMillis }
    }
    // Reference scores for today's goal and partial-lap rows that carry scoring data (see LapScore).
    val laps = remember(todaysRecords, uiState.handicaps) { LapScore.laps(todaysRecords) { uiState.handicapFor(it) } }
    val scores = remember(laps) { LapScore.scores(laps) }
    // "To beat": the lap time each handicap class needs to top today's best score, over the day's
    // course distance. 0.90-1.00 always, the slower classes only when such a glider has scored today.
    val toBeat = remember(laps, isToday) {
        val distanceKm = laps.filter { it.lapSeconds != null }.maxOfOrNull { it.distanceKm }?.takeIf { isToday }
        if (distanceKm == null) {
            emptyList()
        } else {
            (listOf(0.90, 0.95, 1.00) + laps.map { it.handicap }).distinct().sorted().mapNotNull { handicap ->
                LapScore.lapSecondsToBeat(laps, distanceKm, handicap)?.let { ToBeatRow(handicap, it) }
            }
        }
    }
    // Goal and partial-lap rows, pinned above the full history so they don't get buried among the
    // turnpoint passages - in the chosen order. Partial laps (no lap time) follow the laps by lap
    // time, and rows saved before scoring go last in those orders.
    val prefs = LocalContext.current.getSharedPreferences("turnpoint_history", Context.MODE_PRIVATE)
    var sortOrder by remember {
        mutableStateOf(runCatching { FinishSortOrder.valueOf(prefs.getString("finishSortOrder", null)!!) }.getOrDefault(FinishSortOrder.SCORE))
    }
    val todaysFinishes = remember(todaysRecords, scores, sortOrder) {
        val flights = todaysRecords.filter { it.isScoredFlight } // newest first
        when (sortOrder) {
            FinishSortOrder.SCORE -> flights.sortedByDescending { scores[it.id] ?: -1.0 }
            FinishSortOrder.LAP_TIME -> flights.sortedWith(
                compareBy<TurnpointPassageRecord> { it.lapSeconds ?: Double.MAX_VALUE }.thenByDescending { scores[it.id] ?: -1.0 },
            )
            FinishSortOrder.TIME -> flights
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("旋回点通過履歴") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                },
                actions = {
                    if (todaysRecords.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearTurnpointPassages(selectedDay) }) { Text("消去") }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
        DaySelector(
            days = days,
            selectedDay = selectedDay,
            onSelect = { selectedDay = it },
            courseNames = if (isToday) emptyList() else pastCourseNames,
            selectedCourseName = courseName,
            onSelectCourse = { pastCourseName = it },
        )
        if (todaysRecords.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (isToday) "本日の旋回点通過はまだありません" else "この日の記録はありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (todaysFinishes.isNotEmpty()) {
                    item(key = "finishesHeader") { SectionHeader("周回・得点") }
                    val topScore = scores.values.maxOrNull()
                    val leader = todaysFinishes.firstOrNull { scores[it.id] == topScore }
                    if (toBeat.isNotEmpty() && leader != null && topScore != null) {
                        item(key = "toBeat") {
                            ToBeatCard(
                                leaderName = leader.gliderName,
                                leaderScore = topScore,
                                leaderLapSeconds = leader.lapSeconds,
                                rows = toBeat,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    item(key = "sortOrder") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FinishSortOrder.entries.forEach { order ->
                                FilterChip(
                                    selected = sortOrder == order,
                                    onClick = {
                                        sortOrder = order
                                        prefs.edit().putString("finishSortOrder", order.name).apply()
                                    },
                                    label = { Text(order.label) },
                                )
                            }
                        }
                    }
                    items(todaysFinishes, key = { "finish-${it.id}" }) { record ->
                        TurnpointPassageRow(record, scores[record.id], uiState.handicapFor(record.gliderId)) { id, value -> viewModel.setHandicap(id, value) }
                        HorizontalDivider()
                    }
                    if (scores.isNotEmpty()) {
                        item(key = "scoreNote") {
                            Text(
                                "参考得点は競技規則53の式(距離・速度・ハンディキャップ)で、この端末が計時した飛行ごとに計算した目安です(公式成績と同じく小数点以下切り捨て)。周回できなかった飛行は、規則56(1)により最後の有効な旋回点から妻沼に戻るまでの距離点のみです。ゴール高度が280〜330m未満は50点減点、280m未満は速度点なしです(細則7 別表)。「TIME TO BEAT」は、その時間で周回すると全員の得点を計算し直した結果1位(P1)になる時間です(ゴール高度330m以上の場合)。公式成績は各選手のその日最後の飛行をIGCロガーで判定します。ハンディキャップは関東大会の出場機には機種に合わせた値が入っており、それ以外は1.00です。得点の行をタップするか、メンバー一覧で機体ごとに変更できます。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item(key = "allHeader") { SectionHeader("すべての履歴") }
                }
                items(todaysRecords, key = { it.id }) { record ->
                    TurnpointPassageRow(record, scores[record.id], uiState.handicapFor(record.gliderId)) { id, value -> viewModel.setHandicap(id, value) }
                    HorizontalDivider()
                }
            }
        }
        }
    }
}

private val dayFormatter = java.time.format.DateTimeFormatter.ofPattern("M月d日(E)", java.util.Locale.JAPANESE)

/** Picks the day to show (and, for an earlier day judged against several courses, the course). */
@Composable
private fun DaySelector(
    days: List<LocalDate>,
    selectedDay: LocalDate,
    onSelect: (LocalDate) -> Unit,
    courseNames: List<String>,
    selectedCourseName: String?,
    onSelectCourse: (String) -> Unit,
) {
    fun label(day: LocalDate) = (if (day == LocalDate.now()) "今日 " else "") + dayFormatter.format(day)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(label(selectedDay)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    days.forEach { day ->
                        DropdownMenuItem(text = { Text(label(day)) }, onClick = { onSelect(day); expanded = false })
                    }
                }
            }
            if (courseNames.size > 1) {
                var courseExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { courseExpanded = true }) {
                        Text(selectedCourseName?.take(1) ?: "コース", maxLines = 1)
                    }
                    DropdownMenu(expanded = courseExpanded, onDismissRequest = { courseExpanded = false }) {
                        courseNames.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { onSelectCourse(name); courseExpanded = false })
                        }
                    }
                }
            }
        }
        Text(
            "周回・周回未達成の記録と参考得点は30日間、旋回点の通過記録は2日間残ります。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun TurnpointPassageRow(
    record: TurnpointPassageRecord,
    score: Double?,
    handicap: Double,
    onSetHandicap: (String, Double) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (record.isScoredFlight) {
                    Icon(
                        if (record.isFinish) Icons.Filled.Flag else Icons.Outlined.Flag,
                        contentDescription = if (record.isFinish) "ゴール" else "周回未達成",
                        tint = if (record.isFinish) FinishGreen else PartialOrange,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(record.gliderName, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                timeFormatter.format(Instant.ofEpochMilli(record.timestampMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (record.isPartialLap) {
                record.turnpointName
            } else {
                "${record.turnpointName}・${record.altitudeM?.let { "${it.toInt()}m" } ?: "高度不明"}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (record.isScoredFlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (record.isScoredFlight) FontWeight.SemiBold else null,
        )
        val gliderId = record.gliderId
        if (score != null && gliderId != null) {
            // Tappable, so a glider that has left the member list can still get its handicap.
            var expanded by remember { mutableStateOf(false) }
            Box {
                Text(
                    "参考得点 %d点(ハンデ %.2f%s)".format(kotlin.math.floor(score).toInt(), handicap, goalPenaltyNote(record)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinishGreen,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { expanded = true },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    LapScore.handicapOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("%.2f  %s".format(option.value, option.types)) },
                            onClick = {
                                onSetHandicap(gliderId, option.value)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** "・ゴール高度330m未満 -50点" / "・ゴール280m未満のため速度点なし" for a low goal (細則7 別表(4)). */
private fun goalPenaltyNote(record: TurnpointPassageRecord): String {
    if (!record.isFinish) return ""
    return when (LapScore.goalPenalty(record.altitudeM)) {
        LapScore.GoalPenalty.NONE -> ""
        LapScore.GoalPenalty.DEDUCTION -> "・ゴール高度330m未満 -50点"
        LapScore.GoalPenalty.NO_SPEED_POINTS -> "・ゴール280m未満のため速度点なし"
    }
}

private val FinishGreen = Color(0xFF2E7D32)
private val PartialOrange = Color(0xFFEF6C00)
