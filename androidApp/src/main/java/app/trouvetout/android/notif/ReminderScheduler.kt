package app.trouvetout.android.notif

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import app.trouvetout.core.domain.ids.Ulid
import app.trouvetout.core.reminder.ReminderSchedule
import app.trouvetout.core.reminder.ScheduledReminder

/**
 * Hands the reminders to `AlarmManager` (E2, E6; N6: nothing runs in the background, the system wakes the app at the
 * moment). Two inexact alarms per reminder, no exact alarm and no permission (ADR-024): `setWindow` (10 minutes) rings
 * on time while the phone is in use, `setAndAllowWhileIdle` still rings when the phone sleeps (Doze defers the first
 * one to its next maintenance window). The first to ring wins: each reminder rings once, the other alarm finds it
 * [rang]. The preferences file `reminders` holds identifiers and the instant each one rang, never a name.
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
        val rang = rangIds()
        val armed = scheduledIds()
        // An alarm may not have rung yet, its window still open or the system holding it back (Doze, standby bucket):
        // it stays, unless that reminder already rang. Past a horizon it is an old backlog, and goes.
        val upcoming = schedule.reminders.filter { it.id !in rang && mayStillRing(it, armed, now) }.take(MAX_REMINDERS)
        val wanted = upcoming.map { it.id }.toSet()
        // Cancel first: the system caps the alarms of an app.
        for (gone in scheduledIds() - wanted) cancelAlarms(gone)
        for (reminder in upcoming) arm(reminder)
        for ((reminderId, named) in ReminderNotifications.shown(context)) {
            if (schedule.withdraws(reminderId, named)) ReminderNotifications.cancel(context, reminderId)
        }
        // Past the horizon a reminder can ring no more. Not pruned from the schedule: an emission older than a
        // reminder does not name it yet, and dropping it here would let its second alarm ring.
        val oldest = now - MISSED_HORIZON_MILLIS
        val kept = rangEntries().filter { (it.substringAfter(RANG_AT).toLongOrNull() ?: 0L) > oldest }
        prefs.edit {
            putStringSet(KEY_IDS, wanted)
            putStringSet(KEY_RANG, kept.toSet())
        }
    }

    /**
     * Marks [reminderId] as rung; false when it already was (the second of its two alarms, or a second delivery).
     * Called before anything is shown.
     */
    @Synchronized
    fun markRang(reminderId: String): Boolean {
        if (reminderId in rangIds()) return false
        prefs.edit { putStringSet(KEY_RANG, rangEntries() + "$reminderId$RANG_AT${System.currentTimeMillis()}") }
        // The other alarm would only wake the phone for nothing.
        cancelAlarms(reminderId)
        return true
    }

    /**
     * After a restart the alarms are gone: the reminders of the last [MISSED_HORIZON_MILLIS] that fell while the phone
     * was off are shown once (never an old backlog, an import for instance), then the others are set again.
     */
    @Synchronized
    fun catchUp(
        schedule: ReminderSchedule,
        now: Long = System.currentTimeMillis(),
    ) {
        val window = (now - MISSED_HORIZON_MILLIS)..now
        for (missed in schedule.reminders.filter { it.fireAtMillis in window }) {
            if (markRang(missed.id)) {
                ReminderNotifications.post(context, missed.id, missed.title, missed.kind, missed.itemId)
            }
        }
        reconcile(schedule, now)
    }

    /** H6: alarms, notifications and the identifiers file go with the data. */
    @Synchronized
    fun clear() {
        for (id in scheduledIds()) cancelAlarms(id)
        ReminderNotifications.cancelAll(context)
        prefs.edit { clear() }
    }

    private fun mayStillRing(
        reminder: ScheduledReminder,
        armed: Set<String>,
        now: Long,
    ): Boolean {
        val age = now - reminder.fireAtMillis
        return age < WINDOW_MILLIS || (reminder.id in armed && age < MISSED_HORIZON_MILLIS)
    }

    private fun arm(reminder: ScheduledReminder) {
        val manager = alarms ?: return
        manager.setWindow(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, WINDOW_MILLIS, alarm(reminder.id, AWAKE))
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, alarm(reminder.id, IDLE))
    }

    /** Both alarms and their PendingIntents go: nothing of a removed reminder stays with the system. */
    private fun cancelAlarms(reminderId: String) {
        for (kind in listOf(AWAKE, IDLE)) {
            val pending = alarm(reminderId, kind)
            alarms?.cancel(pending)
            pending.cancel()
        }
    }

    private fun scheduledIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()

    private fun rangEntries(): Set<String> = prefs.getStringSet(KEY_RANG, emptySet()).orEmpty().toSet()

    private fun rangIds(): Set<String> = rangEntries().map { it.substringBefore(RANG_AT) }.toSet()

    private fun alarm(
        reminderId: String,
        kind: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        kind,
        Intent(context, ReminderReceiver::class.java).setData(Uri.fromParts(SCHEME, reminderId, null)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val SCHEME: String = "trouvetout-reminder"
        const val WINDOW_MILLIS: Long = 10 * 60 * 1000L

        /** Request codes of the two alarms of a reminder. */
        const val AWAKE: Int = 0
        const val IDLE: Int = 1

        /** Two alarms each, well under the system's cap per app; the list is set again at each change and start. */
        private const val MAX_REMINDERS = 100
        private const val MISSED_HORIZON_MILLIS = 2 * 24 * 60 * 60 * 1000L
        private const val PREFS = "reminders"
        private const val KEY_IDS = "scheduled"
        private const val KEY_RANG = "rang"

        /** Between the identifier of a reminder and the instant it rang. */
        private const val RANG_AT = "@"

        /** The reminder an alarm intent is for; only a well-formed identifier is accepted. */
        fun reminderOf(intent: Intent?): String? = intent?.data
            ?.takeIf { it.scheme == SCHEME }
            ?.schemeSpecificPart
            ?.takeIf { Ulid.isValid(it) }
    }
}
