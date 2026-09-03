package com.cobaltloom.loraviewer.data.remote

import com.cobaltloom.loraviewer.data.model.AppConfig
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.model.PositionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Talks to the same PHP endpoints used by the site's own js/plot53.js. */
class TrailRouteApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchConfig(baseUrl: String): AppConfig = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("load_config.php")?.build()
            ?: throw TrailRouteApiException.InvalidBaseUrl()
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()

        val root = execute(request)
        requireSuccess(root)
        val config = root["config"]?.jsonObject ?: throw TrailRouteApiException.InvalidResponse()
        config.toAppConfig()
    }

    suspend fun fetchCurrentPositions(baseUrl: String, secretKey: String): List<GliderPosition> =
        withContext(Dispatchers.IO) {
            val builder = baseUrl.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("mapapi.php")
                ?: throw TrailRouteApiException.InvalidBaseUrl()
            // The per-account URL path is the site's actual access control; `key` is
            // only sent when the user has supplied one, in case some deployments
            // still check it.
            builder.addQueryParameter("rdm", Math.random().toString())
            if (secretKey.isNotEmpty()) {
                builder.addQueryParameter("key", secretKey)
            }
            val request = Request.Builder().url(builder.build()).get().build()

            val root = execute(request)
            requireSuccess(root)
            val positions = root["positions"]?.jsonArray ?: throw TrailRouteApiException.InvalidResponse()
            positions.map { it.jsonObject.toGliderPosition() }
        }

    private fun execute(request: Request): JsonObject {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TrailRouteApiException.InvalidResponse()
            val body = response.body?.string() ?: throw TrailRouteApiException.InvalidResponse()
            return try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw TrailRouteApiException.InvalidResponse()
            }
        }
    }

    private fun requireSuccess(root: JsonObject) {
        val result = root.lenientString("result")
        if (result != "0") {
            val message = root.lenientString("message").ifEmpty { result }
            throw TrailRouteApiException.ServerError(message)
        }
    }
}

private fun JsonObject.toAppConfig(): AppConfig {
    val settings = this["settings"]?.jsonObject
    val siteTitle = settings?.lenientString("site_title") ?: ""
    val nameMasterDisplayed = this["name_master_displayed"]?.jsonObject
        ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
        ?: emptyMap()
    return AppConfig(siteTitle = siteTitle, nameMasterDisplayed = nameMasterDisplayed)
}

private fun JsonObject.toGliderPosition(): GliderPosition {
    val dtStr = (this["position_datetime"] as? JsonPrimitive)?.contentOrNull
    return GliderPosition(
        imei = lenientString("imei"),
        index = lenientString("index"),
        lat = lenientDouble("lat") ?: 0.0,
        lon = lenientDouble("lon") ?: 0.0,
        alt = lenientDouble("alt"),
        source = PositionSource.fromRaw(lenientString("source")),
        isDisconnected = lenientString("dc_flag") == "1",
        positionDateTimeUtc = dtStr?.let { TrailRouteDateFormatter.parse(it) },
    )
}
