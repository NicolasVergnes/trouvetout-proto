package app.trouvetout.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.trouvetout.android.R
import app.trouvetout.core.privacy.LockDelay
import app.trouvetout.core.privacy.PrivacyState

/** The privacy settings (H3, keyboard learning): state from the core; the lock switch asks the owner first. */
data class PrivacySettingsUi(
    val state: PrivacyState? = null,
    /** A screen lock is set on the phone (without one, the app lock cannot be turned on). */
    val lockAvailable: Boolean = true,
    val onLock: (Boolean) -> Unit = {},
    val onDelay: (LockDelay) -> Unit = {},
    val onKeyboard: (Boolean) -> Unit = {},
)

/** « Vie privée »: lock the app, after how long, and whether the keyboard may learn what is typed. */
@Composable
internal fun PrivacySection(settings: PrivacySettingsUi) {
    val state = settings.state ?: return
    Text(stringResource(R.string.settings_privacy_section), style = MaterialTheme.typography.titleMedium)
    SwitchRow(
        label = stringResource(R.string.settings_lock),
        hint =
            stringResource(
                if (settings.lockAvailable) R.string.settings_lock_hint else R.string.settings_lock_unavailable,
            ),
        checked = state.lockEnabled,
        enabled = settings.lockAvailable || state.lockEnabled,
        onChange = settings.onLock,
    )
    if (state.lockEnabled) {
        Text(stringResource(R.string.settings_lock_delay), style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.selectableGroup()) {
            for (delay in LockDelay.entries) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = state.lockDelay == delay,
                                role = Role.RadioButton,
                                onClick = { settings.onDelay(delay) },
                            ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.lockDelay == delay, onClick = null)
                    Text(stringResource(delay.label()), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    SwitchRow(
        label = stringResource(R.string.settings_keyboard),
        hint = stringResource(R.string.settings_keyboard_hint),
        checked = state.noKeyboardLearning,
        onChange = settings.onKeyboard,
    )
}

private fun LockDelay.label(): Int = when (this) {
    LockDelay.IMMEDIATE -> R.string.settings_lock_delay_now
    LockDelay.ONE_MINUTE -> R.string.settings_lock_delay_minute
    LockDelay.FIVE_MINUTES -> R.string.settings_lock_delay_five
}
