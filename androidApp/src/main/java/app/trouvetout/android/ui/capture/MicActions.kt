package app.trouvetout.android.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.trouvetout.android.R

/** The microphone part of the callbacks: the permission is asked at first use, after an explanation (H9). */
data class MicActions(
    val onMic: () -> Unit,
    val onRationaleContinue: () -> Unit,
    val onDismissRationale: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

/** Wires the microphone of [viewModel] to the permission prompt and closes it when the screen stops. */
@Composable
fun rememberMicActions(viewModel: CaptureViewModel): MicActions {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onMicPermissionResult(granted)
        }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopListening() }
    // Navigating away removes the screen before its entry reaches ON_STOP: the view model outlives it.
    DisposableEffect(viewModel) { onDispose { viewModel.stopListening() } }
    return remember(viewModel, context, launcher) {
        MicActions(
            onMic = { viewModel.onMic(micGranted(context)) },
            onRationaleContinue = {
                viewModel.dismissMicRationale()
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismissRationale = viewModel::dismissMicRationale,
            onOpenAppSettings = { openAppSettings(context) },
        )
    }
}

private fun micGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    context.startActivity(
        android.content.Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
internal fun MicButton(
    listening: Boolean,
    enabled: Boolean,
    onMic: () -> Unit,
) {
    IconButton(onClick = onMic, enabled = enabled || listening, modifier = Modifier.testTag(MIC_BUTTON_TAG)) {
        if (listening) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.capture_mic_stop))
        } else {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.capture_mic))
        }
    }
}

/** « J'écoute… » while the microphone is open, otherwise why it is not (with the way out). */
@Composable
internal fun VoiceStatus(
    state: CaptureUiState,
    onOpenAppSettings: () -> Unit,
) {
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (state.listening) {
        // Not a live region: TalkBack reading it aloud would be heard by the recogniser.
        Text(
            stringResource(R.string.capture_listening),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        return
    }
    val notice = state.voiceNotice ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(noticeText(notice)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = live.weight(1f),
        )
        if (notice == VoiceNotice.PERMISSION_DENIED) {
            TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.action_phone_settings)) }
        }
    }
}

private fun noticeText(notice: VoiceNotice): Int = when (notice) {
    VoiceNotice.NOT_ON_DEVICE -> R.string.voice_not_on_device
    VoiceNotice.NO_RECOGNIZER -> R.string.voice_no_recognizer
    VoiceNotice.LANGUAGE_UNAVAILABLE -> R.string.voice_language_unavailable
    VoiceNotice.NO_MATCH -> R.string.voice_no_match
    VoiceNotice.PERMISSION_DENIED -> R.string.voice_permission_denied
    VoiceNotice.FAILED -> R.string.voice_failed
}

@Composable
internal fun MicRationaleDialog(actions: MicActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissRationale,
        title = { Text(stringResource(R.string.voice_rationale_title)) },
        text = { Text(stringResource(R.string.voice_rationale_text)) },
        confirmButton = {
            TextButton(onClick = actions.onRationaleContinue) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissRationale) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Test hook for the instrumented tests. */
const val MIC_BUTTON_TAG = "capture_mic"
