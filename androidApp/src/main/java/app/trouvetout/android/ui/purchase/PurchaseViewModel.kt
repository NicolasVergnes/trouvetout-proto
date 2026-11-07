package app.trouvetout.android.ui.purchase

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trouvetout.android.App
import app.trouvetout.android.billing.ProductKind
import app.trouvetout.android.billing.PurchaseOutcome
import app.trouvetout.android.billing.StoreProduct
import app.trouvetout.core.licensing.LicenseStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PurchaseMessage {
    PENDING,
    NOTHING_TO_RESTORE,
    RESTORED,
    UNAVAILABLE,
}

data class PurchaseUiState(
    val status: LicenseStatus? = null,
    val products: List<StoreProduct> = emptyList(),
    /** Prices are still being asked of the store. */
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: PurchaseMessage? = null,
)

/** J4, J5: the licence as the core reads it and the store's products. No rule here. */
class PurchaseViewModel(
    private val app: App,
) : ViewModel() {
    private val _state = MutableStateFlow(PurchaseUiState())
    val state: StateFlow<PurchaseUiState> = _state

    init {
        viewModelScope.launch {
            app.licenseChanges.collect { readStatus() }
        }
        viewModelScope.launch {
            val products = runCatching { app.purchases.products() }.getOrDefault(emptyList())
            _state.update { it.copy(products = products, loading = false) }
        }
    }

    fun buy(
        activity: Activity,
        kind: ProductKind,
    ) {
        run {
            // The store's sheet covers the app for a moment: not a departure for the lock.
            app.expectReturn()
            when (app.purchases.purchase(activity, kind)) {
                PurchaseOutcome.COMPLETED -> refresh()
                PurchaseOutcome.PENDING -> say(PurchaseMessage.PENDING)
                PurchaseOutcome.CANCELLED -> Unit
                PurchaseOutcome.UNAVAILABLE -> say(PurchaseMessage.UNAVAILABLE)
            }
        }
    }

    /** J5: the store knows what this account bought, on this phone or another. */
    fun restore() {
        run {
            when (refresh()) {
                null -> say(PurchaseMessage.UNAVAILABLE)
                true -> say(PurchaseMessage.RESTORED)
                false -> say(PurchaseMessage.NOTHING_TO_RESTORE)
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun refresh(): Boolean? {
        val entitled = app.purchases.entitled()
        if (entitled != null) app.readyShared.first().licensing.recordPurchased(entitled)
        readStatus()
        return entitled
    }

    private suspend fun readStatus() {
        val status = app.readyShared.first().licensing.status()
        _state.update { it.copy(status = status) }
    }

    private fun say(message: PurchaseMessage) = _state.update { it.copy(message = message) }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                say(PurchaseMessage.UNAVAILABLE)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
