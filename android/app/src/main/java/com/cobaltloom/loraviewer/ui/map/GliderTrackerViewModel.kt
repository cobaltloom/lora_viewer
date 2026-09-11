package com.cobaltloom.loraviewer.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cobaltloom.loraviewer.data.alert.AlertSettings
import com.cobaltloom.loraviewer.data.alert.AlertSettingsRepository
import com.cobaltloom.loraviewer.data.alert.AlertSeverity
import com.cobaltloom.loraviewer.data.alert.AltitudeCalculationMode
import com.cobaltloom.loraviewer.data.alert.CompetitionAltitudeGuideline
import com.cobaltloom.loraviewer.data.alert.CompetitionGuidelineRepository
import com.cobaltloom.loraviewer.data.alert.CompetitionGuidelineSettings
import com.cobaltloom.loraviewer.data.alert.CompetitionTaskCourseData
import com.cobaltloom.loraviewer.data.alert.Coordinate
import com.cobaltloom.loraviewer.data.alert.GliderAlertReason
import com.cobaltloom.loraviewer.data.alert.ProximityAlertSettings
import com.cobaltloom.loraviewer.data.alert.ProximityAlertSettingsRepository
import com.cobaltloom.loraviewer.data.alert.TurnpointPassageLogRepository
import com.cobaltloom.loraviewer.data.alert.TurnpointPassageRecord
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeGuidelineRepository
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeSettings
import com.cobaltloom.loraviewer.data.alert.bearingDegrees
import com.cobaltloom.loraviewer.data.alert.distanceMeters
import com.cobaltloom.loraviewer.data.favorites.FavoritesRepository
import com.cobaltloom.loraviewer.data.model.AppConfig
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.nickname.NicknameRepository
import com.cobaltloom.loraviewer.data.nickname.NicknameSyncMode
import com.cobaltloom.loraviewer.data.notification.AlertNotifier
import com.cobaltloom.loraviewer.data.repository.GliderRepository
import com.cobaltloom.loraviewer.data.trail.GliderTrailRepository
import com.cobaltloom.loraviewer.data.trail.MapDisplaySettingsRepository
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
    val nicknameSyncMode: NicknameSyncMode = NicknameSyncMode.SYNCED,
    val favorites: Set<String> = emptySet(),
    val alertSettings: AlertSettings = AlertSettings(),
    val competitionGuidelineSettings: CompetitionGuidelineSettings = CompetitionGuidelineSettings(),
    val upperAltitudeSettings: UpperAltitudeSettings = UpperAltitudeSettings(),
    val proximityAlertSettings: ProximityAlertSettings = ProximityAlertSettings(),
    /** Each glider's proximity-to-other-gliders reasons as of the last refresh, keyed by imei -
     * merged into [alertReasons]. Unlike the custom altitude alert/competition guideline, this is
     * never scoped by favorites: it's inherently about other gliders near yours too. */
    val proximityReasonsByImei: Map<String, List<GliderAlertReason>> = emptyMap(),
    val turnpointPassageRecords: List<TurnpointPassageRecord> = emptyList(),
    /** Each glider's positions for its current flight, keyed by imei - drawn on the map as a trail. */
    val trails: Map<String, List<Coordinate>> = emptyMap(),
    /** Whether trails are drawn on the map at all - a free, device-local display preference. */
    val showGliderTrails: Boolean = true,
    /** Whether the map shows satellite/aerial imagery instead of the standard map - a free,
     * device-local display preference. */
    val showSatelliteMap: Boolean = false,
    /** Whether JSAL's distance-judging landmarks are drawn on the map, alongside the upper
     * altitude guideline zones - a subscriber-only display preference. */
    val showDistanceReferencePoints: Boolean = false,
    val showFavoritesOnly: Boolean = false,
    val lastUpdated: Instant? = null,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    /** Whether an active subscription is present. The map itself is free; everything else in this
     * state that derives from it (favorites, nicknames, altitude alerts, guidelines) is gated on
     * this flag so a lapsed subscription stops applying those features immediately, even if their
     * underlying settings are still stored as enabled. */
    val isSubscribed: Boolean = false,
) {
    /** The site's own board-position name (e.g. "7."), ignoring any nickname. */
    fun baseNameFor(glider: GliderPosition): String =
        config?.nameMasterDisplayed?.get(glider.index) ?: "#${glider.index}"

    /** Prefers the pilot-assigned nickname (by IMEI), falling back to [baseNameFor]. */
    fun nameFor(glider: GliderPosition): String =
        (if (isSubscribed) nicknames[glider.imei] else null) ?: baseNameFor(glider)

    fun isFavorite(imei: String): Boolean = isSubscribed && imei in favorites

    /** All positions, or just favorites when the filter is on - falling back to all if none are favorited. */
    val displayedPositions: List<GliderPosition>
        get() {
            if (!showFavoritesOnly) return positions
            val favoritePositions = positions.filter { isFavorite(it.imei) }
            return favoritePositions.ifEmpty { positions }
        }

    /** Every alert rule currently triggered for [glider], each with its own severity. All altitude
     * alerts are a subscription feature, so nothing is reported while unsubscribed. */
    fun alertReasons(glider: GliderPosition): List<GliderAlertReason> {
        if (!isSubscribed) return emptyList()
        val reasons = mutableListOf<GliderAlertReason>()
        // When one or more gliders are favorited, only they are checked against the custom
        // altitude alert and competition guideline - otherwise, e.g. a glider flying from a
        // different field than the one these are configured for triggers noise notifications
        // for gliders the person tracking them doesn't actually care about. With no favorites
        // set, every glider is checked, same as before this distinction existed.
        if (favorites.isEmpty() || glider.imei in favorites) {
            alertSettings.alertSeverity(glider)?.let {
                reasons.add(GliderAlertReason("カスタム設定", it))
            }
            if (CompetitionAltitudeGuideline.isBelowGuideline(glider, competitionGuidelineSettings.isEnabled, alertSettings.minimumFlyingAltitudeM)) {
                reasons.add(GliderAlertReason("競技会ガイドライン", AlertSeverity.WARNING))
            }
        }
        if (upperAltitudeSettings.exceedsCeiling(glider)) {
            val zoneName = upperAltitudeSettings.applicableZone(glider)?.first.orEmpty()
            reasons.add(GliderAlertReason("${zoneName}上限超過", AlertSeverity.WARNING))
        }
        reasons.addAll(proximityReasonsByImei[glider.imei].orEmpty())
        return reasons
    }

    val alertingGliders: List<GliderPosition>
        get() = displayedPositions.filter { alertReasons(it).isNotEmpty() }

    /** Short labels for whichever altitude alerts are currently turned on. */
    val activeAlertLabels: List<String>
        get() {
            if (!isSubscribed) return emptyList()
            val labels = mutableListOf<String>()
            if (alertSettings.isEnabled) {
                labels.add(
                    when (alertSettings.mode) {
                        AltitudeCalculationMode.STEPS -> "カスタム:距離段階"
                        AltitudeCalculationMode.GLIDE_RATIO -> "カスタム:L/D"
                    },
                )
            }
            if (competitionGuidelineSettings.isEnabled) labels.add("競技会ガイドライン")
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
    private val proximityAlertSettingsRepository: ProximityAlertSettingsRepository,
    private val turnpointPassageLogRepository: TurnpointPassageLogRepository,
    private val gliderTrailRepository: GliderTrailRepository,
    private val mapDisplaySettingsRepository: MapDisplaySettingsRepository,
    private val alertNotifier: AlertNotifier,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GliderTrackerUiState())
    val uiState: StateFlow<GliderTrackerUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    /** IMEIs alerting as of the last refresh, so notifications fire only when a glider newly enters an alert. */
    private var previouslyAlertingImeis: Set<String> = emptySet()

    /**
     * "imei|turnpoint name" keys for gliders currently inside a turnpoint's sector, so passage
     * notifications fire once on entry rather than repeatedly while a glider lingers inside.
     */
    private var previouslyInsideTurnpoints: Set<String> = emptySet()

    /** Each glider pair's horizontal distance (meters) as of the previous refresh, keyed by a
     * sorted "imei1|imei2", so a new refresh can tell whether a pair is closing rather than just
     * currently near. */
    private var previousProximityDistancesM: Map<String, Double> = emptyMap()

    /** Pair keys currently past the proximity warning threshold and closing, so the push
     * notification fires once per approach rather than every refresh while the pair stays close. */
    private var proximityWarningPairs: Set<String> = emptySet()

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
            competitionGuidelineRepository.settings.collect { settings ->
                _uiState.update { it.copy(competitionGuidelineSettings = settings) }
            }
        }
        viewModelScope.launch {
            upperAltitudeGuidelineRepository.settings.collect { settings ->
                _uiState.update { it.copy(upperAltitudeSettings = settings) }
            }
        }
        viewModelScope.launch {
            proximityAlertSettingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(proximityAlertSettings = settings) }
            }
        }
        viewModelScope.launch {
            turnpointPassageLogRepository.records.collect { records ->
                _uiState.update { it.copy(turnpointPassageRecords = records) }
            }
        }
        viewModelScope.launch {
            gliderTrailRepository.trails.collect { trails -> _uiState.update { it.copy(trails = trails) } }
        }
        viewModelScope.launch {
            mapDisplaySettingsRepository.showGliderTrails.collect { show ->
                _uiState.update { it.copy(showGliderTrails = show) }
            }
        }
        viewModelScope.launch {
            mapDisplaySettingsRepository.showSatelliteMap.collect { show ->
                _uiState.update { it.copy(showSatelliteMap = show) }
            }
        }
        viewModelScope.launch {
            mapDisplaySettingsRepository.showDistanceReferencePoints.collect { show ->
                _uiState.update { it.copy(showDistanceReferencePoints = show) }
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

    fun updateSubscriptionStatus(isSubscribed: Boolean) {
        _uiState.update { it.copy(isSubscribed = isSubscribed) }
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

    fun updateCompetitionGuidelineSettings(settings: CompetitionGuidelineSettings) {
        viewModelScope.launch { competitionGuidelineRepository.save(settings) }
    }

    fun updateUpperAltitudeSettings(settings: UpperAltitudeSettings) {
        viewModelScope.launch { upperAltitudeGuidelineRepository.save(settings) }
    }

    fun updateProximityAlertSettings(settings: ProximityAlertSettings) {
        viewModelScope.launch { proximityAlertSettingsRepository.save(settings) }
    }

    fun clearTodaysTurnpointPassages() {
        viewModelScope.launch { turnpointPassageLogRepository.clearToday() }
    }

    fun setShowGliderTrails(show: Boolean) {
        viewModelScope.launch { mapDisplaySettingsRepository.setShowGliderTrails(show) }
    }

    fun toggleSatelliteMap() {
        viewModelScope.launch { mapDisplaySettingsRepository.setShowSatelliteMap(!_uiState.value.showSatelliteMap) }
    }

    fun setShowDistanceReferencePoints(show: Boolean) {
        viewModelScope.launch { mapDisplaySettingsRepository.setShowDistanceReferencePoints(show) }
    }

    suspend fun refreshOnce() {
        _uiState.update { it.copy(isLoading = true) }
        try {
            val positions = repository.fetchCurrentPositions()
            var stateWithNewPositions = _uiState.value.copy(positions = positions)
            val proximityReasons = updateProximityAlerts(stateWithNewPositions)
            stateWithNewPositions = stateWithNewPositions.copy(proximityReasonsByImei = proximityReasons)
            notifyNewAlerts(stateWithNewPositions)
            notifyTurnpointPassages(stateWithNewPositions)
            gliderTrailRepository.recordPositions(positions, stateWithNewPositions.alertSettings.minimumFlyingAltitudeM)
            _uiState.update {
                it.copy(
                    positions = positions,
                    proximityReasonsByImei = proximityReasons,
                    lastUpdated = Instant.now(),
                    errorMessage = null,
                    isLoading = false,
                )
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

    /**
     * Detects gliders getting close to each other (see [ProximityAlertSettings]) and returns each
     * glider's proximity reasons for the map's ring coloring, same as any other alert reason. A
     * pair within the warning distance fires a push notification too, but only once per
     * continuous approach, and only while actually closing - gliders sharing a thermal are
     * commonly close together without being on a collision course, so "just nearby" alone only
     * shows quietly on the map (CAUTION), never as a notification. A subscriber-only feature.
     */
    private fun updateProximityAlerts(state: GliderTrackerUiState): Map<String, List<GliderAlertReason>> {
        val settings = state.proximityAlertSettings
        if (!state.isSubscribed || !settings.isEnabled) {
            previousProximityDistancesM = emptyMap()
            proximityWarningPairs = emptySet()
            return emptyMap()
        }
        val flying = state.positions.filter { (it.alt ?: 0.0) > state.alertSettings.minimumFlyingAltitudeM }
        val reference = state.alertSettings.referenceCoordinate
        val patternRadiusM = settings.patternExclusionRadiusKm * 1000
        val patternCeilingM = settings.patternExclusionCeilingM
        val reasonsByImei = mutableMapOf<String, MutableList<GliderAlertReason>>()
        val currentDistancesM = mutableMapOf<String, Double>()
        val currentWarningPairs = mutableSetOf<String>()

        for (i in flying.indices) {
            for (j in flying.indices) {
                if (j <= i) continue
                val gliderA = flying[i]
                val gliderB = flying[j]
                val altitudeA = gliderA.alt ?: continue
                val altitudeB = gliderB.alt ?: continue
                val altitudeDifferenceM = kotlin.math.abs(altitudeA - altitudeB)
                if (altitudeDifferenceM > settings.maxAltitudeDifferenceM) continue

                val distanceM = distanceMeters(gliderA.lat, gliderA.lon, gliderB.lat, gliderB.lon)
                if (distanceM > settings.cautionDistanceM) continue

                // Near the field and low, gliders are routinely close and converging by design
                // (following each other around the landing pattern) - cap at CAUTION there so a
                // notification doesn't fire on essentially every landing.
                val isInPattern = altitudeA <= patternCeilingM && altitudeB <= patternCeilingM &&
                    distanceMeters(reference.latitude, reference.longitude, gliderA.lat, gliderA.lon) <= patternRadiusM &&
                    distanceMeters(reference.latitude, reference.longitude, gliderB.lat, gliderB.lon) <= patternRadiusM

                val pairKey = listOf(gliderA.imei, gliderB.imei).sorted().joinToString("|")
                currentDistancesM[pairKey] = distanceM

                var severity = AlertSeverity.CAUTION
                val previousDistanceM = previousProximityDistancesM[pairKey]
                if (!isInPattern && distanceM <= settings.warningDistanceM &&
                    previousDistanceM != null && distanceM < previousDistanceM
                ) {
                    severity = AlertSeverity.WARNING
                    currentWarningPairs += pairKey
                    if (pairKey !in proximityWarningPairs) {
                        alertNotifier.notifyProximity(state.nameFor(gliderA), state.nameFor(gliderB), distanceM, altitudeDifferenceM)
                    }
                }

                val distanceText = "${distanceM.toInt()}m"
                reasonsByImei.getOrPut(gliderA.imei) { mutableListOf() }
                    .add(GliderAlertReason("${state.nameFor(gliderB)}と接近($distanceText)", severity))
                reasonsByImei.getOrPut(gliderB.imei) { mutableListOf() }
                    .add(GliderAlertReason("${state.nameFor(gliderA)}と接近($distanceText)", severity))
            }
        }

        previousProximityDistancesM = currentDistancesM
        proximityWarningPairs = currentWarningPairs
        return reasonsByImei
    }

    /**
     * Notifies once per glider each time it newly enters a turnpoint's sector (excluding
     * 管理ポイント - see [CompetitionTaskCourseData.notifiableTurnpointNames]), and lets it notify
     * again on a later lap once it leaves and re-enters. Also records the event to
     * [turnpointPassageLogRepository] so it can be reviewed in-app if the notification is missed.
     * The sector is the true 90° wedge from JSAL rule 43 (bisecting the selected task course's
     * incoming and outgoing legs at that turnpoint), so this requires a task course to be
     * selected: with no course selected ("旋回点のみ") there's no leg geometry to derive a sector
     * from, and nothing is notified/recorded. A subscriber-only feature.
     */
    private suspend fun notifyTurnpointPassages(state: GliderTrackerUiState) {
        if (!state.isSubscribed || !state.competitionGuidelineSettings.showTaskCourse) return
        val selectedCourseIndex = state.competitionGuidelineSettings.selectedCourseIndex ?: return
        val course = CompetitionTaskCourseData.courses.getOrNull(selectedCourseIndex) ?: return

        val currentlyInside = mutableSetOf<String>()
        for (glider in state.positions) {
            for (name in CompetitionTaskCourseData.notifiableTurnpointNames) {
                val point = CompetitionTaskCourseData.turnpoints[name] ?: continue
                val distanceKm = distanceMeters(point.latitude, point.longitude, glider.lat, glider.lon) / 1000.0
                if (distanceKm > CompetitionTaskCourseData.TURNPOINT_RADIUS_KM) continue
                val bisector = CompetitionTaskCourseData.sectorBearing(course, name) ?: continue
                val bearingToGlider = bearingDegrees(point.latitude, point.longitude, glider.lat, glider.lon)
                if (!CompetitionTaskCourseData.isBearing(bearingToGlider, bisector)) continue

                val key = "${glider.imei}|$name"
                currentlyInside += key
                if (key !in previouslyInsideTurnpoints) {
                    val gliderName = state.nameFor(glider)
                    alertNotifier.notifyTurnpointPassage(gliderName, name, glider.alt)
                    turnpointPassageLogRepository.record(gliderName, name, glider.alt)
                }
            }
        }
        previouslyInsideTurnpoints = currentlyInside
    }
}
