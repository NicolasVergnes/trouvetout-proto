package app.trouvetout.core.domain.usecase

import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.ReminderKind
import app.trouvetout.core.domain.usecase.CommandContext.Companion.rejected
import kotlin.time.Instant

/** Photos (A10) and reminders (E2, E6) attached to records. */
public class AttachmentCommands internal constructor(
    private val ctx: CommandContext,
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

    public suspend fun completeReminder(
        reminderId: String,
        source: EventSource,
    ): CommandResult {
        val known = ctx.read { db -> db.reminderQueries.selectById(reminderId).executeAsOneOrNull() != null }
        if (!known) return rejected(Rejection.UNKNOWN_REMINDER)
        return ctx.commit(listOf(ctx.event(EventPayload.ReminderDone(reminderId), source)))
    }
}
