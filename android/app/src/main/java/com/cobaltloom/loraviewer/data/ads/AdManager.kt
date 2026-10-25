package com.cobaltloom.loraviewer.data.ads

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.adsDataStore by preferencesDataStore(name = "ads")

/** The [AdManager] for the screens that show ads; null in previews. */
val LocalAdManager = staticCompositionLocalOf<AdManager?> { null }

/**
 * Banner ads on the screens other than the map, and a rewarded ad that turns them off for
 * [REWARD_DURATION_MILLIS]. Every feature is free either way.
 */
class AdManager(context: Context) {
    data class State(
        val isStarted: Boolean = false,
        /** Ads are off until then (epoch millis), after watching a rewarded ad. */
        val adFreeUntilMillis: Long? = null,
        val isRewardedAdReady: Boolean = false,
        val isLoadingRewardedAd: Boolean = false,
    ) {
        val isRewardActive: Boolean get() = (adFreeUntilMillis ?: 0) > System.currentTimeMillis()
        val showsBanners: Boolean get() = isStarted && !isRewardActive
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adFreeUntilKey = longPreferencesKey("rewardedAdFreeUntil")
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var rewardedAd: RewardedAd? = null
    private var expiryRefresh: Job? = null

    init {
        scope.launch {
            val until = appContext.adsDataStore.data.first()[adFreeUntilKey]
            _state.update { it.copy(adFreeUntilMillis = until) }
            scheduleExpiryRefresh()
        }
    }

    fun start() {
        if (_state.value.isStarted) return
        MobileAds.initialize(appContext) {
            _state.update { it.copy(isStarted = true) }
            loadRewardedAd()
        }
    }

    fun loadRewardedAd() {
        if (rewardedAd != null || _state.value.isLoadingRewardedAd) return
        _state.update { it.copy(isLoadingRewardedAd = true) }
        RewardedAd.load(appContext, REWARDED_UNIT_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                _state.update { it.copy(isRewardedAdReady = true, isLoadingRewardedAd = false) }
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                _state.update { it.copy(isRewardedAdReady = false, isLoadingRewardedAd = false) }
            }
        })
    }

    /** Shows the loaded rewarded ad; watching it to the end turns ads off for 24 h. */
    fun showRewardedAd(activity: Activity) {
        val ad = rewardedAd ?: return
        rewardedAd = null
        _state.update { it.copy(isRewardedAdReady = false) }
        ad.show(activity) {
            val until = System.currentTimeMillis() + REWARD_DURATION_MILLIS
            _state.update { it.copy(adFreeUntilMillis = until) }
            scope.launch { appContext.adsDataStore.edit { it[adFreeUntilKey] = until } }
            scheduleExpiryRefresh()
        }
        loadRewardedAd()
    }

    /** Re-emits the state when the reward runs out, so banners come back without a restart. */
    private fun scheduleExpiryRefresh() {
        expiryRefresh?.cancel()
        val remaining = (_state.value.adFreeUntilMillis ?: return) - System.currentTimeMillis()
        if (remaining <= 0) return
        expiryRefresh = scope.launch {
            delay(remaining + 1000)
            _state.update { it.copy(adFreeUntilMillis = null) }
        }
    }

    companion object {
        // Debug builds use Google's test ad units, so development never serves (or clicks) real ads.
        val BANNER_UNIT_ID =
            if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/9214589741"
            else "ca-app-pub-6754188955033183/5843014223"
        val REWARDED_UNIT_ID =
            if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/5224354917"
            else "ca-app-pub-6754188955033183/7667853902"
        const val REWARD_DURATION_MILLIS = 24 * 3600 * 1000L
    }
}
