package app.trouvetout.android

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trouvetout.android.ui.capture.CAPTURE_FIELD_TAG
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ItemDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I6 on a real Android 10: the Accessibility Test Framework (touch targets, labels, contrast, duplicate
 * descriptions…) on the main screens of the app as the user reaches them: home, settings (every section, scrolled
 * into view since lot 12), places, search.
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
        // Lot 12: the sections further down are checked too, each scrolled into view (ATF sees what is laid out).
        for (section in SCROLLED_SECTIONS) {
            val title = hasText(compose.activity.getString(section))
            compose.waitUntilAtLeastOneExists(title, timeoutMillis = TIMEOUT)
            compose.onNode(title).performScrollTo()
            compose.onRoot().tryPerformAccessibilityChecks()
        }
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

    /** Lot 9: the item sheet with its reminders section, opened as a tapped reminder opens it. */
    @Test
    fun the_item_sheet_with_its_reminders_passes_the_accessibility_checks() {
        compose.waitUntilAtLeastOneExists(hasTestTag(CAPTURE_FIELD_TAG), timeoutMillis = TIMEOUT)
        val app = compose.activity.application as App
        val itemId =
            runBlocking {
                val commands = app.readyShared.first().itemCommands
                val created = commands.create(ItemDraft("a11y-rappel"), null, EventSource.TEXT)
                ((created as CommandResult.Done).events.first().payload as EventPayload.ItemCreated).itemId
            }
        app.itemRequest.value = itemId
        compose.waitUntilAtLeastOneExists(
            hasText(compose.activity.getString(R.string.reminder_add)),
            timeoutMillis = TIMEOUT,
        )
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private fun backLabel() = compose.activity.getString(R.string.action_back)

    private fun back() {
        compose.onNode(hasContentDescription(backLabel())).performClick()
    }

    private companion object {
        const val TIMEOUT = 15_000L

        /** The settings sections below the first screen, top to bottom. */
        val SCROLLED_SECTIONS =
            listOf(
                R.string.settings_backup_section,
                R.string.settings_purchase_section,
                R.string.settings_display_section,
                R.string.settings_search_section,
                R.string.settings_photos_section,
            )
    }
}
