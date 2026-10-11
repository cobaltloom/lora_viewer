package com.cobaltloom.loraviewer.data.model

enum class PositionSource(val raw: String, val label: String) {
    GPS("1", "GPS"),
    CELL("2", "セル"),
    UNKNOWN("0", "不明"),
    /** Not a LoRa tracker field value: marks positions that came from OGN Japan. */
    OGN("ogn", "OGN");

    companion object {
        fun fromRaw(raw: String): PositionSource = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}
