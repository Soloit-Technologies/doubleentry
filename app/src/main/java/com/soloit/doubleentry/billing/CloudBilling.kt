package com.soloit.doubleentry.billing

import android.app.Activity
import android.content.Context
import android.util.Log
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
import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.sync.SyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One way to pay for cloud backup: a base plan of [CloudBilling.PRODUCT_ID], with the best offer the user is eligible for. */
data class CloudPlan(
    val basePlanId: String,
    /** The recurring price, e.g. "299,00 kr". */
    val price: String,
    /** ISO-8601 billing period of the recurring price: P1M, P1Y, … */
    val period: String,
    /** ISO-8601 length of a free trial this user can still get (P14D, P1M, …), or null. */
    val freeTrial: String?,
    internal val offerToken: String,
    internal val details: ProductDetails,
) {
    val yearly: Boolean get() = period == "P1Y"
}

data class BillingState(
    /** Google Play billing answered and the subscription is set up in the Play Console. */
    val available: Boolean = false,
    val loading: Boolean = true,
    val plans: List<CloudPlan> = emptyList(),
    /** A purchase is waiting for the server's confirmation. */
    val confirming: Boolean = false,
    /** A payment that Google hasn't completed yet (e.g. cash at a store). */
    val pending: Boolean = false,
    val message: String? = null,
)

/**
 * Google Play subscriptions for cloud backup. A purchase is tagged with the Supabase user id,
 * confirmed by the server ([SyncManager.verifyPurchase], which also starts uploading) and only
 * then acknowledged; Google refunds purchases that stay unacknowledged for three days, so a
 * purchase the server never recorded isn't charged.
 */
class CloudBilling(context: Context, private val sync: SyncManager, private val scope: CoroutineScope) : PurchasesUpdatedListener {
    private val packageName = context.packageName
    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val connectLock = Mutex()
    private val confirmation = PurchaseConfirmation(sync::verifyPurchase, ::acknowledge, _state)

    /** Google Play's page where the user changes or cancels the subscription. */
    val manageUrl: String get() = "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=$packageName"

    /** Loads the plans (prices in the user's currency, trial eligibility). */
    suspend fun loadPlans() {
        _state.update { it.copy(loading = true) }
        val plans = try {
            if (!connect()) null else {
                val params = QueryProductDetailsParams.newBuilder()
                    .setProductList(
                        listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build()),
                    )
                    .build()
                val result = client.queryProductDetails(params)
                if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) null
                else result.productDetailsList.orEmpty().flatMap(::plansOf).sortedByDescending { it.yearly }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load plans", e)
            null
        }
        _state.update { it.copy(loading = false, available = !plans.isNullOrEmpty(), plans = plans.orEmpty()) }
    }

    /** Opens Google Play's purchase sheet; the result arrives in [onPurchasesUpdated]. */
    fun purchase(activity: Activity, plan: CloudPlan) {
        val userId = sync.userId ?: return _state.update { it.copy(message = "Sign in first, so the subscription is linked to your account.") }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(plan.details).setOfferToken(plan.offerToken).build()),
            )
            // Links the purchase to the DoubleEntry account; the server checks it.
            .setObfuscatedAccountId(userId)
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) _state.update { it.copy(message = messageFor(result)) }
    }

    /**
     * Sends purchases Google knows about to the server: those not yet acknowledged, or all of them
     * when [everything] (the user tapped "Restore purchase", or the account has no access although
     * this Google account may have paid, e.g. after a reinstall). [userAsked] reports when nothing was found.
     */
    suspend fun restore(everything: Boolean, userAsked: Boolean = false) {
        if (sync.userId == null || !connect()) return
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return
        val purchases = result.purchasesList.filter { everything || !it.isAcknowledged }
        if (userAsked && purchases.isEmpty()) {
            _state.update { it.copy(message = "No cloud backup subscription was found for the Google account on this device.") }
        }
        purchases.forEach { confirmation.handle(it) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { purchases.orEmpty().forEach { confirmation.handle(it) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { restore(everything = true, userAsked = true) }
            else -> _state.update { it.copy(message = messageFor(result)) }
        }
    }

    /** Acknowledges a purchase with Google; one that fails is acknowledged when the app next opens ([restore]). */
    private suspend fun acknowledge(purchaseToken: String): Boolean {
        val result = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build())
        val ok = result.responseCode == BillingClient.BillingResponseCode.OK
        if (!ok) Log.w(TAG, "Purchase not acknowledged: ${result.responseCode} ${result.debugMessage}")
        return ok
    }

    private suspend fun connect(): Boolean = connectLock.withLock {
        if (client.isReady) return@withLock true
        val done = CompletableDeferred<Boolean>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                done.complete(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                done.complete(false)
            }
        })
        done.await()
    }

    /** Each base plan once, with the cheapest start this user is offered (a free trial when eligible). */
    private fun plansOf(details: ProductDetails): List<CloudPlan> =
        details.subscriptionOfferDetails.orEmpty()
            .groupBy { it.basePlanId }
            .mapNotNull { (basePlanId, offers) ->
                val offer = offers.minByOrNull { offer -> offer.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros ?: Long.MAX_VALUE }
                    ?: return@mapNotNull null
                val phases = offer.pricingPhases.pricingPhaseList
                val recurring = phases.lastOrNull() ?: return@mapNotNull null
                CloudPlan(
                    basePlanId = basePlanId,
                    price = recurring.formattedPrice,
                    period = recurring.billingPeriod,
                    freeTrial = phases.firstOrNull { it.priceAmountMicros == 0L }?.billingPeriod,
                    offerToken = offer.offerToken,
                    details = details,
                )
            }

    private fun messageFor(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            "Google Play can't take payments on this device. Check that you're signed in to Google Play."
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, BillingClient.BillingResponseCode.NETWORK_ERROR ->
            "Google Play can't be reached. Please check your connection and try again."
        else -> "The purchase didn't go through. Please try again."
    }

    companion object {
        /** The subscription product in the Play Console (Monetize → Subscriptions); its base plans are the [CloudPlan]s. */
        const val PRODUCT_ID = "cloud_backup"
        private const val TAG = "CloudBilling"
    }
}

/**
 * Confirms a purchase with the server ([verify]) and only then acknowledges it with Google
 * ([acknowledge]): Google refunds a purchase left unacknowledged for three days, so one the server
 * never recorded isn't charged. Purchases of other products are ignored.
 */
internal class PurchaseConfirmation(
    private val verify: suspend (purchaseToken: String) -> Unit,
    private val acknowledge: suspend (purchaseToken: String) -> Boolean,
    private val state: MutableStateFlow<BillingState>,
) {
    private val lock = Mutex()

    suspend fun handle(purchase: Purchase) = lock.withLock {
        if (CloudBilling.PRODUCT_ID !in purchase.products) return@withLock
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> state.update { it.copy(pending = true) }
            Purchase.PurchaseState.PURCHASED -> {
                state.update { it.copy(confirming = true, pending = false) }
                try {
                    verify(purchase.purchaseToken)
                    if (!purchase.isAcknowledged) acknowledge(purchase.purchaseToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Purchase not confirmed", e)
                    state.update { it.copy(message = SupabaseApi.userMessage(e)) }
                } finally {
                    state.update { it.copy(confirming = false) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "CloudBilling"
    }
}
