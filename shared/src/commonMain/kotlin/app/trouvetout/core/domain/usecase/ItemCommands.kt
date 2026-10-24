package app.trouvetout.core.domain.usecase

import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.CommandContext.Companion.cleanName
import app.trouvetout.core.domain.usecase.CommandContext.Companion.cleanTags
import app.trouvetout.core.domain.usecase.CommandContext.Companion.rejected
import app.trouvetout.core.domain.usecase.CommandContext.Companion.toMoved
import app.trouvetout.core.domain.usecase.CommandContext.Companion.toStored

/** Item use cases (A1, A4, U5, U6): each one appends events, never touches the projection. */
public class ItemCommands internal constructor(
    private val ctx: CommandContext,
) {
    /** Creates the item and, when a location is given, stores it there in the same append. */
    public suspend fun create(
        draft: ItemDraft,
        location: LocationRef? = null,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult {
        val name = cleanName(draft.name) ?: return rejected(Rejection.BLANK_NAME)
        val locationRejection = location?.let { ctx.checkLocation(it) }
        if (locationRejection != null) return rejected(locationRejection)
        val itemId = ctx.newId()
        val created =
            EventPayload.ItemCreated(
                itemId = itemId,
                name = name,
                description = draft.description?.trim()?.ifEmpty { null },
                tags = cleanTags(draft.tags),
                quantity = draft.quantity,
                unit = draft.unit?.trim()?.ifEmpty { null },
                sensitive = draft.sensitive,
            )
        val events =
            listOfNotNull(
                ctx.event(created, source, rawInput),
                location?.let { ctx.event(it.toStored(itemId), source, rawInput) },
            )
        return ctx.commit(events)
    }

    public suspend fun update(
        itemId: String,
        patch: ItemPatch,
        source: EventSource,
    ): CommandResult {
        ctx.checkItem(itemId)?.let { return rejected(it) }
        val name = patch.name?.let { cleanName(it) ?: return rejected(Rejection.BLANK_NAME) }
        val payload =
            EventPayload.ItemUpdated(
                itemId = itemId,
                name = name,
                description = patch.description?.trim(),
                clearDescription = patch.clearDescription,
                tags = patch.tags?.let(::cleanTags),
                quantity = patch.quantity,
                clearQuantity = patch.clearQuantity,
                unit = patch.unit?.trim(),
                sensitive = patch.sensitive,
                pinned = patch.pinned,
            )
        return ctx.commit(listOf(ctx.event(payload, source)))
    }

    public suspend fun store(
        itemId: String,
        location: LocationRef,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult {
        ctx.checkItem(itemId)?.let { return rejected(it) }
        ctx.checkLocation(location)?.let { return rejected(it) }
        return ctx.commit(listOf(ctx.event(location.toStored(itemId), source, rawInput)))
    }

    public suspend fun move(
        itemId: String,
        location: LocationRef,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult {
        ctx.checkItem(itemId)?.let { return rejected(it) }
        ctx.checkLocation(location)?.let { return rejected(it) }
        return ctx.commit(listOf(ctx.event(location.toMoved(itemId), source, rawInput)))
    }

    public suspend fun take(
        itemId: String,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult = simple(itemId, EventPayload.ItemTaken(itemId), source, rawInput)

    /** Back at its last known location, after a take or a loan (U5, U6). */
    public suspend fun putBack(
        itemId: String,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult = simple(itemId, EventPayload.ItemReturned(itemId), source, rawInput)

    public suspend fun lend(
        itemId: String,
        to: String,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult {
        val person = cleanName(to) ?: return rejected(Rejection.BLANK_NAME)
        return simple(itemId, EventPayload.ItemLent(itemId, person), source, rawInput)
    }

    public suspend fun setArchived(
        itemId: String,
        archived: Boolean,
        source: EventSource,
    ): CommandResult = simple(itemId, EventPayload.ItemArchived(itemId, archived), source)

    /** Moves the item to the bin (A13); its events and location are kept for a restore. */
    public suspend fun delete(
        itemId: String,
        source: EventSource,
    ): CommandResult = simple(itemId, EventPayload.ItemDeleted(itemId), source)

    public suspend fun restore(
        itemId: String,
        source: EventSource,
    ): CommandResult {
        val state = ctx.checkItem(itemId, allowDeleted = true)
        if (state != null) return rejected(state)
        if (ctx.checkItem(itemId) == null) return rejected(Rejection.ITEM_NOT_DELETED)
        return ctx.commit(listOf(ctx.event(EventPayload.ItemRestored(itemId), source)))
    }

    private suspend fun simple(
        itemId: String,
        payload: EventPayload,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult {
        ctx.checkItem(itemId)?.let { return rejected(it) }
        return ctx.commit(listOf(ctx.event(payload, source, rawInput)))
    }
}
