package app.trouvetout.android.notif

import android.content.ActivityNotFoundException
import android.content.Context
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
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import app.trouvetout.android.App
import app.trouvetout.android.R

/**
 * Lot 12 (decision of the user, 6 Nov.): Android 12+ may put an app the user has not opened for a few months to
 * sleep (hibernation), which removes its alarms; on Android 10 and 11 the same setting only removes runtime
 * permissions, which reminders do not need there, so nothing is asked; a reminder set far ahead would then never ring. When one is set more than a month
 * ahead and the phone applies those restrictions to the app, this explains it once and offers the system setting.
 * Never blocking: « not now » keeps the reminder as it is. Returns the function to call with the reminder's time.
 * The explanation waits while [blocked] (the notification question is open); [onAsking] tells a screen that closes
 * itself to wait while it shows.
 */
@Composable
fun rememberHibernationAsk(
    blocked: Boolean = false,
    onAsking: (Boolean) -> Unit = {},
): (Long) -> Unit {
    val context = LocalContext.current
    val app = context.applicationContext as App
    var explain by rememberSaveable { mutableStateOf(false) }
    val showing = explain && !blocked
    val currentOnAsking by rememberUpdatedState(onAsking)
    LaunchedEffect(showing) { currentOnAsking(showing) }
    val answered = {
        explain = false
        app.uiPrefs.hibernationExplained = true
    }
    if (showing) {
        AlertDialog(
            onDismissRequest = answered,
            title = { Text(stringResource(R.string.hibernation_title)) },
            text = { Text(stringResource(R.string.hibernation_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        answered()
                        app.expectReturn() // back from the system screen without asking for the lock again
                        openSetting(context)
                    },
                ) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = answered) { Text(stringResource(R.string.action_not_now)) }
            },
        )
    }
    return { fireAtMillis ->
        val far = fireAtMillis - System.currentTimeMillis() > FAR_AHEAD_MILLIS
        if (far && !app.uiPrefs.hibernationExplained) {
            val status = PackageManagerCompat.getUnusedAppRestrictionsStatus(context)
            status.addListener(
                {
                    val restricted = runCatching { status.get() }.getOrNull() in RESTRICTED
                    if (restricted && !app.uiPrefs.hibernationExplained) explain = true
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }
}

private fun openSetting(context: Context) {
    val intent = IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
    try {
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // A phone without the screen: nothing to offer, the reminder stays as it is.
    } catch (_: UnsupportedOperationException) {
        // The restrictions went away since the question (status read asynchronously): nothing left to change.
    }
}

/** A month: below it, the app is opened in time for the system to keep it awake (it needs months of disuse). */
private const val FAR_AHEAD_MILLIS = 30L * 24 * 60 * 60 * 1000

/** The status where the system may put the app to sleep (Android 12+): an exemption is worth offering. */
private val RESTRICTED = setOf(UnusedAppRestrictionsConstants.API_31)
