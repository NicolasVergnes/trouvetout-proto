package app.trouvetout.android.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.trouvetout.android.R
import app.trouvetout.android.ui.theme.AppTheme
import app.trouvetout.core.di.StoreFailure

/**
 * H3: while [locked], the content stays composed (navigation kept) but is covered by an opaque screen and hidden from
 * TalkBack; the system prompt opens by itself once, then on the button.
 */
@Composable
fun LockGate(
    locked: Boolean,
    onUnlock: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { content() }
        if (locked) {
            val focus = LocalFocusManager.current
            var prompted by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                focus.clearFocus(force = true)
                if (!prompted) {
                    prompted = true
                    onUnlock()
                }
            }
            LockScreen(onUnlock)
        }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.lock_text),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lock_unlock))
            }
        }
    }
}

/** The store could not open: why, and for a lost key the only way out (H6, then an import of an export). */
@Composable
fun StoreFailureScreen(
    failure: StoreFailure,
    onWipe: () -> Unit,
    onRetry: () -> Unit = {},
) {
    var confirm by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val keyLost = failure == StoreFailure.KEY_LOST
            Text(
                stringResource(if (keyLost) R.string.store_key_lost_title else R.string.storage_unavailable_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (keyLost) R.string.store_key_lost_text else R.string.storage_unavailable_text),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (keyLost) {
                OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_wipe), color = MaterialTheme.colorScheme.error)
                }
            } else {
                // Storage full or busy, an interrupted migration: the next attempt may well succeed.
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_wipe_confirm_title)) },
            text = { Text(stringResource(R.string.settings_wipe_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onWipe()
                    },
                ) {
                    Text(stringResource(R.string.settings_wipe_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Asks for the device owner, then runs [action]; with no screen lock set, runs nothing. */
@Composable
fun rememberOwnerCheck(): (title: String, action: () -> Unit) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { title, action -> DeviceAuth.authenticate(context, title) { ok -> if (ok) action() } }
    }
}

@Preview(showBackground = true)
@Composable
private fun LockScreenPreview() {
    AppTheme { LockScreen(onUnlock = {}) }
}

@Preview(showBackground = true, fontScale = 2f)
@Composable
private fun StoreFailurePreview() {
    AppTheme { StoreFailureScreen(StoreFailure.KEY_LOST, onWipe = {}) }
}
