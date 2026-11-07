package app.trouvetout.android

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trouvetout.android.ui.capture.CaptureCallbacks
import app.trouvetout.android.ui.capture.CaptureDialogs
import app.trouvetout.android.ui.capture.CaptureUiState
import app.trouvetout.android.ui.settings.BackupCallbacks
import app.trouvetout.android.ui.settings.BackupSection
import app.trouvetout.android.ui.settings.BackupUiState
import app.trouvetout.android.ui.theme.AppTheme
import app.trouvetout.core.capture.CaptureProblem
import app.trouvetout.core.data.backup.BackupProblem
import app.trouvetout.core.data.backup.BackupState
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.ItemDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A9 and U11 on a real Android 10: the copy through the content resolver, the screens that ask. */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<App>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int) = context.getString(id)

    private val noCallbacks = BackupCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun a_backup_replaces_the_chosen_file_and_leaves_no_copy_behind() = runBlocking {
        val shared = app.readyShared.first()
        shared.itemCommands.create(ItemDraft("backup-probe"), null, EventSource.TEXT)
        val chosen = File(app.cacheDir, "chosen.ttbk")
        chosen.writeText("an older backup, longer than a header but shorter than this one will be")
        try {
            val key = shared.backup.newRecoveryKey()
            assertTrue(shared.backup.enable(key, Uri.fromFile(chosen).toString()))

            app.backupRunner.runNow(shared)

            val bytes = chosen.readBytes()
            assertEquals("TTBK", bytes.copyOf(4).decodeToString())
            assertFalse(bytes.decodeToString().contains("backup-probe"))
            assertNull("no problem to report", shared.backup.state().problem)
            assertTrue(File(app.noBackupFilesDir, "backups").list().orEmpty().isEmpty())
            assertFalse("nothing new", shared.backup.isDue())
        } finally {
            shared.backup.disable()
            chosen.delete()
        }
    }

    @Test
    fun a_destination_that_is_gone_is_reported_and_tried_again() = runBlocking {
        val shared = app.readyShared.first()
        shared.itemCommands.create(ItemDraft("backup-probe-2"), null, EventSource.TEXT)
        try {
            val gone = Uri.fromFile(File(app.cacheDir, "no-such-directory/chosen.ttbk"))
            assertTrue(shared.backup.enable(shared.backup.newRecoveryKey(), gone.toString()))

            app.backupRunner.runNow(shared)

            assertEquals(BackupProblem.FAILED, shared.backup.state().problem)
        } finally {
            shared.backup.disable()
        }
    }

    @Test
    fun the_new_key_cannot_be_confirmed_without_typing_its_end() {
        val key = "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG"
        var confirmed = false
        compose.setContent {
            AppTheme {
                BackupSection(BackupUiState(newKey = key), noCallbacks.copy(onConfirmKey = { confirmed = true }))
            }
        }
        compose.onNodeWithText(key).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_key_confirm)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.backup_key_confirm_label)).performTextInput("gggg")
        compose.onNodeWithText(text(R.string.backup_key_confirm)).assertIsEnabled().performClick()
        assertTrue(confirmed)
    }

    @Test
    fun a_failed_backup_and_its_reason_are_said() {
        val state =
            BackupState(location = "content://chosen", lastSuccessMillis = null, problem = BackupProblem.TOO_LARGE)
        compose.setContent { AppTheme { BackupSection(BackupUiState(backup = state), noCallbacks) } }
        compose.onNodeWithText(text(R.string.backup_too_large)).assertExists()
        compose.onNodeWithText(text(R.string.backup_never)).assertExists()
    }

    @Test
    fun the_end_of_the_trial_explains_and_offers_a_way_out_U11() {
        var opened = false
        var dismissed = false
        val callbacks =
            CaptureCallbacks(
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                onDismissMessages = { dismissed = true },
                onOpenSettings = {},
                onOpenPlaces = {},
                onOpenItem = {},
                onOpenPurchase = { opened = true },
            )
        compose.setContent {
            AppTheme { CaptureDialogs(CaptureUiState(problem = CaptureProblem.TRIAL_ENDED), callbacks) }
        }
        compose.onNodeWithText(text(R.string.purchase_ended_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.capture_problem_trial_ended)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.purchase_later)).performClick()
        assertTrue(dismissed)
        compose.onNodeWithText(text(R.string.purchase_see_options)).performClick()
        assertTrue(opened)
    }
}
