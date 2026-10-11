package com.cobaltloom.loraviewer.data.repository

import com.cobaltloom.loraviewer.data.model.AppConfig
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.model.TrackLogDevice
import com.cobaltloom.loraviewer.data.remote.OgnApiClient
import com.cobaltloom.loraviewer.data.remote.TrailRouteApiClient
import com.cobaltloom.loraviewer.data.settings.ApiSettings
import com.cobaltloom.loraviewer.data.settings.ApiSettingsRepository
import com.cobaltloom.loraviewer.data.settings.DataSource
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class GliderRepository(
    private val apiClient: TrailRouteApiClient,
    private val settingsRepository: ApiSettingsRepository,
    private val ognApiClient: OgnApiClient,
) {
    val settings: Flow<ApiSettings> = settingsRepository.settings

    suspend fun setDataSource(value: DataSource) = settingsRepository.setDataSource(value)

    suspend fun fetchConfig(): AppConfig {
        val settings = settingsRepository.settings.first()
        return apiClient.fetchConfig(settings.baseUrl)
    }

    /** Fetches from [source] - the caller passes it so it can tell which source a result came from. */
    suspend fun fetchCurrentPositions(source: DataSource): List<GliderPosition> {
        val settings = settingsRepository.settings.first()
        return when (source) {
            DataSource.LORA -> {
                ognApiClient.stop() // no-op unless we just switched away from OGN
                apiClient.fetchCurrentPositions(settings.baseUrl, settings.secretKey)
            }
            DataSource.OGN -> ognApiClient.fetchCurrentPositions()
        }
    }

    /** Closes the OGN live connection, if open - for when the map stops polling. */
    fun stopStreaming() = ognApiClient.stop()

    suspend fun currentDataSource(): DataSource = settingsRepository.settings.first().effectiveDataSource

    suspend fun currentRefreshIntervalSeconds(): Double =
        settingsRepository.settings.first().refreshIntervalSeconds

    suspend fun fetchTrackLog(start: Instant?, end: Instant?): Map<String, TrackLogDevice> {
        val settings = settingsRepository.settings.first()
        return apiClient.fetchTrackLog(settings.baseUrl, start, end)
    }
}
