package app.trouvetout.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trouvetout.android.ui.capture.CAPTURE_FIELD_TAG
import app.trouvetout.android.ui.capture.CaptureCallbacks
import app.trouvetout.android.ui.capture.CaptureScreen
import app.trouvetout.android.ui.capture.CaptureUiState
import app.trouvetout.android.ui.capture.WELCOME_CARD_TAG
import app.trouvetout.android.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** I10: the first steps are a card on the home screen, never a gate; both of its actions reach the screen's host. */
@RunWith(AndroidJUnit4::class)
class WelcomeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int) = context.getString(id)

    private var dismissed = 0
    private var placesOpened = 0

    private val callbacks =
        CaptureCallbacks(
            onInputChange = {},
            onSubmit = {},
            onPickSuggestion = {},
            onChoosePlace = {},
            onChooseItem = {},
            onDismissDecision = {},
            onDismissCard = {},
            onEditLast = {},
            onUndo = {},
            onDismissMessages = {},
            onOpenSettings = {},
            onOpenPlaces = { placesOpened++ },
            onOpenItem = {},
            onDismissWelcome = { dismissed++ },
        )

    @Test
    fun the_card_offers_the_place_sets_and_goes_away_on_request() {
        compose.setContent {
            AppTheme { CaptureScreen(CaptureUiState(), emptyList(), callbacks, showWelcome = true) }
        }
        compose.onNodeWithTag(WELCOME_CARD_TAG).assertIsDisplayed()
        // The capture field stays usable under it: nothing is blocked.
        compose.onNodeWithTag(CAPTURE_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_places_action)).performClick()
        compose.onNodeWithText(text(R.string.welcome_dismiss)).performClick()
        assertEquals(1, placesOpened)
        assertEquals(1, dismissed)
    }

    @Test
    fun the_simplified_mode_shows_it_without_the_place_sets() {
        compose.setContent {
            AppTheme {
                CaptureScreen(CaptureUiState(), emptyList(), callbacks, simpleMode = true, showWelcome = true)
            }
        }
        compose.onNodeWithTag(WELCOME_CARD_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_places_action)).assertDoesNotExist()
    }
}
