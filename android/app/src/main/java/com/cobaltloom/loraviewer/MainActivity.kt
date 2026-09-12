package com.cobaltloom.loraviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.cobaltloom.loraviewer.data.alert.ProximityAlertSettingsRepository
import com.cobaltloom.loraviewer.data.alert.TurnpointPassageLogRepository
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeGuidelineRepository
import com.cobaltloom.loraviewer.data.billing.BillingRepository
import com.cobaltloom.loraviewer.data.favorites.FavoritesRepository
import com.cobaltloom.loraviewer.data.nickname.NicknameRepository
import com.cobaltloom.loraviewer.data.notification.AlertNotifier
import com.cobaltloom.loraviewer.data.remote.TrailRouteApiClient
import com.cobaltloom.loraviewer.data.repository.GliderRepository
import com.cobaltloom.loraviewer.data.settings.ApiSettingsRepository
import com.cobaltloom.loraviewer.data.trail.GliderTrailRepository
import com.cobaltloom.loraviewer.data.trail.MapDisplaySettingsRepository
import com.cobaltloom.loraviewer.ui.boardscan.BoardScanScreen
import com.cobaltloom.loraviewer.ui.list.GliderListScreen
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import com.cobaltloom.loraviewer.ui.map.MapScreen
import com.cobaltloom.loraviewer.ui.paywall.PaywallScreen
import com.cobaltloom.loraviewer.ui.settings.AdvancedSettingsScreen
import com.cobaltloom.loraviewer.ui.settings.SettingsScreen
import com.cobaltloom.loraviewer.ui.theme.LoraViewerTheme
import com.cobaltloom.loraviewer.ui.track.TrackHistoryScreen
import com.cobaltloom.loraviewer.ui.track.TrackHistoryViewModel
import com.cobaltloom.loraviewer.ui.turnpoint.TurnpointHistoryScreen

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
    const val PAYWALL = "paywall"
    const val TURNPOINT_HISTORY = "turnpointHistory"
    const val ADVANCED_SETTINGS = "advancedSettings"
}

@Composable
private fun LoraViewerApp() {
    val context = LocalContext.current
    val billingRepository = remember { BillingRepository(context) }
    LoraViewerNavHost(billingRepository)
}

/**
 * The map is free to open; favorites, nicknames, and altitude alerts/guidelines were originally
 * gated behind an active subscription, navigating to [Routes.PAYWALL] instead of performing the
 * action when there's no active subscription.
 *
 * For this release the app ships fully free (monetization moved to ads, added separately) rather
 * than through Play Billing, so subscription gating is disabled by hardcoding [isSubscribed] to
 * true instead of reading [BillingRepository.isSubscribed]. [BillingRepository] and
 * [Routes.PAYWALL] are left wired up and untouched so subscriptions can be re-enabled later by
 * reverting this one line.
 */
@Composable
private fun LoraViewerNavHost(billingRepository: BillingRepository) {
    val context = LocalContext.current
    val isSubscribed = true
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
        val proximityAlertSettingsRepository = ProximityAlertSettingsRepository(context)
        val turnpointPassageLogRepository = TurnpointPassageLogRepository(context)
        val gliderTrailRepository = GliderTrailRepository(context)
        val mapDisplaySettingsRepository = MapDisplaySettingsRepository(context)
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
                    proximityAlertSettingsRepository = proximityAlertSettingsRepository,
                    turnpointPassageLogRepository = turnpointPassageLogRepository,
                    gliderTrailRepository = gliderTrailRepository,
                    mapDisplaySettingsRepository = mapDisplaySettingsRepository,
                    alertNotifier = alertNotifier,
                )
            }
        }
    }
    val viewModel: GliderTrackerViewModel = viewModel(factory = trackerFactory)
    LaunchedEffect(isSubscribed) { viewModel.updateSubscriptionStatus(isSubscribed) }

    val trackHistoryFactory = remember {
        viewModelFactory { initializer { TrackHistoryViewModel(gliderRepository) } }
    }
    val trackHistoryViewModel: TrackHistoryViewModel = viewModel(factory = trackHistoryFactory)

    val navController = rememberNavController()
    val onRequireSubscription: () -> Unit = { navController.navigate(Routes.PAYWALL) }

    NavHost(navController = navController, startDestination = Routes.MAP) {
        composable(Routes.MAP) {
            MapScreen(
                viewModel = viewModel,
                onOpenList = { navController.navigate(Routes.LIST) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenTrackHistory = { navController.navigate(Routes.TRACK_HISTORY) },
                onOpenTurnpointHistory = { navController.navigate(Routes.TURNPOINT_HISTORY) },
                onRequireSubscription = onRequireSubscription,
            )
        }
        composable(Routes.LIST) {
            GliderListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenBoardScan = { navController.navigate(Routes.BOARD_SCAN) },
                onRequireSubscription = onRequireSubscription,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onRequireSubscription = onRequireSubscription,
                onOpenAdvancedSettings = { navController.navigate(Routes.ADVANCED_SETTINGS) },
            )
        }
        composable(Routes.ADVANCED_SETTINGS) {
            AdvancedSettingsScreen(
                apiSettingsRepository = apiSettingsRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PAYWALL) {
            PaywallScreen(billingRepository, onBack = { navController.popBackStack() })
        }
        composable(Routes.TRACK_HISTORY) {
            TrackHistoryScreen(viewModel = trackHistoryViewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.TURNPOINT_HISTORY) {
            TurnpointHistoryScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.BOARD_SCAN) {
            BoardScanScreen(viewModel = viewModel, onDone = { navController.popBackStack() })
        }
    }
}
