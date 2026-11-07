package app.trouvetout.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.core.data.backup.BackupProblem
import app.trouvetout.core.data.backup.BackupRestoreResult
import app.trouvetout.core.data.backup.BackupService
import java.text.DateFormat
import java.util.Date

/** Wires the ViewModel and the system's document pickers. */
@Composable
fun BackupRoute() {
    val app = LocalContext.current.applicationContext as App
    val viewModel: BackupViewModel = viewModel { BackupViewModel(app) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val create =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_TYPE)) { uri ->
            if (uri != null) viewModel.fileChosen(uri)
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.restoreChosen(uri)
        }
    BackupSection(
        state,
        BackupCallbacks(
            onTurnOn = {
                app.expectReturn()
                create.launch(BackupService.SUGGESTED_FILE_NAME)
            },
            onConfirmKey = viewModel::confirmNewKey,
            onCancelKey = viewModel::cancelNewKey,
            onBackUpNow = viewModel::backUpNow,
            onTurnOff = viewModel::disable,
            onRestore = {
                app.expectReturn()
                open.launch(arrayOf(MIME_TYPE))
            },
            onRestoreWithKey = viewModel::restore,
            onCancelRestore = viewModel::cancelRestore,
            onDismissMessage = viewModel::dismissMessage,
        ),
    )
}

@Composable
fun BackupSection(
    state: BackupUiState,
    callbacks: BackupCallbacks,
    modifier: Modifier = Modifier,
) {
    val backup = state.backup
    val on = backup?.location != null
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.settings_backup_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.backup_hint), style = MaterialTheme.typography.bodyMedium)
        if (on) {
            Text(lastBackupText(backup.lastSuccessMillis), style = MaterialTheme.typography.bodyMedium)
            backup.problem?.let { problem ->
                Text(
                    stringResource(
                        if (problem == BackupProblem.TOO_LARGE) R.string.backup_too_large else R.string.backup_failed,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Action(R.string.backup_run_now, !state.busy, callbacks.onBackUpNow)
            Action(R.string.backup_disable, !state.busy, callbacks.onTurnOff)
        } else {
            Action(R.string.backup_enable, !state.busy, callbacks.onTurnOn)
        }
        Action(R.string.backup_restore, !state.busy, callbacks.onRestore)
    }
    state.newKey?.let { NewKeyDialog(it, callbacks) }
    if (state.restoreFrom != null) RestoreDialog(callbacks)
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = callbacks.onDismissMessage,
            text = { Text(message.describe()) },
            confirmButton = {
                TextButton(onClick = callbacks.onDismissMessage) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

@Composable
private fun Action(
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label))
    }
}

@Composable
private fun lastBackupText(millis: Long?): String {
    if (millis == null) return stringResource(R.string.backup_never)
    val date =
        remember(millis) { DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(millis)) }
    return stringResource(R.string.backup_last, date)
}

/**
 * Shown once, and asked back: nobody can open the backup without it, not even the app's authors. Kept out of
 * screenshots and of the clipboard; the end typed again makes sure it was read.
 */
@Composable
private fun NewKeyDialog(
    key: String,
    callbacks: BackupCallbacks,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val lastGroup = key.substringAfterLast('-')
    AlertDialog(
        onDismissRequest = {},
        properties = SECRET_DIALOG,
        title = { Text(stringResource(R.string.backup_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_key_text))
                Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.backup_key_confirm_label)) },
                    singleLine = true,
                    keyboardOptions = KEY_KEYBOARD,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = callbacks.onConfirmKey,
                enabled = typed.trim().equals(lastGroup, ignoreCase = true),
            ) {
                Text(stringResource(R.string.backup_key_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.onCancelKey) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RestoreDialog(callbacks: BackupCallbacks) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = callbacks.onCancelRestore,
        properties = SECRET_DIALOG,
        title = { Text(stringResource(R.string.backup_restore)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_restore_text))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.backup_restore_field)) },
                    keyboardOptions = KEY_KEYBOARD,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { callbacks.onRestoreWithKey(typed) }, enabled = typed.isNotBlank()) {
                Text(stringResource(R.string.backup_restore_action))
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.onCancelRestore) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun BackupMessage.describe(): String = when (this) {
    BackupMessage.Written -> stringResource(R.string.backup_done)
    BackupMessage.Failed -> stringResource(R.string.settings_failed_text)
    is BackupMessage.Restored ->
        when (val outcome = result) {
            is BackupRestoreResult.Imported ->
                stringResource(
                    R.string.settings_import_done_text,
                    pluralStringResource(
                        R.plurals.count_events,
                        outcome.report.eventsAppended,
                        outcome.report.eventsAppended,
                    ),
                    outcome.report.eventsSkipped,
                    pluralStringResource(
                        R.plurals.count_photos,
                        outcome.report.photosImported,
                        outcome.report.photosImported,
                    ),
                    outcome.report.dropped,
                )
            is BackupRestoreResult.Refused -> stringResource(R.string.settings_import_refused_invalid)
            BackupRestoreResult.BadKey -> stringResource(R.string.backup_bad_key)
            BackupRestoreResult.NotABackup -> stringResource(R.string.backup_not_a_backup)
            BackupRestoreResult.CannotOpen -> stringResource(R.string.backup_cannot_open)
        }
}

private const val MIME_TYPE = "application/octet-stream"
private val SECRET_DIALOG = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = false)
private val KEY_KEYBOARD =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.Characters,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
    )
