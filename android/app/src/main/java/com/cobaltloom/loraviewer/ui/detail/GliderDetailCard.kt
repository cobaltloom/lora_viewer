package com.cobaltloom.loraviewer.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.AlertSeverity
import com.cobaltloom.loraviewer.data.alert.GliderAlertReason
import com.cobaltloom.loraviewer.data.alert.overallSeverity
import com.cobaltloom.loraviewer.data.model.GliderPosition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GliderDetailCard(
    glider: GliderPosition,
    displayName: String,
    isFavorite: Boolean,
    alertReasons: List<GliderAlertReason>,
    onToggleFavorite: () -> Unit,
    onEditName: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onDismiss),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val severity = alertReasons.overallSeverity()
            if (severity != null) {
                val labels = alertReasons.joinToString("・") { "${it.severity.label}: ${it.label}" }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            if (severity == AlertSeverity.WARNING) Color(0xFFD32F2F) else Color(0xFFF57C00),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    Text("高度アラート($labels)", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.padding(top = 6.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = "お気に入り",
                        tint = if (isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onEditName),
                )
                Text(
                    text = glider.source.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = glider.alt?.let { "${it.toInt()} m" } ?: "---",
                    style = MaterialTheme.typography.bodyMedium,
                )
                glider.positionDateTimeUtc?.let {
                    Text(text = formatLocalTime(it), style = MaterialTheme.typography.bodyMedium)
                }
                if (glider.isDisconnected) {
                    Text(
                        text = "切断",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                text = "%.5f, %.5f".format(glider.lat, glider.lon),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatLocalTime(instant: Instant): String = timeFormatter.format(instant)
