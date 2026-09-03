package com.cobaltloom.loraviewer.data.model

/**
 * The subset of load_config.php's response the map screen needs: the site
 * title and the board-position -> display-name lookup ("1." etc). The PHP
 * response carries more (imei_master, event_settings, ...) that isn't used
 * yet.
 */
data class AppConfig(
    val siteTitle: String,
    val nameMasterDisplayed: Map<String, String>,
)
