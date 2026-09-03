package com.cobaltloom.loraviewer.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cobaltloom.loraviewer.data.model.GliderPosition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

private val JsalMenumaFallbackCenter = LatLng(36.1994, 139.4429)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: GliderTrackerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLocationPermission = granted }

    var mapLoaded by remember { mutableStateOf(false) }
    var didCenterInitially by remember { mutableStateOf(false) }
    var editingGlider by remember { mutableStateOf<GliderPosition?>(null) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(JsalMenumaFallbackCenter, 12f)
    }

    LaunchedEffect(Unit) {
        viewModel.startPolling()
        if (!hasLocationPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(uiState.positions, mapLoaded) {
        if (!mapLoaded || didCenterInitially || uiState.positions.isEmpty()) return@LaunchedEffect
        didCenterInitially = true
        val positions = uiState.positions
        if (positions.size == 1) {
            val only = positions.first()
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), 14f),
            )
        } else {
            val bounds = LatLngBounds.Builder().apply {
                positions.forEach { include(LatLng(it.lat, it.lon)) }
            }.build()
            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 100))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(uiState.config?.siteTitle?.ifEmpty { null } ?: "LoRa妻沼") })
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                uiSettings = MapUiSettings(myLocationButtonEnabled = hasLocationPermission),
                onMapLoaded = { mapLoaded = true },
            ) {
                uiState.positions.forEach { glider ->
                    MarkerComposable(
                        state = MarkerState(position = LatLng(glider.lat, glider.lon)),
                        title = uiState.nameFor(glider),
                        anchor = Offset(0.5f, 0.5f),
                        onClick = { editingGlider = glider; true },
                    ) {
                        GliderMarkerContent(glider)
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "機体数: ${uiState.positions.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        val lastUpdated = uiState.lastUpdated
                        if (lastUpdated != null) {
                            Text(
                                text = "更新: ${formatTime(lastUpdated)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
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

    editingGlider?.let { glider ->
        var nicknameInput by remember(glider.imei) { mutableStateOf(uiState.nicknames[glider.imei] ?: "") }
        AlertDialog(
            onDismissRequest = { editingGlider = null },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setNickname(glider.imei, nicknameInput)
                    editingGlider = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingGlider = null }) { Text("キャンセル") }
            },
            title = { Text("ニックネームを編集") },
            text = {
                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = { nicknameInput = it },
                    label = { Text("${glider.index}番機のニックネーム") },
                    singleLine = true,
                )
            },
        )
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTime(instant: Instant): String = timeFormatter.format(instant)
