package app.trouvetout.android.notif

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import app.trouvetout.core.reminder.ReminderSchedule

/**
 * Hands the reminders to `AlarmManager` (E2, E6; N6: nothing runs in the background, the system wakes the app at the
 * moment). `setWindow` with a 10-minute window, no exact alarm and no permission: `set` lets the system defer a far
 * reminder by up to three quarters of the delay. The preferences file `reminders` holds identifiers and one instant,
 * never a name.
 */
class ReminderScheduler(
    private val context: Context,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Sets the alarms of [schedule], cancels the ones no longer in it, withdraws notifications that must go. */
    @Synchronized
    fun reconcile(
        schedule: ReminderSchedule,
        now: Long = System.currentTimeMillis(),
    ) {
        // An alarm inside its window may not have rung yet: it stays.
        val upcoming = schedule.upcoming(now - WINDOW_MILLIS, MAX_ALARMS)
        val wanted = upcoming.map { it.id }.toSet()
        for (reminder in upcoming) {
            alarms?.setWindow(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, WINDOW_MILLIS, alarm(reminder.id))
        }
        for (gone in scheduledIds() - wanted) cancelAlarm(gone)
        for ((reminderId, named) in ReminderNotifications.shown(context)) {
            if (schedule.withdraws(reminderId, named)) ReminderNotifications.cancel(context, reminderId)
        }
        prefs.edit {
            putStringSet(KEY_IDS, wanted)
            putLong(KEY_HANDLED, maxOf(handledUntil(), now - WINDOW_MILLIS))
        }
    }

    /**
     * After a restart the alarms are gone: the reminders that fell while the phone was off are shown once, then
     * the others are set again.
     */
    @Synchronized
    fun catchUp(
        schedule: ReminderSchedule,
        now: Long = System.currentTimeMillis(),
    ) {
        val since = handledUntil()
        for (missed in schedule.reminders.filter { it.fireAtMillis in (since + 1)..now }) {
            ReminderNotifications.post(context, missed.id, missed.title, missed.kind, missed.itemId)
        }
        prefs.edit { putLong(KEY_HANDLED, now) }
        reconcile(schedule, now)
    }

    /** The alarm of a reminder rang: it counts as handled (a restart will not show it again). */
    @Synchronized
    fun rang(fireAtMillis: Long) {
        prefs.edit { putLong(KEY_HANDLED, maxOf(handledUntil(), fireAtMillis)) }
    }

    /** H6: alarms, notifications and the identifiers file go with the data. */
    @Synchronized
    fun clear() {
        for (id in scheduledIds()) cancelAlarm(id)
        ReminderNotifications.cancelAll(context)
        prefs.edit { clear() }
    }

    /** The alarm and its PendingIntent both go: nothing of a removed reminder stays with the system. */
    private fun cancelAlarm(reminderId: String) {
        val pending = alarm(reminderId)
        alarms?.cancel(pending)
        pending.cancel()
    }

    private fun scheduledIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()

    private fun handledUntil(): Long = prefs.getLong(KEY_HANDLED, 0L)

    private fun alarm(reminderId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.fromParts(SCHEME, reminderId, null)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val SCHEME: String = "trouvetout-reminder"
        const val WINDOW_MILLIS: Long = 10 * 60 * 1000L

        /** Far beyond any real use; the list is set again at each change and at each start. */
        private const val MAX_ALARMS = 500
        private const val PREFS = "reminders"
        private const val KEY_IDS = "scheduled"
        private const val KEY_HANDLED = "handled_until"

        /** The reminder an alarm intent is for. */
        fun reminderOf(intent: Intent?): String? = intent?.data?.takeIf { it.scheme == SCHEME }?.schemeSpecificPart
    }
}
