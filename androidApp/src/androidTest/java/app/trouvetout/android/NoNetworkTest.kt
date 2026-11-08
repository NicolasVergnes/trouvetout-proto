package app.trouvetout.android

import android.Manifest
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trouvetout.android.billing.PlayPurchaseGateway
import app.trouvetout.core.capture.CaptureOutcome
import app.trouvetout.core.capture.PlaceChoice
import app.trouvetout.core.capture.PlaceResolution
import app.trouvetout.core.data.export.ImportResult
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.search.SearchMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N4 measured, not only declared (lot 12): the app has no network permission, and the normal journey — a typed
 * capture (U1), a search with the embedding model loaded (U3), an export then its import (U8), the question to the
 * store — moves not one byte through the network for the app's uid.
 */
@RunWith(AndroidJUnit4::class)
class NoNetworkTest {
    private val app = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun the_app_cannot_open_the_network() {
        assertEquals(
            "no INTERNET permission, merged manifest included",
            PackageManager.PERMISSION_DENIED,
            app.checkSelfPermission(Manifest.permission.INTERNET),
        )
    }

    @Test
    fun the_normal_journey_moves_no_byte_through_the_network() = runBlocking {
        val uid = Process.myUid()
        val tx0 = TrafficStats.getUidTxBytes(uid)
        val rx0 = TrafficStats.getUidRxBytes(uid)
        assumeTrue("traffic counters available", tx0 != TrafficStats.UNSUPPORTED.toLong())

        ActivityScenario.launch(MainActivity::class.java).use {
            val shared = app.readyShared.first()
            val stamp = System.currentTimeMillis() % 100_000

            // U1, typed: the place is created on the fly.
            val proposal = shared.capture.propose("câble USB-C blanc $stamp @ bureau $stamp")
            val place = proposal.place as PlaceResolution.Unknown
            val recorded =
                shared.capture.commit(proposal, PlaceChoice.CreateNew(place.names, null, false), source = EventSource.TEXT)
            assertTrue(recorded is CaptureOutcome.Recorded)

            // U3, with the model loaded on this device.
            shared.search.prepareAndWait()
            assertEquals(SearchMode.Hybrid, shared.search.mode.value)
            assertNotNull(shared.search.search("le chargeur du Kindle").answer)

            // U8: export, then merge it back.
            val export = shared.exportService.export()
            assertTrue(shared.importService.import(export.relativePath) is ImportResult.Done)

            // The store is asked through the Play Store app (IPC), never by the app itself.
            PlayPurchaseGateway(app, "") {}.entitled()
        }

        assertEquals("bytes sent", 0L, TrafficStats.getUidTxBytes(uid) - tx0)
        assertEquals("bytes received", 0L, TrafficStats.getUidRxBytes(uid) - rx0)
    }
}
