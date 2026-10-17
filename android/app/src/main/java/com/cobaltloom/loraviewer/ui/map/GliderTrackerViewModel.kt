package com.cobaltloom.loraviewer.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cobaltloom.loraviewer.data.alert.AlertSettings
import com.cobaltloom.loraviewer.data.alert.AlertSettingsRepository
import com.cobaltloom.loraviewer.data.alert.AlertSeverity
import com.cobaltloom.loraviewer.data.alert.AltitudeCalculationMode
import com.cobaltloom.loraviewer.data.alert.CompetitionAltitudeGuideline
import com.cobaltloom.loraviewer.data.alert.CompetitionGuidelineRepository
import com.cobaltloom.loraviewer.data.alert.CompetitionGuidelineSettings
import com.cobaltloom.loraviewer.data.alert.CompetitionTaskCourse
import com.cobaltloom.loraviewer.data.alert.CompetitionTaskCourseData
import com.cobaltloom.loraviewer.data.alert.Coordinate
import com.cobaltloom.loraviewer.data.alert.GliderAlertReason
import com.cobaltloom.loraviewer.data.alert.ProximityAlertSettings
import com.cobaltloom.loraviewer.data.alert.ProximityAlertSettingsRepository
import com.cobaltloom.loraviewer.data.alert.TaskLapCache
import com.cobaltloom.loraviewer.data.alert.TaskLapCalculator
import com.cobaltloom.loraviewer.data.alert.TaskLapStatus
import com.cobaltloom.loraviewer.data.alert.TimedPoint
import com.cobaltloom.loraviewer.data.alert.TurnpointPassageLogRepository
import com.cobaltloom.loraviewer.data.alert.TurnpointPassageRecord
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeGuidelineRepository
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeSettings
import com.cobaltloom.loraviewer.data.alert.bearingDegrees
import com.cobaltloom.loraviewer.data.alert.distanceMeters
import com.cobaltloom.loraviewer.data.alert.lapSummary
import com.cobaltloom.loraviewer.data.favorites.FavoritesRepository
import com.cobaltloom.loraviewer.data.model.AppConfig
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.nickname.NicknameRepository
import com.cobaltloom.loraviewer.data.nickname.NicknameSyncMode
import com.cobaltloom.loraviewer.data.notification.AlertNotifier
import com.cobaltloom.loraviewer.data.remote.TrackServerPoint
import com.cobaltloom.loraviewer.data.repository.GliderRepository
import com.cobaltloom.loraviewer.data.settings.DataSource
import com.cobaltloom.loraviewer.data.trail.GliderTrailRepository
import com.cobaltloom.loraviewer.data.trail.MapDisplaySettingsRepository
import com.cobaltloom.loraviewer.ui.common.assignGliderColorSlots
import com.cobaltloom.loraviewer.ui.common.gliderColor
import java.time.Duration
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
    /** Each trailing glider's palette slot, so simultaneous trails get distinct colors. */
    val trailColorSlots: Map<String, Int> = emptyMap(),
    /** Whether trails are drawn on the map at all - a free, device-local display preference. */
    val showGliderTrails: Boolean = true,
    /** Whether the map shows satellite/aerial imagery instead of the standard map - a free,
     * device-local display preference. */
    val showSatelliteMap: Boolean = false,
    /** Whether JSAL's distance-judging landmarks are drawn on the map, alongside the upper
     * altitude guideline zones - a subscriber-only display preference. */
    val showDistanceReferencePoints: Boolean = false,
    /** Whether the KK4-3 civil training/testing area boundary is drawn on the map - a
     * subscriber-only display preference. */
    val showKk43Area: Boolean = false,
    val showFavoritesOnly: Boolean = false,
    val lastUpdated: Instant? = null,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    /** Whether an active subscription is present. The map itself is free; everything else in this
     * state that derives from it (favorites, nicknames, altitude alerts, guidelines) is gated on
     * this flag so a lapsed subscription stops applying those features immediately, even if their
     * underlying settings are still stored as enabled. */
    val isSubscribed: Boolean = false,
    /** The position source actually in use (see ApiSettings.effectiveDataSource). */
    val dataSource: DataSource = DataSource.LORA,
    /** Whether the OGN option is offered in Settings at all (Firestore `ognEnabled`). */
    val isOgnAvailable: Boolean = false,
    /** Whether to offer the launch-time LoRa/OGN chooser: OGN is available and the person hasn't
     * turned it off. Starts false so it can't flash up before the saved settings load. */
    val shouldOfferDataSourcePrompt: Boolean = false,
    /** Each flying glider's progress around the selected task course (reference timing). */
    val lapStatuses: Map<String, TaskLapStatus> = emptyMap(),
) {
    /** The task course chosen in the competition guideline settings, if the course is shown. */
    val selectedCourse: CompetitionTaskCourse?
        get() = if (isSubscribed && competitionGuidelineSettings.showTaskCourse) {
            competitionGuidelineSettings.selectedCourseIndex?.let { CompetitionTaskCourseData.courses.getOrNull(it) }
        } else {
            null
        }

    /** The site's own board-position name (e.g. "7."), ignoring any nickname. */
    fun baseNameFor(glider: GliderPosition): String =
        glider.sourceName ?: config?.nameMasterDisplayed?.get(glider.index) ?: "#${glider.index}"

    /** Prefers the pilot-assigned nickname (by IMEI), falling back to [baseNameFor]. */
    fun nameFor(glider: GliderPosition): String =
        (if (isSubscribed && !glider.isAnonymous) nicknames[glider.imei] else null) ?: baseNameFor(glider)

    fun isFavorite(imei: String): Boolean = isSubscribed && imei in favorites

    /** The color of [imei]'s trail and trail name tag - distinct among gliders currently flying. */
    fun trailColorFor(imei: String): Color = gliderColor(trailColorSlots[imei], imei)

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
        // Both are Menuma-specific, so only for gliders within 15 km of the field center.
        if ((favorites.isEmpty() || glider.imei in favorites) && CompetitionAltitudeGuideline.isWithinAlertRadius(glider)) {
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
    private companion object {
        val MIN_BACKFILL_INTERVAL: Duration = Duration.ofSeconds(60)
        const val MAX_TIMED_POINTS = 7200
        const val RECENT_FINISH_MILLIS = 5 * 60 * 1000L
    }

    private val _uiState = MutableStateFlow(GliderTrackerUiState())
    val uiState: StateFlow<GliderTrackerUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    /** Each source's passage log; [GliderTrackerUiState.turnpointPassageRecords] is the current source's. */
    private var passageRecordsBySource: Map<DataSource, List<TurnpointPassageRecord>> = emptyMap()

    /** Each source's favorites; [GliderTrackerUiState.favorites] is the current source's. */
    private var favoritesBySource: Map<DataSource, Set<String>> = emptyMap()

    /** Each flying glider's timed positions this flight (live, plus any backfilled from the track
     * server), kept for lap timing. Dropped when the glider lands, like its trail. */
    private val timedTracks = mutableMapOf<String, MutableList<TimedPoint>>()
    private val lapCache = TaskLapCache()

    /** "imei|time" of starts and finishes already logged (finishes also announced), so each is
     * reported once. */
    private val reportedStarts = mutableSetOf<String>()
    private val reportedFinishes = mutableSetOf<String>()

    /** Set when the next refresh should backfill trails from the track server: at start, after a
     * source switch, and when a glider is first seen already flying. */
    private var needsTrailBackfill = true
    private var lastTrailBackfill = Instant.EPOCH

    /** Null until settings first load, so that initial load isn't mistaken for a source switch. */
    private var currentDataSource: DataSource? = null

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
            favoritesRepository.favoritesBySource.collect { bySource ->
                favoritesBySource = bySource
                _uiState.update { it.copy(favorites = bySource[it.dataSource].orEmpty()) }
            }
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
            turnpointPassageLogRepository.recordsBySource.collect { bySource ->
                passageRecordsBySource = bySource
                _uiState.update { it.copy(turnpointPassageRecords = bySource[it.dataSource].orEmpty()) }
            }
        }
        viewModelScope.launch { gliderTrailRepository.load() }
        viewModelScope.launch {
            gliderTrailRepository.trails.collect { trails ->
                _uiState.update {
                    it.copy(trails = trails, trailColorSlots = assignGliderColorSlots(it.trailColorSlots, trails.keys))
                }
            }
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
        viewModelScope.launch {
            repository.settings
                .catch { }
                .collect { settings ->
                    val previous = currentDataSource
                    val source = settings.effectiveDataSource
                    currentDataSource = source
                    _uiState.update {
                        it.copy(
                            dataSource = source,
                            isOgnAvailable = settings.isOgnAvailable,
                            shouldOfferDataSourcePrompt = settings.isOgnAvailable && !settings.isDataSourcePromptSuppressed,
                            favorites = favoritesBySource[source].orEmpty(),
                            turnpointPassageRecords = passageRecordsBySource[source].orEmpty(),
                        )
                    }
                    // The first value is just the saved choice, not a switch: keep persisted trails.
                    if (previous != null && source != previous) onDataSourceChanged()
                }
        }
        viewModelScope.launch {
            mapDisplaySettingsRepository.showKk43Area.collect { show ->
                _uiState.update { it.copy(showKk43Area = show) }
            }
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            loadConfigIfNeeded()
            while (isActive) {
                refreshOnce()
                val intervalSeconds = repository.currentRefreshIntervalSeconds()
                delay((intervalSeconds * 1000).toLong())
            }
        }
    }

    fun setDataSource(source: DataSource) {
        viewModelScope.launch { repository.setDataSource(source) }
    }

    /** Whether the launch-time chooser has been shown (or dismissed) in this app session. */
    var hasOfferedDataSourcePrompt by mutableStateOf(false)
        private set

    /** Closes the launch-time chooser, applying [source] if one was picked. */
    fun finishDataSourcePrompt(source: DataSource?, dontShowAgain: Boolean) {
        hasOfferedDataSourcePrompt = true
        viewModelScope.launch {
            if (source != null) repository.setDataSource(source)
            if (dontShowAgain) repository.setDataSourcePromptSuppressed(true)
        }
    }

    /** Starts clean on the new source instead of mixing one source's stale gliders into the other's
     * map, and fetches right away instead of waiting out the current interval. */
    private suspend fun onDataSourceChanged() {
        gliderTrailRepository.clear()
        timedTracks.clear()
        lapCache.clear()
        _uiState.update { it.copy(lapStatuses = emptyMap()) }
        needsTrailBackfill = true
        lastTrailBackfill = Instant.EPOCH
        _uiState.update { it.copy(positions = emptyList(), proximityReasonsByImei = emptyMap()) }
        previouslyAlertingImeis = emptySet()
        previouslyInsideTurnpoints = emptySet()
        previousProximityDistancesM = emptyMap()
        proximityWarningPairs = emptySet()
        if (pollingJob?.isActive == true) {
            stopPolling()
            startPolling()
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        repository.stopStreaming()
    }

    /** Saves trail points not yet saved, e.g. when the app goes to the background. */
    fun saveTrailsNow() {
        viewModelScope.launch { gliderTrailRepository.flush() }
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
        viewModelScope.launch { favoritesRepository.toggle(_uiState.value.dataSource, imei) }
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
        viewModelScope.launch { turnpointPassageLogRepository.clearToday(_uiState.value.dataSource) }
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

    fun setShowKk43Area(show: Boolean) {
        viewModelScope.launch { mapDisplaySettingsRepository.setShowKk43Area(show) }
    }

    suspend fun refreshOnce() {
        // Only for the first fetch: toggling this on every refresh recomposed the whole map twice.
        if (_uiState.value.lastUpdated == null) _uiState.update { it.copy(isLoading = true) }
        try {
            val source = repository.currentDataSource()
            val positions = repository.fetchCurrentPositions(source)
            // The source may have been switched while this request was in flight.
            if (source != repository.currentDataSource()) {
                _uiState.update { it.copy(isLoading = false) }
                return
            }
            var stateWithNewPositions = _uiState.value.copy(positions = positions)
            val proximityReasons = updateProximityAlerts(stateWithNewPositions)
            stateWithNewPositions = stateWithNewPositions.copy(proximityReasonsByImei = proximityReasons)
            notifyNewAlerts(stateWithNewPositions)
            notifyTurnpointPassages(stateWithNewPositions)
            val groundAltitudeM = stateWithNewPositions.alertSettings.minimumFlyingAltitudeM
            recordTimedPoints(positions, groundAltitudeM)
            updateLapStatuses(stateWithNewPositions)
            if (gliderTrailRepository.recordPositions(positions, groundAltitudeM)) {
                needsTrailBackfill = true // newly seen in flight: its earlier part may be on the server
            }
            // Launched separately: a slow track server must never hold up the live refresh loop.
            viewModelScope.launch { backfillTrailsIfNeeded(source, groundAltitudeM) }
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

    /** Fills in trails from the track server so they reach back to takeoff even if the app was
     * opened mid-flight. Best effort: without a configured server, or on any error, trails stay
     * live-only. */
    private suspend fun backfillTrailsIfNeeded(source: DataSource, groundAltitudeM: Double) {
        val now = Instant.now()
        if (!needsTrailBackfill || Duration.between(lastTrailBackfill, now) < MIN_BACKFILL_INTERVAL) return
        needsTrailBackfill = false
        lastTrailBackfill = now
        val flights = runCatching { repository.fetchTrackServerFlights(source, groundAltitudeM) }.getOrNull() ?: return
        if (source != repository.currentDataSource()) return
        gliderTrailRepository.backfill(flights.mapValues { (_, points) -> points.map { it.coordinate } })
        for ((id, points) in flights) {
            val live = timedTracks[id] ?: continue
            val server = points.map { TimedPoint(it.timeMillis, it.coordinate.latitude, it.coordinate.longitude, it.altitudeM) }
            val lastServerTime = server.lastOrNull()?.timeMillis ?: continue
            timedTracks[id] = (server + live.filter { it.timeMillis > lastServerTime }).takeLast(MAX_TIMED_POINTS).toMutableList()
        }
        updateLapStatuses(_uiState.value)
        recordPastTurnpointPassages(flights)
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
                    .add(GliderAlertReason("${state.nameFor(gliderB)}と接近($distanceText)", severity, pairKey))
                reasonsByImei.getOrPut(gliderB.imei) { mutableListOf() }
                    .add(GliderAlertReason("${state.nameFor(gliderA)}と接近($distanceText)", severity, pairKey))
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
            for (name in turnpointSectorsContaining(glider.lat, glider.lon, course)) {
                val key = "${glider.imei}|$name"
                currentlyInside += key
                if (key !in previouslyInsideTurnpoints) {
                    val gliderName = state.nameFor(glider)
                    alertNotifier.notifyTurnpointPassage(gliderName, name, glider.alt)
                    turnpointPassageLogRepository.record(state.dataSource, gliderName, name, glider.alt)
                }
            }
        }
        previouslyInsideTurnpoints = currentlyInside
    }

    private fun recordTimedPoints(positions: List<GliderPosition>, groundAltitudeM: Double) {
        val now = System.currentTimeMillis()
        for (glider in positions) {
            val alt = glider.alt
            if (alt == null || alt <= groundAltitudeM) {
                timedTracks.remove(glider.imei)
                continue
            }
            val time = glider.positionDateTimeUtc?.toEpochMilli() ?: now
            val track = timedTracks.getOrPut(glider.imei) { mutableListOf() }
            if (track.isNotEmpty() && time <= track.last().timeMillis) continue
            track += TimedPoint(time, glider.lat, glider.lon, alt)
            if (track.size > MAX_TIMED_POINTS) track.subList(0, track.size - MAX_TIMED_POINTS).clear()
        }
        timedTracks.keys.retainAll(positions.map { it.imei }.toSet())
    }

    /**
     * Re-times every flying glider around the selected course and logs/announces new starts and
     * finishes. Reference only: official timing uses each glider's 1-second IGC logger.
     */
    private suspend fun updateLapStatuses(state: GliderTrackerUiState) {
        val course = state.selectedCourse
        if (course == null) {
            _uiState.update { it.copy(lapStatuses = emptyMap()) }
            return
        }
        val center = CompetitionAltitudeGuideline.referenceCoordinate
        val guideline: (TimedPoint) -> Double? = { p ->
            CompetitionAltitudeGuideline.requiredAltitudeM(distanceMeters(center.latitude, center.longitude, p.lat, p.lon) / 1000.0)
        }
        val now = System.currentTimeMillis()
        val statuses = lapCache.statuses(timedTracks, course, guideline)
        for ((id, status) in statuses) {
            val glider = state.positions.firstOrNull { it.imei == id } ?: continue
            val gliderName = state.nameFor(glider)
            // Logged only once the first turnpoint is reached, so a flight that lands without
            // rounding any turnpoint leaves no stray "スタート" in the history.
            status.startMillis?.takeIf { status.passedTurnpoints.isNotEmpty() }?.let { start ->
                if (reportedStarts.add("$id|$start")) {
                    turnpointPassageLogRepository.recordPastPassage(state.dataSource, gliderName, "スタート", status.startAltitudeM, start)
                }
            }
            val finish = status.finishMillis ?: continue
            if (!reportedFinishes.add("$id|$finish")) continue
            val summary = lapSummary(status, course.distanceKm, now) { "" }.removePrefix("周回: ")
            turnpointPassageLogRepository.recordPastPassage(state.dataSource, gliderName, TurnpointPassageRecord.FINISH_PREFIX + summary, status.finishAltitudeM, finish)
            // Only announce a finish that just happened - not one found in history on reopening.
            if (now - finish < RECENT_FINISH_MILLIS) alertNotifier.notifyTaskFinish(gliderName, summary)
        }
        _uiState.update { it.copy(lapStatuses = statuses) }
    }

    /** Names of [course]'s turnpoints whose 90° sector (within the turnpoint radius) contains the point. */
    private fun turnpointSectorsContaining(lat: Double, lon: Double, course: CompetitionTaskCourse): List<String> =
        CompetitionTaskCourseData.notifiableTurnpointNames.filter { name ->
            val point = CompetitionTaskCourseData.turnpoints[name] ?: return@filter false
            val bisector = CompetitionTaskCourseData.sectorBearing(course, name) ?: return@filter false
            distanceMeters(point.latitude, point.longitude, lat, lon) / 1000.0 <= CompetitionTaskCourseData.TURNPOINT_RADIUS_KM &&
                CompetitionTaskCourseData.isBearing(bearingDegrees(point.latitude, point.longitude, lat, lon), bisector)
        }

    /**
     * Adds turnpoint passages flown before the app was opened, found by walking each flying
     * glider's track from the track server. Logged at the time they happened; no notification,
     * since a ping about a passage from long ago would only confuse.
     */
    private suspend fun recordPastTurnpointPassages(flights: Map<String, List<TrackServerPoint>>) {
        val state = _uiState.value
        if (!state.isSubscribed || !state.competitionGuidelineSettings.showTaskCourse) return
        val selectedCourseIndex = state.competitionGuidelineSettings.selectedCourseIndex ?: return
        val course = CompetitionTaskCourseData.courses.getOrNull(selectedCourseIndex) ?: return
        for ((id, points) in flights) {
            val glider = state.positions.firstOrNull { it.imei == id } ?: continue
            val gliderName = state.nameFor(glider)
            var inside = emptySet<String>()
            for (point in points) {
                val now = turnpointSectorsContaining(point.coordinate.latitude, point.coordinate.longitude, course).toSet()
                for (name in now - inside) {
                    turnpointPassageLogRepository.recordPastPassage(state.dataSource, gliderName, name, point.altitudeM, point.timeMillis)
                }
                inside = now
            }
        }
    }
}
