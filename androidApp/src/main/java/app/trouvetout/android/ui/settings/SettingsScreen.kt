package app.trouvetout.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.android.ui.theme.AppTheme
import app.trouvetout.core.data.export.ImportRejection

/** Wires the ViewModel, the document picker and the share sheet; the screen itself is pure. */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(app) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.import(uri)
        }
    LaunchedEffect(state.exportToShare) {
        state.exportToShare?.let { uri ->
            ExportSharing.launch(context, uri)
            viewModel.exportShared()
        }
    }
    SettingsScreen(
        state = state,
        onBack = onBack,
        onExport = viewModel::export,
        onImport = {
            picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        },
        onWipe = viewModel::wipeAll,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onWipe: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmWipe by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_data_section), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_export_hint), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onExport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_export))
            }
            Text(stringResource(R.string.settings_import_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_import))
            }
            Text(stringResource(R.string.settings_wipe_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { confirmWipe = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_wipe), color = MaterialTheme.colorScheme.error)
            }
            if (state.busy) {
                val busyLabel = stringResource(R.string.settings_busy)
                CircularProgressIndicator(Modifier.semantics { contentDescription = busyLabel })
            }
        }
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.settings_wipe_confirm_title)) },
            text = { Text(stringResource(R.string.settings_wipe_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmWipe = false
                        onWipe()
                    },
                ) {
                    Text(stringResource(R.string.settings_wipe_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResource(message.titleRes())) },
            text = { Text(message.describe()) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

private fun SettingsMessage.titleRes(): Int = when (this) {
    is SettingsMessage.Exported -> R.string.settings_export_done_title
    is SettingsMessage.Imported -> R.string.settings_import_done_title
    is SettingsMessage.ImportRefused -> R.string.settings_import_refused_title
    SettingsMessage.Wiped -> R.string.settings_wipe_done_title
    SettingsMessage.Failed -> R.string.settings_failed_title
}

@Composable
private fun SettingsMessage.describe(): String = when (this) {
    is SettingsMessage.Exported ->
        stringResource(
            R.string.settings_export_done_text,
            pluralStringResource(R.plurals.count_events, events.toInt(), events),
            pluralStringResource(R.plurals.count_photos, photos.toInt(), photos),
        )
    is SettingsMessage.Imported ->
        stringResource(
            R.string.settings_import_done_text,
            pluralStringResource(R.plurals.count_events, report.eventsAppended, report.eventsAppended),
            report.eventsSkipped,
            pluralStringResource(R.plurals.count_photos, report.photosImported, report.photosImported),
            report.dropped,
        )
    is SettingsMessage.ImportRefused ->
        stringResource(
            when (reason) {
                ImportRejection.FORMAT_TOO_NEW -> R.string.settings_import_refused_newer
                ImportRejection.FILE_NOT_FOUND,
                ImportRejection.NOT_AN_ARCHIVE,
                -> R.string.settings_import_refused_not_archive
                else -> R.string.settings_import_refused_invalid
            },
        )
    SettingsMessage.Wiped -> stringResource(R.string.settings_wipe_done_text)
    SettingsMessage.Failed -> stringResource(R.string.settings_failed_text)
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    AppTheme {
        SettingsScreen(SettingsUiState(), onBack = {}, onExport = {}, onImport = {}, onWipe = {}, onDismissMessage = {})
    }
}
