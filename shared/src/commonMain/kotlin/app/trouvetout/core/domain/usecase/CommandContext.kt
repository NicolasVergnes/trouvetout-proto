package app.trouvetout.core.domain.usecase

import app.trouvetout.core.data.EventStore
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import kotlin.time.Clock

/** Shared plumbing of the command classes: event construction, validation reads, append. */
internal class CommandContext(
    private val store: EventStore,
    private val ids: MonotonicUlidGenerator,
    private val clock: Clock,
    private val deviceId: String,
) {
    fun newId(): String = ids.next()

    fun event(
        payload: EventPayload,
        source: EventSource,
        rawInput: String? = null,
    ): Event = Event(
        id = ids.next(),
        at = clock.now(),
        deviceId = deviceId,
        payload = payload,
        source = source,
        rawInput = rawInput,
    )

    suspend fun commit(events: List<Event>): CommandResult {
        store.append(events)
        return CommandResult.Done(events)
    }

    suspend fun <T> read(block: (TrouveToutDb) -> T): T = store.read(block)

    /** Null when the item exists and (unless [allowDeleted]) is not in the bin. */
    suspend fun checkItem(
        itemId: String,
        allowDeleted: Boolean = false,
    ): Rejection? = read { db ->
        val row = db.itemQueries.selectById(itemId).executeAsOneOrNull()
        when {
            row == null -> Rejection.UNKNOWN_ITEM
            row.deleted_at != null && !allowDeleted -> Rejection.ITEM_DELETED
            else -> null
        }
    }

    suspend fun checkPlace(placeId: String): Rejection? = read { db ->
        val row = db.placeQueries.selectById(placeId).executeAsOneOrNull()
        if (row == null || row.deleted_at != null) Rejection.UNKNOWN_PLACE else null
    }

    suspend fun checkContainer(containerId: String): Rejection? = read { db ->
        val row = db.containerQueries.selectById(containerId).executeAsOneOrNull()
        if (row == null || row.deleted_at != null) Rejection.UNKNOWN_CONTAINER else null
    }

    suspend fun checkLocation(ref: LocationRef): Rejection? = when (ref) {
        is LocationRef.InPlace -> checkPlace(ref.placeId)
        is LocationRef.InContainer -> checkContainer(ref.containerId)
    }

    companion object {
        fun rejected(reason: Rejection): CommandResult = CommandResult.Rejected(reason)

        fun cleanName(name: String): String? = name.trim().ifEmpty { null }

        fun cleanTags(tags: List<String>): List<String> = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun LocationRef.toStored(itemId: String): EventPayload.ItemStored = when (this) {
            is LocationRef.InPlace -> EventPayload.ItemStored(itemId, placeId = placeId)
            is LocationRef.InContainer -> EventPayload.ItemStored(itemId, containerId = containerId)
        }

        fun LocationRef.toMoved(itemId: String): EventPayload.ItemMovedTo = when (this) {
            is LocationRef.InPlace -> EventPayload.ItemMovedTo(itemId, placeId = placeId)
            is LocationRef.InContainer -> EventPayload.ItemMovedTo(itemId, containerId = containerId)
        }
    }
}
