package com.cobaltloom.loraviewer.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks whether the user has an active subscription, and drives the purchase flow. The whole
 * app is gated behind this: without an active subscription, a paywall is shown instead of the
 * map. Mirrors the iOS app's StoreKit-based SubscriptionManager, using Google Play Billing.
 */
class BillingRepository(context: Context) : PurchasesUpdatedListener {
    companion object {
        /** Must match the subscription's Product ID configured in Play Console. */
        const val MONTHLY_PRODUCT_ID = "monthly_subscription"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch {
                        loadProductDetails()
                        refreshEntitlement()
                    }
                } else {
                    _isLoading.value = false
                    _errorMessage.value = "課金サービスへの接続に失敗しました。"
                }
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    suspend fun loadProductDetails() {
        _isLoading.value = true
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(MONTHLY_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        val result = billingClient.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull()
        _productDetails.value = details
        if (details == null) {
            _errorMessage.value = "商品情報の取得に失敗しました。通信環境を確認して、もう一度お試しください。"
        }
        _isLoading.value = false
    }

    /**
     * queryPurchasesAsync only returns purchases Play still considers active, so "found nothing"
     * has to be treated as "not subscribed" rather than leaving the previous status in place.
     */
    suspend fun refreshEntitlement() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val result = billingClient.queryPurchasesAsync(params)
        val active = result.purchasesList.any { purchase ->
            MONTHLY_PRODUCT_ID in purchase.products && purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        _isSubscribed.value = active
        for (purchase in result.purchasesList) {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                acknowledge(purchase)
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity) {
        val details = _productDetails.value ?: return
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return
        _errorMessage.value = null
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        billingClient.launchBillingFlow(activity, flowParams)
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                scope.launch {
                    purchases?.forEach { purchase ->
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                            acknowledge(purchase)
                        }
                    }
                    refreshEntitlement()
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            else -> _errorMessage.value = "購入処理に失敗しました。もう一度お試しください。"
        }
    }

    private suspend fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billingClient.acknowledgePurchase(params)
    }
}
