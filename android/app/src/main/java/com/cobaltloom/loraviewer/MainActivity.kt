package com.cobaltloom.loraviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cobaltloom.loraviewer.data.alert.AlertSettingsRepository
import com.cobaltloom.loraviewer.data.alert.CompetitionGuidelineRepository
import com.cobaltloom.loraviewer.data.alert.Coordinate
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeGuidelineRepository
import com.cobaltloom.loraviewer.data.favorites.FavoritesRepository
import com.cobaltloom.loraviewer.data.nickname.NicknameRepository
import com.cobaltloom.loraviewer.data.notification.AlertNotifier
import com.cobaltloom.loraviewer.data.remote.TrailRouteApiClient
import com.cobaltloom.loraviewer.data.repository.GliderRepository
import com.cobaltloom.loraviewer.data.settings.ApiSettingsRepository
import com.cobaltloom.loraviewer.ui.boardscan.BoardScanScreen
import com.cobaltloom.loraviewer.ui.list.GliderListScreen
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import com.cobaltloom.loraviewer.ui.map.MapScreen
import com.cobaltloom.loraviewer.ui.settings.ReferencePointPickerScreen
import com.cobaltloom.loraviewer.ui.settings.SettingsScreen
import com.cobaltloom.loraviewer.ui.theme.LoraViewerTheme
import com.cobaltloom.loraviewer.ui.track.TrackHistoryScreen
import com.cobaltloom.loraviewer.ui.track.TrackHistoryViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LoraViewerTheme {
                LoraViewerApp()
            }
        }
    }
}

private object Routes {
    const val MAP = "map"
    const val LIST = "list"
    const val SETTINGS = "settings"
    const val TRACK_HISTORY = "trackHistory"
    const val BOARD_SCAN = "boardScan"
    const val REFERENCE_POINT_PICKER = "referencePointPicker"
}

@Composable
private fun LoraViewerApp() {
    val context = LocalContext.current
    val apiSettingsRepository = remember { ApiSettingsRepository(context) }
    val gliderRepository = remember {
        GliderRepository(TrailRouteApiClient(), apiSettingsRepository)
    }

    val trackerFactory = remember {
        val nicknameRepository = NicknameRepository(context)
        val favoritesRepository = FavoritesRepository(context)
        val alertSettingsRepository = AlertSettingsRepository(context)
        val competitionGuidelineRepository = CompetitionGuidelineRepository(context)
        val upperAltitudeGuidelineRepository = UpperAltitudeGuidelineRepository(context)
        val alertNotifier = AlertNotifier(context)
        viewModelFactory {
            initializer {
                GliderTrackerViewModel(
                    repository = gliderRepository,
                    nicknameRepository = nicknameRepository,
                    favoritesRepository = favoritesRepository,
                    alertSettingsRepository = alertSettingsRepository,
                    competitionGuidelineRepository = competitionGuidelineRepository,
                    upperAltitudeGuidelineRepository = upperAltitudeGuidelineRepository,
                    alertNotifier = alertNotifier,
                )
            }
        }
    }
    val viewModel: GliderTrackerViewModel = viewModel(factory = trackerFactory)

    val trackHistoryFactory = remember {
        viewModelFactory { initializer { TrackHistoryViewModel(gliderRepository) } }
    }
    val trackHistoryViewModel: TrackHistoryViewModel = viewModel(factory = trackHistoryFactory)

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.MAP) {
        composable(Routes.MAP) {
            MapScreen(
                viewModel = viewModel,
                onOpenList = { navController.navigate(Routes.LIST) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenTrackHistory = { navController.navigate(Routes.TRACK_HISTORY) },
            )
        }
        composable(Routes.LIST) {
            GliderListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenBoardScan = { navController.navigate(Routes.BOARD_SCAN) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                apiSettingsRepository = apiSettingsRepository,
                onBack = { navController.popBackStack() },
                onOpenReferencePointPicker = { navController.navigate(Routes.REFERENCE_POINT_PICKER) },
            )
        }
        composable(Routes.TRACK_HISTORY) {
            TrackHistoryScreen(viewModel = trackHistoryViewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.BOARD_SCAN) {
            BoardScanScreen(viewModel = viewModel, onDone = { navController.popBackStack() })
        }
        composable(Routes.REFERENCE_POINT_PICKER) {
            val current by viewModel.uiState.collectAsState()
            val alertSettings = current.alertSettings
            val initial = if (alertSettings.customLatitude != 0.0 || alertSettings.customLongitude != 0.0) {
                Coordinate(alertSettings.customLatitude, alertSettings.customLongitude)
            } else {
                null
            }
            ReferencePointPickerScreen(
                initialCoordinate = initial,
                onConfirm = { coordinate ->
                    viewModel.updateAlertSettings(
                        alertSettings.copy(customLatitude = coordinate.latitude, customLongitude = coordinate.longitude),
                    )
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() },
            )
        }
    }
}
