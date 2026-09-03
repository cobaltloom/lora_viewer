package com.cobaltloom.loraviewer.data.model

/**
 * The subset of load_config.php's response this app needs: the site title,
 * the board-position -> display-name lookup ("1." etc), and the
 * board-position -> IMEI lookup (used to resolve a board scan's per-position
 * names into per-IMEI nicknames). The PHP response carries more
 * (name_master, event_settings, ...) that isn't used yet.
 */
data class AppConfig(
    val siteTitle: String,
    val nameMasterDisplayed: Map<String, String>,
    val imeiMaster: Map<String, String>,
)
