package app.trouvetout.android.notif

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.trouvetout.android.R

/**
 * H9: the notification permission (API 33+) is asked after a reminder is set, never at launch, with an explanation
 * first. Returns the function to call once a reminder was set; it does nothing when notifications are allowed, below
 * API 33 (no runtime permission), or once the user said « not now » in this screen. [onResult] gets the answer;
 * [onAsking] tells a screen that closes itself to wait while the question is open.
 */
@Composable
fun rememberNotificationAsk(
    onAsking: (Boolean) -> Unit = {},
    onResult: (Boolean) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    var explain by rememberSaveable { mutableStateOf(false) }
    var declined by rememberSaveable { mutableStateOf(false) }
    var requesting by rememberSaveable { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // A refusal in the system dialog is an answer too: the explanation does not come back in this screen.
            declined = !granted
            requesting = false
            onResult(granted)
        }
    val asking = explain || requesting
    val currentOnAsking by rememberUpdatedState(onAsking)
    LaunchedEffect(asking) { currentOnAsking(asking) }
    if (explain) {
        AlertDialog(
            onDismissRequest = {
                explain = false
                declined = true
            },
            title = { Text(stringResource(R.string.reminder_permission_title)) },
            text = { Text(stringResource(R.string.reminder_permission_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explain = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requesting = true
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                ) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        explain = false
                        declined = true
                    },
                ) { Text(stringResource(R.string.action_not_now)) }
            },
        )
    }
    return {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderNotifications.enabled(context)
        if (needed && !declined) {
            explain = true
            // At once, not one frame later: a question that waits for this one (hibernation) must not show with it.
            currentOnAsking(true)
        }
    }
}
