package com.cobaltloom.loraviewer.ui.standings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.Competition
import com.cobaltloom.loraviewer.data.alert.CompetitionScoring
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import java.time.LocalDate

/** One team being edited. */
private data class TeamDraft(val name: String, val gliderIds: List<String>)

private fun Double?.asField(): String = this?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()

private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

/**
 * Where a representative sets up the competition - name, period, teams and their gliders - and
 * corrects or confirms any day's team scores (penalties, or scores that differ from the app's own);
 * saving shares it with every app.
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
    val teams = remember { mutableStateListOf<TeamDraft>().apply { initial?.teams?.forEach { add(TeamDraft(it.name, it.gliderIds)) } } }
    // Date → team → (団体, 全国選出点) as typed; blank = the app's own score.
    val corrections = remember {
        mutableStateMapOf<String, Map<String, Pair<String, String>>>().apply {
            initial?.days?.forEach { (date, scores) ->
                put(date, scores.mapValues { (_, s) -> s.total.asField() to s.national.asField() })
            }
        }
    }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Gliders the app knows now, for picking a team's gliders: id → shown name.
    val known = uiState.positions.filter { !it.isAnonymous }.associate { it.imei to uiState.baseNameFor(it) }
    fun nameOf(id: String) = known[id]?.let { "$it ($id)" } ?: id

    fun draft(): Competition = Competition(
        name = name.trim(),
        startDate = parseDate(startDate)?.toString(),
        endDate = parseDate(endDate)?.toString(),
        teams = teams.filter { it.name.isNotBlank() }.map { Competition.Team(it.name.trim(), it.gliderIds) },
        days = corrections.mapValues { (_, byTeam) ->
            byTeam.mapValues { (_, value) -> Competition.DayScore(value.first.trim().toDoubleOrNull(), value.second.trim().toDoubleOrNull()) }
                .filterValues { it.total != null || it.national != null }
        }.filterValues { it.isNotEmpty() },
    )
    // The day whose scores are being checked/corrected, and the app's own scores for it.
    val days = remember(startDate, endDate) { draft().dates(today) }
    var selectedDay by remember { mutableStateOf(days.lastOrNull()) }
    val auto = remember(selectedDay, teams.toList(), uiState.turnpointPassageRecords, uiState.handicaps) {
        selectedDay?.let { day ->
            val records = uiState.recordsOn(day)
            val course = if (day == today) uiState.selectedCourse?.name else CompetitionScoring.dayCourse(records)
            CompetitionScoring.autoDayScores(draft(), records, course) { uiState.handicapFor(it) }
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
                        "期間内の毎日、各チームの機体の参考得点(上位3本)を自動で積み上げます。競技のない日は0点です。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            item(key = "teamsHeader") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("チーム", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { teams.add(TeamDraft("", emptyList())) }) { Text("チームを追加") }
                        OutlinedButton(
                            onClick = {
                                val assigned = teams.flatMap { it.gliderIds }.toSet()
                                known.filterKeys { it !in assigned }.forEach { (id, n) -> teams.add(TeamDraft(n, listOf(id))) }
                            },
                        ) { Text("今の機体から作成") }
                    }
                    Text(
                        "機体は、地図に今出ている機体(LoRa・FLARMそれぞれ)から選びます。LoRaとFLARMの両方を使う場合は、同じチームに両方の機体を選んでください。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(teams, key = { index, _ -> "team$index" }) { index, team ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            OutlinedTextField(
                                value = team.name, onValueChange = { teams[index] = team.copy(name = it) }, label = { Text("チーム名") },
                                singleLine = true, modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { teams.removeAt(index) }) { Icon(Icons.Filled.Delete, contentDescription = "削除") }
                        }
                        Text("機体: " + team.gliderIds.joinToString("、") { nameOf(it) }.ifEmpty { "なし" }, style = MaterialTheme.typography.bodySmall)
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text("機体を選ぶ") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                (known.keys + team.gliderIds).distinct().sorted().forEach { id ->
                                    DropdownMenuItem(
                                        text = { Text((if (id in team.gliderIds) "✓ " else "") + nameOf(id)) },
                                        onClick = {
                                            // Read afresh: the menu stays open across several picks.
                                            val current = teams[index]
                                            teams[index] = current.copy(
                                                gliderIds = if (id in current.gliderIds) current.gliderIds - id else current.gliderIds + id,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "daysHeader") {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("日ごとの得点の修正", style = MaterialTheme.typography.titleMedium)
                    if (days.isEmpty()) {
                        Text("期間を設定すると、日ごとの得点を確認・修正できます。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text(selectedDay?.let { "第${days.indexOf(it) + 1}日 $it" } ?: "日付を選ぶ")
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                days.forEachIndexed { i, day ->
                                    DropdownMenuItem(text = { Text("第${i + 1}日 $day") }, onClick = { selectedDay = day; expanded = false })
                                }
                            }
                        }
                        Text(
                            "空欄はこの端末の記録から計算した値(灰色)を使います。ペナルティなどで公式と違うときだけ入力してください。「この日の自動値で確定」で、全員の端末を同じ値にそろえられます。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                val day = selectedDay?.toString() ?: return@OutlinedButton
                                val existing = corrections[day].orEmpty()
                                corrections[day] = teams.filter { it.name.isNotBlank() }.associate { t ->
                                    val teamName = t.name.trim()
                                    val a = auto[teamName]
                                    val e = existing[teamName]
                                    teamName to (
                                        (e?.first?.takeIf { it.isNotBlank() } ?: a?.total.asField()) to
                                            (e?.second?.takeIf { it.isNotBlank() } ?: a?.national.asField())
                                        )
                                }
                            },
                        ) { Text("この日の自動値で確定") }
                    }
                }
            }
            val day = selectedDay?.toString()
            if (day != null) {
                itemsIndexed(teams.filter { it.name.isNotBlank() }, key = { i, t -> "day$day$i${t.name}" }) { _, team ->
                    val teamName = team.name.trim()
                    val value = corrections[day]?.get(teamName) ?: ("" to "")
                    val a = auto[teamName]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(teamName, modifier = Modifier.weight(1f).padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = value.first,
                            onValueChange = { corrections[day] = corrections[day].orEmpty() + (teamName to (it to value.second)) },
                            label = { Text("団体") }, placeholder = { Text(a?.total?.toInt()?.toString() ?: "0") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = value.second,
                            onValueChange = { corrections[day] = corrections[day].orEmpty() + (teamName to (value.first to it)) },
                            label = { Text("全国") }, placeholder = { Text(a?.national?.toInt()?.toString() ?: "0") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
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
