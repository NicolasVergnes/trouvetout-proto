package app.trouvetout.core.domain.usecase

import app.trouvetout.core.capture.Tokens
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.CommandContext.Companion.cleanName
import app.trouvetout.core.domain.usecase.CommandContext.Companion.rejected

/** Container use cases (A3, D4). */
public class ContainerCommands internal constructor(
    private val ctx: CommandContext,
) {
    public suspend fun create(
        label: String,
        placeId: String,
        number: Long? = null,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult {
        val cleanLabel = cleanName(label) ?: return rejected(Rejection.BLANK_NAME)
        ctx.checkPlace(placeId)?.let { return rejected(it) }
        val payload = EventPayload.ContainerCreated(ctx.newId(), cleanLabel, placeId, number ?: numberIn(cleanLabel))
        return ctx.commit(listOf(ctx.event(payload, source, rawInput)))
    }

    /** One event; every item inside follows (D4). */
    public suspend fun move(
        containerId: String,
        placeId: String,
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult {
        ctx.checkContainer(containerId)?.let { return rejected(it) }
        ctx.checkPlace(placeId)?.let { return rejected(it) }
        return ctx.commit(listOf(ctx.event(EventPayload.ContainerMoved(containerId, placeId), source, rawInput)))
    }

    /** Only an empty container can be deleted. */
    public suspend fun delete(
        containerId: String,
        source: EventSource,
    ): CommandResult {
        ctx.checkContainer(containerId)?.let { return rejected(it) }
        val empty = ctx.read { db -> db.locationStateQueries.itemsInContainer(containerId).executeAsList().isEmpty() }
        if (!empty) return rejected(Rejection.CONTAINER_NOT_EMPTY)
        return ctx.commit(listOf(ctx.event(EventPayload.ContainerDeleted(containerId), source)))
    }

    public companion object {
        /** The first number of a label (« Carton 7 » → 7, « Boîte 3 et 4 » → 3), null when there is none. */
        public fun numberIn(label: String): Long? =
            Tokens.tokenize(label).firstOrNull { it.isNumber }?.key?.toLongOrNull()
    }
}
