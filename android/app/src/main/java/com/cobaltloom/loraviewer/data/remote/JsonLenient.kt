package com.cobaltloom.loraviewer.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * TrailRouteView's PHP backend is inconsistent about whether numeric fields
 * are emitted as JSON numbers or as strings. kotlinx.serialization's
 * JsonPrimitive stores the raw token as a string either way, so
 * doubleOrNull/contentOrNull already parse leniently regardless of which
 * form the server chose - these just guard the "not present / wrong shape"
 * cases.
 */
fun JsonObject.lenientDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.lenientString(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
