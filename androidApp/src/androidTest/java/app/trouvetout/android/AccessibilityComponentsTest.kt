package app.trouvetout.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trouvetout.android.ui.capture.SimpleModeButtons
import app.trouvetout.android.ui.lock.HiddenRow
import app.trouvetout.android.ui.lock.LockScreen
import app.trouvetout.android.ui.lock.StoreFailureScreen
import app.trouvetout.android.ui.settings.PrivacySettingsUi
import app.trouvetout.android.ui.settings.SettingsScreen
import app.trouvetout.android.ui.settings.SettingsUiState
import app.trouvetout.android.ui.theme.AppTheme
import app.trouvetout.core.di.StoreFailure
import app.trouvetout.core.privacy.PrivacyState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** I6: the screens of lot 8 that a test cannot reach through the app (lock, lost key) and the simplified home. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AccessibilityComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun check(content: @Composable () -> Unit) {
        compose.enableAccessibilityChecks()
        compose.setContent { AppTheme { content() } }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun the_lock_screen() = check { LockScreen(onUnlock = {}) }

    @Test
    fun the_lost_key_screen() = check { StoreFailureScreen(StoreFailure.KEY_LOST, onWipe = {}) }

    @Test
    fun the_simplified_home_and_a_hidden_row() = check {
        androidx.compose.foundation.layout.Column {
            SimpleModeButtons(onStore = {}, onFind = {})
            HiddenRow("2 sensitive objects hidden: tap to show them", onReveal = {})
        }
    }

    @Test
    fun the_settings_with_the_lock_on() = check {
        SettingsScreen(
            SettingsUiState(),
            onBack = {},
            onExport = {},
            onImport = {},
            onWipe = {},
            onDismissMessage = {},
            privacy = PrivacySettingsUi(state = PrivacyState(lockEnabled = true, noKeyboardLearning = true)),
            simpleMode = true,
        )
    }
}
