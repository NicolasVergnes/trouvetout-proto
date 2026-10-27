package app.trouvetout.android

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U1 in text on a real Android 10: the app starts on the capture screen, a sentence creates the place and the
 * object, and the object is listed. Runs against the app's own private storage of the test device.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CaptureSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun a_typed_sentence_creates_the_place_and_lists_the_object() {
        val item = "torch-${System.currentTimeMillis() % 100_000}"
        val place = "shelf-${System.currentTimeMillis() % 100_000}"
        val send = compose.activity.getString(R.string.capture_send)
        val create = compose.activity.getString(R.string.capture_place_create, place)

        compose.waitUntilAtLeastOneExists(hasSetTextAction(), timeoutMillis = TIMEOUT)
        compose.onNode(hasSetTextAction()).performTextInput("$item @ $place")
        compose.onNode(hasContentDescription(send)).performClick()

        // The place is unknown: the question offers to create it (B7).
        compose.waitUntilAtLeastOneExists(hasText(create), timeoutMillis = TIMEOUT)
        compose.onNode(hasText(create) and hasClickAction()).performClick()

        // The object is in the list (not only in the confirmation card, which is a clickable row too).
        compose.waitUntilAtLeastOneExists(hasText(item) and hasClickAction(), timeoutMillis = TIMEOUT)
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
