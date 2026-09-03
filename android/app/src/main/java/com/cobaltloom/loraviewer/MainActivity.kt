package com.cobaltloom.loraviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cobaltloom.loraviewer.data.nickname.NicknameRepository
import com.cobaltloom.loraviewer.data.remote.TrailRouteApiClient
import com.cobaltloom.loraviewer.data.repository.GliderRepository
import com.cobaltloom.loraviewer.data.settings.ApiSettingsRepository
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import com.cobaltloom.loraviewer.ui.map.MapScreen
import com.cobaltloom.loraviewer.ui.theme.LoraViewerTheme

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

@Composable
private fun LoraViewerApp() {
    val context = LocalContext.current
    val factory = remember {
        val repository = GliderRepository(TrailRouteApiClient(), ApiSettingsRepository(context))
        val nicknameRepository = NicknameRepository()
        viewModelFactory { initializer { GliderTrackerViewModel(repository, nicknameRepository) } }
    }
    val viewModel: GliderTrackerViewModel = viewModel(factory = factory)
    MapScreen(viewModel = viewModel)
}
