package app.trouvetout.android

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trouvetout.android.ui.capture.CAPTURE_FIELD_TAG
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I6 on a real Android 10: the Accessibility Test Framework (touch targets, labels, contrast, duplicate
 * descriptions…) on the main screens of the app as the user reaches them: home, settings, places, search.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun enableChecks() {
        compose.enableAccessibilityChecks()
    }

    @Test
    fun the_main_screens_pass_the_accessibility_checks() {
        compose.waitUntilAtLeastOneExists(hasTestTag(CAPTURE_FIELD_TAG), timeoutMillis = TIMEOUT)
        compose.onRoot().tryPerformAccessibilityChecks()

        compose.onNode(hasText(compose.activity.getString(R.string.settings_title))).performClick()
        compose.waitUntilAtLeastOneExists(
            hasText(compose.activity.getString(R.string.settings_privacy_section)),
            timeoutMillis = TIMEOUT,
        )
        compose.onRoot().tryPerformAccessibilityChecks()
        back()

        compose.waitUntilAtLeastOneExists(hasTestTag(CAPTURE_FIELD_TAG), timeoutMillis = TIMEOUT)
        compose.onNode(hasText(compose.activity.getString(R.string.places_title))).performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription(backLabel()), timeoutMillis = TIMEOUT)
        compose.onRoot().tryPerformAccessibilityChecks()
        back()

        compose.waitUntilAtLeastOneExists(hasTestTag("open_search"), timeoutMillis = TIMEOUT)
        compose.onNode(hasTestTag("open_search")).performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription(backLabel()), timeoutMillis = TIMEOUT)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private fun backLabel() = compose.activity.getString(R.string.action_back)

    private fun back() {
        compose.onNode(hasContentDescription(backLabel())).performClick()
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
