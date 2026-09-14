package com.cobaltloom.loraviewer.data.alert

/**
 * How serious an alert is. Declaration order is CAUTION then WARNING, so Kotlin enums' built-in
 * ordinal-based Comparable already orders the more severe one as greater.
 */
enum class AlertSeverity(val label: String) {
    CAUTION("注意"),
    WARNING("警告"),
}

/**
 * One rule (custom alert or competition guideline) a glider is currently triggering, and its
 * severity. [pairKey] is set only for proximity reasons, identifying the glider pair
 * (order-independent) this reason came from - the same pair produces one reason on each glider,
 * and this lets the alert banner collapse them into a single mention instead of reporting the
 * pair from both directions.
 */
data class GliderAlertReason(val label: String, val severity: AlertSeverity, val pairKey: String? = null)

/** The most severe reason present, or null if there are none. */
fun List<GliderAlertReason>.overallSeverity(): AlertSeverity? = maxOfOrNull { it.severity }
