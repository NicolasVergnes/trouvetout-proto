package app.trouvetout.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.ItemDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** H6 on a real Android 10, through `App.wipeAll()` as the settings and the lost-key screen call it. */
@RunWith(AndroidJUnit4::class)
class WipeTest {
    private val app = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun the_total_wipe_removes_data_key_caches_and_preferences() = runBlocking {
        val before = app.readyShared.first()
        before.itemCommands.create(ItemDraft("wipe-probe"), null, EventSource.TEXT)
        app.uiPrefs.setSimpleMode(true)
        val keyFile = File(app.noBackupFilesDir, "keys/db.key")
        assertTrue(keyFile.isFile)
        val oldKey = keyFile.readBytes()
        File(app.cacheDir, "probe").writeText("cache")
        val trialStart = before.licensing.status().trialEndMillis
        before.backup.enable(before.backup.drawRecoveryKey(), "file:///not-a-document")

        app.wipeAll()

        val after = app.readyShared.first { it !== before }
        assertEquals("the trial does not start again (ADR-025)", trialStart, after.licensing.status().trialEndMillis)
        assertNull("the recovery key goes with the data", after.backup.state().location)
        assertEquals(0L, after.itemRepository.observeActive().first().size.toLong())
        assertFalse("caches emptied", File(app.cacheDir, "probe").exists())
        assertFalse("display preferences reset", app.uiPrefs.simpleMode.value)
        assertTrue("a new key for the new database", keyFile.isFile)
        assertFalse("the old key is gone", oldKey.contentEquals(keyFile.readBytes()))
    }
}
