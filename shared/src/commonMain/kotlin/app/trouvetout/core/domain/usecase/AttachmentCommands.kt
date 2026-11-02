package app.trouvetout.core.domain.usecase

import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.capture.DateResolver
import app.trouvetout.core.capture.DateSpec
import app.trouvetout.core.capture.DayRef
import app.trouvetout.core.domain.model.ReminderKind
import app.trouvetout.core.domain.usecase.CommandContext.Companion.rejected
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** Photos (A10) and reminders (E2, E6) attached to records. */
public class AttachmentCommands internal constructor(
    private val ctx: CommandContext,
    /** Places the next check of E6 on the phone's calendar (9:00, six months ahead); read at each use. */
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    public suspend fun attachPhoto(
        ownerType: PhotoOwnerType,
        ownerId: String,
        file: PhotoFile,
        source: EventSource,
    ): CommandResult {
        val ownerRejection =
            when (ownerType) {
                PhotoOwnerType.ITEM -> ctx.checkItem(ownerId)
                PhotoOwnerType.PLACE -> ctx.checkPlace(ownerId)
                PhotoOwnerType.CONTAINER -> ctx.checkContainer(ownerId)
            }
        if (ownerRejection != null) return rejected(ownerRejection)
        val payload =
            EventPayload.PhotoAttached(
                photoId = ctx.newId(),
                ownerType = ownerType,
                ownerId = ownerId,
                fileName = file.fileName,
                thumbName = file.thumbName,
                width = file.width,
                height = file.height,
                sha256 = file.sha256,
            )
        return ctx.commit(listOf(ctx.event(payload, source)))
    }

    public suspend fun detachPhoto(
        photoId: String,
        source: EventSource,
    ): CommandResult {
        val known = ctx.read { db -> db.photoQueries.selectById(photoId).executeAsOneOrNull() != null }
        if (!known) return rejected(Rejection.UNKNOWN_PHOTO)
        return ctx.commit(listOf(ctx.event(EventPayload.PhotoDetached(photoId), source)))
    }

    public suspend fun setReminder(
        itemId: String,
        fireAt: Instant,
        kind: ReminderKind = ReminderKind.DATE,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult {
        ctx.checkItem(itemId)?.let { return rejected(it) }
        val payload = EventPayload.ReminderSet(ctx.newId(), itemId, fireAt.toEpochMilliseconds(), kind)
        return ctx.commit(listOf(ctx.event(payload, source, rawInput)))
    }

    /** Removes a reminder (E2) or acknowledges it; undone by setting it again. */
    public suspend fun completeReminder(
        reminderId: String,
        source: EventSource,
    ): CommandResult {
        val row = ctx.read { db -> db.reminderQueries.selectById(reminderId).executeAsOneOrNull() }
            ?: return rejected(Rejection.UNKNOWN_REMINDER)
        return ctx.commit(listOf(ctx.event(EventPayload.ReminderDone(reminderId, row.item_id), source)))
    }

    /**
     * E6 « vérifier tous les 6 mois »: switching it on sets a CHECK reminder six months ahead, unless one is pending;
     * switching it off completes every pending CHECK of the item (an import can bring several). Nothing to write
     * gives an empty [CommandResult.Done].
     */
    public suspend fun setChecking(
        itemId: String,
        on: Boolean,
        source: EventSource,
    ): CommandResult {
        ctx.checkItem(itemId)?.let { return rejected(it) }
        val pending = pendingChecks(itemId)
        val payloads =
            when {
                on && pending.isEmpty() -> listOf(nextCheck(itemId))
                on -> emptyList()
                else -> pending.map { EventPayload.ReminderDone(it, itemId) }
            }
        if (payloads.isEmpty()) return CommandResult.Done(emptyList())
        return ctx.commit(payloads.map { ctx.event(it, source) })
    }

    /** E6 « vérifié »: the pending checks are done and the next one is six months from now, in one append. */
    public suspend fun confirmChecked(
        itemId: String,
        source: EventSource,
    ): CommandResult {
        ctx.checkItem(itemId)?.let { return rejected(it) }
        val payloads = pendingChecks(itemId).map { EventPayload.ReminderDone(it, itemId) } + nextCheck(itemId)
        return ctx.commit(payloads.map { ctx.event(it, source) })
    }

    private suspend fun pendingChecks(itemId: String): List<String> = ctx.read { db ->
        db.reminderQueries
            .selectPendingByItem(itemId)
            .executeAsList()
            .filter { it.kind == ReminderKind.CHECK.name }
            .map { it.id }
    }

    private suspend fun nextCheck(itemId: String): EventPayload.ReminderSet {
        val fireAt = DateResolver.resolve(DateSpec(DayRef.InMonths(CHECK_MONTHS)), ctx.clock.now(), timeZone())
        return EventPayload.ReminderSet(ctx.newId(), itemId, fireAt.toEpochMilliseconds(), ReminderKind.CHECK)
    }

    public companion object {
        /** E6: a critical item is checked every six months. */
        public const val CHECK_MONTHS: Int = 6
    }
}
