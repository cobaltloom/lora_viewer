package com.cobaltloom.loraviewer.ui.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cobaltloom.loraviewer.data.model.TrackLogDevice
import com.cobaltloom.loraviewer.data.repository.GliderRepository
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackHistoryUiState(
    val trackData: Map<String, TrackLogDevice> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class TrackHistoryViewModel(private val repository: GliderRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackHistoryUiState())
    val uiState: StateFlow<TrackHistoryUiState> = _uiState.asStateFlow()

    fun search(start: Instant, end: Instant) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val trackData = repository.fetchTrackLog(start, end)
                _uiState.update { it.copy(trackData = trackData, isLoading = false, errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
