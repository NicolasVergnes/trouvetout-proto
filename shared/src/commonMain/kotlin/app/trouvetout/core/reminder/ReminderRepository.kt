package app.trouvetout.core.reminder

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.toReminder
import app.trouvetout.core.domain.model.Reminder
import app.trouvetout.core.domain.model.ReminderKind
import app.trouvetout.core.platform.CoroutineDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A reminder as a notification may show it (E2, E6, H4): [title] is the item's name, null when the item is sensitive
 * (the notification then stays generic). Never the place: a notification can be read on a locked phone.
 */
public data class ScheduledReminder(
    val id: String,
    val itemId: String,
    val fireAtMillis: Long,
    val kind: ReminderKind,
    val title: String?,
)

/**
 * What the apps hand to the system (E2): every pending reminder of a live item, past ones included so that a
 * delivered notification can be withdrawn when its item becomes sensitive or goes to the bin.
 */
public class ReminderSchedule(
    public val reminders: List<ScheduledReminder>,
) {
    /** The reminders still to come, soonest first: at most [limit] (iOS keeps 64 pending notifications per app). */
    public fun upcoming(
        nowMillis: Long,
        limit: Int = MAX_SCHEDULED,
    ): List<ScheduledReminder> = reminders.filter { it.fireAtMillis > nowMillis }.take(limit)

    /**
     * Whether a notification already shown for [reminderId] must be withdrawn: the reminder is done or its item is
     * gone (bin, archive), or it named an item ([shownNamed]) that is now sensitive.
     */
    public fun withdraws(
        reminderId: String,
        shownNamed: Boolean,
    ): Boolean {
        val current = reminders.firstOrNull { it.id == reminderId } ?: return true
        return shownNamed && current.title == null
    }

    public companion object {
        public const val MAX_SCHEDULED: Int = 50
    }
}

/** Read side of reminders (E2, E6). Writing goes through `AttachmentCommands`, like every change. */
public class ReminderRepository internal constructor(
    private val db: TrouveToutDb,
    private val dispatchers: CoroutineDispatchers,
) {
    /** Emits again after each append that touches reminders or items (rename, « sensitive », bin, archive). */
    public fun observeSchedule(): Flow<ReminderSchedule> = db.reminderQueries
        .selectLive()
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows ->
            ReminderSchedule(rows.map { scheduled(it.id, it.item_id, it.fire_at, it.kind, it.name, it.sensitive) })
        }

    /** One-shot read of the same list, for a host woken without a running flow (boot, app update). */
    public suspend fun schedule(): ReminderSchedule = withContext(dispatchers.io) {
        val rows = db.reminderQueries.selectLive().executeAsList()
        ReminderSchedule(rows.map { scheduled(it.id, it.item_id, it.fire_at, it.kind, it.name, it.sensitive) })
    }

    /** The reminder as it reads now, when the system wakes the app for it; null when done or its item is gone. */
    public suspend fun live(reminderId: String): ScheduledReminder? = withContext(dispatchers.io) {
        db.reminderQueries.selectLiveById(reminderId).executeAsOneOrNull()?.let {
            scheduled(it.id, it.item_id, it.fire_at, it.kind, it.name, it.sensitive)
        }
    }

    /** Pending reminders of one item, soonest first, past ones included (the sheet shows them as « passé »). */
    public fun observeForItem(itemId: String): Flow<List<Reminder>> = db.reminderQueries
        .selectPendingByItem(itemId)
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.map { it.toReminder() } }

    @Suppress("LongParameterList") // the columns of one joined row
    private fun scheduled(
        id: String,
        itemId: String,
        fireAt: Long,
        kind: String,
        name: String,
        sensitive: Boolean,
    ) = ScheduledReminder(
        id = id,
        itemId = itemId,
        fireAtMillis = fireAt,
        kind = ReminderKind.entries.firstOrNull { it.name == kind } ?: ReminderKind.DATE,
        title = name.takeUnless { sensitive },
    )
}
