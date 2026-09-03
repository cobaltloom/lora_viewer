package com.cobaltloom.loraviewer.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.Coordinate
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * A standalone map for picking a coordinate by panning until a fixed center pin sits where you
 * want it, rather than typing latitude/longitude by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferencePointPickerScreen(
    initialCoordinate: Coordinate?,
    onConfirm: (Coordinate) -> Unit,
    onCancel: () -> Unit,
) {
    val startCoordinate = initialCoordinate ?: com.cobaltloom.loraviewer.data.alert.CompetitionAltitudeGuideline.referenceCoordinate
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(startCoordinate.latitude, startCoordinate.longitude), 13f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("基準地点を選択") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "キャンセル") }
                },
                actions = {
                    TextButton(onClick = {
                        val center = cameraPositionState.position.target
                        onConfirm(Coordinate(center.latitude, center.longitude))
                    }) { Text("この地点に設定") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = cameraPositionState)
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 36.dp),
            )
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Text(
                    "地図を動かして、中央のピンの位置を基準地点に合わせてください",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
