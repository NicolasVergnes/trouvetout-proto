package app.trouvetout.android.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.trouvetout.android.App
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.domain.model.ReminderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An alarm of one reminder rang (not exported: only the app's own alarms reach it). Each reminder rings once, whichever
 * of its two alarms comes first. The reminder is read again now, so a rename, a « sensitive » switch, a deletion or a
 * removed reminder since it was set are honoured; the name never travels in the alarm. When the store cannot be read
 * in time, the generic notification is shown.
 */
class ReminderReceiver : BroadcastReceiver() {
    // Any failure of the store ends the broadcast quietly: a background crash would show nothing better.
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val reminderId = ReminderScheduler.reminderOf(intent) ?: return
        val app = context.applicationContext as App
        // Nothing could show: not counted as rung, so it rings at the next reconciliation once they are allowed again.
        if (!ReminderNotifications.enabled(app)) return
        if (!app.reminderScheduler.markRang(reminderId)) return
        val pending = goAsync()
        app.appScope.launch {
            try {
                val shared = withTimeoutOrNull(READY_TIMEOUT_MILLIS) { app.readyShared.first() }
                show(app, shared, reminderId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // The store failed or was wiped meanwhile (H6): nothing of the old data is shown.
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun show(
        app: App,
        shared: SharedFactory?,
        reminderId: String,
    ) {
        if (shared == null) {
            ReminderNotifications.post(app, reminderId, null, ReminderKind.DATE, null)
            return
        }
        val live = shared.reminders.live(reminderId) ?: return
        ReminderNotifications.post(app, live.id, live.title, live.kind, live.itemId)
    }

    private companion object {
        /** A broadcast has about ten seconds; the first open after an update may be migrating the store. */
        const val READY_TIMEOUT_MILLIS = 8_000L
    }
}

/**
 * The phone restarted or the app was updated: alarms are gone. Protected broadcasts only, the action is checked.
 */
class BootReceiver : BroadcastReceiver() {
    // Any failure of the store ends the broadcast quietly: a background crash would show nothing better.
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as App
        val pending = goAsync()
        app.appScope.launch {
            try {
                val shared = withTimeoutOrNull(READY_TIMEOUT_MILLIS) { app.readyShared.first() } ?: return@launch
                app.reminderScheduler.catchUp(shared.reminders.schedule())
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // The store failed or was wiped meanwhile: nothing to set.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val READY_TIMEOUT_MILLIS = 8_000L
    }
}
