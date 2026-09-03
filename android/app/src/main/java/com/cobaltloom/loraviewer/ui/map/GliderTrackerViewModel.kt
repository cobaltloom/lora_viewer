package com.cobaltloom.loraviewer.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cobaltloom.loraviewer.data.alert.AlertSettings
import com.cobaltloom.loraviewer.data.alert.AlertSettingsRepository
import com.cobaltloom.loraviewer.data.alert.AlertSeverity
import com.cobaltloom.loraviewer.data.alert.AltitudeCalculationMode
import com.cobaltloom.loraviewer.data.alert.CompetitionAltitudeGuideline
import com.cobaltloom.loraviewer.data.alert.CompetitionGuidelineRepository
import com.cobaltloom.loraviewer.data.alert.GliderAlertReason
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeGuidelineRepository
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeSettings
import com.cobaltloom.loraviewer.data.favorites.FavoritesRepository
import com.cobaltloom.loraviewer.data.model.AppConfig
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.nickname.NicknameRepository
import com.cobaltloom.loraviewer.data.nickname.NicknameSyncMode
import com.cobaltloom.loraviewer.data.notification.AlertNotifier
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

/** The safety-altitude reference point used when no custom one is set: JSAL's own airfield coordinate. */
val DefaultAlertReferenceCoordinate = CompetitionAltitudeGuideline.referenceCoordinate

data class GliderTrackerUiState(
    val config: AppConfig? = null,
    val positions: List<GliderPosition> = emptyList(),
    val nicknames: Map<String, String> = emptyMap(),
    val nicknameSyncMode: NicknameSyncMode = NicknameSyncMode.SYNCED,
    val favorites: Set<String> = emptySet(),
    val alertSettings: AlertSettings = AlertSettings(),
    val competitionGuidelineEnabled: Boolean = false,
    val upperAltitudeSettings: UpperAltitudeSettings = UpperAltitudeSettings(),
    val showFavoritesOnly: Boolean = false,
    val lastUpdated: Instant? = null,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
) {
    /** Prefers the pilot-assigned nickname (by IMEI), falling back to the site's own board-position name. */
    fun nameFor(glider: GliderPosition): String =
        nicknames[glider.imei]
            ?: config?.nameMasterDisplayed?.get(glider.index)
            ?: "#${glider.index}"

    fun isFavorite(imei: String): Boolean = imei in favorites

    /** All positions, or just favorites when the filter is on - falling back to all if none are favorited. */
    val displayedPositions: List<GliderPosition>
        get() {
            if (!showFavoritesOnly) return positions
            val favoritePositions = positions.filter { isFavorite(it.imei) }
            return favoritePositions.ifEmpty { positions }
        }

    /** Every alert rule currently triggered for [glider], each with its own severity. */
    fun alertReasons(glider: GliderPosition): List<GliderAlertReason> {
        val reasons = mutableListOf<GliderAlertReason>()
        alertSettings.alertSeverity(glider, DefaultAlertReferenceCoordinate)?.let {
            reasons.add(GliderAlertReason("カスタム設定", it))
        }
        if (CompetitionAltitudeGuideline.isBelowGuideline(glider, competitionGuidelineEnabled, alertSettings.minimumFlyingAltitudeM)) {
            reasons.add(GliderAlertReason("競技会ガイドライン", AlertSeverity.WARNING))
        }
        if (upperAltitudeSettings.exceedsCeiling(glider)) {
            val zoneName = upperAltitudeSettings.applicableZone(glider)?.first.orEmpty()
            reasons.add(GliderAlertReason("${zoneName}上限超過", AlertSeverity.WARNING))
        }
        return reasons
    }

    val alertingGliders: List<GliderPosition>
        get() = displayedPositions.filter { alertReasons(it).isNotEmpty() }

    /** Short labels for whichever altitude alerts are currently turned on. */
    val activeAlertLabels: List<String>
        get() {
            val labels = mutableListOf<String>()
            if (alertSettings.isEnabled) {
                labels.add(
                    when (alertSettings.mode) {
                        AltitudeCalculationMode.STEPS -> "カスタム:距離段階"
                        AltitudeCalculationMode.GLIDE_RATIO -> "カスタム:L/D"
                    },
                )
            }
            if (competitionGuidelineEnabled) labels.add("競技会ガイドライン")
            if (upperAltitudeSettings.isEnabled) labels.add("上限高度")
            return labels
        }
}

