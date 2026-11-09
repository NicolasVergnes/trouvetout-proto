package app.trouvetout.android.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.trouvetout.android.R
import app.trouvetout.android.ui.theme.AppTheme

/** Test hook of the first-steps card. */
const val WELCOME_CARD_TAG = "welcome_card"

/**
 * I10, first steps without a blocking tutorial: a card above the empty list on a fresh device, never modal; the core
 * decides when it shows ([app.trouvetout.core.onboarding.WelcomeGuide]). [onOpenPlaces] is null in the simplified
 * mode, which has no places screen at hand.
 */
@Composable
internal fun WelcomeCard(
    onDismiss: () -> Unit,
    onOpenPlaces: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().testTag(WELCOME_CARD_TAG)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.welcome_say), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.welcome_ask), style = MaterialTheme.typography.bodyMedium)
            if (onOpenPlaces != null) {
                Text(stringResource(R.string.welcome_places), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.welcome_private), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenPlaces != null) {
                    TextButton(onClick = onOpenPlaces, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.welcome_places_action))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.welcome_dismiss))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@PreviewLightDark
@PreviewFontScale
@Composable
private fun WelcomeCardPreview() {
    AppTheme { WelcomeCard(onDismiss = {}, onOpenPlaces = {}) }
}
