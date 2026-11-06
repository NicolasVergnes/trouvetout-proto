package app.trouvetout.core.reminder

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.ReminderKind
import app.trouvetout.core.domain.usecase.AttachmentCommands
import app.trouvetout.core.domain.usecase.CommandContext
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ItemCommands
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.ItemPatch
import app.trouvetout.core.domain.usecase.UndoService
import app.trouvetout.core.platform.CoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** E2, E6, H4: what the apps schedule, the six-month check, undo. The clock starts on 14 Nov 2023, 23:13 Paris. */
class RemindersTest {
    private val db = openTestDatabase()
    private val store = db.testEventStore()
    private val clock = TickingClock()
    private val ctx = CommandContext(store, MonotonicUlidGenerator(clock, Random(3)), clock, "device-R")
    private val paris = TimeZone.of("Europe/Paris")
    private val items = ItemCommands(ctx)
    private val attachments = AttachmentCommands(ctx) { paris }
    private val undo = UndoService(store, ctx, "device-R")
    private val repository =
        ReminderRepository(
            db,
            CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
        )

    private fun CommandResult.done() = assertIs<CommandResult.Done>(this)

    private suspend fun item(name: String): String {
        clock.tick()
        val created = items.create(ItemDraft(name), null, EventSource.TEXT).done().events
        return (created.first().payload as EventPayload.ItemCreated).itemId
    }

    private suspend fun remind(
        itemId: String,
        inMillis: Long,
    ): String {
        clock.tick()
        val at = Instant.fromEpochMilliseconds(clock.millis + inMillis)
        val set = attachments.setReminder(itemId, at, source = EventSource.TEXT).done().events.single().payload
        return (set as EventPayload.ReminderSet).reminderId
    }

    @Test
    fun the_schedule_never_names_a_sensitive_item_and_follows_renames_and_the_bin_H4() = runTest {
        val passport = item("passeport")
        val tent = item("tente")
        val first = remind(passport, HOUR)
        remind(tent, 2 * HOUR)
        val schedule = repository.observeSchedule().first()
        assertEquals(listOf("passeport", "tente"), schedule.reminders.map { it.title })

        clock.tick()
        items.update(passport, ItemPatch(sensitive = true), EventSource.TEXT).done()
        val masked = repository.observeSchedule().first()
        assertEquals(listOf(null, "tente"), masked.reminders.map { it.title }, "sensitive: generic notification")
        assertTrue(masked.withdraws(first, shownNamed = true), "a notification that named it is withdrawn")
        assertFalse(masked.withdraws(first, shownNamed = false))

        clock.tick()
        items.delete(tent, EventSource.TEXT).done()
        val binned = repository.observeSchedule().first()
        assertEquals(listOf(passport), binned.reminders.map { it.itemId }, "an item in the bin rings no more")
        clock.tick()
        items.restore(tent, EventSource.TEXT).done()
        assertEquals(2, repository.schedule().reminders.size, "restored from the bin, its reminder is back")

        clock.tick()
        items.setArchived(tent, true, EventSource.TEXT).done()
        assertEquals(1, repository.schedule().reminders.size, "archived: no notification")
        assertNull(repository.live("nope"))
        assertEquals(null, repository.live(first)?.title)
    }

    @Test
    fun only_future_reminders_are_scheduled_soonest_first_within_the_limit() = runTest {
        val tent = item("tente")
        repeat(ReminderSchedule.MAX_SCHEDULED + 5) { remind(tent, HOUR + it * MINUTE) }
        val past = remind(tent, -HOUR)
        val schedule = repository.schedule()
        val upcoming = schedule.upcoming(clock.millis)
        assertEquals(ReminderSchedule.MAX_SCHEDULED, upcoming.size)
        assertEquals(upcoming.sortedBy { it.fireAtMillis }, upcoming)
        assertTrue(upcoming.none { it.id == past })
        assertFalse(schedule.withdraws(past, shownNamed = true), "a delivered past reminder stays")
        assertEquals(schedule.reminders.size, schedule.reminders.count { it.title == "tente" })
    }

    @Test
    fun removing_a_reminder_is_undone_by_setting_it_again() = runTest {
        val tent = item("tente")
        val reminder = remind(tent, HOUR)
        clock.tick()
        val done = attachments.completeReminder(reminder, EventSource.TEXT).done().events.single().payload
        assertEquals(EventPayload.ReminderDone(reminder, tent), done, "the item history shows it")
        assertTrue(repository.schedule().reminders.isEmpty())
        clock.tick()
        undo.undoLast(EventSource.TEXT).done()
        assertEquals(listOf(reminder), repository.schedule().reminders.map { it.id })
        clock.tick()
        undo.undoLast(EventSource.TEXT).done()
        assertTrue(repository.schedule().reminders.isEmpty(), "undoing the undo removes it again")
    }

    @Test
    fun checking_every_six_months_is_one_pending_reminder_pushed_back_when_checked_E6() = runTest {
        val tent = item("tente")
        clock.tick()
        val on = attachments.setChecking(tent, true, EventSource.TEXT).done().events.single().payload
        val set = assertIs<EventPayload.ReminderSet>(on)
        assertEquals(ReminderKind.CHECK, set.kind)
        assertEquals(
            LocalDateTime(2024, 5, 14, 9, 0),
            Instant.fromEpochMilliseconds(set.fireAtEpochMillis).toLocalDateTime(paris),
            "six months ahead, at 9:00 local time",
        )
        clock.tick()
        assertTrue(attachments.setChecking(tent, true, EventSource.TEXT).done().events.isEmpty(), "already on")

        clock.millis += 200L * DAY
        val checked = attachments.confirmChecked(tent, EventSource.TEXT).done().events.map { it.payload }
        assertEquals(EventPayload.ReminderDone(set.reminderId, tent), checked.first())
        val next = assertIs<EventPayload.ReminderSet>(checked.last())
        assertTrue(next.fireAtEpochMillis > clock.millis + 150L * DAY)
        assertEquals(listOf(next.reminderId), repository.schedule().reminders.map { it.id })

        clock.tick()
        undo.undoLast(EventSource.TEXT).done()
        assertEquals(listOf(set.reminderId), repository.schedule().reminders.map { it.id }, "« vérifié » undone")

        clock.tick()
        attachments.setChecking(tent, false, EventSource.TEXT).done()
        assertTrue(repository.schedule().reminders.isEmpty())
        val dated = remind(tent, HOUR)
        clock.tick()
        assertTrue(attachments.setChecking(tent, false, EventSource.TEXT).done().events.isEmpty())
        assertEquals(listOf(dated), repository.schedule().reminders.map { it.id }, "a dated reminder is untouched")
    }

    @Test
    fun the_item_sheet_lists_pending_reminders_past_ones_included() = runTest {
        val tent = item("tente")
        val past = remind(tent, -HOUR)
        val soon = remind(tent, HOUR)
        clock.tick()
        attachments.completeReminder(soon, EventSource.TEXT).done()
        assertEquals(listOf(past), repository.observeForItem(tent).first().map { it.id })
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }
}
