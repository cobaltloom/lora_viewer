package com.cobaltloom.loraviewer.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cobaltloom.loraviewer.data.model.AppConfig
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.nickname.NicknameRepository
import com.cobaltloom.loraviewer.data.repository.GliderRepository
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GliderTrackerUiState(
    val config: AppConfig? = null,
    val positions: List<GliderPosition> = emptyList(),
    val nicknames: Map<String, String> = emptyMap(),
    val lastUpdated: Instant? = null,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
) {
    /** Prefers the pilot-assigned nickname (by IMEI), falling back to the site's own board-position name. */
    fun nameFor(glider: GliderPosition): String =
        nicknames[glider.imei]
            ?: config?.nameMasterDisplayed?.get(glider.index)
            ?: "#${glider.index}"
}

class GliderTrackerViewModel(
    private val repository: GliderRepository,
    private val nicknameRepository: NicknameRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GliderTrackerUiState())
    val uiState: StateFlow<GliderTrackerUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            nicknameRepository.nicknames
                .catch { /* keep whatever nicknames were last known if the listener drops */ }
                .collect { nicknames -> _uiState.update { it.copy(nicknames = nicknames) } }
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            loadConfigIfNeeded()
            while (isActive) {
                refreshOnce()
                val intervalSeconds = repository.currentRefreshIntervalSeconds().coerceAtLeast(3.0)
                delay((intervalSeconds * 1000).toLong())
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        stopPolling()
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun setNickname(imei: String, name: String) {
        nicknameRepository.setNickname(imei, name)
    }

    private suspend fun loadConfigIfNeeded() {
        if (_uiState.value.config != null) return
        try {
            val config = repository.fetchConfig()
            _uiState.update { it.copy(config = config) }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = e.message) }
        }
    }

    private suspend fun refreshOnce() {
        _uiState.update { it.copy(isLoading = true) }
        try {
            val positions = repository.fetchCurrentPositions()
            _uiState.update {
                it.copy(positions = positions, lastUpdated = Instant.now(), errorMessage = null, isLoading = false)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = e.message, isLoading = false) }
        }
    }
}
