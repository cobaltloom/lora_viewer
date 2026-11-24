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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cobaltloom.loraviewer.data.ads.AdManager
import com.cobaltloom.loraviewer.data.ads.LocalAdManager
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * A full-width banner for a Scaffold's bottomBar, unless ads are off (a rewarded ad watched in the
 * last 24 h). The SDK picks the ad's height, capped at 60 dp (50 dp on a short screen such as
 * landscape, where the map has little room); the slot shrinks to the ad it actually gets. [aboveBar]:
 * placed over another bottom bar (the map's), so no navigation-bar inset, and spaced off that bar's
 * buttons so a tap meant for them doesn't land on the ad.
 */
@Composable
fun AdBanner(aboveBar: Boolean = false) {
    val adManager = LocalAdManager.current ?: return
    val state by adManager.state.collectAsState()
    if (!state.showsBanners) return
    val maxHeightDp = if (LocalConfiguration.current.screenHeightDp < 480) 50 else 60
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().then(if (aboveBar) Modifier.padding(bottom = 8.dp) else Modifier.navigationBarsPadding()),
        ) {
            val widthDp = maxWidth.value.toInt()
            // An AdView's size can be set only once, so a new width or cap (rotation) makes a new one.
            key(widthDp, maxHeightDp) {
                val adSize = remember { AdSize.getInlineAdaptiveBannerAdSize(widthDp, maxHeightDp) }
                var loadedHeightDp by remember { mutableStateOf<Int?>(null) }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(minOf(loadedHeightDp ?: maxHeightDp, maxHeightDp).dp),
                    factory = { viewContext ->
                        AdView(viewContext).apply {
                            setAdSize(adSize)
                            adUnitId = AdManager.BANNER_UNIT_ID
                            adListener = object : AdListener() {
                                override fun onAdLoaded() {
                                    loadedHeightDp = this@apply.adSize?.height
                                }
                            }
                            loadAd(AdRequest.Builder().build())
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
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
