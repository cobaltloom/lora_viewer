package com.cobaltloom.loraviewer.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cobaltloom.loraviewer.data.ads.AdManager
import com.cobaltloom.loraviewer.data.ads.LocalAdManager
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * An adaptive banner for a Scaffold's bottomBar, unless ads are off (a rewarded ad watched in the
 * last 24 h). Not used on the map, which stays clear and cool.
 */
@Composable
fun AdBanner() {
    val adManager = LocalAdManager.current ?: return
    val state by adManager.state.collectAsState()
    if (!state.showsBanners) return
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
            val context = LocalContext.current
            val widthDp = maxWidth.value.toInt()
            val adSize = remember(widthDp) { AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp) }
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(adSize.height.dp),
                factory = { viewContext ->
                    AdView(viewContext).apply {
                        setAdSize(adSize)
                        adUnitId = AdManager.BANNER_UNIT_ID
                        loadAd(AdRequest.Builder().build())
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

/** The activity hosting [this] context, for showing a full-screen ad. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The settings row for turning ads off for 24 h with a rewarded ad. */
@Composable
fun AdFreeRow() {
    val adManager = LocalAdManager.current ?: return
    val state by adManager.state.collectAsState()
    val context = LocalContext.current
    val until = state.adFreeUntilMillis
    if (state.isRewardActive && until != null) {
        val time = java.text.SimpleDateFormat("M月d日 H:mm", java.util.Locale.JAPAN).format(java.util.Date(until))
        Text("$time まで広告なし", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    TextButton(
        onClick = {
            if (state.isRewardedAdReady) context.findActivity()?.let(adManager::showRewardedAd) else adManager.loadRewardedAd()
        },
        enabled = state.isStarted && !state.isLoadingRewardedAd,
    ) {
        Text(
            when {
                state.isRewardedAdReady -> "動画広告を見て24時間広告なし"
                state.isLoadingRewardedAd || !state.isStarted -> "動画広告を読み込み中…"
                else -> "動画広告を読み込めませんでした(タップで再読み込み)"
            },
        )
    }
}
