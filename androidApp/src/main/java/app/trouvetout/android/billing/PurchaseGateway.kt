package app.trouvetout.android.billing

import android.app.Activity

/** The two products of ADR-014. */
enum class ProductKind {
    /** The one-time unlock. */
    LIFETIME,

    /** The yearly subscription. */
    YEARLY,
}

/** A product as the store offers it to this account: the price is the store's, already formatted. */
data class StoreProduct(
    val kind: ProductKind,
    val price: String,
)

enum class PurchaseOutcome {
    COMPLETED,

    /** Payment still to be confirmed (cash, slow card): nothing is granted yet. */
    PENDING,
    CANCELLED,
    UNAVAILABLE,
}

/** What the app needs from the store; only [PlayPurchaseGateway] talks to Play Billing. */
interface PurchaseGateway {
    /** Whether a verified purchase or a running subscription exists; null when the store could not answer. */
    suspend fun entitled(): Boolean?

    /** Empty when the store cannot say. */
    suspend fun products(): List<StoreProduct>

    suspend fun purchase(
        activity: Activity,
        kind: ProductKind,
    ): PurchaseOutcome
}
