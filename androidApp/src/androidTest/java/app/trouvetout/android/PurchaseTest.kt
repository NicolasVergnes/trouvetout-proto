package app.trouvetout.android

import android.app.Activity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trouvetout.android.billing.PlayPurchaseGateway
import app.trouvetout.android.billing.ProductKind
import app.trouvetout.android.billing.PurchaseGateway
import app.trouvetout.android.billing.PurchaseOutcome
import app.trouvetout.android.billing.PurchaseVerifier
import app.trouvetout.android.billing.StoreProduct
import app.trouvetout.android.ui.purchase.PurchaseScreen
import app.trouvetout.android.ui.purchase.PurchaseUiState
import app.trouvetout.android.ui.theme.AppTheme
import app.trouvetout.core.licensing.LicenseStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** J4, J5, U11, ADR-025: the store's client without its telemetry, the signature check, the screen. */
@RunWith(AndroidJUnit4::class)
class PurchaseTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int) = context.getString(id)

    /**
     * The telemetry of Play Billing is left out of the app: the client must start, log and report what it can without
     * it, never crash, never grant. The CI emulator has no Play Store: « no answer ». A phone with one and a signed-in
     * account (a Fairphone 4, 10 Nov. 2026) answers « nothing bought »: the app is not in a Play Console yet, so no
     * product either.
     */
    @Test
    fun the_store_client_works_without_its_telemetry_and_grants_nothing() = runBlocking {
        val gateway = PlayPurchaseGateway(ApplicationProvider.getApplicationContext(), "") {}
        val entitled = gateway.entitled()
        if (hasPlayStore()) {
            assertTrue("never granted, answer: $entitled", entitled != true)
        } else {
            assertNull(entitled)
        }
        assertEquals(emptyList<StoreProduct>(), gateway.products())
    }

    private fun hasPlayStore() = runCatching { context.packageManager.getPackageInfo(PLAY_STORE, 0) }.isSuccess

    @Test
    fun the_telemetry_and_the_location_library_are_not_in_the_app() {
        listOf(
            "com.google.android.datatransport.runtime.TransportRuntime",
            "com.google.android.gms.location.LocationServices",
        ).forEach { name ->
            val found = runCatching { Class.forName(name) }.isSuccess
            assertFalse(name, found)
        }
    }

    @Test
    fun only_a_purchase_signed_with_the_app_key_is_genuine() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = Base64.getEncoder().encodeToString(pair.public.encoded)
        val json = """{"productId":"unlock_lifetime"}"""
        val signature =
            Signature.getInstance("SHA1withRSA").run {
                initSign(pair.private)
                update(json.toByteArray())
                Base64.getEncoder().encodeToString(sign())
            }
        val verifier = PurchaseVerifier(key)
        assertTrue(verifier.isGenuine(json, signature))
        assertFalse("altered purchase", verifier.isGenuine("""{"productId":"other"}""", signature))
        assertFalse("not base64", verifier.isGenuine(json, "%%%"))
        assertFalse("no key", PurchaseVerifier("").isGenuine(json, signature))
        assertFalse("bad key", PurchaseVerifier("bm90IGEga2V5").isGenuine(json, signature))
    }

    @Test
    fun a_refresh_without_an_answer_leaves_the_last_purchase_state() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        val core = app.readyShared.first()
        val previous = app.purchases
        try {
            app.purchases = FakeGateway(entitled = true)
            app.syncPurchases()
            assertTrue(core.licensing.status().purchased)
            app.purchases = FakeGateway(entitled = null)
            app.syncPurchases()
            assertTrue("no answer: nobody loses a purchase offline", core.licensing.status().purchased)
            app.purchases = FakeGateway(entitled = false)
            app.syncPurchases()
            assertFalse("a subscription that ended", core.licensing.status().purchased)
        } finally {
            app.purchases = previous
        }
    }

    @Test
    fun the_ended_screen_says_what_goes_on_working_and_offers_both_products_U11() {
        val bought = mutableListOf<ProductKind>()
        var restored = false
        var closed = false
        compose.setContent {
            AppTheme {
                PurchaseScreen(
                    state = ended,
                    onBuy = { bought += it },
                    onRestore = { restored = true },
                    onBack = { closed = true },
                    onDismissMessage = {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.purchase_ended_text)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.purchase_lifetime, "10,00 €")).performClick()
        compose.onNodeWithText(context.getString(R.string.purchase_yearly, "2,00 €")).performClick()
        compose.onNodeWithText(text(R.string.purchase_restore)).performClick()
        compose.onNodeWithText(text(R.string.purchase_later)).performClick()
        assertEquals(listOf(ProductKind.LIFETIME, ProductKind.YEARLY), bought)
        assertTrue(restored)
        assertTrue("non blocking: there is always a way out", closed)
    }

    @Test
    fun a_store_that_does_not_answer_is_said_and_the_screen_stays_usable() {
        compose.setContent {
            AppTheme {
                PurchaseScreen(ended.copy(products = emptyList()), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.purchase_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.purchase_restore)).assertIsDisplayed()
    }

    @Test
    fun a_purchase_is_acknowledged_on_screen_and_nothing_more_is_offered() {
        compose.setContent {
            AppTheme {
                val status = LicenseStatus(purchased = true, trialEndMillis = 0L, canAddItems = true)
                PurchaseScreen(ended.copy(status = status), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.purchase_done_text)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.purchase_lifetime, "10,00 €")).assertDoesNotExist()
    }

    private val ended =
        PurchaseUiState(
            status = LicenseStatus(purchased = false, trialEndMillis = 0L, canAddItems = false),
            products =
                listOf(StoreProduct(ProductKind.LIFETIME, "10,00 €"), StoreProduct(ProductKind.YEARLY, "2,00 €")),
            loading = false,
        )

    private class FakeGateway(
        private val entitled: Boolean?,
    ) : PurchaseGateway {
        override suspend fun entitled(): Boolean? = entitled

        override suspend fun products(): List<StoreProduct> = emptyList()

        override suspend fun purchase(
            activity: Activity,
            kind: ProductKind,
        ): PurchaseOutcome = PurchaseOutcome.UNAVAILABLE
    }

    private companion object {
        const val PLAY_STORE = "com.android.vending"
    }
}
