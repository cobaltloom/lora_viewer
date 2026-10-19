package com.cobaltloom.loraviewer.ui.standings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.Competition
import com.cobaltloom.loraviewer.data.alert.CompetitionScoring
import com.cobaltloom.loraviewer.data.alert.CompetitionTaskCourseData
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import java.time.LocalDate

private fun Double?.asField(): String = this?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()

private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

/**
 * Where the competition is set up - name and period - and, per day, the course flown (when the
 * automatic pick is wrong) and any corrected glider scores (penalties, or where they differ from the
 * official results), plus shared handicaps; saving shares it with every app. The gliders themselves
 * come from the track server's results for the current source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandingsEditorScreen(viewModel: GliderTrackerViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val initial = uiState.competition
    val today = LocalDate.now()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var startDate by remember { mutableStateOf(initial?.startDate ?: today.toString()) }
    var endDate by remember { mutableStateOf(initial?.endDate ?: today.plusDays(4).toString()) }
    // Date → course name, or "" for the automatic pick.
    val courses = remember { mutableStateMapOf<String, String>().apply { initial?.days?.forEach { (d, day) -> put(d, day.course.orEmpty()) } } }
    // Date → glider → (団体, 全国選出点) as typed; blank = the server results' score.
    val corrections = remember {
        mutableStateMapOf<String, Map<String, Pair<String, String>>>().apply {
            initial?.days?.forEach { (d, day) -> put(d, day.scores.mapValues { (_, s) -> s.total.asField() to s.national.asField() }) }
        }
    }
    // Glider → shared handicap as typed; blank = each device's own.
    val handicaps = remember { mutableStateMapOf<String, String>().apply { initial?.handicaps?.forEach { (id, h) -> put(id, h.toString()) } } }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun draft(): Competition = Competition(
        name = name.trim(),
        startDate = parseDate(startDate)?.toString(),
        endDate = parseDate(endDate)?.toString(),
        days = (courses.keys + corrections.keys).associateWith { date ->
            Competition.Day(
                course = courses[date]?.takeIf { it.isNotBlank() },
                scores = corrections[date].orEmpty()
                    .mapValues { (_, v) -> Competition.DayScore(v.first.trim().toDoubleOrNull(), v.second.trim().toDoubleOrNull()) }
                    .filterValues { it.total != null || it.national != null },
            )
        }.filterValues { it.course != null || it.scores.isNotEmpty() },
        handicaps = handicaps.mapNotNull { (id, v) -> v.trim().toDoubleOrNull()?.let { id to it } }.toMap(),
    )
    val days = remember(startDate, endDate) { draft().dates(today) }
    var selectedDay by remember { mutableStateOf(days.lastOrNull()) }
    val gliders = remember(startDate, endDate, uiState.competitionResults) { CompetitionScoring.gliders(draft(), today, uiState.competitionResults) }
    val dayKey = selectedDay?.toString()
    val dayResults = dayKey?.let { uiState.competitionResults[it] }
    val autoCourse = selectedDay?.let { CompetitionScoring.dayCourse(draft().copy(days = emptyMap()), it, dayResults) }
    // The server results' scores for the selected day on its (chosen or automatic) course, uncorrected.
    val auto = remember(dayKey, courses.toMap(), uiState.competitionResults, handicaps.toMap(), uiState.handicaps) {
        selectedDay?.let { day ->
            val uncorrected = draft().let { c -> c.copy(days = c.days.mapValues { (_, d) -> d.copy(scores = emptyMap()) }) }
            CompetitionScoring.dayScores(uncorrected, day, dayResults) { id ->
                handicaps[id]?.trim()?.toDoubleOrNull() ?: uiState.handicapFor(id)
            }
        }.orEmpty()
    }

    fun save() {
        if (parseDate(startDate) == null || parseDate(endDate) == null) {
            message = "開始日・終了日は 2026-10-11 の形で入力してください"
            return
        }
        saving = true
        viewModel.saveCompetition(draft()) { error ->
            saving = false
            message = error ?: "保存しました。全員の団体順位に反映されます。"
            if (error == null) viewModel.refreshCompetition()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("競技会の設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                },
                actions = { TextButton(onClick = { save() }, enabled = !saving) { Text("保存") } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "general") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text("競技会名") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startDate, onValueChange = { startDate = it }, label = { Text("開始日") },
                            singleLine = true, placeholder = { Text("2026-10-11") }, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = endDate, onValueChange = { endDate = it }, label = { Text("終了日") },
                            singleLine = true, placeholder = { Text("2026-10-18") }, modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "期間中に飛んだ機体が自動でチームとして並び、トラックサーバーが計時した周回から毎日の得点を積み上げます(端末を閉じていた日も含みます)。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            item(key = "day") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("日ごとのコースと得点の修正", style = MaterialTheme.typography.titleMedium)
                    if (days.isEmpty()) {
                        Text("期間を設定すると、日ごとのコースと得点を確認・修正できます。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            var dayMenu by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { dayMenu = true }) {
                                    Text(selectedDay?.let { "第${days.indexOf(it) + 1}日 $it" } ?: "日付を選ぶ")
                                }
                                DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                                    days.forEachIndexed { i, day ->
                                        DropdownMenuItem(text = { Text("第${i + 1}日 $day") }, onClick = { selectedDay = day; dayMenu = false })
                                    }
                                }
                            }
                            var courseMenu by remember { mutableStateOf(false) }
                            Box {
                                val chosen = dayKey?.let { courses[it] }.orEmpty()
                                OutlinedButton(onClick = { courseMenu = true }) {
                                    Text(if (chosen.isBlank()) "コース: 自動(${autoCourse?.take(1) ?: "-"})" else "コース: ${chosen.take(1)}")
                                }
                                DropdownMenu(expanded = courseMenu, onDismissRequest = { courseMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("自動(周回が最も多いコース: ${autoCourse ?: "なし"})") },
                                        onClick = { dayKey?.let { courses[it] = "" }; courseMenu = false },
                                    )
                                    CompetitionTaskCourseData.courses.forEach { course ->
                                        DropdownMenuItem(text = { Text(course.name) }, onClick = { dayKey?.let { courses[it] = course.name }; courseMenu = false })
                                    }
                                }
                            }
                        }
                        Text(
                            "得点の空欄はトラックサーバーの計時から計算した値(灰色)を使います。ペナルティなどで公式と違うときだけ入力してください。ハンデの空欄は各端末の設定値です。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                val key = dayKey ?: return@OutlinedButton
                                val existing = corrections[key].orEmpty()
                                corrections[key] = gliders.associateWith { id ->
                                    val a = auto[id]
                                    val e = existing[id]
                                    (e?.first?.takeIf { it.isNotBlank() } ?: a?.total.asField()) to
                                        (e?.second?.takeIf { it.isNotBlank() } ?: a?.national.asField())
                                }
                            },
                        ) { Text("この日の自動値で確定") }
                    }
                }
            }
            if (dayKey != null) {
                items(gliders, key = { "g$dayKey$it" }) { id ->
                    val value = corrections[dayKey]?.get(id) ?: ("" to "")
                    val a = auto[id]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(uiState.standingsName(id), modifier = Modifier.weight(1.3f).padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = value.first,
                            onValueChange = { corrections[dayKey] = corrections[dayKey].orEmpty() + (id to (it to value.second)) },
                            label = { Text("団体") }, placeholder = { Text(a?.total?.toInt()?.toString() ?: "0") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = value.second,
                            onValueChange = { corrections[dayKey] = corrections[dayKey].orEmpty() + (id to (value.first to it)) },
                            label = { Text("全国") }, placeholder = { Text(a?.national?.toInt()?.toString() ?: "0") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = handicaps[id].orEmpty(),
                            onValueChange = { handicaps[id] = it },
                            label = { Text("ハンデ") }, placeholder = { Text("%.2f".format(uiState.handicapFor(id))) },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(0.9f),
                        )
                    }
                }
            }
            item(key = "save") {
                Column(Modifier.padding(16.dp)) {
                    Button(onClick = { save() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("保存して全員に共有") }
                }
            }
        }
    }
}
