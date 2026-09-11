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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Satellite
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cobaltloom.loraviewer.data.alert.AlertSeverity
import com.cobaltloom.loraviewer.data.alert.AltitudeCalculationMode
import com.cobaltloom.loraviewer.data.alert.CompetitionAltitudeGuideline
import com.cobaltloom.loraviewer.data.alert.CompetitionTaskCourseData
import com.cobaltloom.loraviewer.data.alert.DistanceReferencePointData
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeGuideline
import com.cobaltloom.loraviewer.data.alert.overallSeverity
import com.cobaltloom.loraviewer.data.alert.pointOnCircle
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.ui.common.colorForGlider
import com.cobaltloom.loraviewer.ui.detail.GliderDetailCard
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val JsalMenumaFallbackCenter = LatLng(36.1994, 139.4429)

/** Extra breathing room applied to the tight bounding box of the initial positions. */
private const val InitialBoundsPaddingFactor = 1.4

/** Floor on the initial bounds' span (degrees, ~1.1km), so a single glider or a tight cluster on
 * the ground still gets a reasonably zoomed-out view instead of one tight to just its exact
 * position. Mirrors the iOS app's MKCoordinateRegion(coordinates:). */
private const val MinInitialSpanDegrees = 0.01

/** A bounding box around [positions], padded so the initial camera fit doesn't zoom in tighter than useful. */
private fun paddedBounds(positions: List<GliderPosition>): LatLngBounds {
    var minLat = positions[0].lat
    var maxLat = positions[0].lat
    var minLon = positions[0].lon
    var maxLon = positions[0].lon
    for (position in positions) {
        minLat = minOf(minLat, position.lat)
        maxLat = maxOf(maxLat, position.lat)
        minLon = minOf(minLon, position.lon)
        maxLon = maxOf(maxLon, position.lon)
    }
    val centerLat = (minLat + maxLat) / 2
    val centerLon = (minLon + maxLon) / 2
    val latSpan = maxOf((maxLat - minLat) * InitialBoundsPaddingFactor, MinInitialSpanDegrees)
    val lonSpan = maxOf((maxLon - minLon) * InitialBoundsPaddingFactor, MinInitialSpanDegrees)
    return LatLngBounds(
        LatLng(centerLat - latSpan / 2, centerLon - lonSpan / 2),
        LatLng(centerLat + latSpan / 2, centerLon + lonSpan / 2),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: GliderTrackerViewModel,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrackHistory: () -> Unit,
    onOpenTurnpointHistory: () -> Unit,
    onRequireSubscription: () -> Unit,
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
        cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(paddedBounds(uiState.positions), 24))
    }

    // Kept up to date as the map is panned/zoomed, so distance labels on the alert circles can be
    // placed on whichever side of the circle faces the visible area, instead of a fixed compass
    // point that can pan or zoom out of view.
    val cameraTarget = cameraPositionState.position.target

    val hasAnyFavoritePosition = uiState.positions.any { uiState.isFavorite(it.imei) }
    val alertReferenceCoordinate = if (uiState.isSubscribed && uiState.alertSettings.isEnabled) {
        uiState.alertSettings.referenceCoordinate(DefaultAlertReferenceCoordinate)
    } else {
        null
    }
    val competitionGuidelineActive = uiState.isSubscribed && uiState.competitionGuidelineSettings.isEnabled
    val taskCourseActive = uiState.isSubscribed && uiState.competitionGuidelineSettings.showTaskCourse
    val upperAltitudeActive = uiState.isSubscribed && uiState.upperAltitudeSettings.isEnabled

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
                    IconButton(onClick = viewModel::toggleSatelliteMap) {
                        Icon(
                            imageVector = if (uiState.showSatelliteMap) Icons.Filled.Map else Icons.Filled.Satellite,
                            contentDescription = if (uiState.showSatelliteMap) "標準地図に切り替え" else "航空写真に切り替え",
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
                    TextButton(
                        onClick = { if (uiState.isSubscribed) onOpenTurnpointHistory() else onRequireSubscription() },
                    ) {
                        Icon(Icons.Filled.ListAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("通過履歴")
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
                properties = MapProperties(
                    isMyLocationEnabled = hasLocationPermission,
                    mapType = if (uiState.showSatelliteMap) MapType.HYBRID else MapType.NORMAL,
                ),
                uiSettings = MapUiSettings(myLocationButtonEnabled = hasLocationPermission),
                onMapLoaded = { mapLoaded = true },
            ) {
                if (alertReferenceCoordinate != null) {
                    if (uiState.alertSettings.mode == AltitudeCalculationMode.STEPS) {
                        uiState.alertSettings.steps.forEach { step ->
                            val radiusMeters = step.distanceKm * 1000
                            Circle(
                                center = LatLng(alertReferenceCoordinate.latitude, alertReferenceCoordinate.longitude),
                                radius = radiusMeters,
                                fillColor = Color.Red.copy(alpha = 0.04f),
                                strokeColor = Color.Red.copy(alpha = 0.4f),
                                strokeWidth = 1f,
                            )
                            val labelPoint = pointOnCircle(
                                alertReferenceCoordinate.latitude, alertReferenceCoordinate.longitude,
                                radiusMeters, cameraTarget.latitude, cameraTarget.longitude,
                            )
                            MarkerComposable(
                                state = MarkerState(LatLng(labelPoint.latitude, labelPoint.longitude)),
                                anchor = Offset(0.5f, 0.5f),
                            ) {
                                DistanceLabel(step.distanceKm)
                            }
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

                if (competitionGuidelineActive) {
                    val ref = CompetitionAltitudeGuideline.referenceCoordinate
                    val refLatLng = LatLng(ref.latitude, ref.longitude)
                    Circle(
                        center = refLatLng,
                        radius = CompetitionAltitudeGuideline.INNER_RADIUS_KM * 1000,
                        fillColor = Color(0xFF9C27B0).copy(alpha = 0.06f),
                        strokeColor = Color(0xFF9C27B0).copy(alpha = 0.5f),
                        strokeWidth = 1f,
                    )
                    run {
                        val labelPoint = pointOnCircle(
                            ref.latitude, ref.longitude,
                            CompetitionAltitudeGuideline.INNER_RADIUS_KM * 1000, cameraTarget.latitude, cameraTarget.longitude,
                        )
                        MarkerComposable(
                            state = MarkerState(LatLng(labelPoint.latitude, labelPoint.longitude)),
                            anchor = Offset(0.5f, 0.5f),
                        ) {
                            DistanceLabel(CompetitionAltitudeGuideline.INNER_RADIUS_KM)
                        }
                    }
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
                            val labelPoint = pointOnCircle(ref.latitude, ref.longitude, km * 1000, cameraTarget.latitude, cameraTarget.longitude)
                            MarkerComposable(
                                state = MarkerState(LatLng(labelPoint.latitude, labelPoint.longitude)),
                                anchor = Offset(0.5f, 0.5f),
                            ) {
                                DistanceLabel(km)
                            }
                        }
                    MarkerComposable(state = MarkerState(refLatLng), title = "競技会基準地点", anchor = Offset(0.5f, 0.5f)) {
                        Icon(Icons.Filled.SportsScore, contentDescription = null, tint = Color(0xFF9C27B0))
                    }
                }

                if (taskCourseActive) {
                    CompetitionTaskCourseData.turnpointDisplayOrder.forEach { name ->
                        val point = CompetitionTaskCourseData.turnpoints[name] ?: return@forEach
                        MarkerComposable(
                            state = MarkerState(LatLng(point.latitude, point.longitude)),
                            title = name,
                            anchor = Offset(0.5f, 0.5f),
                        ) {
                            Icon(Icons.Filled.Navigation, contentDescription = null, tint = Color(0xFFFF9800))
                        }
                    }
                    val managementPoint = CompetitionTaskCourseData.turnpoints["管理ポイント"] ?: CompetitionAltitudeGuideline.referenceCoordinate
                    Circle(
                        center = LatLng(managementPoint.latitude, managementPoint.longitude),
                        radius = CompetitionTaskCourseData.MANAGEMENT_POINT_RADIUS_KM * 1000,
                        fillColor = Color(0xFFFF9800).copy(alpha = 0.05f),
                        strokeColor = Color(0xFFFF9800).copy(alpha = 0.4f),
                        strokeWidth = 1f,
                    )
                    val selectedCourseIndex = uiState.competitionGuidelineSettings.selectedCourseIndex
                    val selectedCourse = selectedCourseIndex?.let { CompetitionTaskCourseData.courses.getOrNull(it) }
                    if (selectedCourse != null) {
                        Polyline(
                            points = CompetitionTaskCourseData.coordinates(selectedCourse).map { LatLng(it.latitude, it.longitude) },
                            color = Color(0xFFFF9800),
                            width = 6f,
                        )
                    }
                }

                if (upperAltitudeActive) {
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
                if (uiState.isSubscribed && uiState.showDistanceReferencePoints) {
                    DistanceReferencePointData.points.forEach { point ->
                        MarkerComposable(
                            state = MarkerState(LatLng(point.coordinate.latitude, point.coordinate.longitude)),
                            title = point.name,
                            anchor = Offset(0.5f, 0.5f),
                        ) {
                            Surface(shape = CircleShape, color = Color(0xFF3F51B5)) {
                                Text(
                                    text = point.name.first().toString(),
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(5.dp),
                                )
                            }
                        }
                    }
                }

                if (uiState.showGliderTrails) {
                    uiState.displayedPositions.forEach { glider ->
                        val trail = uiState.trails[glider.imei]
                        if (trail != null && trail.size > 1) {
                            Polyline(
                                points = trail.map { LatLng(it.latitude, it.longitude) },
                                color = colorForGlider(glider.imei),
                                width = 4f,
                            )
                        }
                    }
                }

                uiState.displayedPositions.forEach { glider ->
                    MarkerComposable(
                        state = MarkerState(position = LatLng(glider.lat, glider.lon)),
                        title = uiState.nameFor(glider),
                        anchor = Offset(0.5f, 0.5f),
                        onClick = { selectedGlider = glider; true },
                    ) {
                        Box {
                            GliderMarkerContent(
                                glider = glider,
                                isSelected = selectedGlider?.imei == glider.imei,
                                isFavorite = uiState.isFavorite(glider.imei),
                                alertSeverity = uiState.alertReasons(glider).overallSeverity(),
                            )
                            val trail = uiState.trails[glider.imei]
                            if (uiState.showGliderTrails && trail != null && trail.size > 1) {
                                GliderNameLabel(
                                    name = uiState.nameFor(glider),
                                    color = colorForGlider(glider.imei),
                                    modifier = Modifier.align(Alignment.TopStart).offset(x = 30.dp, y = (-4).dp),
                                )
                            }
                        }
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
                    onToggleFavorite = {
                        if (uiState.isSubscribed) viewModel.toggleFavorite(glider.imei) else onRequireSubscription()
                    },
                    onEditName = {
                        if (uiState.isSubscribed) editingGlider = glider else onRequireSubscription()
                    },
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

/** Small distance-in-km label used on the alert circles, e.g. "3.0km". */
@Composable
private fun DistanceLabel(km: Double) {
    Surface(color = Color.White.copy(alpha = 0.85f), shape = MaterialTheme.shapes.large) {
        Text(
            text = "%.1fkm".format(km),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
