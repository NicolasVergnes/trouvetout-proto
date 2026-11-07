package app.trouvetout.android.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Play Billing (J5), which talks to the Play Store on the phone; the app itself has no network permission. Everything
 * is asked again at each return to the foreground, nothing is cached here: the core keeps the last answer.
 */
class PlayPurchaseGateway(
    private val context: Context,
    licenseKey: String,
    /** A purchase changed outside a flow of this app: a pending payment confirmed, a purchase from another screen. */
    private val onChanged: () -> Unit,
) : PurchaseGateway,
    PurchasesUpdatedListener {
    private val verifier = PurchaseVerifier(licenseKey)

    /** At the first use: the app also wakes for an alarm or a quick capture, and must not call the store for those. */
    private val client: BillingClient by lazy {
        BillingClient
            .newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }
    private val connecting = Mutex()

    @Volatile
    private var flow: CompletableDeferred<PurchaseOutcome>? = null

    override suspend fun entitled(): Boolean? {
        if (!connected()) return null
        val oneTime = client.queryPurchasesAsync(purchasesOf(ProductType.INAPP))
        val subscriptions = client.queryPurchasesAsync(purchasesOf(ProductType.SUBS))
        if (!oneTime.billingResult.isOk() || !subscriptions.billingResult.isOk()) return null
        val owned = (oneTime.purchasesList + subscriptions.purchasesList).filter(::isOwned)
        owned.forEach { acknowledge(it) }
        return owned.isNotEmpty()
    }

    override suspend fun products(): List<StoreProduct> {
        if (!connected()) return emptyList()
        return listOfNotNull(
            details(ProductKind.LIFETIME)?.let(::lifetimeOffer)?.let { StoreProduct(ProductKind.LIFETIME, it.price) },
            details(ProductKind.YEARLY)?.let(::yearlyOffer)?.let { StoreProduct(ProductKind.YEARLY, it.price) },
        )
    }

    override suspend fun purchase(
        activity: Activity,
        kind: ProductKind,
    ): PurchaseOutcome {
        if (!connected()) return PurchaseOutcome.UNAVAILABLE
        // Never kept between flows: a stale object makes the flow fail.
        val details = details(kind) ?: return PurchaseOutcome.UNAVAILABLE
        val offer = (if (kind == ProductKind.LIFETIME) lifetimeOffer(details) else yearlyOffer(details))
            ?: return PurchaseOutcome.UNAVAILABLE
        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams
                            .newBuilder()
                            .setProductDetails(details)
                            .setOfferToken(offer.token)
                            .build(),
                    ),
                ).build()
        val answer = CompletableDeferred<PurchaseOutcome>()
        flow = answer
        val launched = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
        if (!launched.isOk()) {
            flow = null
            return PurchaseOutcome.UNAVAILABLE
        }
        return answer.await()
    }

    override fun onPurchasesUpdated(
        result: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        val waiting = flow
        flow = null
        waiting?.complete(outcomeOf(result, purchases.orEmpty()))
        // Acknowledged and reported by the refresh: one path for every way a purchase can arrive.
        if (result.isOk() || result.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED) onChanged()
    }

    private fun outcomeOf(
        result: BillingResult,
        purchases: List<Purchase>,
    ): PurchaseOutcome = when {
        result.responseCode == BillingResponseCode.USER_CANCELED -> PurchaseOutcome.CANCELLED
        result.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseOutcome.COMPLETED
        !result.isOk() -> PurchaseOutcome.UNAVAILABLE
        purchases.any(::isOwned) -> PurchaseOutcome.COMPLETED
        purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> PurchaseOutcome.PENDING
        else -> PurchaseOutcome.UNAVAILABLE
    }

    private fun isOwned(purchase: Purchase): Boolean =
        purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
            purchase.products.any { it == LIFETIME_ID || it == YEARLY_ID } &&
            verifier.isGenuine(purchase.originalJson, purchase.signature)

    /** Unacknowledged, the store refunds after three days; a failure here is retried at the next refresh. */
    private suspend fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
        )
    }

    private suspend fun details(kind: ProductKind): ProductDetails? {
        val product =
            QueryProductDetailsParams.Product
                .newBuilder()
                .setProductId(if (kind == ProductKind.LIFETIME) LIFETIME_ID else YEARLY_ID)
                .setProductType(if (kind == ProductKind.LIFETIME) ProductType.INAPP else ProductType.SUBS)
                .build()
        val answer =
            client.queryProductDetails(
                QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
            )
        return answer.productDetailsList?.firstOrNull().takeIf { answer.billingResult.isOk() }
    }

    private class Offer(
        val token: String,
        val price: String,
    )

    private fun lifetimeOffer(details: ProductDetails): Offer? =
        details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.let { offer ->
            offer.offerToken?.let { Offer(it, offer.formattedPrice) }
        }

    /** The plain yearly base plan, without any promotional offer. */
    private fun yearlyOffer(details: ProductDetails): Offer? =
        details.subscriptionOfferDetails
            ?.firstOrNull { it.basePlanId == YEARLY_BASE_PLAN && it.offerId == null }
            ?.let { offer ->
                offer.pricingPhases.pricingPhaseList.firstOrNull()?.let { Offer(offer.offerToken, it.formattedPrice) }
            }

    private fun purchasesOf(type: String) = QueryPurchasesParams.newBuilder().setProductType(type).build()

    private fun BillingResult.isOk() = responseCode == BillingResponseCode.OK

    private suspend fun connected(): Boolean = connecting.withLock {
        client.isReady || withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { connect() } == true
    }

    private suspend fun connect(): Boolean = suspendCancellableCoroutine { continuation ->
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.isOk())
                }

                // enableAutoServiceReconnection: the client reconnects by itself at the next call.
                override fun onBillingServiceDisconnected() = Unit
            },
        )
    }

    private companion object {
        // Permanent once declared in the console, and free of any brand (ADR-010).
        const val LIFETIME_ID = "unlock_lifetime"
        const val YEARLY_ID = "subscription_yearly"
        const val YEARLY_BASE_PLAN = "yearly"
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
    }
}
