package com.cobaltloom.loraviewer.ui.turnpoint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.TurnpointPassageRecord
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
    val todaysRecords = remember(uiState.turnpointPassageRecords) {
        val today = LocalDate.now()
        uiState.turnpointPassageRecords.filter { it.localDate == today }.sortedByDescending { it.timestampMillis }
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
                        TextButton(onClick = { viewModel.clearTodaysTurnpointPassages() }) { Text("消去") }
                    }
                },
            )
        },
    ) { padding ->
        if (todaysRecords.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("本日の旋回点通過はまだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(todaysRecords, key = { it.id }) { record ->
                    TurnpointPassageRow(record)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TurnpointPassageRow(record: TurnpointPassageRecord) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(record.gliderName, style = MaterialTheme.typography.titleMedium)
            Text(
                timeFormatter.format(Instant.ofEpochMilli(record.timestampMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${record.turnpointName}・${record.altitudeM?.let { "${it.toInt()}m" } ?: "高度不明"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
