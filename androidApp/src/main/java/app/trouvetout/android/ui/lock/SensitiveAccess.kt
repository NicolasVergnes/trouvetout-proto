package app.trouvetout.android.ui.lock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * H4 for the screens: whether sensitive items are shown, and how to ask for them. Provided by the activity from the
 * core's app lock; the default shows everything (previews, tests).
 */
data class SensitiveAccess(
    /** The app lock is on: a sensitive result is masked until [revealed]. */
    val lockEnabled: Boolean = false,
    /** Sensitive items are shown, until the app leaves. */
    val revealed: Boolean = true,
    /** Asks the owner when the lock is on, then shows them. */
    val onReveal: () -> Unit = {},
) {
    /** A sensitive search result is replaced by a masked row. */
    fun masks(sensitive: Boolean): Boolean = sensitive && lockEnabled && !revealed
}

val LocalSensitive = staticCompositionLocalOf { SensitiveAccess() }

/** One row that stands for hidden sensitive items, or a masked result: a tap asks to show them. */
@Composable
fun HiddenRow(
    text: String,
    onReveal: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null) },
        headlineContent = { Text(text) },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onReveal)
                .testTag("reveal_sensitive"),
    )
}