class GliderTrackerViewModel(
    private val repository: GliderRepository,
    private val nicknameRepository: NicknameRepository,
    private val favoritesRepository: FavoritesRepository,
    private val alertSettingsRepository: AlertSettingsRepository,
    private val competitionGuidelineRepository: CompetitionGuidelineRepository,
    private val upperAltitudeGuidelineRepository: UpperAltitudeGuidelineRepository,
    private val alertNotifier: AlertNotifier,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GliderTrackerUiState())
    val uiState: StateFlow<GliderTrackerUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    /** IMEIs alerting as of the last refresh, so notifications fire only when a glider newly enters an alert. */
    private var previouslyAlertingImeis: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            nicknameRepository.nicknames
                .catch { }
                .collect { nicknames -> _uiState.update { it.copy(nicknames = nicknames) } }
        }
        viewModelScope.launch {
            nicknameRepository.syncMode.collect { mode -> _uiState.update { it.copy(nicknameSyncMode = mode) } }
        }
        viewModelScope.launch {
            favoritesRepository.favoriteImeis.collect { favorites -> _uiState.update { it.copy(favorites = favorites) } }
        }
        viewModelScope.launch {
            alertSettingsRepository.settings.collect { settings -> _uiState.update { it.copy(alertSettings = settings) } }
        }
        viewModelScope.launch {
            competitionGuidelineRepository.isEnabled.collect { enabled ->
                _uiState.update { it.copy(competitionGuidelineEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            upperAltitudeGuidelineRepository.settings.collect { settings ->
                _uiState.update { it.copy(upperAltitudeSettings = settings) }
            }
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

    fun setNicknameSyncMode(mode: NicknameSyncMode) {
        viewModelScope.launch { nicknameRepository.setSyncMode(mode) }
    }

    fun toggleFavorite(imei: String) {
        viewModelScope.launch { favoritesRepository.toggle(imei) }
    }

    fun toggleFavoritesOnly() {
        _uiState.update { it.copy(showFavoritesOnly = !it.showFavoritesOnly) }
    }

    fun updateAlertSettings(settings: AlertSettings) {
        viewModelScope.launch { alertSettingsRepository.save(settings) }
    }

    fun setCompetitionGuidelineEnabled(enabled: Boolean) {
        viewModelScope.launch { competitionGuidelineRepository.setEnabled(enabled) }
    }

    fun updateUpperAltitudeSettings(settings: UpperAltitudeSettings) {
        viewModelScope.launch { upperAltitudeGuidelineRepository.save(settings) }
    }

    suspend fun refreshOnce() {
        _uiState.update { it.copy(isLoading = true) }
        try {
            val positions = repository.fetchCurrentPositions()
            notifyNewAlerts(_uiState.value.copy(positions = positions))
            _uiState.update {
                it.copy(positions = positions, lastUpdated = Instant.now(), errorMessage = null, isLoading = false)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = e.message, isLoading = false) }
        }
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

    /**
     * Notifies once per glider each time it newly enters an alerting state, checked against all
     * known positions (not just the ones currently shown by the favorites filter).
     */
    private fun notifyNewAlerts(stateWithNewPositions: GliderTrackerUiState) {
        val currentlyAlerting = mutableSetOf<String>()
        for (glider in stateWithNewPositions.positions) {
            val reasons = stateWithNewPositions.alertReasons(glider)
            if (reasons.isEmpty()) continue
            currentlyAlerting += glider.imei
            if (glider.imei !in previouslyAlertingImeis) {
                alertNotifier.notify(stateWithNewPositions.nameFor(glider), reasons)
            }
        }
        previouslyAlertingImeis = currentlyAlerting
    }
}
