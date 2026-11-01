package app.trouvetout.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.trouvetout.android.R
import app.trouvetout.core.ml.PhotoModelState

/** The photo settings (B8, C10, ADR-022): state from the core, the two switches. */
data class PhotoSettings(
    val state: PhotoModelState?,
    val onNamePhotos: (forceTextOnly: Boolean) -> Unit,
    val onPhotoSearch: (Boolean) -> Unit,
)

/** « Photos »: the switches on a phone that can run the photo model, otherwise one sentence saying why not. */
@Composable
internal fun PhotoSection(settings: PhotoSettings) {
    val state = settings.state ?: return
    Text(stringResource(R.string.settings_photos_section), style = MaterialTheme.typography.titleMedium)
    when {
        !state.bundled -> Text(
            stringResource(R.string.settings_photos_absent),
            style = MaterialTheme.typography.bodyMedium,
        )
        !state.deviceCapable -> Text(
            stringResource(R.string.settings_photos_memory),
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> {
            Text(stringResource(R.string.settings_photos_text), style = MaterialTheme.typography.bodyMedium)
            if (state.stoppedWhileLoading) {
                Text(
                    stringResource(R.string.settings_photos_stopped),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.loadFailed) {
                Text(
                    stringResource(R.string.settings_photos_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SwitchRow(
                label = stringResource(R.string.settings_photos_model),
                hint = stringResource(R.string.settings_photos_model_hint),
                checked = !state.forceTextOnly,
                onChange = { on -> settings.onNamePhotos(!on) },
            )
            SwitchRow(
                label = stringResource(R.string.settings_photos_search),
                hint = if (state.imagesActive) null else stringResource(R.string.settings_photos_search_hint),
                checked = state.photoSearch && state.imagesActive,
                enabled = state.imagesActive,
                onChange = settings.onPhotoSearch,
            )
        }
    }
}

/** A whole-row switch: one target for TalkBack, at least 48 dp high. */
@Composable
private fun SwitchRow(
    label: String,
    hint: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
