package app.trouvetout.android.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.trouvetout.android.MainActivity
import app.trouvetout.android.R
import app.trouvetout.core.domain.ids.Ulid
import app.trouvetout.core.domain.model.ReminderKind

/**
 * Reminder notifications (E2, E6, H4). The text names the item at most, never its place; a sensitive item (title
 * null) gets the generic text, and so does the lock screen (`setPublicVersion`). Tapping opens the item sheet through
 * [LINK_ALIAS], a non-exported alias of the main activity: no other app can open a sheet by sending an intent.
 */
object ReminderNotifications {
    const val CHANNEL_ID: String = "reminders"
    const val EXTRA_ITEM_ID: String = "app.trouvetout.reminder.item"
    private const val EXTRA_NAMED = "app.trouvetout.reminder.named"
    private const val NOTIFICATION_ID = 9

    /** Fully qualified name of the `<activity-alias>` declared in the manifest. */
    const val LINK_ALIAS: String = "app.trouvetout.android.notif.ReminderLink"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_description)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
        manager.createNotificationChannel(channel)
    }

    /** False when the user refused notifications (API 33+ permission, or the app's switch in the system settings). */
    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Posts the notification of [reminderId]; [title] null keeps it generic; [itemId] null opens the home screen. */
    fun post(
        context: Context,
        reminderId: String,
        title: String?,
        kind: ReminderKind,
        itemId: String?,
    ) {
        if (!enabled(context)) return
        ensureChannel(context)
        val generic = context.getString(R.string.notif_generic)
        val text =
            when {
                title == null -> generic
                kind == ReminderKind.CHECK -> context.getString(R.string.notif_check, title)
                else -> context.getString(R.string.notif_reminder, title)
            }
        val public =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(generic)
                .build()
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent(context, reminderId, itemId))
                .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_NAMED, title != null) })
                .build()
        try {
            NotificationManagerCompat.from(context).notify(reminderId, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The permission was withdrawn between the check and the post: nothing to show.
        }
    }

    /** The reminders whose notification is showing, with whether it names the item. */
    fun shown(context: Context): Map<String, Boolean> {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyMap()
        return manager.activeNotifications
            .filter { it.id == NOTIFICATION_ID }
            .mapNotNull { shown -> shown.tag?.let { it to shown.notification.extras.getBoolean(EXTRA_NAMED, true) } }
            .toMap()
    }

    fun cancel(
        context: Context,
        reminderId: String,
    ) = NotificationManagerCompat.from(context).cancel(reminderId, NOTIFICATION_ID)

    /** H6: every notification of the app goes with the data. */
    fun cancelAll(context: Context) = NotificationManagerCompat.from(context).cancelAll()

    /** The item a tap on a reminder opens, if [intent] came through the alias and carries a valid ULID. */
    fun itemOf(intent: Intent?): String? {
        if (intent?.component?.className != LINK_ALIAS) return null
        return intent.getStringExtra(EXTRA_ITEM_ID)?.takeIf { Ulid.isValid(it) }
    }

    private fun openIntent(
        context: Context,
        reminderId: String,
        itemId: String?,
    ): PendingIntent {
        val intent =
            if (itemId != null) {
                Intent().setComponent(ComponentName(context.packageName, LINK_ALIAS)).putExtra(EXTRA_ITEM_ID, itemId)
            } else {
                Intent(context, MainActivity::class.java)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // One PendingIntent per reminder whatever the hash of its id (extras do not tell them apart).
                .setData(Uri.fromParts(ReminderScheduler.SCHEME, reminderId, null))
        return PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
