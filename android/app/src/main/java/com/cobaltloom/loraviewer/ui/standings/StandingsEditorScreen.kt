package com.cobaltloom.loraviewer.ui.standings

import android.content.Context
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.TeamStandings
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import java.time.LocalDate

/** One team being edited: text fields as typed, turned into numbers on saving. */
private data class TeamDraft(val name: String, val gliderIds: List<String>, val total: String, val national: String)

private fun Double?.asField(): String = this?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()

/**
 * Where a representative (who knows the editor key set on the track server) enters the competition's
 * teams, their gliders and the official totals so far; saving shares them with every app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandingsEditorScreen(viewModel: GliderTrackerViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val prefs = LocalContext.current.getSharedPreferences("standings", Context.MODE_PRIVATE)
    val initial = uiState.standings
    var editorKey by remember { mutableStateOf(prefs.getString("editorKey", "").orEmpty()) }
    var competition by remember { mutableStateOf(initial?.competition.orEmpty()) }
    var throughDay by remember { mutableStateOf(initial?.throughDay?.toString().orEmpty()) }
    var throughDate by remember { mutableStateOf(initial?.throughDate ?: LocalDate.now().minusDays(1).toString()) }
    val teams = remember {
        mutableStateListOf<TeamDraft>().apply {
            initial?.teams?.forEach { add(TeamDraft(it.name, it.gliderIds, it.total.asField(), it.national.asField())) }
        }
    }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Gliders the app knows now, for picking a team's gliders: id → shown name.
    val known = uiState.positions.filter { !it.isAnonymous }.associate { it.imei to uiState.baseNameFor(it) }
    fun nameOf(id: String) = known[id]?.let { "$it ($id)" } ?: id

    fun save() {
        val badTeam = teams.firstOrNull { it.name.isBlank() }
        if (badTeam != null) {
            message = "チーム名が空のチームがあります"
            return
        }
        val standings = TeamStandings(
            competition = competition.trim(),
            throughDay = throughDay.trim().toIntOrNull(),
            throughDate = throughDate.trim().takeIf { runCatching { LocalDate.parse(it) }.isSuccess },
            teams = teams.map {
                TeamStandings.Team(it.name.trim(), it.gliderIds, it.total.trim().toDoubleOrNull(), it.national.trim().toDoubleOrNull())
            },
        )
        prefs.edit().putString("editorKey", editorKey).apply()
        saving = true
        viewModel.saveStandings(editorKey, standings) { error ->
            saving = false
            message = error ?: "保存しました。全員の団体順位に反映されます。"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("団体成績の入力") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                },
                actions = { TextButton(onClick = { save() }, enabled = !saving) { Text("保存") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "general") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    OutlinedTextField(
                        value = editorKey, onValueChange = { editorKey = it }, label = { Text("合言葉(代表者用)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = competition, onValueChange = { competition = it }, label = { Text("競技会名") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = throughDay, onValueChange = { throughDay = it }, label = { Text("第◯日終了時点") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = throughDate, onValueChange = { throughDate = it }, label = { Text("その日の日付") },
                            singleLine = true, placeholder = { Text("2026-10-17") }, modifier = Modifier.weight(1.4f),
                        )
                    }
                    Text(
                        "日付が今日なら、本日分は公式に含まれているものとして暫定得点を足しません。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { teams.add(TeamDraft("", emptyList(), "", "")) }) { Text("チームを追加") }
                        OutlinedButton(
                            onClick = {
                                val assigned = teams.flatMap { it.gliderIds }.toSet()
                                known.filterKeys { it !in assigned }.forEach { (id, name) -> teams.add(TeamDraft(name, listOf(id), "", "")) }
                            },
                        ) { Text("今の機体から作成") }
                    }
                }
            }
            itemsIndexed(teams, key = { index, _ -> index }) { index, team ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            OutlinedTextField(
                                value = team.name, onValueChange = { teams[index] = team.copy(name = it) }, label = { Text("チーム名") },
                                singleLine = true, modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { teams.removeAt(index) }) { Icon(Icons.Filled.Delete, contentDescription = "削除") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = team.total, onValueChange = { teams[index] = team.copy(total = it) }, label = { Text("団体 累計") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = team.national, onValueChange = { teams[index] = team.copy(national = it) },
                                label = { Text("全国選出点 累計") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "機体: " + team.gliderIds.joinToString("、") { nameOf(it) }.ifEmpty { "なし" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text("機体を選ぶ") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                (known.keys + team.gliderIds).distinct().sorted().forEach { id ->
                                    val selected = id in team.gliderIds
                                    DropdownMenuItem(
                                        text = { Text((if (selected) "✓ " else "") + nameOf(id)) },
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
            item(key = "save") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("保存して全員に共有") }
                    Text(
                        "機体は、地図に今出ている機体(LoRa・FLARMそれぞれ)から選びます。LoRaとFLARMの両方を使う場合は、同じチームに両方の機体を選んでください。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
