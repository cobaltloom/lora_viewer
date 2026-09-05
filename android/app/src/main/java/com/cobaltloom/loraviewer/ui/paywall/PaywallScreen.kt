package com.cobaltloom.loraviewer.ui.paywall

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.billing.BillingRepository
import kotlinx.coroutines.launch

private const val PRIVACY_POLICY_URL = "https://cobaltloom.github.io/lora_viewer/privacy-policy.html"

/**
 * Shown whenever a subscriber-only feature (favorites, nicknames, altitude alerts, guidelines) is
 * attempted without an active subscription. The map itself stays free and never routes here.
 * Play's own purchase sheet discloses the price/renewal terms at the moment of purchase; this
 * screen still surfaces them up front, and links to the privacy policy, matching the iOS app's
 * PaywallView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(billingRepository: BillingRepository, onBack: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val productDetails by billingRepository.productDetails.collectAsState()
    val isLoading by billingRepository.isLoading.collectAsState()
    val errorMessage by billingRepository.errorMessage.collectAsState()
    val isSubscribed by billingRepository.isSubscribed.collectAsState()

    LaunchedEffect(isSubscribed) { if (isSubscribed) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会員限定機能") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Flight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp).padding(top = 24.dp),
            )
            Text(
                "LoRa妻沼",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "お気に入り登録・ニックネーム変更・高度アラート・競技会ガイドライン表示は購読会員限定の機能です。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            val offerDetails = productDetails?.subscriptionOfferDetails?.firstOrNull()
            when {
                offerDetails != null -> {
                    val phases = offerDetails.pricingPhases.pricingPhaseList
                    val recurringPhase = phases.lastOrNull()
                    val trialPhase = phases.firstOrNull { it.priceAmountMicros == 0L }

                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            recurringPhase?.let {
                                Text("${it.formattedPrice} / 月", style = MaterialTheme.typography.titleLarge)
                            }
                            trialPhase?.let {
                                Text(
                                    "初回登録から${formatBillingPeriod(it.billingPeriod)}無料",
                                    color = Color(0xFF2E7D32),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Text(
                                "自動更新。いつでもGoogle Playの「定期購入」設定から解約できます。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }

                    Button(
                        onClick = { activity?.let { billingRepository.launchPurchaseFlow(it) } },
                        enabled = activity != null,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    ) {
                        Text("購読する")
                    }
                }
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 32.dp))
                    Text("読み込み中…", modifier = Modifier.padding(top = 8.dp))
                }
                else -> {
                    Text(
                        "商品情報を読み込めませんでした。通信環境を確認してください。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }

            errorMessage?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            TextButton(
                onClick = { scope.launch { billingRepository.refreshEntitlement() } },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("購入状況を更新")
            }

            Row(modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) {
                TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) {
                    Text("プライバシーポリシー", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Formats the common single-unit ISO-8601 durations Play uses for billing periods (P1M, P1W, ...). */
private fun formatBillingPeriod(iso8601Period: String): String {
    val match = Regex("""P(\d+)([YMWD])""").find(iso8601Period) ?: return iso8601Period
    val (amount, unit) = match.destructured
    val label = when (unit) {
        "D" -> "日間"
        "W" -> "週間"
        "M" -> "ヶ月"
        "Y" -> "年"
        else -> ""
    }
    return "$amount$label"
}
