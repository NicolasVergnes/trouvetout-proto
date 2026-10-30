package app.trouvetout.android

import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trouvetout.android.capture.CaptureActivity
import app.trouvetout.android.ui.capture.CAPTURE_FIELD_TAG
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The quick capture of the widget and the tile (B5): a typed sentence is recorded and confirmed without the
 * home screen; on Android 10–11 the microphone never listens (no on-device guarantee, B2) and says why.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class QuickCaptureTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun a_typed_sentence_is_recorded_and_confirmed() {
        val item = "lamp-${System.currentTimeMillis() % 100_000}"
        val place = "attic-${System.currentTimeMillis() % 100_000}"
        ActivityScenario.launch<CaptureActivity>(CaptureActivity.intent(app, CaptureActivity.MODE_TEXT)).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(CAPTURE_FIELD_TAG), timeoutMillis = TIMEOUT)
            compose.onNode(hasTestTag(CAPTURE_FIELD_TAG)).performTextInput("$item @ $place")
            compose.onNode(hasContentDescription(app.getString(R.string.capture_send))).performClick()

            val create = app.getString(R.string.capture_place_create, place)
            compose.waitUntilAtLeastOneExists(hasText(create), timeoutMillis = TIMEOUT)
            compose.onNode(hasText(create) and hasClickAction()).performClick()

            // The confirmation card (B6) names the object and the place.
            compose.waitUntilAtLeastOneExists(hasText(item), timeoutMillis = TIMEOUT)
            compose.waitUntilAtLeastOneExists(
                hasText(app.getString(R.string.capture_card_stored, place)),
                timeoutMillis = TIMEOUT,
            )
        }
    }

    @Test
    fun voice_on_android_10_and_11_falls_back_to_text_with_a_reason() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        ActivityScenario.launch<CaptureActivity>(CaptureActivity.intent(app, CaptureActivity.MODE_VOICE)).use {
            val reasons =
                listOf(R.string.voice_not_on_device, R.string.voice_no_recognizer).map { app.getString(it) }
            compose.waitUntilAtLeastOneExists(
                hasText(reasons[0]) or hasText(reasons[1]),
                timeoutMillis = TIMEOUT,
            )
            // Never listening, never asking for the microphone.
            compose.onNode(hasText(app.getString(R.string.capture_listening))).assertDoesNotExist()
            compose.onNode(hasText(app.getString(R.string.voice_rationale_title))).assertDoesNotExist()
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
