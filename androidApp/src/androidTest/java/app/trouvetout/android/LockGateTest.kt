package app.trouvetout.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trouvetout.android.ui.lock.LockGate
import app.trouvetout.android.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** H3: while locked, the content below the cover is neither visible nor in the accessibility tree. */
@RunWith(AndroidJUnit4::class)
class LockGateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_locked_app_shows_no_object() {
        var prompts = 0
        compose.setContent {
            AppTheme { LockGate(locked = true, onUnlock = { prompts++ }) { Text("passeport") } }
        }
        compose.onNodeWithText("passeport").assertDoesNotExist()
        val unlock = ApplicationProvider.getApplicationContext<android.content.Context>().getString(
            R.string.lock_unlock,
        )
        compose.onNodeWithText(unlock).assertIsDisplayed()
        compose.runOnIdle { check(prompts == 1) { "the system prompt opens once by itself" } }
    }

    @Test
    fun an_unlocked_app_shows_its_content() {
        compose.setContent { AppTheme { LockGate(locked = false, onUnlock = {}) { Text("passeport") } } }
        compose.onNodeWithText("passeport").assertIsDisplayed()
    }
}
