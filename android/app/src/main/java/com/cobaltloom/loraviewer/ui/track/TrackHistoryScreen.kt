package com.cobaltloom.loraviewer.ui.track

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.model.TrackPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val trackColors = listOf(
    Color(0xFF00BCD4), Color(0xFFE91E63), Color(0xFF4CAF50), Color(0xFFFF9800),
    Color(0xFF2196F3), Color(0xFF9C27B0), Color(0xFFF44336), Color(0xFFFFEB3B),
)

private fun colorFor(imei: String): Color = trackColors[Math.floorMod(imei.hashCode(), trackColors.size)]

private val displayFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackHistoryScreen(viewModel: TrackHistoryViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var startInstant by remember { mutableStateOf(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()) }
    var endInstant by remember { mutableStateOf(Instant.now()) }
    var editingField by remember { mutableStateOf<TrackHistoryField?>(null) }
    val cameraPositionState = rememberCameraPositionState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("行動軌跡") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { editingField = TrackHistoryField.START }, modifier = Modifier.weight(1f)) {
                        Text("開始: ${displayFormatter.format(startInstant)}")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedButton(onClick = { editingField = TrackHistoryField.END }, modifier = Modifier.weight(1f)) {
                        Text("終了: ${displayFormatter.format(endInstant)}")
                    }
                }
                Button(
                    onClick = { viewModel.search(startInstant, endInstant) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    enabled = !uiState.isLoading,
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text("検索する")
                }
            }

            GoogleMap(modifier = Modifier.weight(1f).fillMaxWidth(), cameraPositionState = cameraPositionState) {
                uiState.trackData.forEach { (imei, device) ->
                    val color = colorFor(imei)
                    val points = device.positionLog.map { LatLng(it.lat, it.lon) }
                    if (points.size >= 2) {
                        Polyline(points = points, color = color, width = 6f)
                    }
                    device.positionLog.forEach { point: TrackPoint ->
                        Circle(
                            center = LatLng(point.lat, point.lon),
                            radius = 20.0,
                            fillColor = color,
                            strokeColor = color,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(uiState.trackData) {
        val allPoints = uiState.trackData.values.flatMap { it.positionLog }.map { LatLng(it.lat, it.lon) }
        if (allPoints.isEmpty()) return@LaunchedEffect
        if (allPoints.size == 1) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(allPoints.first(), 13f))
        } else {
            val bounds = LatLngBounds.Builder().apply { allPoints.forEach { include(it) } }.build()
            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 100))
        }
    }

    uiState.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
            title = { Text("エラー") },
            text = { Text(message) },
        )
    }

    editingField?.let { field ->
        val initial = if (field == TrackHistoryField.START) startInstant else endInstant
        DateTimePickerDialog(
            initial = initial,
            onConfirm = { picked ->
                if (field == TrackHistoryField.START) startInstant = picked else endInstant = picked
                editingField = null
            },
            onDismiss = { editingField = null },
        )
    }
}

private enum class TrackHistoryField { START, END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerDialog(initial: Instant, onConfirm: (Instant) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val initialLocal = initial.atZone(zone)
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialLocal.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val timePickerState = rememberTimePickerState(
        initialHour = initialLocal.hour,
        initialMinute = initialLocal.minute,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val selectedMillis = datePickerState.selectedDateMillis
                val localDate = if (selectedMillis != null) {
                    Instant.ofEpochMilli(selectedMillis).atZone(ZoneOffset.UTC).toLocalDate()
                } else {
                    initialLocal.toLocalDate()
                }
                val localTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                onConfirm(localDate.atTime(localTime).atZone(zone).toInstant())
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
        text = {
            Column {
                DatePicker(state = datePickerState, showModeToggle = false)
                TimePicker(state = timePickerState)
            }
        },
    )
}
