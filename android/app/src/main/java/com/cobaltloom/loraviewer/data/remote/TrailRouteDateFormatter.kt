package com.cobaltloom.loraviewer.data.remote

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** TrailRouteView sends/receives all timestamps as "yyyy-MM-dd HH:mm:ss" in UTC. */
object TrailRouteDateFormatter {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    fun parse(value: String): Instant? =
        try {
            Instant.from(formatter.parse(value))
        } catch (e: Exception) {
            null
        }
}
