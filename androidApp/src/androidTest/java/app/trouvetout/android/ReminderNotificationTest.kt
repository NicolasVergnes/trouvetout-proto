package app.trouvetout.android

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trouvetout.android.notif.ReminderNotifications
import app.trouvetout.android.notif.ReminderReceiver
import app.trouvetout.android.notif.ReminderScheduler
import app.trouvetout.android.print.InventoryPrintAdapter
import app.trouvetout.android.print.PrintEntry
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.ItemPatch
import app.trouvetout.core.reminder.ReminderSchedule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Instant

/**
 * E2, E6, H4 on a real Android 10: the alarm of a reminder is set, its receiver posts the notification read from the
 * store at that moment (generic for a sensitive item, nothing for a deleted one, never a place), the total wipe
 * removes alarms and notifications. C16: the printed pages are a readable PDF.
 */
@RunWith(AndroidJUnit4::class)
class ReminderNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val notifications = app.getSystemService(NotificationManager::class.java)

    private suspend fun shared(): SharedFactory = app.readyShared.first()

    private fun App.emptySchedule() = ReminderSchedule(emptyList())

    private suspend fun item(name: String): String {
        val done = shared().itemCommands.create(ItemDraft(name), null, EventSource.TEXT) as CommandResult.Done
        return (done.events.first().payload as EventPayload.ItemCreated).itemId
    }

    private suspend fun remindIn(
        itemId: String,
        millis: Long,
    ): String {
        val at = Instant.fromEpochMilliseconds(System.currentTimeMillis() + millis)
        val result = shared().attachmentCommands.setReminder(itemId, at, source = EventSource.TEXT)
        val done = result as CommandResult.Done
        return (done.events.single().payload as EventPayload.ReminderSet).reminderId
    }

    private fun alarmOf(
        reminderId: String,
        kind: Int = ReminderScheduler.AWAKE,
    ): PendingIntent? = PendingIntent.getBroadcast(
        app,
        kind,
        Intent(app, ReminderReceiver::class.java).setData(Uri.fromParts(ReminderScheduler.SCHEME, reminderId, null)),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    /** What the alarm would do, without waiting for its window. */
    private suspend fun ring(reminderId: String): String? {
        val uri = Uri.fromParts(ReminderScheduler.SCHEME, reminderId, null)
        app.sendBroadcast(Intent(app, ReminderReceiver::class.java).setData(uri))
        repeat(POLLS) {
            notifications.activeNotifications.firstOrNull { it.tag == reminderId }?.let {
                return it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
            }
            delay(POLL_MILLIS)
        }
        return null
    }

    private suspend fun awaitAlarm(reminderId: String): PendingIntent? {
        repeat(POLLS) {
            alarmOf(reminderId)?.let { return it }
            delay(POLL_MILLIS)
        }
        return null
    }

    @Test
    fun a_reminder_is_set_with_the_system_and_rings_with_the_name_only() = runBlocking {
        val passport = item("passeport-rappel")
        val reminder = remindIn(passport, HOUR)
        assertNotNull("the alarm follows the journal", awaitAlarm(reminder))
        val title = ring(reminder)
        assertNotNull(title)
        assertTrue("names the item: $title", title!!.contains("passeport-rappel"))
        val public = notifications.activeNotifications.first { it.tag == reminder }.notification.publicVersion
        val publicTitle = public?.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        assertEquals("lock screen: generic", app.getString(R.string.notif_generic), publicTitle)
        notifications.cancelAll()
    }

    @Test
    fun a_sensitive_item_rings_generic_and_a_deleted_one_not_at_all_H4() = runBlocking {
        val passport = item("passeport-sensible")
        val reminder = remindIn(passport, HOUR)
        shared().itemCommands.update(passport, ItemPatch(sensitive = true), EventSource.TEXT)
        assertEquals(app.getString(R.string.notif_generic), ring(reminder))

        val tent = item("tente-supprimee")
        val gone = remindIn(tent, HOUR)
        shared().itemCommands.delete(tent, EventSource.TEXT)
        assertNull("an item in the bin rings no more", ring(gone))
        notifications.cancelAll()
    }

    @Test
    fun a_reminder_rings_once_whichever_of_its_alarms_comes_first() = runBlocking {
        val tent = item("tente-une-fois")
        val reminder = remindIn(tent, HOUR)
        assertNotNull(ring(reminder))
        notifications.cancelAll()
        // The system removes it a moment later: not what the second alarm is checked against.
        repeat(POLLS) { if (notifications.activeNotifications.any { it.tag == reminder }) delay(POLL_MILLIS) }
        assertTrue(notifications.activeNotifications.none { it.tag == reminder })
        assertNull("the second alarm finds it rung", ring(reminder))
        // A schedule emitted before the reminder existed does not make it ring again.
        app.reminderScheduler.reconcile(app.emptySchedule())
        assertNull("still rung", ring(reminder))
        app.reminderScheduler.reconcile(shared().reminders.schedule())
        assertNull("a rung reminder is not set again", alarmOf(reminder))
        assertNull("nor its other alarm", alarmOf(reminder, ReminderScheduler.IDLE))
    }

    @Test
    fun an_alarm_held_back_by_the_system_is_kept_until_the_horizon() = runBlocking {
        val lamp = item("lampe-retardee")
        val reminder = remindIn(lamp, HOUR)
        assertNotNull(awaitAlarm(reminder))
        val schedule = shared().reminders.schedule()
        val fireAt = schedule.reminders.first { it.id == reminder }.fireAtMillis

        app.reminderScheduler.reconcile(schedule, now = fireAt + 11 * MINUTE)
        assertNotNull("11 min late: it may still ring", alarmOf(reminder))
        assertNotNull(alarmOf(reminder, ReminderScheduler.IDLE))

        app.reminderScheduler.reconcile(schedule, now = fireAt + 3 * DAY)
        assertNull("3 days late: an old backlog", alarmOf(reminder))
        assertNull(alarmOf(reminder, ReminderScheduler.IDLE))
    }

    @Test
    fun only_the_notification_alias_with_a_valid_id_opens_an_item() {
        val id = "01HZY0000000000000000000AB"
        val direct = Intent(app, MainActivity::class.java).putExtra(ReminderNotifications.EXTRA_ITEM_ID, id)
        assertNull("another app's explicit intent to the main activity", ReminderNotifications.itemOf(direct))
        val alias = ComponentName(app.packageName, ReminderNotifications.LINK_ALIAS)
        val bad = Intent().setComponent(alias).putExtra(ReminderNotifications.EXTRA_ITEM_ID, "../../etc")
        assertNull("not a ULID", ReminderNotifications.itemOf(bad))
        val good = Intent().setComponent(alias).putExtra(ReminderNotifications.EXTRA_ITEM_ID, id)
        assertEquals(id, ReminderNotifications.itemOf(good))
    }

    @Test
    fun the_total_wipe_removes_alarms_and_notifications_H6() = runBlocking {
        val before = shared()
        val drill = item("perceuse-effacee")
        val reminder = remindIn(drill, HOUR)
        assertNotNull(awaitAlarm(reminder))
        ring(reminder)
        app.wipeAll()
        app.readyShared.first { it !== before }
        assertNull("no alarm left", alarmOf(reminder))
        assertNull("no alarm left", alarmOf(reminder, ReminderScheduler.IDLE))
        assertTrue("no notification left", notifications.activeNotifications.isEmpty())
        val prefs = app.getSharedPreferences("reminders", android.content.Context.MODE_PRIVATE)
        // The new core reconciles at once and may write its empty lists: no identifier is what counts.
        assertTrue("no alarm identifier left", prefs.getStringSet("scheduled", null).isNullOrEmpty())
        assertTrue("no rung identifier left", prefs.getStringSet("rang", null).isNullOrEmpty())
    }

    @Test
    fun the_printed_inventory_is_a_readable_pdf_C16() {
        val entries =
            listOf(PrintEntry("Cave", PrintEntry.Style.TITLE)) +
                (1..120).map { PrintEntry("Objet $it · 3 m", PrintEntry.Style.BODY, depth = it % 3) }
        val file = File(app.cacheDir, "inventory-test.pdf")
        val pages = file.outputStream().use { InventoryPrintAdapter.writePdf(entries, it) }
        assertTrue("120 lines take more than one page", pages > 1)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { assertEquals(pages, it.pageCount) }
        }
        file.delete()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val POLLS = 50
        const val POLL_MILLIS = 200L
    }
}
