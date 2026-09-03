package com.cobaltloom.loraviewer.data.alert

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.data.model.GliderPosition
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.upperAltitudeDataStore by preferencesDataStore(name = "upper_altitude_guideline")

/** A polygon airspace zone, as published by JSAL for 妻沼滑空場: vertices connected back to the first one. */
data class AirspaceZone(val name: String, val boundary: List<Coordinate>) {
    /** Standard ray-casting point-in-polygon test; accurate enough at this scale (tens of km). */
    fun contains(coordinate: Coordinate): Boolean {
        var isInside = false
        var j = boundary.size - 1
        for (i in boundary.indices) {
            val vertexI = boundary[i]
            val vertexJ = boundary[j]
            val straddles = (vertexI.latitude > coordinate.latitude) != (vertexJ.latitude > coordinate.latitude)
            if (straddles) {
                val longitudeAtLatitude = vertexI.longitude +
                    (coordinate.latitude - vertexI.latitude) / (vertexJ.latitude - vertexI.latitude) *
                    (vertexJ.longitude - vertexI.longitude)
                if (coordinate.longitude < longitudeAtLatitude) isInside = !isInside
            }
            j = i
        }
        return isInside
    }
}

/** How B区域's altitude ceiling for "today" is decided. */
@Serializable
enum class UpperCeilingMode {
    /** Weekday vs. weekend, per JSAL's standard rule ([UpperAltitudeSettings.treatTodayAsHoliday] covers holidays). */
    AUTO,

    /** A competition (or other special arrangement) is in effect, with its own granted ceiling. */
    COMPETITION,
}

@Serializable
data class UpperAltitudeSettings(
    val isEnabled: Boolean = false,
    val mode: UpperCeilingMode = UpperCeilingMode.AUTO,
    val treatTodayAsHoliday: Boolean = false,
    val competitionCeilingFt: Double = 4500.0,
) {
    /** B区域's ceiling (ft) for today, given the current mode/settings. */
    val bZoneCeilingFt: Double
        get() = when (mode) {
            UpperCeilingMode.COMPETITION -> competitionCeilingFt
            UpperCeilingMode.AUTO -> {
                val today = LocalDate.now().dayOfWeek
                val isWeekend = today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY
                if (isWeekend || treatTodayAsHoliday) {
                    UpperAltitudeGuideline.ZONE_B_WEEKEND_CEILING_FT
                } else {
                    UpperAltitudeGuideline.ZONE_B_WEEKDAY_CEILING_FT
                }
            }
        }

    val bZoneCeilingM: Double get() = bZoneCeilingFt * UpperAltitudeGuideline.FEET_TO_METERS

    /** The zone [glider] is currently inside and its ceiling (meters MSL), or null if in neither. */
    fun applicableZone(glider: GliderPosition): Pair<String, Double>? {
        val coordinate = Coordinate(glider.lat, glider.lon)
        if (UpperAltitudeGuideline.zoneB.contains(coordinate)) return UpperAltitudeGuideline.zoneB.name to bZoneCeilingM
        if (UpperAltitudeGuideline.zoneA.contains(coordinate)) {
            return UpperAltitudeGuideline.zoneA.name to UpperAltitudeGuideline.zoneACeilingM
        }
        return null
    }

    /** True if [glider] is above the ceiling for whichever zone it's currently inside. */
    fun exceedsCeiling(glider: GliderPosition): Boolean {
        if (!isEnabled) return false
        val altM = glider.alt ?: return false
        val zone = applicableZone(glider) ?: return false
        return altM > zone.second
    }
}

/**
 * The official JSAL 妻沼滑空場 upper altitude limits: A区域 and B区域, two overlapping polygons
 * around the field, each with its own MSL ceiling. B区域 is the inner, more restrictive one.
 */
object UpperAltitudeGuideline {
    const val FEET_TO_METERS = 0.3048

    private fun dms(latD: Int, latM: Double, lonD: Int, lonM: Double) =
        Coordinate(latitude = latD + latM / 60, longitude = lonD + lonM / 60)

    /** A区域: the outer polygon, ceiling always 4,500ft MSL. */
    val zoneA = AirspaceZone(
        name = "A区域",
        boundary = listOf(
            dms(36, 12 + 35.0 / 60, 139, 22 + 45.0 / 60),
            dms(36, 14 + 10.0 / 60, 139, 28 + 23.0 / 60),
            dms(36, 13 + 14.0 / 60, 139, 34 + 46.0 / 60),
            dms(36, 15 + 18.0 / 60, 139, 37 + 30.0 / 60),
            dms(36, 17 + 6.0 / 60, 139, 36 + 43.0 / 60),
            dms(36, 21 + 11.0 / 60, 139, 26 + 48.0 / 60),
            dms(36, 16 + 11.0 / 60, 139, 18 + 48.0 / 60),
        ),
    )

    const val ZONE_A_CEILING_FT = 4500.0
    val zoneACeilingM: Double get() = ZONE_A_CEILING_FT * FEET_TO_METERS

    /** B区域: the inner polygon (shares its first three vertices with A区域). */
    val zoneB = AirspaceZone(
        name = "B区域",
        boundary = listOf(
            dms(36, 12 + 35.0 / 60, 139, 22 + 45.0 / 60),
            dms(36, 14 + 10.0 / 60, 139, 28 + 23.0 / 60),
            dms(36, 13 + 14.0 / 60, 139, 34 + 46.0 / 60),
            dms(36, 10 + 40.0 / 60, 139, 31 + 15.0 / 60),
            dms(36, 10 + 37.0 / 60, 139, 25 + 0.0 / 60),
        ),
    )

    const val ZONE_B_WEEKDAY_CEILING_FT = 2500.0
    const val ZONE_B_WEEKEND_CEILING_FT = 3500.0
}

class UpperAltitudeGuidelineRepository(private val context: Context) {
    private val key = stringPreferencesKey("upperAltitudeSettingsJson")
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<UpperAltitudeSettings> = context.upperAltitudeDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<UpperAltitudeSettings>(it) }.getOrNull() }
            ?: UpperAltitudeSettings()
    }

    suspend fun save(settings: UpperAltitudeSettings) {
        context.upperAltitudeDataStore.edit { it[key] = json.encodeToString(settings) }
    }
}
