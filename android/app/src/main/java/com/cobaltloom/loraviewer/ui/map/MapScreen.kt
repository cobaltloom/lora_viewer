package com.cobaltloom.loraviewer.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cobaltloom.loraviewer.data.alert.AlertSeverity
import com.cobaltloom.loraviewer.data.alert.AltitudeCalculationMode
import com.cobaltloom.loraviewer.data.alert.CompetitionAltitudeGuideline
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeGuideline
import com.cobaltloom.loraviewer.data.alert.overallSeverity
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.ui.detail.GliderDetailCard
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val JsalMenumaFallbackCenter = LatLng(36.1994, 139.4429)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: GliderTrackerViewModel,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrackHistory: () -> Unit,
) {
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
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    var mapLoaded by remember { mutableStateOf(false) }
    var didCenterInitially by remember { mutableStateOf(false) }
    var selectedGlider by remember { mutableStateOf<GliderPosition?>(null) }
    var editingGlider by remember { mutableStateOf<GliderPosition?>(null) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(JsalMenumaFallbackCenter, 12f)
    }

    LaunchedEffect(Unit) {
        viewModel.startPolling()
        if (!hasLocationPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(uiState.positions, mapLoaded) {
        if (!mapLoaded || didCenterInitially || uiState.positions.isEmpty()) return@LaunchedEffect
        didCenterInitially = true
        val positions = uiState.positions
        if (positions.size == 1) {
            val only = positions.first()
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), 14f))
        } else {
            val bounds = LatLngBounds.Builder().apply {
                positions.forEach { include(LatLng(it.lat, it.lon)) }
            }.build()
            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 100))
        }
    }

    val hasAnyFavoritePosition = uiState.positions.any { uiState.isFavorite(it.imei) }
    val alertReferenceCoordinate = if (uiState.alertSettings.isEnabled) {
        uiState.alertSettings.referenceCoordinate(DefaultAlertReferenceCoordinate)
    } else {
        null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.config?.siteTitle?.ifEmpty { null } ?: "LoRa妻沼") },
                navigationIcon = {
                    IconButton(onClick = onOpenList) {
                        Icon(Icons.Filled.List, contentDescription = "メンバー一覧")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::toggleFavoritesOnly,
                        enabled = hasAnyFavoritePosition || uiState.showFavoritesOnly,
                    ) {
                        Icon(
                            imageVector = if (uiState.showFavoritesOnly) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = "お気に入りのみ表示",
                            tint = if (uiState.showFavoritesOnly) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("機体数: ${uiState.positions.size}", style = MaterialTheme.typography.bodySmall)
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        uiState.lastUpdated?.let {
                            Text("更新: ${formatTime(it)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = onOpenTrackHistory) {
                        Icon(Icons.Filled.Route, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("行動軌跡")
                    }
                }
            }
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
                if (alertReferenceCoordinate != null) {
                    if (uiState.alertSettings.mode == AltitudeCalculationMode.STEPS) {
                        uiState.alertSettings.steps.forEach { step ->
                            Circle(
                                center = LatLng(alertReferenceCoordinate.latitude, alertReferenceCoordinate.longitude),
                                radius = step.distanceKm * 1000,
                                fillColor = Color.Red.copy(alpha = 0.04f),
                                strokeColor = Color.Red.copy(alpha = 0.4f),
                                strokeWidth = 1f,
                            )
                        }
                    }
                    MarkerComposable(
                        state = MarkerState(LatLng(alertReferenceCoordinate.latitude, alertReferenceCoordinate.longitude)),
                        title = "基準地点",
                        anchor = Offset(0.5f, 0.5f),
                    ) {
                        Icon(Icons.Filled.Flag, contentDescription = null, tint = Color.Red)
                    }
                }

                if (uiState.competitionGuidelineEnabled) {
                    val ref = CompetitionAltitudeGuideline.referenceCoordinate
                    val refLatLng = LatLng(ref.latitude, ref.longitude)
                    Circle(
                        center = refLatLng,
                        radius = CompetitionAltitudeGuideline.INNER_RADIUS_KM * 1000,
                        fillColor = Color(0xFF9C27B0).copy(alpha = 0.06f),
                        strokeColor = Color(0xFF9C27B0).copy(alpha = 0.5f),
                        strokeWidth = 1f,
                    )
                    CompetitionAltitudeGuideline.boundaryDistancesKm
                        .filter { it > CompetitionAltitudeGuideline.INNER_RADIUS_KM }
                        .forEach { km ->
                            Circle(
                                center = refLatLng,
                                radius = km * 1000,
                                fillColor = Color.Transparent,
                                strokeColor = Color(0xFF9C27B0).copy(alpha = 0.35f),
                                strokeWidth = 1f,
                            )
                        }
                    MarkerComposable(state = MarkerState(refLatLng), title = "競技会基準地点", anchor = Offset(0.5f, 0.5f)) {
                        Icon(Icons.Filled.SportsScore, contentDescription = null, tint = Color(0xFF9C27B0))
                    }
                }

                if (uiState.upperAltitudeSettings.isEnabled) {
                    Polygon(
                        points = UpperAltitudeGuideline.zoneA.boundary.map { LatLng(it.latitude, it.longitude) },
                        fillColor = Color(0xFF2196F3).copy(alpha = 0.03f),
                        strokeColor = Color(0xFF2196F3).copy(alpha = 0.5f),
                        strokeWidth = 1f,
                    )
                    Polygon(
                        points = UpperAltitudeGuideline.zoneB.boundary.map { LatLng(it.latitude, it.longitude) },
                        fillColor = Color(0xFF00BCD4).copy(alpha = 0.06f),
                        strokeColor = Color(0xFF00BCD4).copy(alpha = 0.6f),
                        strokeWidth = 1.5f,
                    )
                }

                uiState.displayedPositions.forEach { glider ->
                    MarkerComposable(
                        state = MarkerState(position = LatLng(glider.lat, glider.lon)),
                        title = uiState.nameFor(glider),
                        anchor = Offset(0.5f, 0.5f),
                        onClick = { selectedGlider = glider; true },
                    ) {
                        GliderMarkerContent(
                            glider = glider,
                            isSelected = selectedGlider?.imei == glider.imei,
                            isFavorite = uiState.isFavorite(glider.imei),
                            alertSeverity = uiState.alertReasons(glider).overallSeverity(),
                        )
                    }
                }
            }

            Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 8.dp)) {
                if (uiState.activeAlertLabels.isNotEmpty()) {
                    ActiveAlertsIndicator(uiState.activeAlertLabels)
                }
                if (uiState.alertingGliders.isNotEmpty()) {
                    AlertBanner(uiState)
                }
            }

            selectedGlider?.let { glider ->
                GliderDetailCard(
                    glider = glider,
                    displayName = uiState.nameFor(glider),
                    isFavorite = uiState.isFavorite(glider.imei),
                    alertReasons = uiState.alertReasons(glider),
                    onToggleFavorite = { viewModel.toggleFavorite(glider.imei) },
                    onEditName = { editingGlider = glider },
                    onDismiss = { selectedGlider = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                )
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
            dismissButton = { TextButton(onClick = { editingGlider = null }) { Text("キャンセル") } },
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

@Composable
private fun ActiveAlertsIndicator(labels: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = labels.joinToString("・"),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun AlertBanner(uiState: GliderTrackerUiState) {
    val worstSeverity = uiState.alertingGliders.mapNotNull { uiState.alertReasons(it).overallSeverity() }.maxOrNull()
    val lines = uiState.alertingGliders.joinToString("、") { glider ->
        "${uiState.nameFor(glider)}: " + uiState.alertReasons(glider).joinToString("・") { it.label }
    }
    Surface(
        color = if (worstSeverity == AlertSeverity.WARNING) {
            Color(0xFFD32F2F)
        } else {
            Color(0xFFF57C00)
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
            Text(
                text = "高度アラート(${worstSeverity?.label ?: ""}) $lines",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTime(instant: Instant): String = timeFormatter.format(instant)
