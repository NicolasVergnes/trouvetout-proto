package app.trouvetout.core.domain.usecase

import app.trouvetout.core.data.EventStore
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.EventSubject
import app.trouvetout.core.domain.model.EventSubject.SubjectType

/**
 * A13 "annuler": reverts the last append of this device by appending inverse events — the journal is never
 * rewritten. The last append is the group of this device's most recent events sharing the same instant (a
 * capture produces `ItemCreated` + `ItemStored` together). Imported events and a few irreversible ones
 * (place merge/delete, container delete) are refused with [Rejection.NOT_UNDOABLE].
 */
public class UndoService internal constructor(
    private val store: EventStore,
    private val ctx: CommandContext,
    private val deviceId: String,
) {
    /** The events that [undoLast] would revert, newest first; empty when there is nothing to undo. */
    public suspend fun peek(): List<Event> {
        val recent = store.lastOf(deviceId, MAX_GROUP)
        val head = recent.firstOrNull() ?: return emptyList()
        if (head.source == EventSource.IMPORT) return emptyList()
        return recent.takeWhile { it.at == head.at && it.source != EventSource.IMPORT }
    }

    public suspend fun undoLast(source: EventSource): CommandResult {
        val group = peek()
        val inverses = group.map { inverseOf(it) }
        return when {
            group.isEmpty() -> CommandContext.rejected(Rejection.NOTHING_TO_UNDO)
            inverses.any { it == null } -> CommandContext.rejected(Rejection.NOT_UNDOABLE)
            else -> ctx.commit(inverses.filterNotNull().map { ctx.event(it, source) })
        }
    }

    /**
     * Reverts exactly [events] (newest first), whatever their instants: a text capture writes several groups
     * (place, container, one per object) and its card undoes them all at once.
     */
    public suspend fun undo(
        events: List<Event>,
        source: EventSource,
    ): CommandResult {
        val ordered = events.sortedWith(compareByDescending<Event> { it.at }.thenByDescending { it.id })
        val inverses = ordered.map { inverseOf(it) }
        return when {
            ordered.isEmpty() -> CommandContext.rejected(Rejection.NOTHING_TO_UNDO)
            inverses.any { it == null } -> CommandContext.rejected(Rejection.NOT_UNDOABLE)
            else -> ctx.commit(inverses.filterNotNull().map { ctx.event(it, source) })
        }
    }

    /** Null when the event cannot be reverted. */
    private suspend fun inverseOf(event: Event): EventPayload? = when (val p = event.payload) {
        is EventPayload.ItemCreated -> EventPayload.ItemDeleted(p.itemId)
        is EventPayload.ItemDeleted -> EventPayload.ItemRestored(p.itemId)
        is EventPayload.ItemRestored -> EventPayload.ItemDeleted(p.itemId)
        is EventPayload.ItemArchived -> EventPayload.ItemArchived(p.itemId, archived = !p.archived)
        is EventPayload.ItemTaken -> EventPayload.ItemReturned(p.itemId)
        is EventPayload.ItemLent -> EventPayload.ItemReturned(p.itemId)
        is EventPayload.ItemReturned -> previousStatus(p.itemId, before = event)
        is EventPayload.ItemStored -> previousLocation(p.itemId, before = event)
        is EventPayload.ItemMovedTo -> previousLocation(p.itemId, before = event)
        is EventPayload.ItemUpdated -> previousFields(p.itemId, before = event)
        else -> inverseOfNonItem(event)
    }

    /** Places, containers, photos and reminders; merges and deletions have no exact inverse. */
    private suspend fun inverseOfNonItem(event: Event): EventPayload? = when (val p = event.payload) {
        is EventPayload.PlaceCreated -> EventPayload.PlaceDeleted(p.placeId)
        is EventPayload.PlaceRenamed -> previousName(p.placeId, before = event)
        is EventPayload.ContainerCreated -> EventPayload.ContainerDeleted(p.containerId)
        is EventPayload.ContainerMoved -> previousContainerPlace(p.containerId, before = event)
        is EventPayload.PhotoAttached -> EventPayload.PhotoDetached(p.photoId)
        is EventPayload.PhotoDetached -> reattach(p.photoId, before = event)
        is EventPayload.ReminderSet -> EventPayload.ReminderDone(p.reminderId)
        else -> null
    }

    private suspend fun historyBefore(
        subject: EventSubject,
        before: Event,
    ): List<Event> = store.historyOf(subject).filter { it.at < before.at || (it.at == before.at && it.id < before.id) }

    /** Before a store or move, the item was taken, lent, or at its previous location (or nowhere). */
    private suspend fun previousLocation(
        itemId: String,
        before: Event,
    ): EventPayload {
        val history = historyBefore(EventSubject(SubjectType.ITEM, itemId), before).map { it.payload }
        return when (val lastStatus = history.lastOrNull { it.isStatusEvent() }) {
            is EventPayload.ItemTaken -> lastStatus
            is EventPayload.ItemLent -> lastStatus
            else -> {
                val last = history.lastOrNull { it is EventPayload.ItemStored || it is EventPayload.ItemMovedTo }
                when (last) {
                    is EventPayload.ItemStored -> EventPayload.ItemStored(itemId, last.placeId, last.containerId)
                    is EventPayload.ItemMovedTo -> EventPayload.ItemStored(itemId, last.placeId, last.containerId)
                    else -> EventPayload.ItemStored(itemId) // back to "no known location"
                }
            }
        }
    }

    private fun EventPayload.isStatusEvent(): Boolean = this is EventPayload.ItemTaken ||
        this is EventPayload.ItemLent ||
        this is EventPayload.ItemReturned ||
        this is EventPayload.ItemStored ||
        this is EventPayload.ItemMovedTo

    /** Before an `ItemReturned`, the item was taken or lent: re-emit that state (or nothing to do). */
    private suspend fun previousStatus(
        itemId: String,
        before: Event,
    ): EventPayload {
        val history = historyBefore(EventSubject(SubjectType.ITEM, itemId), before).map { it.payload }
        val lastStatus = history.lastOrNull { it.isStatusEvent() }
        return when (lastStatus) {
            is EventPayload.ItemTaken -> lastStatus
            is EventPayload.ItemLent -> lastStatus
            else -> EventPayload.ItemReturned(itemId) // was already stored: returning again changes nothing
        }
    }

    private suspend fun previousFields(
        itemId: String,
        before: Event,
    ): EventPayload? {
        val history = historyBefore(EventSubject(SubjectType.ITEM, itemId), before).map { it.payload }
        val created = history.filterIsInstance<EventPayload.ItemCreated>().firstOrNull() ?: return null
        var name = created.name
        var description = created.description
        var tags = created.tags
        var quantity = created.quantity
        var unit = created.unit
        var sensitive = created.sensitive
        var pinned = false
        for (update in history.filterIsInstance<EventPayload.ItemUpdated>()) {
            update.name?.let { name = it }
            if (update.clearDescription) description = null else update.description?.let { description = it }
            update.tags?.let { tags = it }
            if (update.clearQuantity) {
                quantity = null
                unit = null
            } else {
                update.quantity?.let { quantity = it }
                update.unit?.let { unit = it }
            }
            update.sensitive?.let { sensitive = it }
            update.pinned?.let { pinned = it }
        }
        return EventPayload.ItemUpdated(
            itemId = itemId,
            name = name,
            description = description,
            clearDescription = description == null,
            tags = tags,
            quantity = quantity,
            clearQuantity = quantity == null,
            unit = unit,
            sensitive = sensitive,
            pinned = pinned,
        )
    }

    private suspend fun previousName(
        placeId: String,
        before: Event,
    ): EventPayload? {
        val history = historyBefore(EventSubject(SubjectType.PLACE, placeId), before).map { it.payload }
        val last =
            history.lastOrNull {
                (it is EventPayload.PlaceCreated && it.placeId == placeId) ||
                    it is EventPayload.PlaceRenamed
            }
        return when (last) {
            is EventPayload.PlaceCreated -> EventPayload.PlaceRenamed(placeId, last.name, last.aliases)
            is EventPayload.PlaceRenamed -> EventPayload.PlaceRenamed(placeId, last.name, last.aliases)
            else -> null
        }
    }

    private suspend fun previousContainerPlace(
        containerId: String,
        before: Event,
    ): EventPayload? {
        val history = historyBefore(EventSubject(SubjectType.CONTAINER, containerId), before).map { it.payload }
        val last =
            history.lastOrNull {
                (it is EventPayload.ContainerCreated && it.containerId == containerId) ||
                    (it is EventPayload.ContainerMoved && it.containerId == containerId)
            }
        return when (last) {
            is EventPayload.ContainerCreated -> EventPayload.ContainerMoved(containerId, last.placeId)
            is EventPayload.ContainerMoved -> EventPayload.ContainerMoved(containerId, last.placeId)
            else -> null
        }
    }

    private suspend fun reattach(
        photoId: String,
        before: Event,
    ): EventPayload? = historyBefore(EventSubject(SubjectType.PHOTO, photoId), before)
        .map { it.payload }
        .filterIsInstance<EventPayload.PhotoAttached>()
        .lastOrNull()

    private companion object {
        const val MAX_GROUP = 64
    }
}
