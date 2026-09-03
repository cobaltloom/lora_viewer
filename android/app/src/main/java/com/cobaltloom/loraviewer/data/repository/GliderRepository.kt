package com.cobaltloom.loraviewer.data.repository

import com.cobaltloom.loraviewer.data.model.AppConfig
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.remote.TrailRouteApiClient
import com.cobaltloom.loraviewer.data.settings.ApiSettingsRepository
import kotlinx.coroutines.flow.first

class GliderRepository(
    private val apiClient: TrailRouteApiClient,
    private val settingsRepository: ApiSettingsRepository,
) {
    suspend fun fetchConfig(): AppConfig {
        val settings = settingsRepository.settings.first()
        return apiClient.fetchConfig(settings.baseUrl)
    }

    suspend fun fetchCurrentPositions(): List<GliderPosition> {
        val settings = settingsRepository.settings.first()
        return apiClient.fetchCurrentPositions(settings.baseUrl, settings.secretKey)
    }

    suspend fun currentRefreshIntervalSeconds(): Double =
        settingsRepository.settings.first().refreshIntervalSeconds
}
